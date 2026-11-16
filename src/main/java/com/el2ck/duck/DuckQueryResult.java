package com.el2ck.duck;

/**
 * 【文件职责】承载 DuckDB 查询的列和行结果，供上层 API 转换。
 */

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DuckDB 查询结果:列名/类型 + 行(单元格统一转字符串，便于 JSON 序列化)。
 * 对应 Go duckdb.QueryResult。
 */
@Data
public class DuckQueryResult {
    /** 各列列名，与 types 按序一一对应 */
    private List<String> columns = new ArrayList<>();
    /** 各列数据库类型名(JDBC ColumnTypeName)，与 columns 按序对应 */
    private List<String> types = new ArrayList<>();
    /** 行数据:每行是各单元格字符串化后的列表(便于 JSON 序列化与统一处理) */
    // 与 rawRows 严格等长同序:同一行的同一个格子分别以“文本形态”和“原始形态”出现两次。
    // 文本形态供普通节点(过滤/映射/写入)统一按字符串处理;原始形态只给工作流 JS 节点,
    // 让脚本里拿到的是数字/日期对象而不是文本(见 Engine.nodeCode)。
    private List<List<String>> rows = new ArrayList<>();
    /** 原始 JDBC 值，供工作流 JS 节点保留数字/日期等运行时类型。 */
    // 元素可能是 null(对应 rows 中的 ""),也可能仍是驱动返回的对象;取值方负责判空,
    // 不要假设 rawRows 的值一定能直接拼进 SQL。
    private List<List<Object>> rawRows = new ArrayList<>();
}
