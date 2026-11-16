package com.el2ck.ckpool;

/**
 * 【文件职责】表示 ClickHouse 查询结果的列元数据与行数据快照，供上层服务转换为 API 响应。
 */

import lombok.Data;

import java.util.List;

/**
 * 一条查询结果集。与 Go ckpool.QueryResult 一致。
 * 由 CkClient.query 填充:columns/types 依 ResultSet 元数据生成,rows 中每个单元格
 * 已经 normalize 归一化为可安全 JSON 序列化的表示(Bean + Lombok @Data 直出 JSON)。
 * 0 行时 rows 为 [] 而非 null(前端直接迭代);total 恒等于 rows.size()。
 */
@Data
public class QueryResult {
    /** 列名列表(按 SELECT 结果的列顺序,用于前端渲染表头)。 */
    private List<String> columns;
    /** 各列数据库类型名(对应 Go DatabaseTypeName,供类型推断)。 */
    // 取自 JDBC 元数据,是驱动侧的 ClickHouse 类型文本(如 UInt64、Nullable(String)、
    // Decimal(18, 4)),与 columns 等长且同序;前端据此决定右对齐/数字格式化等展示方式。
    private List<String> types;
    /** 数据行列表,每行按列顺序存放单元格值;0 行时为空列表 [] 而非 null。 */
    // 元素已由 CkClient.normalize 归一化为可安全 JSON 序列化的表示(时间转文本、Decimal 转
    // 字符串、byte[] 转 UTF-8 文本、数组递归),因此这里不会出现驱动专有类型。
    private List<List<Object>> rows;
    /** 命中的总行数(与 rows.size() 一致)。 */
    // 用 Integer 而非 long:与 Go 版字段类型对齐(JSON 输出为数字),行数受 max_result_rows
    // 兜底限制,不会溢出。
    private Integer total;
}
