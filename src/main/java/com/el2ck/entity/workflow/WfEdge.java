/**
 * 【文件职责】本文件定义工作流编排模块的领域结构模型 WfEdge，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.workflow;

import lombok.Data;

/** 工作流节点连线。 */
@Data
public class WfEdge {
    /** 起始节点 id。 */
    private String from;
    /** 目标节点 id。 */
    private String to;
}
