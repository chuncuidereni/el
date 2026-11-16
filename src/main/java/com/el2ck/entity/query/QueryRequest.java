/**
 * 【文件职责】本文件定义查询执行模块的请求/写入参数模型 QueryRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import com.el2ck.entity.table.ColumnAliasHint;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 控制台请求。allowWrite=false 时写语句会被服务端拦截。
 *
 * <p>除 sql / allowWrite 外,其余字段服务于「可视化查询」链路(见
 * {@link com.el2ck.workflow.QueryPlanBuilder})—— 手写 SQL 不传它们,行为与以前一致。
 */
@Data
public class QueryRequest {
    /** 待执行的 SQL(可包含多条语句)。 */
    private String sql;
    /** 是否允许执行写语句;false 时写语句会被服务端拦截。 */
    private Boolean allowWrite;

    /**
     * 本次执行的库(仅用于「列注释优先取哪个库」的判定)。
     *
     * <p>⚠️ 与 Go 的一处差异:Go 会为不同的库另开一条绑定该库的连接,Java 不这么做 ——
     * 可视化链路生成的 SQL 里库名是全限定的({@code `库`.`表`}),换连接没有意义;
     * 而为每个查询请求新开连接在并发下会把 ClickHouse 打满。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String database;

    /**
     * 程序算子后处理计划(可视化构建器的 SM3 / 3DES / FF1 等)。
     *
     * <p>ClickHouse 不参与计算,结果返回前由本程序逐行变换第 index 列。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ProgColumn> progCols = new ArrayList<>();

    /**
     * 输出别名 → 源字段的映射,用于精确取字段注释。
     *
     * <p>⚠️ 只由服务端根据配置推导,【不接受请求体传入】({@code @JsonIgnore})——
     * 它是查询元数据的能力,让客户端随便指定等于开放一个任意表的注释探测接口。
     */
    @JsonIgnore
    private List<ColumnAliasHint> columnAliases = new ArrayList<>();

    /**
     * 分页页码(从 1 开始);仅当 {@link #pageSize} &gt; 0 时生效。
     *
     * <p>启用分页后,SELECT / WITH 会被包裹成子查询取一页,并额外跑一次 {@code count()}
     * 得到总行数(结果不再截断到 1000 行,按页浏览)。
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int page;

    /** 每页行数;大于 0 时启用服务端分页(上限 1000)。 */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int pageSize;
}
