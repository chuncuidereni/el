/**
 * 【文件职责】本文件定义查询工作流模块的领域数据模型 QueryWorkflowWhere，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 步骤④的 WHERE 条件(与 HAVING 共用同一种形状),对应 Go {@code model.QueryWorkflowWhere}。
 *
 * <p>复用同一套操作符白名单与值转义,区别只是 HAVING 作用在 GROUP BY 之后。
 */
@Data
public class QueryWorkflowWhere {
    /** 输出列(字段别名);「不输出」的字段会被改写成 CTE 侧表达式。 */
    private String column;
    /** 比较操作符，或 distinct：对最终结果按字段去重，无需 value，多个字段按组合值处理。 */
    private String op;
    /** 值;in / not_in 用逗号分隔多个值。 */
    private String value;
    /** 仅 between 用:区间上界。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String value2;
    /**
     * 值渲染方式:string(默认,自动加引号转义)| number。
     *
     * <p>Go 的注释里还提到一个 {@code raw}(原样拼接)取值,但 Go 的 {@code renderLiteral}
     * 实现里【没有】这条分支 —— 任何非 number 的取值都落回 string,未知 kind 直接报错。
     * Java 侧照实现对齐,不提供原样拼接(那是一条 SQL 注入面)。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String valueKind;
    /**
     * 时间预设(仅日期 / 时间列):today | thisWeek | thisMonth | last7d | last30d | custom。
     *
     * <p>普通条件命中时忽略 op / value，生成左闭右开区间；distinct 始终按去重处理。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String preset;
}
