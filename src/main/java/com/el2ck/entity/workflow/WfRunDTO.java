/**
 * 【文件职责】本文件定义工作流编排模块的API 数据传输模型 WfRunDTO，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/** 工作流运行历史 DTO(运行记录 + 节点日志)。日志键为小写 logs。 */
@Data
public class WfRunDTO {
    /** 运行记录主键。 */
    @JsonProperty("ID")
    private long id;
    /** 所属工作流 ID。 */
    @JsonProperty("WorkflowID")
    private long workflowId;
    /** 运行状态。 */
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
    /** 本次运行的节点级日志列表(输出键 logs,小写)。 */
    private List<WfRunLog> logs;
}
