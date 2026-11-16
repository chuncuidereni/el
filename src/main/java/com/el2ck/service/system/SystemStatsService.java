/**
 * 【文件职责】本文件定义系统监控与设置模块的业务服务 SystemStatsService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.system;

import com.el2ck.config.AppConfig;
import com.el2ck.mapper.system.AppSettingsMapper;
import com.el2ck.entity.system.SystemStats;
import org.springframework.stereotype.Service;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * 系统资源占用采样(对应 Go internal/system 的 Sampler)。
 *
 * <p>CPU 使用率靠<b>两次采样之间的进程 CPU 时间差</b>算出来,所以第一次调用必然返回 0 ——
 * 与 Go 的 Sampler 一样保留上次采样点。前端每 2 秒拉一次,第二次起数字才有意义。
 *
 * <p>⚠️ JVM 没有 Go 的 goroutine / runtime 堆 / 工作集这些概念,字段名沿用前端契约但取值不同,
 * 逐项口径见 {@link SystemStats} 的注释。这里只保证:不拿别的量冒充,并且取不到时给 0 而不是编一个数。
 */
@Service
public class SystemStatsService {

    /** 采样时间的显示格式(HH:mm:ss,与 Go 的 "15:04:05" 一致)。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 进程启动时间,用于算 uptime。 */
    private final long startedAtMillis = System.currentTimeMillis();

    /** 元数据库统计接口与数据目录,用于统计文件占用。 */
    private final AppSettingsMapper metadata;
    private final String dataDir;

    /** 上次采样的进程 CPU 时间(纳秒)与时刻,用于算使用率差。 */
    private volatile long lastProcessCpuNanos = -1;
    private volatile long lastSampleMillis = 0;

    /** 最近一次 GC 停顿(毫秒);由下面的通知监听器持续更新。 */
    private volatile double lastGcPauseMs = 0;

    /**
     * 文件大小的短 TTL 缓存(与 Go 同口径:30 秒)。
     *
     * <p>⚠️ 为什么必须有:前端每 2 秒拉一次,而 {@code dirSize} 对一个装满文件的
     * duckdb / uploads 目录是递归 walk —— 文件一多就是百毫秒级,还会把 IO 打满。
     * Go 的 Sampler 早就为这件事加了 30 秒缓存;本类此前每次采样都重算,
     * 属于迁移时漏掉的一处。
     */
    private static final long FILE_CACHE_TTL_MS = 30_000;

    /** 缓存的文件占用:0=元数据库,1=duckdb,2=uploads。 */
    private volatile long[] fileCache = new long[3];
    private volatile long fileCacheAt = 0;

    /**
     * 构造器:从应用配置取元数据库统计接口与数据目录,并挂上 GC 通知监听器。
     *
     * <p>JVM 的 GarbageCollectorMXBean 只暴露<b>累计</b>次数与累计耗时,"最近一次停顿"必须靠
     * GarbageCollectionNotification 才能拿到 —— 没有别的途径。监听器注册失败不影响其它字段采集。
     */
    public SystemStatsService(AppConfig config, AppSettingsMapper metadata) {
        this.metadata = metadata;
        this.dataDir = config.getDataDir() == null || config.getDataDir().trim().isEmpty()
                ? "./data" : config.getDataDir();
        registerGcListener();
    }

    /** 采集一次快照。 */
    public SystemStats collect() {
        long now = System.currentTimeMillis();
        SystemStats stats = new SystemStats();
        stats.setTime(LocalTime.now().format(TIME_FORMAT));
        stats.setUptimeSec((now - startedAtMillis) / 1000);
        stats.setCpuCores(Runtime.getRuntime().availableProcessors());
        stats.setCpuPercent(sampleCpuPercent(now));

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        stats.setAllocMB(toMb(heap.getUsed()));
        stats.setHeapInuseMB(toMb(heap.getCommitted()));
        // "向 OS 要的总量"≈ 堆已提交 + 非堆已提交;Go 的 Sys 还含栈与运行时结构,JVM 取不到,故是近似值
        stats.setSysMB(toMb(heap.getCommitted() + nonHeap.getCommitted()));
        stats.setWorkingSetMB(toMb(committedVirtualMemory()));

        // 字段名沿用 Go 的 goroutines,值是 JVM 的线程数
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        stats.setGoroutines(threads.getThreadCount());
        stats.setNumGC(totalGcCount());
        stats.setLastGCPauseMs(lastGcPauseMs);

        long[] sizes = fileSizes(now);
        stats.setDbFileMB(toMb(sizes[0]));
        stats.setDuckdbFileMB(toMb(sizes[1]));
        stats.setUploadsMB(toMb(sizes[2]));
        return stats;
    }

