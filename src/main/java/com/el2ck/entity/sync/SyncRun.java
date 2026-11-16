/**
 * 【文件职责】本文件定义数据同步模块的运行状态或日志模型 SyncRun，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 同步任务执行历史。与 Go 版一致:字段名以大写开头输出
 * (ID/TaskID/StartedAt/...),请勿改动。
 */
@Data
public class SyncRun {
    /** 日志逻辑删除时间，仅用于服务端可见性；执行状态与断点不随删除改变。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String deletedAt;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String progressJson;
    @JsonProperty("DurationMs")
    private Long durationMs;
    /**
     * 返回本次同步运行的进度快照。
     *
     * @return 包含已处理数量、总数和阶段信息的进度对象；实现按当前运行状态生成
     */
    @JsonProperty("Progress")
    public SyncRunProgress getProgress() {
        if(progressJson==null || progressJson.isEmpty()) return null;
        try { return JSON.readValue(progressJson,SyncRunProgress.class); }
        catch(java.io.IOException e) { return null; }
    }
    /**
     * 设置 Progress 字段。
     *
     * @param progress 进度对象
     */
    public void setProgress(SyncRunProgress progress) {
        try { progressJson=progress==null ? null : JSON.writeValueAsString(progress); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("无法序列化同步进度",e); }
    }
    /** 运行记录主键。 */
    @JsonProperty("ID")
    private long id;
    /** 所属同步任务 ID。 */
    @JsonProperty("TaskID")
    private long taskId;
    /** 运行状态(如 pending/running/success/failed)。 */
    @JsonProperty("Status")
    private String status;
    /** 开始时间。 */
    @JsonProperty("StartedAt")
    private String startedAt;
    /** 结束时间(运行中为空)。 */
    @JsonProperty("FinishedAt")
    private String finishedAt;
    /** 读取行数。 */
    @JsonProperty("RowsRead")
    private long rowsRead;
    /** 写入行数。 */
    @JsonProperty("RowsWritten")
    private long rowsWritten;
    /** 失败信息(成功时为空)。 */
    @JsonProperty("Error")
    private String error;
    /** 人类可读的运行说明(如“断点续传：从 key=… 继续”)。 */
    @JsonProperty("Note")
    private String note;
    /** 本次是否从上次未完成的断点继续。 */
    @JsonProperty("Resumed")
    private boolean resumed;
    /** 续传的起始分段键(全新运行或非续传任务为空)。 */
    @JsonProperty("CheckpointFrom")
    private String checkpointFrom;
    /** 续传开始时已确认的行数(全新运行为 0)。 */
    @JsonProperty("CheckpointRows")
    private long checkpointRows;
}
