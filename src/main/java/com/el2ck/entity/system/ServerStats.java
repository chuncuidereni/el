/**
 * 【文件职责】本文件定义系统监控与设置模块的查询或操作结果模型 ServerStats，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.system;

import lombok.Data;

/**
 * 目标库(ClickHouse)服务的运行指标快照,对应 Go {@code service.ServerStats}。
 *
 * <p>与 {@link SystemStats} 互补:那个是<b>平台进程自己</b>的占用,这个是被监控的
 * <b>目标库服务</b>的指标(内存 / CPU / 磁盘 / 连接)。
 *
 * <p>⚠️ 约定:凡是「0 等于未知」的项一律用 <b>-1</b> 表示未知(见 {@code MonitorService.ratioPct}),
 * 前端据此显示 {@code --}。不要把 0 当成真实值展示 —— 那会让人以为服务真的空闲。
 */
@Data
public class ServerStats {
    /** 采样时间(HH:mm:ss)。 */
    private String time;

    /** 未能采集的监控项及原因;其对应的数值以 -1 表示未知。 */
    private java.util.List<String> warnings = new java.util.ArrayList<>();

    /** 服务已运行秒数。 */
    private long uptimeSec;

    /** 服务常驻内存(RSS),MB。 */
    private double memResidentMB;
    /** 服务生效的内存上限,MB(0 = 未知)。 */
    private double memLimitMB;
    /** 常驻 / 上限 × 100;-1 = 分母未知。 */
    private double memOfLimitPct;
    /** 所在机器总内存,MB。 */
    private double osMemTotalMB;
    /** 所在机器可用内存,MB。 */
    private double osMemAvailMB;
    /** 常驻 / 机器总内存 × 100;-1 = 分母未知。 */
    private double memOfOSTotalPct;

    /** 整机 CPU 使用率(%);-1 = 该库未暴露 CPU 时间指标。 */
    private double cpuPercent;

    /** 各挂载点容量。 */
    private java.util.List<DiskStat> disks = new java.util.ArrayList<>();

    /** 客户端连接合计(TCP + HTTP + 集群内互联)。 */
    private long totalConnections;
    /** TCP 连接数。 */
    private long tcpConnections;
    /** HTTP 连接数。 */
    private long httpConnections;
    /** 集群内互联连接数。 */
    private long isectConnections;
    /** 正在执行的查询数。 */
    private long runningQueries;
    /** 正在执行的合并数。 */
    private long runningMerges;
}
