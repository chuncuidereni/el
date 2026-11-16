/**
 * 【文件职责】本文件定义查询工作流模块的领域结构模型 QueryWorkflowJoin，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 步骤④的 JOIN 配置,对应 Go {@code model.QueryWorkflowJoin}。 */
@Data
public class QueryWorkflowJoin {
    /** LEFT / INNER / FULL / RIGHT / CROSS;留空按 LEFT。 */
    private String type;
    /** 全局默认左键,如 {@code a.mdn_norm};留空则用主表的 mdn_norm 归一化列。 */
    private String onLeft;
    /** 全局默认右键,如 {@code b.phone_norm};留空则用被关联表的 mdn_norm 归一化列。 */
    private String onRight;
    /**
     * 逐表覆盖:第三张表起各表关联键不同时用它。
     *
     * <p>不填的项回退到全局 {@link #onLeft}/{@link #onRight};两侧都留空则用各自的 mdn_norm 归一化列。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryWorkflowJoinOn> ons = new ArrayList<>();
}
