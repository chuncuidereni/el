/**
 * 【文件职责】本文件定义表管理与数据编辑模块的查询或操作结果模型 TableCheckResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张表的体检报告。对应 Go service.TableCheckResult。
 *
 * <p>⚠️ {@code columns} / {@code findings} / {@code rules} <b>必须是非 null 的集合</b>:
 * Go 侧踩过 —— nil 切片会序列化成 {@code null},而前端模板直接读
 * {@code result.findings.length},于是「没有结论的表」(最健康的表,恰恰最常见)
 * 在页面上直接报错。故这里全部用空集合初始化。
 *
 * <p>字符串字段也一律以空串初始化:Go 的这些字段没有 omitempty,缺值时输出 {@code ""} 而不是 {@code null}。
 */
@Data
public class TableCheckResult {
    /** 库名。 */
    private String database = "";
    /** 表名。 */
    private String table = "";
    /** 引擎(如 MergeTree);system.tables 查不到时为空串。 */
    private String engine = "";
    /** 分区键表达式原文。 */
    private String partitionKeyExpr = "";
    /** 排序键表达式原文。 */
    private String sortingKeyExpr = "";
    /** 主键表达式原文。 */
    private String primaryKeyExpr = "";
    /** 报告里的列(事实,不做判断)。 */
    private List<DDLCheckColumn> columns = new ArrayList<>();
    /** 体检结论;空列表表示本次列出的规则都没发现问题。 */
    private List<DDLCheckFinding> findings = new ArrayList<>();
    /**
     * 本次【只查了】这几条。回传它是为了让「0 条结论」不被误读成「这张表没问题」——
     * 覆盖面必须写在脸上。
     */
    private List<String> rules = new ArrayList<>();
}