    /**
     * 三处文件占用(元数据库 / duckdb / uploads),带 30 秒缓存。
     *
     * <p>与 Go 的 {@code fileCache} 同一个做法:过期才重算,没过期直接复用。
     * 用 {@code synchronized} 而不是只靠 volatile:并发采样时不能让两次 walk 同时跑,
     * 那正是这个缓存要避免的开销。
     */
    private long[] fileSizes(long nowMillis) {
        synchronized (this) {
            if (nowMillis - fileCacheAt <= FILE_CACHE_TTL_MS && fileCacheAt != 0) {
                return fileCache.clone();
            }
            fileCache = new long[]{
                    metadataSize(),
                    dirSize(Paths.get(dataDir, "duckdb")),
                    dirSize(Paths.get(dataDir, "uploads")),
            };
            fileCacheAt = nowMillis;
            return fileCache.clone();
        }
    }

    /**
     * 用两次采样的进程 CPU 时间差除以墙钟时间差得到使用率。
     * 首次调用没有基线,返回 0;多核下结果会超过 100(与 Go 同口径,不除以核数)。
     */
    private double sampleCpuPercent(long nowMillis) {
        long cpuNanos = processCpuNanos();
        synchronized (this) {
            double percent = 0;
            if (lastProcessCpuNanos >= 0 && cpuNanos >= 0 && nowMillis > lastSampleMillis) {
                double elapsedSeconds = (nowMillis - lastSampleMillis) / 1000.0;
                double cpuSeconds = (cpuNanos - lastProcessCpuNanos) / 1e9;
                if (elapsedSeconds > 0) percent = cpuSeconds / elapsedSeconds * 100;
            }
            lastProcessCpuNanos = cpuNanos;
            lastSampleMillis = nowMillis;
            return percent;
        }
    }

    /** 进程累计 CPU 时间(纳秒);平台不支持时返回 -1(而非 0,以免被当成"这一秒没耗 CPU")。 */
    private static long processCpuNanos() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                long nanos = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
                return nanos < 0 ? -1 : nanos;
            }
        } catch (Throwable ignored) {
            // 非 HotSpot JVM 拿不到进程 CPU 时间(抛 UnsupportedOperationException/NoClassDefFoundError),
            // 此时 cpuPercent 恒为 0 —— 宁可显示 0,也不要用整机负载冒充进程占用
        }
        return -1;
    }

    /** 已提交的虚拟内存;取不到时返回 0。 */
    private static long committedVirtualMemory() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                long size = ((com.sun.management.OperatingSystemMXBean) os).getCommittedVirtualMemorySize();
                return Math.max(size, 0);
            }
        } catch (Throwable ignored) {
            // 同 processCpuNanos:拿不到就报 0,不猜
        }
        return 0;
    }

    /** 全部 GC 器的累计回收次数之和。 */
    private static long totalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            if (count > 0) total += count;
        }
        return total;
    }

    /** 订阅 GC 完成通知,记下最近一次停顿耗时。 */
    private void registerGcListener() {
        NotificationListener listener = (Notification notification, Object handback) -> {
            try {
                CompositeData data = (CompositeData) notification.getUserData();
                if (data == null) return;
                // 标准字段名由 GarbageCollectionNotificationInfo 定义,这里只取时长,不引入额外的 MXBean 依赖
                CompositeData info = (CompositeData) data.get("gcInfo");
                if (info != null && info.get("duration") != null) {
                    lastGcPauseMs = ((Number) info.get("duration")).doubleValue();
                }
            } catch (RuntimeException ignored) {
                // 通知格式与预期不符:保留上一次的值,不让一次解析失败影响整个采样接口
            }
        };
        try {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc instanceof NotificationEmitter) {
                    ((NotificationEmitter) gc).addNotificationListener(listener, null, null);
                }
            }
        } catch (RuntimeException ignored) {
            // 注册不上就只有 lastGCPauseMs 恒为 0,其余字段照常采集
        }
    }

    /** MySQL table/index allocation estimate; unavailable statistics report zero. */
    private long metadataSize() {
        try { return Math.max(0, metadata.metadataSizeBytes()); }
        catch (RuntimeException unavailable) { return 0; }
    }

    /** 单个文件大小;不存在返回 0。 */
    private static long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 目录递归大小;目录不存在返回 0。 */
    private static long dirSize(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).mapToLong(SystemStatsService::fileSize).sum();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 字节 → MB。 */
    private static double toMb(long bytes) {
        return bytes / 1024.0 / 1024.0;
    }

    /** 供测试断言"两次采样之间确有间隔"用;生产路径不调用。 */
    Duration uptime() {
        return Duration.ofMillis(System.currentTimeMillis() - startedAtMillis);
    }
}
