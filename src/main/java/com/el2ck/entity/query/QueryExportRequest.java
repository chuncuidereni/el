/**
 * 【文件职责】本文件定义查询执行模块的请求/写入参数模型 QueryExportRequest，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * SQL 结果导出 CSV 入参。对应 Go service.QueryExportRequest。
 *
 * <p>与表导出的差别:这里导出的是**任意 SELECT 的结果**,不绑定某张表,
 * 所以没有过滤树/表名,只有一条 SQL 加与结果列相关的选项。
 */
@Data
public class QueryExportRequest {
    /** 待导出的 SQL(应传单条 SELECT/WITH;传多条时取第一条可导出的)。 */
    private String sql;

    /** 在哪个库下执行;空 = 用连接自带的库。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String database;

    /** 导出哪些列;空 = 全部列(按结果顺序)。 */
    private List<String> columns;

    /** 表头显示名(按下标与 columns 一一对应);该位为空则用原列名。 */
    private List<String> headerNames;

    /** 是否输出表头行。 */
    private Boolean withHeader;

    /** 分隔符;空 = 逗号,{@code \t} / {@code tab} = 制表符。 */
    private String delimiter;

    /** 最大导出行数;0/空 = 不限(服务端仍封顶 200 万)。 */
    private Integer limit;

    /**
     * 程序算子后处理计划 —— 导出必须一起应用,否则脱敏 / 加密列会以【明文】导出。
     *
     * <p>⚠️ 计划里的下标是「生成 SQL 时的全列下标」,而导出会把结果投影成所选列,
     * 所以服务端会按列名把下标重新定位到投影上(见 QueryService 的 remapProgColsToProjection)。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<ProgColumn> progCols;
}
