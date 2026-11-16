/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域结构模型 TableKeys，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

import java.util.List;

/**
 * 表键信息:主键/分区键/排序键。
 * *Expr 为原始表达式文本;同名字段数组为表达式解析出的、且确为该表真实列的标识符交集。
 */
@Data
public class TableKeys {
    /** 主键表达式原文(如 a, b)。 */
    private String primaryKeyExpr;
    /** 分区键表达式原文。 */
    private String partitionKeyExpr;
    /** 排序键表达式原文。 */
    private String sortingKeyExpr;
    /** 主键列(解析出的真实列标识符集合)。 */
    private List<String> primaryKey;
    /** 分区键列(解析出的真实列标识符集合)。 */
    private List<String> partitionKey;
    /** 排序键列(解析出的真实列标识符集合)。 */
    private List<String> sortingKey;
}
