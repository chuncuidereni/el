/**
 * 【文件职责】本文件定义查询执行模块的领域结构模型 ProgColumn，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条「程序后处理」计划:对结果集的第 {@link #index} 列应用 {@link #ops},对应 Go {@code model.ProgColumn}。
 *
 * <p>程序算子(SM3 / 3DES / FF1 等)由本程序逐行计算,ClickHouse 完全不知道这些列的值,
 * 因此被它们处理过的输出列不能用于过滤 / 分组 / 排序 —— 该约束在
 * {@code QueryPlanBuilder} 里校验、在这里只是承载数据。
 *
 * <p>{@link #index} 是「结果列下标(从 0 开始)」。导出时结果会被投影成所选列,
 * 下标必须按投影重定位({@code QueryService.remapProgColsToProjection}),
 * 否则会对错列加密、让本该脱敏的列以明文进 CSV。
 */
@Data
public class ProgColumn {
    /** 结果列下标(从 0 开始)。 */
    private int index;
    /** 列别名(仅用于错误提示)。 */
    private String alias;
    /** 算子链,按顺序逐行应用。 */
    private List<QueryPipelineStep> ops = new ArrayList<>();
}
