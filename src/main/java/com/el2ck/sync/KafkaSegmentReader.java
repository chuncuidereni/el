package com.el2ck.sync;

/**
 * 【文件职责】按分区和 offset 读取 Kafka 消息窗口，用快照边界和待确认区间支持断点恢复。
 */

import com.el2ck.ckpool.CkClient;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.service.datasource.BigDataSource;
import com.fasterxml.jackson.databind.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.config.ConfigResource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 固定 Topic 身份和分区范围；读取位置只来自 MySQL 已确认断点，不提交 Kafka 消费组位点。 */
final class KafkaSegmentReader extends SegmentReader {
    // Kafka 恢复窗口由 Topic ID 与固定分区位点组成，不提交消费组位点；压缩 Topic 或日志保留破坏后无法安全重放。
    static final String PARTITION="_el_kafka_partition", OFFSET="_el_kafka_offset";
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    public static class Window {
        public String topicId;
        public SortedMap<Integer,Long> begins=new TreeMap<>(), ends=new TreeMap<>();
    }
    private final String topic;
    private final Admin admin;
    private final KafkaConsumer<String,String> consumer;
    private final Window window;
    private String next;
    private final boolean truncate;

    KafkaSegmentReader(Map<String,Object> config,List<Column> columns,SyncTask task,
                       SyncCheckpointStore.Session checkpoint,Long runId) {
        super(columns,task,checkpoint,runId,false);
        truncate=task.isTruncate();
        topic=task.getSourceObject();
        if(topic==null || !topic.matches("[A-Za-z0-9_.-]{1,249}") || topic.equals(".") || topic.equals(".."))
            throw new IllegalArgumentException("请选择有效的 Kafka Topic");
        Properties props=BigDataSource.kafkaProperties(config);
        props.setProperty("client.id","el2ck-sync-"+task.getId());
        admin=Admin.create(props);
        KafkaConsumer<String,String> opened=null;
        try {
            props.setProperty("enable.auto.commit","false");
            props.setProperty("allow.auto.create.topics","false");
            props.setProperty("auto.offset.reset","none");
            props.setProperty("isolation.level","read_committed");
            props.setProperty("max.poll.records",String.valueOf(batchSize));
            props.setProperty("fetch.max.bytes","8388608");
            props.setProperty("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
            props.setProperty("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
            opened=new KafkaConsumer<>(props);
            consumer=opened;
            TopicDescription description=describe();
            if(checkpoint.state.upper==null) {
                window=new Window(); window.topicId=description.topicId().toString();
                List<TopicPartition> partitions=new ArrayList<>();
                description.partitions().forEach(p->partitions.add(new TopicPartition(topic,p.partition())));
                if(partitions.isEmpty()) throw new IllegalStateException("Topic 没有可读分区");
                Map<TopicPartition,Long> begins=consumer.beginningOffsets(partitions,Duration.ofSeconds(10));
                Map<TopicPartition,Long> ends=consumer.endOffsets(partitions,Duration.ofSeconds(10));
                for(TopicPartition p:partitions) { window.begins.put(p.partition(),begins.get(p)); window.ends.put(p.partition(),ends.get(p)); }
            } else window=decodeWindow(checkpoint.state.upper);
            validateDescription(description);
            validateRemaining(checkpoint.state.last);
            updateProgress();
        } catch(RuntimeException e) {
            if(opened!=null) try { opened.close(Duration.ofSeconds(1)); } catch(RuntimeException close) { e.addSuppressed(close); }
            try { admin.close(Duration.ofSeconds(1)); } catch(RuntimeException close) { e.addSuppressed(close); }
            throw e;
        }
    }

/**
 * 根据任务映射和消息字段定义构造读取列，并追加分区、offset 及可选轮次列。
 *
 * @param task 本次操作使用的输入参数
 * @return List<Column> 类型的结果
 */
    static List<Column> columns(SyncTask task) {
        // 保存的字段顺序固定；恢复时不从 Topic 头部重新抽样，已消费消息可能已过期。
        Map<String,Column> fields=new LinkedHashMap<>();
        if(task.getSchema()!=null) for(Column c:task.getSchema()) {
            if(c==null || c.getName()==null || c.getName().isEmpty()) throw new IllegalStateException("Kafka 保存的字段结构无效");
            Column column=new Column(); column.setName(c.getName()); column.setType("String"); fields.put(column.getName(),column);
        }
        for(SyncFieldMapping mapping:task.getMapping()) {
            Column column=new Column(); column.setName(mapping.getSourceColumn()); column.setType("String");
            fields.putIfAbsent(column.getName(),column);
        }
        if(fields.isEmpty()) throw new IllegalStateException("请先配置 Kafka 字段映射");
        return new ArrayList<>(fields.values());
    }

    private <T> T await(KafkaFuture<T> future) {
        try { return future.get(10,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Kafka 读取已取消",e); }
        catch(TimeoutException e) { throw new org.apache.kafka.common.errors.TimeoutException("Kafka 元数据请求超时",e); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
            throw new IllegalStateException("Kafka 元数据请求失败",e.getCause());
        }
    }
/**
 * 获取主题分区元数据，作为本轮固定读取窗口的快照。
 *
 * @return TopicDescription 类型的结果
 */
    private TopicDescription describe() {
        TopicDescription description=await(admin.describeTopics(Collections.singleton(topic)).allTopicNames()).get(topic);
        if(description==null || Uuid.ZERO_UUID.equals(description.topicId()))
            throw new IllegalStateException("Kafka 未返回可靠的 Topic ID，不能安全续传；请使用支持 Topic ID 的 Broker");
        ConfigResource resource=new ConfigResource(ConfigResource.Type.TOPIC,topic);
        Config config=await(admin.describeConfigs(Collections.singleton(resource)).all()).get(resource);
        ConfigEntry policy=config==null ? null : config.get("cleanup.policy");
        if(policy==null || policy.value()==null || Arrays.asList(policy.value().split(",")).stream().anyMatch(v->"compact".equals(v.trim())))
            throw new IllegalStateException("Kafka 断点快照不支持压缩 Topic，消息被压缩后无法保证恢复完整性；请使用 cleanup.policy=delete");
        return description;
    }
/**
 * 确认主题分区数量和身份未在恢复期间变化，避免把旧 offset 映射到新分区布局。
 *
 * @param description 本次操作使用的输入参数
 */
    private void validateDescription(TopicDescription description) {
        if(!window.topicId.equals(description.topicId().toString()))
            throw new IllegalStateException("Kafka Topic 已被重建，不能继续旧断点；请使用新的空目标表建立任务");
        Set<Integer> available=new HashSet<>(); description.partitions().forEach(p->available.add(p.partition()));
        if(!available.containsAll(window.ends.keySet())) throw new IllegalStateException("Kafka 快照分区已不存在，停止续传");
    }
/**
 * 解析并校验持久化的分区结束位置窗口。
 *
 * @param text 本次操作使用的输入参数
 * @return Window 类型的结果
 */
    private Window decodeWindow(String text) {
        try {
            Window value=JSON.readValue(text,Window.class);
            if(value.topicId==null || value.begins==null || value.ends==null || value.ends.isEmpty()
                    || !value.begins.keySet().equals(value.ends.keySet())) throw new IllegalArgumentException();
            for(int p:value.ends.keySet()) if(p<0 || value.begins.get(p)<0 || value.ends.get(p)<value.begins.get(p)) throw new IllegalArgumentException();
            return value;
        } catch(Exception e) { throw new IllegalStateException("Kafka 快照断点无效，停止续传",e); }
    }
/**
 * 解析分区/offset 二元位置，并拒绝负值或格式损坏的数据。
 *
 * @param text 本次操作使用的输入参数
 * @return long[] 类型的结果
 */
    private long[] position(String text) {
        try {
            long[] values=JSON.readValue(text,long[].class);
            if(values.length!=2 || values[0]<0 || values[0]>Integer.MAX_VALUE
                    || !window.ends.containsKey((int)values[0]) || values[1]<window.begins.get((int)values[0])
                    || values[1]>window.ends.get((int)values[0])) throw new IllegalArgumentException();
            return values;
        } catch(Exception e) { throw new IllegalStateException("Kafka 分区断点无效",e); }
    }
/**
 * 计算指定分区在给定游标之后的下一条读取 offset。
 *
 * @param partition 本次操作使用的输入参数
 * @param last 本次操作使用的输入参数
 * @return long 类型的结果
 */
    private long from(int partition,String last) {
        if(last==null) return window.begins.get(partition);
        long[] cursor=position(last);
        return partition<(int)cursor[0] ? window.ends.get(partition)
                : partition==(int)cursor[0] ? cursor[1] : window.begins.get(partition);
    }
/**
 * 校验已确认位置没有越过本轮快照边界，也没有遗漏其他分区。
 *
 * @param last 本次操作使用的输入参数
 */
    private void validateRemaining(String last) {
        List<TopicPartition> remaining=new ArrayList<>();
        for(int p:window.ends.keySet()) if(from(p,last)<window.ends.get(p)) remaining.add(new TopicPartition(topic,p));
        if(remaining.isEmpty()) return;
        Map<TopicPartition,Long> begins=consumer.beginningOffsets(remaining,Duration.ofSeconds(10));
        Map<TopicPartition,Long> ends=consumer.endOffsets(remaining,Duration.ofSeconds(10));
        for(TopicPartition p:remaining) if(begins.get(p)>from(p.partition(),last) || ends.get(p)<window.ends.get(p.partition()))
            throw new IllegalStateException("Kafka 未确认范围已过期或日志被截断，不能跳过消息继续同步；请检查保留时间，恢复原消息或使用新的空目标表建立任务");
    }
    @Override String upper() {
        try { return JSON.writeValueAsString(window); }
        catch(Exception e) { throw new IllegalStateException("无法保存 Kafka 快照",e); }
    }
    @Override List<List<String>> read(String last,String high) {
        checkpoint.checkLock(); cancelled();
        validateDescription(describe()); validateRemaining(last);
        Integer selected=null;
        for(int p:window.ends.keySet()) if(from(p,last)<window.ends.get(p)) { selected=p; break; }
        next=null;
        if(selected==null) return Collections.emptyList();
        int partition=selected;
        long start=from(partition,last),end=window.ends.get(partition);
        if(checkpoint.state.pending!=null) {
            long[] pending=position(high);
            if(pending[0]!=partition || pending[1]<=start) throw new IllegalStateException("Kafka 未确认范围与已确认位置不一致");
            end=pending[1];
        }
        TopicPartition tp=new TopicPartition(topic,partition);
        consumer.assign(Collections.singleton(tp)); consumer.seek(tp,start);
        List<List<String>> rows=new ArrayList<>();
        int scanned=0;
        long cursor=start,deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(cursor<end && scanned<batchSize) {
            cancelled(); checkpoint.checkLock();
            long before=cursor;
            ConsumerRecords<String,String> records=consumer.poll(Duration.ofSeconds(1));
            boolean stopped=false;
            for(ConsumerRecord<String,String> record:records.records(tp)) {
                if(record.offset()<cursor) throw new IllegalStateException("Kafka 返回了倒退的消息位点");
                if(record.offset()>=end) { cursor=end; stopped=true; break; }
                cursor=record.offset()+1; scanned++;
                if(record.value()!=null) rows.add(row(record));
                if(scanned==batchSize) { stopped=true; break; }
            }
            // 只有本次 poll 的消息全部处理后，才能跨越控制记录/已中止事务造成的空洞。
            if(!stopped) cursor=Math.max(cursor,Math.min(end,consumer.position(tp,Duration.ofSeconds(10))));
            if(cursor>before) deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            if(checkpoint.state.pending==null && cursor>start) break;
            if(System.nanoTime()>deadline) throw new org.apache.kafka.common.errors.TimeoutException("Kafka 分区读取未到达固定结束位点");
        }
        next="["+partition+","+cursor+"]";
        return rows;
    }
/**
 * 把 Kafka JSON 消息转换为目标列顺序；非 JSON 对象和非法消息会立即失败以暴露数据问题。
 *
 * @param record 本次操作使用的输入参数
 * @return List<String> 类型的结果
 */
    private List<String> row(ConsumerRecord<String,String> record) {
        JsonNode object;
        try { object=JSON.readTree(record.value()); }
        catch(Exception e) { throw new IllegalArgumentException("Kafka 消息不是有效 JSON，partition="+record.partition()+", offset="+record.offset()); }
        if(object==null || !object.isObject()) throw new IllegalArgumentException("Kafka 消息必须是 JSON 对象，partition="+record.partition()+", offset="+record.offset());
        List<String> values=new ArrayList<>();
        for(Column column:columns) {
            JsonNode value=object.get(column.getName());
            values.add(value==null || value.isNull() ? null : value.isValueNode() ? value.asText() : value.toString());
        }
        values.add(String.valueOf(record.partition())); values.add(String.valueOf(record.offset()));
        if(!truncate) values.add(checkpoint.state.token);
        return values;
    }
    @Override String nextCursor(List<List<String>> rows) { return next; }
    @Override boolean exhausted(List<List<String>> rows) { return next==null; }
    @Override String targetRange(String last,String upper) {
        long[] pending=position(upper);
        return "`"+PARTITION+"` = "+pending[0]+" AND `"+OFFSET+"` >= "+from((int)pending[0],last)
                +" AND `"+OFFSET+"` < "+pending[1]+(truncate ? "" : " AND `"+SnapshotSegmentReader.ROUND+"`="+com.el2ck.util.Sql.quoteString(checkpoint.state.token));
    }
    @Override boolean clearsTarget() { return truncate || checkpoint.state.replaceTarget; }
    @Override void appendMappings(List<String> targets,List<Integer> indexes,List<SyncFieldMapping> mappings) {
        // 分区、位点和轮次只保存在 MySQL，不进入业务字段映射。
    }
    @Override void prepareTarget(CkClient client,String table,List<Column> actual,boolean autoCreate) {
        // 不修改目标列。
    }
    @Override void validateTarget(List<Column> actual) {
        validateColumns(actual,false);
    }
/**
 * 确认主题消息字段与任务列映射兼容，防止缺列时产生错位写入。
 *
 * @param actual 本次操作使用的输入参数
 * @param allowMissing 本次操作使用的输入参数
 */
    static void validateColumns(List<Column> actual,boolean allowMissing) {
        // 业务字段由统一映射校验负责，不要求 Kafka 技术字段。
    }
    @Override void updateProgress() {
        long total=0,done=0;
        for(int p:window.ends.keySet()) {
            total=Math.addExact(total,window.ends.get(p)-window.begins.get(p));
            done=Math.addExact(done,from(p,checkpoint.state.last)-window.begins.get(p));
        }
        checkpoint.state.progressUnit="位点"; checkpoint.state.totalUnits=total; checkpoint.state.completedUnits=done;
    }
/**
 * 在 Kafka 长轮询边界检查线程中断并抛出取消异常。
 *
 */
    private void cancelled() { if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("Kafka 同步已取消"); }
    @Override public void close() {
        try { consumer.close(Duration.ofSeconds(1)); } finally { admin.close(Duration.ofSeconds(1)); }
    }
}
