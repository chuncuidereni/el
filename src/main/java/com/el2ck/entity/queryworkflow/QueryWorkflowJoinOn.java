/**
 * 【文件职责】本文件定义查询工作流模块的领域数据模型 QueryWorkflowJoinOn，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 单张关联表的 ON 条件(第三张表起可用),对应 Go {@code model.QueryWorkflowJoinOn}。 */
@Data
public class QueryWorkflowJoinOn {
    /** 该条件作用于哪张源表(从 1 开始;0 是主表,不允许配)。 */
    private int sourceIdx;
    /** 左侧,只能是「表别名.列名」;留空回退到全局 onLeft。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String onLeft;
    /** 右侧,只能是「表别名.列名」;留空回退到全局 onRight。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String onRight;
}
