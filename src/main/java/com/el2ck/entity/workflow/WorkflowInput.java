/**
 * 【文件职责】本文件定义工作流编排模块的请求/写入参数模型 WorkflowInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import lombok.Data;

import java.util.List;

/** 创建/更新工作流的请求体。 */
@Data
public class WorkflowInput {
    /** 工作流名称。 */
    private String name;
    /** 工作流描述。 */
    private String description;
    /** 节点对象数组(保存时序列化为 JSON)。 */
    private List<WfNode> nodes;
    /** 连线对象数组(保存时序列化为 JSON)。 */
    private List<WfEdge> edges;
    /** 是否启用。 */
    private Boolean enabled;
}
