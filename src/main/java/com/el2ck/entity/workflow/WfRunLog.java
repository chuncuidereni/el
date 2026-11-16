/**
 * 【文件职责】本文件定义工作流编排模块的运行状态或日志模型 WfRunLog，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 工作流节点级运行日志(大写键输出)。 */
@Data
public class WfRunLog {
    /** 日志主键。 */
    @JsonProperty("ID")
    private long id;
    /** 所属运行 ID。 */
    @JsonProperty("RunID")
    private long runId;
    /** 节点 id。 */
    @JsonProperty("NodeID")
    private String nodeId;
    /** 节点类型。 */
    @JsonProperty("NodeType")
    private String nodeType;
    /** 节点执行状态。 */
    @JsonProperty("Status")
    private String status;
    /** 节点输入行数。 */
    @JsonProperty("RowsIn")
    private long rowsIn;
    /** 节点输出行数。 */
    @JsonProperty("RowsOut")
    private long rowsOut;
    /** 节点执行耗时(毫秒)。 */
    @JsonProperty("DurationMs")
    private long durationMs;
    /** 节点失败信息(成功时为空)。 */
    @JsonProperty("Error")
    private String error;
}
