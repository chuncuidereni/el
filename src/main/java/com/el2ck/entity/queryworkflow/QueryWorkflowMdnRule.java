/**
 * 【文件职责】本文件定义查询工作流模块的领域结构模型 QueryWorkflowMdnRule，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import com.el2ck.entity.query.QueryPipelineStep;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单张源表的 mdn 归一化(向导步骤②),对应 Go {@code model.QueryWorkflowMdnRule}。
 *
 * <p>可选:单表查询可以不配(不注入 mdn_norm 列);多表 JOIN 时每张源表必须各配一条。
 */
@Data
public class QueryWorkflowMdnRule {
    /** 对应 sources 数组的下标。越界与重复下标都会在生成 SQL 时显式报错。 */
    private int sourceIdx;
    /** 源表里哪一列是 mdn。 */
    private String sourceCol;
    /** 归一化后的别名(供 JOIN 用)。 */
    private String alias;
    /** 旧:无参算子链(向后兼容)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> pipeline = new ArrayList<>();
    /** 带参处理链。非空时优先于 {@link #pipeline}。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryPipelineStep> steps = new ArrayList<>();
}
