/**
 * 【文件职责】ARB 服务表自动摊平调度器，负责启动时清理失联运行记录、周期触发摊平和优雅停机。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.job;

import com.el2ck.service.arb.ArbWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务表自动摊平的后台循环,对应 Go {@code internal/schedule/flatten.go} 的
 * {@code StartFlattener} 与 {@code store.ResetStaleArbRuns}。
 *
 * <h3>为什么需要它(而不是让物化视图自己搞定)</h3>
 *
 * 聚合表由【每源一个物化视图】实时更新 —— 源表一插入就流进聚合层。但服务表(摊平后的普通列)
 * 必须【全表 GROUP BY】才能算,而物化视图是 INSERT 触发器,只看得见刚插进来的那一批,
 * 看不到全表。硬给聚合表再挂一个物化视图去写服务表,只会把「某一个源那一批」摊成 name=''
 * 这种看着正常的错值,而且服务表是普通 MergeTree,不会按 mdn 合并 → 一个号码多行。
 *
 * <p>所以服务表的更新只能靠外部触发:手动点「刷新服务表」,或者这里的定时循环。
 *
 * <p>循环本身很便宜:每 tick 只比两张表的【最后写入时间】(读 system.parts 元数据,不扫数据),
 * 聚合表比服务表新才真的去摊一次。
 *
 * <h3>与 Go 的两点差异</h3>
 *
 * <ul>
 *   <li>Go 用 goroutine + ticker;这里用守护线程的 {@link ScheduledExecutorService},
 *       与 {@code ScheduledSyncRunner} 同一路数,便于在 {@link PreDestroy} 里干净地停下。</li>
 *   <li>Go 的 {@code flattenOnce} 要 recover panic —— 那个 goroutine 是它自己起的,cron 的
 *       Recover 管不到,一次 panic 会终止整个进程(连带所有正在跑的同步任务)。Java 侧
 *       单个工作流的异常在 {@link ArbWorkflowService#flattenStale()} 里就地吞掉,
 *       不依赖线程级兜底;这里再兜一层 Error 也无意义(守护线程的 OutOfMemory 已不可恢复)。</li>
 * </ul>
 *
 * <p>⚠️ 不并发:同一工作流的摊平由数据库部分唯一索引挡着(同时只允许一条 running),
 * 所以上一次没跑完时这次会直接失败跳过,不会叠起来。
 */
@Component
public class ArbFlattenJob {

    private static final Logger LOG = LoggerFactory.getLogger(ArbFlattenJob.class);

    /** 工作流服务(摊平循环与启动重置都在它上面)。 */
    private final ArbWorkflowService svc;

    /**
     * 摊平间隔(毫秒)。&lt;= 0 表示关闭自动摊平。
     *
     * <p>默认 1 分钟,与 Go 的 {@code arbFlattenInterval} 默认值一致。
     */
    private final long intervalMs;

    /** 单线程调度器:回调串行执行,不会叠起来。 */
    private ScheduledExecutorService executor;

    public ArbFlattenJob(ArbWorkflowService svc,
                         @Value("${app.arb-flatten-interval-ms:60000}") long intervalMs) {
        this.svc = svc;
        this.intervalMs = intervalMs;
    }

    /**
     * 启动时先做两件事:重置遗留的 running 记录,再起摊平循环。
     *
     * <p>重置为什么必须有:进程在收尾前被杀时,run 行会停在 running,而部分唯一索引
     * {@code idx_arb_runs_one_running} 挡着 —— 这条工作流在【下一次进程重启前再也跑不了】。
     */
    @PostConstruct
    public void start() {
        try {
            int reset = svc.resetStaleRuns();
            if (reset > 0) {
                LOG.warn("重置了 {} 条卡在 running 的工作流运行记录(进程重启导致)", reset);
            }
        } catch (RuntimeException e) {
            // 非致命:仅影响历史状态展示与重入判断,失败只记日志(与 Go 的启动清理同口径)
            LOG.warn("重置遗留的工作流运行状态失败: {}", e.getMessage());
        }

        if (intervalMs <= 0) {
            LOG.info("服务表自动摊平:已关闭(设置 app.arb-flatten-interval-ms 可开启)");
            return;
        }
        LOG.info("服务表自动摊平:每 {} 毫秒检查一次(只摊落后的)", intervalMs);
        executor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "arb-flatten");
            t.setDaemon(true);
            return t;
        });
        // initialDelay 用同一个间隔:启动瞬间各表状态未定,没必要立刻扫一遍
        executor.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** 一次检查。任何异常都只记日志 —— 摊平只是优化,下个 tick 再试。 */
    private void tick() {
        try {
            int done = svc.flattenStale();
            if (done > 0) {
                LOG.info("自动摊平了 {} 个工作流的服务表", done);
            }
        } catch (RuntimeException e) {
            // 整体失败(比如库读不了):记一笔,下个 tick 再试
            LOG.warn("自动摊平检查失败: {}", e.getMessage());
        }
    }

    /** 应用退出时停掉循环。 */
    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
