/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域数据模型 ArbWorkflow，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.el2ck.entity.queryworkflow.QueryWorkflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 多源仲裁工作流定义，对应 Go {@code model.ArbWorkflow}(MySQL 表 arb_workflows)。
 *
 * <p>配置分三块(对应界面上的 ②源 / ③字段 / ④仲裁方案),整体序列化进 config_json 一列
 * (见 {@link ArbWorkflowConfig});{@link #wide} 标记也在里面。
 *
 * <p>产物是一张聚合层表 {@code <TargetTable>_agg}(AggregatingMergeTree)+ 每源一个物化视图
 * 写它,再摊平成服务表 {@code <TargetTable>_svc}(普通 MergeTree,业务只查这张)。
 *
 * <p>⚠️ 与 {@link QueryWorkflow}(数据探索 / JOIN 型宽表任务)是两套东西:那个是同名字段
 * 静态挑一侧,没有优先级概念;这里是 UNION ALL + GROUP BY,同名字段由 (非空,优先级,时间) 仲裁。
 */
@Data
public class ArbWorkflow {

    /** 聚合表的固定后缀。对用户隐藏:界面上只填中间那段名字,前后缀由服务层统一拼。 */
    public static final String AGG_SUFFIX = "_agg";
    /** 服务表(摊平后的普通列)的固定后缀。 */
    public static final String SVC_SUFFIX = "_svc";
    /** 聚合表基础名的默认前缀。 */
    public static final String PREFIX = "dwd_";
    /** 聚合表里的号码列名(固定)。各源的号码列归一化后统一落到这一列,后续所有仲裁都按它 GROUP BY。 */
    public static final String MDN_COLUMN = "mdn";
    /** 聚合表默认落库的库名(与目标库表页一致)。界面上不暴露 CK 的库概念。 */
    public static final String DEFAULT_DB = "dwd_data";
    /** 目标表名 + 后缀的长度上限(ClickHouse 标识符限制)。 */
    public static final int MAX_TABLE_LEN = 110;

    /** 主键。 */
    private long id;
    /** 工作流名称。服务层保证去空白后非空,且全表唯一(忽略大小写)。 */
    private String name;
    /** 描述,可为空串。 */
    private String description;
    /** 是否启用。停用后拒绝执行(setup/rebuild/flatten)。 */
    private boolean enabled;
    /** 源表与目标表所在的连接(生成器要求它们同库同连接)。 */
    private long connectionId;
    /**
     * 「作为宽表」标记:全平台汇总的那一张(所有贴源表并成一个大宽表)。
     *
     * <p>它不是新的机制,只是给这张工作流加个身份 —— 宽表页签直接显示它的服务表。
     * 同一时刻只允许一个,服务层保存时清掉别的。
     *
     * <p>⚠️ 字段名刻意不叫 {@code isWide}:Lombok 对布尔字段 {@code isWide} 会生成
     * {@code isWide()}/{@code setWide()},Jackson 从 getter 反推出的属性名会变成 {@code wide},
     * 与前端约定的 {@code isWide} 对不上。用 {@link JsonProperty} 钉死两端的名字。
     */
    @JsonProperty("isWide")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean wide;
    /** 聚合表的库(服务层统一置为 {@link #DEFAULT_DB})。 */
    private String targetDb;
    /** 聚合表的基础名。真实表名 = 此值 + {@link #AGG_SUFFIX}。 */
    private String targetTable;

    /** 「②源」:参与仲裁的贴源表。 */
    private List<ArbSource> sources = new ArrayList<>();
    /** 「③字段」:源列 → 目标列 的映射。 */
    private List<ArbField> fields = new ArrayList<>();
    /** 「④仲裁方案」:每个目标列的取值方式。 */
    private List<ArbColumn> columns = new ArrayList<>();

    /** 最近一次执行时间(列表页直接显示)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String lastRunAt;
    /** 最近一次执行状态:running / success / failed。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String lastStatus;
    /** 最近一次执行的行数。omitempty:0 不序列化(对齐 Go)。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long lastRows;
    /** 最近一次执行的错误。产物被删后这里写「产物已删除…」,列表上要能看出表已经不在了。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String lastError;

    /** 创建时间。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String createdAt;
    /** 更新时间。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String updatedAt;

    /** 聚合表名(存仲裁状态,业务不要直接查)。 */
    public String aggTableName() {
        return targetTable + AGG_SUFFIX;
    }

    /** 服务表名(已摊平成普通列,业务只查这张)。 */
    public String svcTableName() {
        return targetTable + SVC_SUFFIX;
    }
}
