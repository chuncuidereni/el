/**
 * 【文件职责】本文件定义工作流编排模块的运行状态或日志模型 WfTask，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import lombok.Data;

/** 工作流定义存储模型(nodes/edges 以 JSON 文本存取)。 */
@Data
public class WfTask {
    /** 工作流主键。 */
    private long id;
    /** 工作流名称。 */
    private String name;
    /** 工作流描述。 */
    private String description;
    /** 节点对象数组的 JSON 文本(nodes_json)。 */
    private String nodesJson;
    /** 连线对象数组的 JSON 文本(edges_json)。 */
    private String edgesJson;
    /** 是否启用。 */
    private boolean enabled;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;
}
