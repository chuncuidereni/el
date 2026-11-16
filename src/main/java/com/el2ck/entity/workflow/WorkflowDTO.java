/**
 * 【文件职责】本文件定义工作流编排模块的API 数据传输模型 WorkflowDTO，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import lombok.Data;

import java.util.List;

/** 工作流对外 DTO(nodes/edges 已解析为对象数组)。 */
@Data
public class WorkflowDTO {
    /** 工作流 ID。 */
    private Long id;
    /** 名称。 */
    private String name;
    /** 描述。 */
    private String description;
    /** 节点对象数组。 */
    private List<WfNode> nodes;
    /** 连线对象数组。 */
    private List<WfEdge> edges;
    /** 是否启用。 */
    private Boolean enabled;
    /** 最近一次运行状态(冗余展示)。 */
    private String lastRunStatus;
    /** 最近一次运行开始时间(冗余展示)。 */
    private String lastRunStartedAt;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;
}
