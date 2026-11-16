/**
 * 【文件职责】本文件定义数据同步模块的领域数据模型 SyncRunProgress，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import lombok.Data;

/** 日志中的已确认进度；总量、百分比及剩余时间允许未知。 */
@Data
public class SyncRunProgress {
    /** 定时入队身份只在元数据中使用；进度合并更新时保留，用于跨实例触发去重。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String scheduledCron;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long scheduledAtMs;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long scheduledThroughMs;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long queuedAtMs;
    private long confirmedRows;
    private long runRows;
    private Long totalRows;
    private boolean estimated;
    private Double percent;
    private double averageRowsPerSecond;
    private Long etaSeconds;
    private int retryCount;
    /** 自动重试计划恢复的时间戳；等待期间用于让页面明确显示不会卡死。 */
    private Long nextRetryAtMs;
    private String currentKey;
    private Long updatedAtMs;
    private String sourceType;
    private String phase;
    private String currentObject;
    private String unit;
    private Long completedUnits;
    private Long totalUnits;
}
