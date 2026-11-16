/**
 * 【文件职责】本文件定义系统监控与设置模块的查询或操作结果模型 SystemStats，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.system;

import lombok.Data;

/**
 * 系统资源占用快照(对应 Go internal/system 的 Stats,字段名与前端 SystemStats 一致)。
 *
 * <p>⚠️ <b>口径差异</b>:Go 版测的是 Go 运行时(goroutine 数、GC 次数、runtime 堆),
 * JVM 没有同名概念。本类保留原有字段名以维持前端契约,但取值是<b>JVM 的对应量</b>,
 * 逐一对照见各字段注释 —— 前端标签也必须按下面的口径显示,不能沿用「协程数」这类说法。
 */
@Data
public class SystemStats {
    /** 采样时间(HH:mm:ss)。 */
    private String time;
    /** 进程已运行秒数。 */
    private long uptimeSec;
    /** 逻辑核数。 */
    private int cpuCores;
    /** 进程 CPU 使用率(%)。多核下会超过 100(与 Go 同口径,不除以核数)。 */
    private double cpuPercent;
    /** 堆已用(对应 Go 的 Alloc:当前存活对象占用)。 */
    private double allocMB;
    /** JVM 向操作系统申请的总内存:堆已提交 + 非堆已提交(对应 Go 的 Sys)。 */
    private double sysMB;
    /** 堆已提交大小(对应 Go 的 HeapInuse:已从 OS 划过来、含尚未使用的空页)。 */
    private double heapInuseMB;
    /**
     * 进程占用的内存。JVM 没有「工作集(物理内存)」接口,这里取<b>已提交的虚拟内存</b>
     * (OperatingSystemMXBean#getCommittedVirtualMemorySize)。它与 Go 的工作集不是同一个量,
     * 通常明显偏大,前端标签须写明是虚拟内存。
     */
    private double workingSetMB;
    /** 当前线程数(对应 Go 的 goroutines;字段名保留以维持契约)。 */
    private int goroutines;
    /** GC 总次数。 */
    private long numGC;
    /** 最近一次 GC 停顿(毫秒);从未观测到 GC 时为 0。 */
    private double lastGCPauseMs;

    /** MySQL 元数据库表与索引占用估算值(MB)，字段名兼容旧接口。 */
    private double dbFileMB;
    /** DuckDB 文件数据库大小。 */
    private double duckdbFileMB;
    /** uploads 目录大小。 */
    private double uploadsMB;
}
