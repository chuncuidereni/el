/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域数据模型 ColumnAliasHint，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/**
 * 一个输出别名对应的源字段,用于【精确】取该列的 ClickHouse 字段注释。
 * 对应 Go {@code service.ColumnAliasHint}。
 *
 * <p>⚠️ 为什么需要它:可视化向导允许给输出字段改别名(如 mdn → 手机号),
 * 而结果列名就是别名 —— 别名在 ClickHouse 里根本不存在,拿它去 {@code system.columns}
 * 按列名查注释必然查不到。而配置里本来就写着每个别名的来源(源表 + 源列),按它查是精确的。
 *
 * <p>手写 SQL 没有这份映射,为空,仍走「按列名猜」的兜底。
 */
@Data
public class ColumnAliasHint {
    /** 输出列别名(结果里的列名)。 */
    private String alias;
    /** 源列所在的库。 */
    private String database;
    /** 源列所在的表。 */
    private String table;
    /** 源列名。 */
    private String column;
}
