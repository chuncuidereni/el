/**
 * 【文件职责】本文件定义查询工作流模块的请求/写入参数模型 QueryWorkflowInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import lombok.Data;

/** 查询工作流的创建 / 更新入参,对应 Go {@code model.QueryWorkflowInput}。 */
@Data
public class QueryWorkflowInput {
    /** 名称,必填。 */
    private String name;
    /** 描述,可为空串。 */
    private String description;
    /** 是否启用。 */
    private boolean enabled;
    /** 向导完整配置;保存前会先过一遍 SQL 生成校验。 */
    private QueryWorkflowConfig config = new QueryWorkflowConfig();
}
