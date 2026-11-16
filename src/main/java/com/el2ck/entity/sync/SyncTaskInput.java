/**
 * 【文件职责】本文件定义数据同步模块的请求/写入参数模型 SyncTaskInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import com.el2ck.entity.common.Column;

import lombok.Data;

/** 创建/更新同步任务的请求体(不含 ID 与冗余名称)。 */
@Data
public class SyncTaskInput {
    private MysqlDateTableRule mysqlDateTables;
    private Integer timeoutMinutes;
    private Boolean resumable;
    private String stableKey;
    private Integer batchSize;
    /** 任务名称。 */
    private String name;
    /** 任务描述。 */
    private String description;
    /** 源数据源 ID。 */
    private Long dataSourceId;
    /** 源对象(本地文件路径;未来数据库源:表名/集合名)。 */
    private String sourceObject;
    private String remotePath;
    private String filePattern;
    private Boolean hasHeader;
    /** 仅 SFTP 目录 CSV 生效；未传时更新保留原值，新建默认开启。 */
    private Boolean ignoreExtraCsvColumns;
    private String csvEncoding;
    /** 新增或编辑时必须明确允许未映射的可写目标列；缺省按空名单处理。 */
    private java.util.List<String> allowedTargetDefaults;
    private java.util.List<Column> schema;
    /** 目标连接 ID。 */
    private Long connectionId;
    /** 目标 ClickHouse 数据库。 */
    private String targetDb;
    /** 目标 ClickHouse 表。 */
    private String targetTable;
    /** 列映射列表(由前端按列配置生成)。 */
    private java.util.List<SyncFieldMapping> mapping;
    /** 同步模式 full/incremental。 */
    private String mode;
    /** 执行方式:manual / scheduled。 */
    private String scheduleType;
    /** 定时执行 Cron 表达式。 */
    @com.fasterxml.jackson.annotation.JsonAlias("cronExpr")
    private String cronExpression;
    /** 增量键(源列;仅 incremental 模式需要)。 */
    private String incrColumn;
    /** MergeTree 排序键(目标列;空=取第一映射列)。 */
    private String orderKey;
    /** 是否为 ODS 标准源表(自动建表时改用 ods_insert_at/ods_source_id 溯源列)。 */
    @com.fasterxml.jackson.annotation.JsonProperty("isOds")
    private Boolean ods;
    /** 目标自动建表的分区表达式(如 toYYYYMMDD(ods_insert_at));空=不分区。 */
    private String partitionExpr;
    /** 目标自动建表的排序键(目标列,多列)。 */
    private java.util.List<String> sortingKeys;
    /** 目标自动建表的主键(目标列,须为排序键前缀)。 */
    private java.util.List<String> primaryKeys;
    /** 源侧号码列标注(用于分区键校验)。 */
    private String phoneColumn;
    /** 是否启用。 */
    private Boolean enabled;
    /** full 模式是否先清空目标表。 */
    private Boolean truncate;
    /** 目标表不存在时是否自动创建。 */
    private Boolean autoCreateTable;
}
