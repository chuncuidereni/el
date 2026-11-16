/**
 * 【文件职责】本文件定义数据同步模块的运行状态或日志模型 SyncTask，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import com.el2ck.entity.common.Column;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.List;

/**
 * 同步任务定义(mapping 以 JSON 存取)。mode: full / incremental。
 * enabled/truncate/autoCreateTable 在 MySQL 中以 0/1 存取。
 */
@Data
public class SyncTask {
    /** null 为原单表模式；非空时按业务日期发现 MySQL 每日分表。 */
    private MysqlDateTableRule mysqlDateTables;
    private int timeoutMinutes = 0;
    private boolean resumable = true;
    private String stableKey = "";
    private int batchSize = 10000;
    /** 同步模式:全量。 */
    public static final String MODE_FULL = "full";
    /** 同步模式:增量。 */
    public static final String MODE_INCREMENTAL = "incremental";

    /** 同步任务主键。 */
    private long id;
    /** 任务名称。 */
    private String name;
    /** 任务描述。 */
    private String description;
    /** 源数据源ID(现阶段仅本地文件 csv/json) */
    private long dataSourceId;
    /** 源对象(本地文件路径;未来数据库源:表名/集合名;空=用数据源自身配置) */
    private String sourceObject;
    private String remotePath = "";
    private String filePattern = "*.csv";
    private boolean hasHeader = true;
    /** 仅目录 CSV：默认按有效表头（无表头时首条记录）列数读取，忽略额外尾列。 */
    private boolean ignoreExtraCsvColumns = true;
    /** SFTP CSV 编码选择；null 为历史配置，新轮次默认自动探测。 */
    private String csvEncoding;
    /** null 表示历史任务；非 null 为明确允许省略并由目标补默认值的列名单。 */
    private List<String> allowedTargetDefaults;
    private List<Column> schema = new java.util.ArrayList<>();
    @JsonIgnore
    private String schemaJson;
    /** 目标连接ID */
    private long connectionId;
    /** 目标 ClickHouse 数据库。 */
    private String targetDb;
    /** 目标 ClickHouse 表。 */
    private String targetTable;
    /** 列映射列表(持久化到 mapping_json 文本列)。 */
    private List<SyncFieldMapping> mapping;
    /** 持久层专用:mapping 的 JSON 文本列(mapping_json),不参与 JSON 序列化。 */
    @JsonIgnore
    private String mappingJson;
    /** 同步模式 full/incremental。 */
    private String mode;
    /** 执行方式:manual / scheduled。 */
    private String scheduleType;
    /** XXL-JOB 使用的 Cron 表达式。 */
    private String cronExpression;
    /** 增量键(源列;仅 incremental) */
    private String incrColumn;
    /** MergeTree 排序键(目标列;空=第一映射列) */
    private String orderKey;
    /**
     * 是否为 ODS 标准源表:自动建表时用 ods_insert_at(默认入库时间)/ods_source_id(来源=数据源标识)
     * 两列替代非 ODS 任务的 source_id/create_time 溯源列。持久化列 is_ods。
     * 字段名用 {@code ods}(非 {@code isOds})避开 Lombok/MyBatis 对 boolean is 前缀的属性名歧义,
     * 对外 JSON 名由 {@code @JsonProperty} 固定为 Go 的 {@code isOds}。
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isOds")
    private boolean ods;
    /** 目标自动建表的分区表达式(如 toYYYYMMDD(ods_insert_at));空=不分区。持久化列 partition_expr。 */
    private String partitionExpr;
    /** 目标自动建表的排序键(目标列,多列;空=orderKey/首映射列)。持久化到 sorting_keys 文本列(JSON 数组)。 */
    private List<String> sortingKeys;
    /** 持久层专用:sortingKeys 的 JSON 文本列(sorting_keys),不参与 JSON 序列化。 */
    @JsonIgnore
    private String sortingKeysJson;
    /** 目标自动建表的主键(目标列,可选,须为排序键前缀)。持久化到 primary_keys 文本列(JSON 数组)。 */
    private List<String> primaryKeys;
    /** 持久层专用:primaryKeys 的 JSON 文本列(primary_keys),不参与 JSON 序列化。 */
    @JsonIgnore
    private String primaryKeysJson;
    /** 源侧号码列标注(空=未标注);分区键校验用来拒绝"拿号码列当分区键"。持久化列 phone_column。 */
    private String phoneColumn;
    /** 是否启用。 */
    private boolean enabled;
    /** full 模式是否先 TRUNCATE(默认 true) */
    private boolean truncate;
    /** 目标表不存在时是否按字段映射自动创建(默认 true) */
    private boolean autoCreateTable;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;

    /** 校验同步模式是否为 full / incremental。 */
    public static boolean validMode(String m) {
        return MODE_FULL.equals(m) || MODE_INCREMENTAL.equals(m);
    }
}
