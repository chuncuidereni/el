/**
 * 【文件职责】本文件定义查询工作流模块的领域结构模型 QueryWorkflowConfig，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询工作流向导的完整配置(4 步向导的整体状态),对应 Go {@code model.QueryWorkflowConfig},
 * 序列化后存进 {@code query_workflows.config_json}。
 *
 * <p>4 步:① 选源表(sources)→ ② mdn 归一化(mdnRules)→ ③ 字段处理(fields)
 * → ④ 结果(join / where / having / orderBy / limit / groupBy / distinctKeys)。
 */
@Data
public class QueryWorkflowConfig {
    /** ① 源表列表。所有源表必须在同一连接下(生成 SQL 时校验)。 */
    private List<QueryWorkflowSource> sources = new ArrayList<>();
    /** ② 每张源表的 mdn 归一化规则。多表 JOIN 时每张源表都必须配。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryWorkflowMdnRule> mdnRules = new ArrayList<>();
    /** ③ 输出字段。 */
    private List<QueryWorkflowFieldRule> fields = new ArrayList<>();
    /** ④ JOIN 配置。 */
    private QueryWorkflowJoin join = new QueryWorkflowJoin();
    /** ④ WHERE 条件。 */
    private List<QueryWorkflowWhere> where = new ArrayList<>();
    /**
     * ④ 对【聚合结果】的过滤(HAVING)。形状与 {@link #where} 完全相同(复用同一套操作符
     * 白名单与值转义),区别只是作用在 GROUP BY 之后 —— 因此要求配置里至少有一个聚合字段。
     * 可引用的列 = 输出字段别名(分组列或聚合列)。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryWorkflowWhere> having = new ArrayList<>();
    /** ④ 排序。 */
    private List<QueryWorkflowOrder> orderBy = new ArrayList<>();
    /** ④ 行数上限;0 = 不限制。 */
    private int limit;
    /**
     * 已弃用:仅兼容旧配置,等价于 {@code groupBy=["mdn_norm"]}。
     *
     * <p>Go 侧 {@code json:"groupByMdn,omitempty"},故 false 不序列化。
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean groupByMdn;
    /** 按【输出字段别名】分组,如 {@code ["city","status"]}。空 = 不分组。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> groupBy = new ArrayList<>();
    /**
     * 去重键(按【输出字段别名】):非空时按这些列各保留一行,其余列【原样带出来】——
     * 生成 ClickHouse 的 {@code LIMIT 1 BY <键>}。
     *
     * <p>与 {@link #groupBy} 的分工(语义不同,不能混):
     * <ul>
     *   <li>{@code groupBy} 把其它列折叠成聚合值(sum/count/any…),结果是「统计」</li>
     *   <li>{@code distinctKeys} 按 key 取一行,其它列保留原值,结果是「去重后的明细列表」</li>
     * </ul>
     * 保留哪一条由 ORDER BY 决定:例如 {@code orderBy=login_at desc + distinctKeys=[mdn]}
     * = 每个号码留最近的那条(不写 ORDER BY 时保留哪条不确定)。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> distinctKeys = new ArrayList<>();
}
