/**
 * 【文件职责】本文件定义工作流编排模块的运行状态或日志模型 WfRun，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 工作流运行记录(大写键输出)。 */
@Data
public class WfRun {
    /** 运行记录主键。 */
    @JsonProperty("ID")
    private long id;
    /** 所属工作流 ID。 */
    @JsonProperty("WorkflowID")
    private long workflowId;
    /** 运行状态(如 pending/running/success/failed)。 */
    @JsonProperty("Status")
    private String status;
    /** 开始时间。 */
    @JsonProperty("StartedAt")
    private String startedAt;
    /** 结束时间(运行中为空)。 */
    @JsonProperty("FinishedAt")
    private String finishedAt;
    /** 失败信息(成功时为空)。 */
    @JsonProperty("Error")
    private String error;
}
