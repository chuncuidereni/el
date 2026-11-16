/**
 * 【文件职责】本文件定义查询工作流模块的领域数据模型 QueryWorkflowOrder，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import lombok.Data;

/** 步骤④的排序项(按输出列别名),对应 Go {@code model.QueryWorkflowOrder}。 */
@Data
public class QueryWorkflowOrder {
    /** 输出列名(字段别名)。 */
    private String column;
    /**
     * 是否降序。
     *
     * <p>Go 侧此字段是 {@code json:"desc"} —— 没有 omitempty,故 {@code false} 也会序列化。
     * Java 侧若加 NON_DEFAULT 会让 {@code desc:false} 消失,与 Go 的报文外形不一致,故保持默认序列化。
     */
    private boolean desc;
}
