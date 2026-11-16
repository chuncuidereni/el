/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域结构模型 FilterNode，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 数据网格过滤条件树(递归)。
 * 非叶子:logic=and|or + children;叶子:column/op/value。
 */
@Data
public class FilterNode {
    /** 组合逻辑 and|or(仅非叶子节点使用)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String logic;
    /** 过滤列名(叶子节点)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String column;
    /** 比较操作符；distinct 表示筛选后按该字段去重，多个字段合为组合键，无需 value。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String op;
    /** 比较值(与 op 配套,可为字面量或表达式)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object value;
    /** 数值比较:true 时把 String 列按数值比较(toFloat64OrZero)。对应 Go FilterNode.Numeric。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean numeric;
    /** 子条件节点(组合逻辑时使用)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<FilterNode> children;
}
