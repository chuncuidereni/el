/**
 * 【文件职责】本文件定义表管理与数据编辑模块的查询或操作结果模型 TableStat，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/**
 * 某库下每张表的统计:列数 / 行数 / 磁盘大小(字节)。对应 Go service.TableStats。
 * <p>读取 system.columns / system.tables,服务端按「连接 + 库」做 TTL 缓存,前端提供手动刷新。
 */
@Data
public class TableStat {
    /** 表名。 */
    private String name;
    /** 列数(来自 system.columns 的聚合)。 */
    private int cols;
    /** system.tables.total_rows;引擎无法提供统计时为 null。 */
    private Long rows;
    /** system.tables.total_bytes;引擎无法提供统计时为 null。 */
    private Long bytes;
}
