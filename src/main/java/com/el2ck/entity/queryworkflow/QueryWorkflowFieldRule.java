/**
 * 【文件职责】本文件定义查询工作流模块的领域结构模型 QueryWorkflowFieldRule，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import com.el2ck.entity.query.QueryPipelineStep;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个输出字段(向导步骤③):从某张源表选出,经若干函数变换,可选聚合与程序算子,
 * 对应 Go {@code model.QueryWorkflowFieldRule}。
 */
@Data
public class QueryWorkflowFieldRule {
    /** 输出列名(英文标识符)。 */
    private String alias;
    /** 来源 source 索引。 */
    private int sourceIdx;
    /** 源列名。 */
    private String sourceCol;
    /** 旧:无参算子链(向后兼容)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> pipeline = new ArrayList<>();
    /** 带参处理链。非空时优先于 {@link #pipeline}。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryPipelineStep> steps = new ArrayList<>();
    /**
     * 由本程序在【结果返回后】应用的算子链。
     *
     * <p>⚠️ 与 {@link #steps} 的区别:
     * <ul>
     *   <li>{@code steps} 生成 ClickHouse SQL,由 CK 计算 → 结果列可过滤 / 分组 / 排序</li>
     *   <li>{@code prog} 由本程序逐行计算,ClickHouse 完全不知道 → 结果列【只能展示 / 导出】</li>
     * </ul>
     * 作用于 steps 的输出,等价于接在链尾(因此单独一个字段,天然保证顺序)。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryPipelineStep> prog = new ArrayList<>();
    /**
     * 聚合函数名;空 = 不聚合。与处理链可组合:{@code sum(toFloat64(amount))}。
     *
     * <p>特殊值 {@code countAll} 生成 {@code count()}(不引用列)。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String agg;
    /**
     * 只参与查询(过滤 / 排序 / 聚合后过滤),不输出到结果。
     *
     * <p>⚠️ 它仍然是一个【字段】:仍参与别名与 GROUP BY 校验,只是不进外层 SELECT。
     * 因此过滤 / 排序引用它时必须改写成 CTE 侧的带别名表达式(SELECT 别名不存在)。
     *
     * <p>Go 侧是 {@code json:"hidden,omitempty"} —— false 不序列化,故这里用 NON_DEFAULT。
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean hidden;
}
