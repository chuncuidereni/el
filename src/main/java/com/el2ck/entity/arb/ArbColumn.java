/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbColumn，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 一个目标列的取值方案,对应 Go {@code model.ArbColumn}(工作流的「④仲裁方案」一块)。
 *
 * <p>三种仲裁规则共用【同一种】状态类型,靠把不参与比较的那一位填 0 来表达,
 * 所以不会破坏「全表状态类型统一」——混用状态类型会让「省略列的 INSERT」报 Code 43,
 * 且报错列名具有误导性(见 {@code ArbPlanBuilder#stateType})。
 */
@Data
public class ArbColumn {

    /** 仲裁出一个值:非空 &gt; 来源优先级 &gt; 更新时间(字典序取最大)。值一律转 String 存。 */
    public static final String MODE_ARBITRATE = "arbitrate";
    /** 跨源求和。 */
    public static final String MODE_SUM = "sum";
    /** 取最大。 */
    public static final String MODE_MAX = "max";
    /** 取最小。 */
    public static final String MODE_MIN = "min";

    /** 非空 &gt; 优先级 &gt; 时间。默认规则。 */
    public static final String RULE_PRI_TIME = "pri_time";
    /** 非空 &gt; 优先级(时间不参与,时间位填 1970-01-01)。 */
    public static final String RULE_PRI = "pri";
    /** 非空 &gt; 时间(优先级不参与,优先级位填 0)。 */
    public static final String RULE_TIME = "time";

    /** 目标列名。不能叫 mdn(那是号码列保留名)。 */
    private String name;
    /** 取值方式:{@link #MODE_ARBITRATE} / {@link #MODE_SUM} / {@link #MODE_MAX} / {@link #MODE_MIN}。空 = 仲裁。 */
    private String mode;
    /** 仲裁规则,仅 arbitrate 用,默认 {@link #RULE_PRI_TIME}。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String rule;
}
