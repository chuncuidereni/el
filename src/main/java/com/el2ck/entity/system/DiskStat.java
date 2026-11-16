/**
 * 【文件职责】本文件定义系统监控与设置模块的查询或操作结果模型 DiskStat，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.system;

import lombok.Data;

/**
 * 单个挂载点容量,对应 Go {@code service.DiskStat}。
 *
 * <p>⚠️ {@code totalGB} 为 0 表示该版本没有暴露总量,此时 {@code usedPct} = -1,
 * 前端退化成只展示剩余容量。
 */
@Data
public class DiskStat {
    /** 磁盘名(如 {@code default})。 */
    private String name;
    /** 挂载路径。 */
    private String path;
    /** 剩余容量,GB。 */
    private double freeGB;
    /** 总容量,GB(0 = 该版本未暴露)。 */
    private double totalGB;
    /** 已用百分比;总量未知时为 -1。 */
    private double usedPct;
}
