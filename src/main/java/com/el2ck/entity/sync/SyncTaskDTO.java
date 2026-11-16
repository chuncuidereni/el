/**
 * 【文件职责】本文件定义数据同步模块的API 数据传输模型 SyncTaskDTO，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import com.el2ck.entity.common.Column;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 同步任务对外 DTO(含冗余的数据源/连接名称)。 */
@Data
public class SyncTaskDTO {
    private MysqlDateTableRule mysqlDateTables;
    /** 已有完整逐表断点；前端用于全量转增量联动，保存和执行仍校验配置指纹。 */
    private boolean dateTableBaselineReady;
    /** 已知断点状态下是否允许进入接管预览；预览和确认仍检查最新指纹及目标内容。 */
    private boolean canAdoptBaseline;
    private String baselineAdoptionBlockedReason;
    /** 是否有独立的定时等待请求；当前正在运行时可以同时为 true。 */
    private boolean queued;
    private int timeoutMinutes;
    private boolean resumable;
    private String stableKey;
    private int batchSize;
    private long checkpointRows;
    private String checkpointKey;
    private boolean checkpointPending;
    /** 同步任务 ID。 */
    private Long id;
    /** 任务名称。 */
    private String name;
    /** 任务描述。 */
    private String description;
    /** 源数据源 ID。 */
    private Long dataSourceId;
    /** 源数据源名称(冗余,联表填充)。 */
    private String dataSourceName;
    /** 源数据源类型，供列表展示类型标签及 SFTP 操作入口。 */
    private String dataSourceType;
    /** 源对象(源表名/集合名/文件路径)。 */
    private String sourceObject;
    private String remotePath;
    private String filePattern;
    private Boolean hasHeader;
    private boolean ignoreExtraCsvColumns;
    private String csvEncoding;
    private java.util.List<String> allowedTargetDefaults;
    private java.util.List<Column> schema;
    /** 目标连接 ID。 */
    private Long connectionId;
    /** 目标连接名称(冗余,联表填充)。 */
    private String connectionName;
    /** 目标 ClickHouse 数据库。 */
    private String targetDb;
    /** 目标 ClickHouse 表。 */
    private String targetTable;
    /** 列映射列表。 */
    private java.util.List<SyncFieldMapping> mapping;
    /** 同步模式 full/incremental。 */
    private String mode;
    /** 执行方式:manual / scheduled。 */
    private String scheduleType;
    /** 定时执行 Cron 表达式。 */
    private String cronExpression;
    /** 增量键(源列;仅 incremental)。 */
    private String incrColumn;
    /** MergeTree 排序键(目标列)。 */
    private String orderKey;
    /** 是否为 ODS 标准源表。 */
    @com.fasterxml.jackson.annotation.JsonProperty("isOds")
    private boolean ods;
    /** 目标自动建表的分区表达式;空=不分区。 */
    private String partitionExpr;
    /** 目标自动建表的排序键(目标列,多列)。 */
    private java.util.List<String> sortingKeys;
    /** 目标自动建表的主键(目标列)。 */
    private java.util.List<String> primaryKeys;
    /** 源侧号码列标注。 */
    private String phoneColumn;
    /** 是否启用。 */
    private Boolean enabled;
    /** full 模式是否先清空目标表。 */
    private Boolean truncate;
    /** 目标表不存在时是否自动创建。 */
    private Boolean autoCreateTable;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;

    /** 最近一次执行，供首页概览和任务列表使用。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LastRun lastRun;

    @Data
    public static class LastRun {
        /** 与单次运行详情相同的结构化进度，列表轮询无需另查源库或目标库。 */
        private SyncRunProgress progress;
        private long id;
        private String status;
        private String startedAt;
        private String finishedAt;
        private long rowsRead;
        private long rowsWritten;
        private String error;
        /** 运行说明(断点续传/失败续传提示等)。 */
        private String note;
        /** 本次是否从断点续传。 */
        private boolean resumed;
        /** 续传的起始分段键。 */
        private String checkpointFrom;
        /** 续传开始时已确认的行数。 */
        private long checkpointRows;
    }
}
