/**
 * 【文件职责】本文件定义数据源管理模块的数据快照组件 KafkaSnapshot。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.datasource;

import com.el2ck.entity.common.Column;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;
import java.util.*;

/** 有界快照：独立分区分配，不加入消费组，不提交消费位点。 */
public final class KafkaSnapshot implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private final KafkaConsumer<String,String> consumer;
    private final Map<TopicPartition,Long> ends;
    private final Map<TopicPartition,Long> starts;
    private final Deque<JsonNode> pending = new ArrayDeque<>();

    /**
     * 创建有界 Kafka 快照读取器。
     *
     * <p>读取器独立分配全部分区、从起始位点开始、关闭自动提交并固定本次读取的结束位点；
     * 构造失败时会关闭已创建的 consumer。
     *
     * @param config Kafka 连接配置
     * @param topic 合法 Topic 名
     * @throws IllegalArgumentException Topic 非法、为空或没有可读分区时抛出
     */
    public KafkaSnapshot(Map<String,Object> config, String topic) {
        if (topic == null || !topic.matches("[A-Za-z0-9_.-]{1,249}") || topic.equals(".") || topic.equals(".."))
            throw new IllegalArgumentException("请选择有效的 Kafka Topic");
        Properties p = BigDataSource.kafkaProperties(config);
        p.setProperty("enable.auto.commit", "false");
        p.setProperty("allow.auto.create.topics", "false");
        p.setProperty("auto.offset.reset", "none");
        p.setProperty("isolation.level", "read_committed");
        p.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.setProperty("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumer = new KafkaConsumer<>(p);
        try {
            List<TopicPartition> partitions = new ArrayList<>();
            consumer.partitionsFor(topic, Duration.ofSeconds(10)).forEach(i -> partitions.add(new TopicPartition(topic,i.partition())));
            if (partitions.isEmpty()) throw new IllegalArgumentException("Topic 不存在或没有可读分区");
            consumer.assign(partitions);
            ends = consumer.endOffsets(partitions, Duration.ofSeconds(10));
            starts = consumer.beginningOffsets(partitions, Duration.ofSeconds(10));
            starts.forEach(consumer::seek);
        } catch (RuntimeException e) { consumer.close(Duration.ofSeconds(1)); throw e; }
    }

    /**
     * 从快照读取至多 limit 条 JSON 对象消息。
     *
     * <p>跳过 tombstone 和快照结束位点后的记录；连续 30 秒无新消息时失败。
     *
     * @param limit 单次最多返回条数
     * @return 保持消费顺序的 JSON 对象
     * @throws IllegalArgumentException 消息不是 JSON 对象时抛出
     * @throws IllegalStateException 线程被中断或读取超时时抛出
     */
    public List<JsonNode> next(int limit) {
        List<JsonNode> rows = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (rows.size() < limit) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Kafka 读取已取消");
            while (!pending.isEmpty() && rows.size() < limit) rows.add(pending.removeFirst());
            if (rows.size() == limit) break;
            boolean done = true;
            for (Map.Entry<TopicPartition,Long> end : ends.entrySet()) {
                if (consumer.position(end.getKey(), Duration.ofSeconds(10)) >= end.getValue())
                    consumer.pause(Collections.singleton(end.getKey()));
                else done = false;
            }
            if (done) break;
            if (System.nanoTime() > deadline) throw new IllegalStateException("Kafka 快照读取超时，尚未到达结束位点，请重试");
            ConsumerRecords<String,String> records = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String,String> record : records) {
                if (record.offset() >= ends.get(new TopicPartition(record.topic(),record.partition()))) continue;
                if (record.value() == null) continue; // tombstone 不代表业务行
                try {
                    JsonNode json = JSON.readTree(record.value());
                    if (json == null || !json.isObject()) throw new IllegalArgumentException();
                    pending.add(json);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Kafka 消息必须是 JSON 对象，partition="+record.partition()+", offset="+record.offset());
                }
            }
            if (!records.isEmpty()) deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        }
        return rows;
    }

    /** 位点跨度包含 tombstone 和事务控制消息，不等于业务行数。 */
    public long totalOffsets() {
        long total = 0;
        for (TopicPartition partition : ends.keySet()) total += Math.max(0, ends.get(partition) - starts.get(partition));
        return total;
    }

    /** 只统计本次固定快照内扫描的位点；新到消息不会扩大进度分母。 */
    public long scannedOffsets() {
        long scanned = 0;
        for (TopicPartition partition : ends.keySet()) {
            long position = consumer.position(partition, Duration.ofSeconds(10));
            scanned += Math.max(0, Math.min(position, ends.get(partition)) - starts.get(partition));
        }
        return scanned;
    }

    /** 从前 100 条消息发现字段，使用文本映射以保留 JSON 数值精度。 */
    public static List<Column> columns(Map<String,Object> config, String topic) {
        Set<String> names = new LinkedHashSet<>();
        try (KafkaSnapshot snapshot = new KafkaSnapshot(config,topic)) {
            for (JsonNode row : snapshot.next(100)) row.fieldNames().forEachRemaining(names::add);
        }
        if (names.isEmpty()) throw new IllegalArgumentException("Topic 中没有可用于识别字段的 JSON 消息");
        List<Column> columns = new ArrayList<>();
        for (String name : names) { Column c = new Column(); c.setName(name); c.setType("String"); columns.add(c); }
        return columns;
    }
    @Override public void close() { consumer.close(Duration.ofSeconds(1)); }
}
