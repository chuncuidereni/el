package com.el2ck.sync;

/**
 * 【文件职责】封装 MongoDB 增量游标、字段投影和批次读取，供同步与快照流程复用。
 */

import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.util.RemoteSourceFile;
import com.el2ck.util.SyncTime;
import com.el2ck.util.MongoFields;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Mongo 游标直接按批次取值，增量条件在源端执行，字段路径保留 BSON 类型。
 *
 * <p>与「先导出再读」不同，这里对集合直接开游标、分批拉取，避免把整个集合落到临时文件；
 * 增量过滤({@code 增量列 > 水位})下推到 MongoDB 由源端执行，可利用索引先筛掉不需要的行。
 * 字段路径保留其原生 BSON 类型，读取时再按类型序列化，使探测阶段推断出的目标列类型与实际取值一致。
 */
final class MongoSyncSource implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private final List<String> paths = new ArrayList<>();
    private final List<Column> columns = new ArrayList<>();
    private MongoCursor<Document> cursor;
    private boolean closed;
    private boolean exhausted;
    private boolean cursorClosed;

    static MongoSyncSource open(Map<String, Object> config, String object, List<SyncFieldMapping> mappings) {
        String database = MongoFields.database(config);
        String name = object == null ? "" : object.trim();
        if (name.isEmpty()) name = Objects.toString(config.get("collection"), "").trim();
        if (database.isEmpty() || name.isEmpty()) throw new IllegalStateException("MongoDB 数据库和集合不能为空");
        com.mongodb.ConnectionString connection = new com.mongodb.ConnectionString(RemoteSourceFile.mongoUri(config));
        com.mongodb.MongoClientSettings settings = com.mongodb.MongoClientSettings.builder().applyConnectionString(connection)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)).build();
        MongoClient client = MongoClients.create(settings);
        try { return new MongoSyncSource(client, client.getDatabase(database).getCollection(name), mappings); }
        catch (RuntimeException e) { client.close(); throw e; }
    }

    /**
     * 打开集合并推断各映射字段的类型(包可见，供测试注入已连接的 client/collection)。
     *
     * <p>先采样最多 200 条文档用于推断字段类型：样本量足以覆盖常见字段，又不会在探测阶段
     * 拖慢连接。某个映射路径在样本里没出现时(如稀疏字段)，再对该字段单独探测一次
     * (见 {@link #probe})；两次都取不到，才退化为映射里配置的类型。
     */
    MongoSyncSource(MongoClient client, MongoCollection<Document> collection, List<SyncFieldMapping> mappings) {
        this.client = client;
        this.collection = collection;
        if (mappings == null || mappings.isEmpty()) throw new IllegalArgumentException("至少配置一个字段映射");
        List<Document> samples = new ArrayList<>();
        try (MongoCursor<Document> sampleCursor = collection.find(new Document()).limit(200)
                .maxTime(30, TimeUnit.SECONDS).iterator()) {
            while (sampleCursor.hasNext()) { cancelled(); samples.add(sampleCursor.next()); }
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (Column column : MongoFields.columns(samples)) types.put(column.getName(), column.getType());
        for (SyncFieldMapping mapping : mappings) {
            if (mapping == null) throw new IllegalArgumentException("字段映射不能为空");
            String path = mapping.getSourceColumn() == null ? "" : mapping.getSourceColumn().trim();
            validatePath(path);
            if (paths.contains(path)) continue;
            String detected = types.get(path);
            if (detected == null) {
                Object sample = probe(path);
                if (sample == null && !samples.isEmpty()) throw new IllegalStateException("源字段不存在或没有可识别的值: " + path);
                detected = sample == null ? mapping.getType() : type(sample);
            }
            Column column = new Column(); column.setName(path);
            column.setType(detected);
            paths.add(path); columns.add(column);
        }
    }

    List<Column> columns() { return columns; }

    /**
     * 单字段探测：查一条该路径「存在且非空」的文档，取其值推断 BSON 类型。
     * 用于样本里没覆盖到的稀疏字段；查不到返回 null。
     */
    private Object probe(String path) {
        Document doc = collection.find(Filters.and(Filters.exists(path), Filters.ne(path, null)))
                .projection(Projections.include(path)).limit(1).maxTime(30, TimeUnit.SECONDS).first();
        return field(doc, path);
    }

    /**
     * 启动游标：增量列和水位都给定时在源端加 {@code Filters.gt} 过滤，否则全量扫描；
     * 按 _id 升序、每批 10000 条。
     *
     * <p>⚠️ 比较值必须与源字段的 BSON 类型一致，否则 MongoDB 会按类型排序规则比较，得到错误结果。
     * 因此这里按增量列的探测类型分别构造下界样本：时间用 {@code new Date(0)}(epoch)、
     * {@code Decimal128} 用 0、Float64/Int64 用各自的零值，再交给 {@link #bound} 把水位文本
     * 强转成同类型；类型表里没有该字段时才回退到按样本探测。
     */
    void start(String column, String watermark) {
        if (closed || cursor != null) throw new IllegalStateException("MongoDB 同步游标不能重复启动");
        Bson filter = new Document();
        if (column != null && watermark != null && !watermark.trim().isEmpty()) {
            String sourceType = columns.stream().filter(c -> column.equals(c.getName())).map(Column::getType)
                    .findFirst().orElseThrow(() -> new IllegalStateException("源增量字段不存在: " + column));
            Object sample;
            if (SyncTime.isTimestamp(sourceType)) sample = new Date(0);
            else if (sourceType.startsWith("Decimal")) sample = new Decimal128(BigDecimal.ZERO);
            else if (sourceType.equals("Float64")) sample = Double.valueOf(0);
            else if (sourceType.equals("Int64")) sample = Long.valueOf(0);
            else sample = probe(column);
            if (sample == null) throw new IllegalStateException("MongoDB 增量字段没有可识别的非空值: " + column);
            filter = Filters.gt(column, bound(sample, watermark));
        }
        cursor = collection.find(filter).projection(Projections.include(MongoFields.projectionPaths(paths)))
                .sort(Sorts.ascending("_id")).batchSize(10000).iterator();
    }

    /**
     * 取下一批至多 {@code limit} 行，每行按字段顺序输出文本列。
     * 游标耗尽后标记 exhausted 并关闭游标，后续调用返回空列表；未启动即调用视为错误。
     * 每读一行前检查线程中断(见 {@link #cancelled})，使超时/取消能及时打断长游标遍历。
     */
    List<List<String>> next(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("批次行数必须大于 0");
        List<List<String>> rows = new ArrayList<>(limit);
        if (closed || exhausted) return rows;
        if (cursor == null) throw new IllegalStateException("MongoDB 同步游标尚未启动");
        while (rows.size() < limit) {
            cancelled();
            if (!cursor.hasNext()) { exhausted = true; closeCursor(); break; }
            Document doc = cursor.next();
            List<String> row = new ArrayList<>(paths.size());
            for (String path : paths) row.add(text(field(doc, path)));
            rows.add(row);
        }
        return rows;
    }

    /** 按点分路径从文档取值(委托 {@link MongoFields#field})。 */
    static Object field(Map<?, ?> doc, String path) {
        return MongoFields.field(doc, path);
    }

    /** 校验点分路径合法性(委托 {@link MongoFields#validatePath})。 */
    static void validatePath(String path) {
        MongoFields.validatePath(path);
    }

    /** 由 BSON 值推断目标列类型(委托 {@link MongoFields#type})。 */
    static String type(Object value) {
        return MongoFields.type(value);
    }

    /**
     * 把增量水位文本强转成与样本同 BSON 类型的比较值，供源端 {@code Filters.gt} 使用。
     *
     * <p>转换规则：Date 先按 {@code DateTime64(3)} 校验精度(与 MongoDB Date 的毫秒精度一致)再解析成时间；
     * Decimal128 直接用水位文本构造 BigDecimal；浮点要求有限(NaN/Inf 无法参与有效比较)；
     * 整型用 {@code longValueExact} 要求水位恰为整数，带小数或超出 long 即报错；
     * 字符串、ObjectId 直接构造。任何不兼容都以「水位与源字段类型不兼容」抛出，
     * 避免拿错类型的值去比较而静默漏数。
     */
    static Object bound(Object sample, String value) {
        try {
            if (sample instanceof Date) {
                SyncTime.requireExact(value, "DateTime64(3)");
                return Date.from(SyncTime.parse(value).toInstant());
            }
            if (sample instanceof Decimal128) return new Decimal128(new BigDecimal(value));
            if (sample instanceof Double || sample instanceof Float) {
                double number = Double.parseDouble(value);
                if (!Double.isFinite(number)) throw new IllegalArgumentException();
                return number;
            }
            if (sample instanceof Integer || sample instanceof Long) return new BigDecimal(value).longValueExact();
            if (sample instanceof String) return value;
            if (sample instanceof ObjectId) return new ObjectId(value);
        } catch (RuntimeException e) { throw new IllegalStateException("MongoDB 增量水位与源字段类型不兼容", e); }
        throw new IllegalStateException("MongoDB 增量字段 BSON 类型不支持: " + sample.getClass().getSimpleName());
    }

    /**
     * 把字段值序列化成最终写库的文本。
     *
     * <p>规则：null → 空串(由写入阶段按目标类型落成 NULL/默认值)；Date → 本机时区的
     * {@code yyyy-MM-dd HH:mm:ss[.fff]};Decimal128 → 普通十进制文本(用 {@code toPlainString}
     * 而非科学计数法，避免格式与精度争议)；Map/List(对象/数组)先经 {@link MongoFields#jsonValue}
     * 转成 JSON 可表达结构、再序列化为 JSON 文本；其余按 {@code toString()}。
     */
    static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Date) return SyncTime.format(((Date) value).toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        if (value instanceof Decimal128) return ((Decimal128) value).bigDecimalValue().toPlainString();
        if (value instanceof Map || value instanceof List) {
            try { return JSON.writeValueAsString(MongoFields.jsonValue(value)); }
            catch (Exception e) { throw new IllegalStateException("MongoDB 嵌套值无法序列化", e); }
        }
        return value.toString();
    }

    /**
     * 检查当前线程是否已被中断；中断即抛异常终止同步。
     * 在游标遍历中每行调用一次，让上层用 interrupt 实现的超时/取消能真正打断读取。
     */
    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已超时或被中断");
    }

    /** 幂等关闭：先关游标再关客户端，保证游标与连接都不泄漏。 */
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { closeCursor(); }
        finally { client.close(); }
    }

    /** 关闭游标(幂等)，供游标正常耗尽与提前关闭两条路径调用。 */
    private void closeCursor() {
        if (cursor != null && !cursorClosed) { cursorClosed = true; cursor.close(); }
    }
}
