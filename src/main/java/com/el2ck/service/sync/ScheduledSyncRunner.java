/**
 * 【文件职责】本文件定义数据同步模块的调度运行器 ScheduledSyncRunner。按调度配置触发后台任务，并隔离单次执行失败对后续调度的影响。
 */

package com.el2ck.service.sync;

import com.el2ck.service.auth.ExecutionIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 应用内定时任务兜底调度器。
 *
 * XXL-JOB 启用时由 XXL-JOB Admin 调度；未启用时，本组件直接按任务 Cron
 * 触发同步，保证单机部署也能使用“定时执行”。每次重排都会取消已失效的任务，
 * 因此编辑 Cron、停用或删除任务无需重启应用。
 */
@Component
public class ScheduledSyncRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ScheduledSyncRunner.class);
    private final ExecutionIdentityService identities;
    // 同步服务：到期先保存排队请求，再由派发器领取执行。
    private final SyncService syncService;
    // XXL-JOB 是否启用;启用后调度交由 XXL-JOB Admin,本组件空转
    private final boolean xxlEnabled;
    // 单线程定时调度器:回调串行执行,避免对同一批任务的并发触发
    private final ScheduledExecutorService executor;
    private final Clock clock;
    // taskId → 当前调度条目(含 Cron 与已排定的 Future)
    private final Map<Long, ScheduleEntry> entries = new HashMap<>();
    // 与 entries 共用监视器，关闭后不接受重排，也不允许旧回调登记下一次执行。
    private boolean stopped;

    /**
     * 注入依赖。xxl.job.enabled 为 true 时由 XXL-JOB Admin 负责调度,
     * 本组件不再自行触发定时任务。
     */
    @Autowired
    public ScheduledSyncRunner(ExecutionIdentityService identities, SyncService syncService,
                               @Value("${xxl.job.enabled:false}") boolean xxlEnabled) {
        this(identities, syncService, xxlEnabled, newExecutor(), Clock.systemDefaultZone());
    }

    /** 注入时钟和队列，便于验证过期回调及关闭交错，不依赖真实时间等待。 */
    ScheduledSyncRunner(ExecutionIdentityService identities, SyncService syncService, boolean xxlEnabled,
                        ScheduledExecutorService executor, Clock clock) {
        this.identities = identities;
        this.syncService = syncService;
        this.xxlEnabled = xxlEnabled;
        this.executor = executor;
        this.clock = clock;
    }

    private static ScheduledExecutorService newExecutor() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sync-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** 应用启动后立即做一次全量重排,使已启用的定时任务尽快生效。 */
    @PostConstruct
    public void start() {
        // reconcile 会按任务定义建立全部调度条目
        reconcile();
    }

    /** 周期扫描任务定义，及时响应新增、编辑、停用和删除。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void reconcile() {
        // XXL-JOB 接管时不再由本组件自行调度。
        if (xxlEnabled) {
            return;
        }
        // 对调度表加锁,与回调内 finally 安排下一次互斥,防止并发重排。
        synchronized (entries) {
            if (stopped) return;
            // desired:仍应保持调度的 taskId → Cron;随后据此做「增改」与「清理」两轮比对。
            Map<Long, String> desired = new HashMap<>();
            // 读取全部任务定义
            for (Map.Entry<Long,String> task : identities.scheduledSyncs().entrySet()) {
                // Cron 统一归一到调度格式(五段则前置秒位 0)
                String cron = SyncService.normalizeCronExpression(task.getValue());
                // 无有效 Cron 无法排程,跳过
                if (cron.isEmpty()) {
                    continue;
                }
                // 记为仍应保留的条目
                desired.put(task.getKey(), cron);
                ScheduleEntry old = entries.get(task.getKey());
                // 尚未调度或 Cron 有变:撤销旧 Future,按新 Cron 重排。
                if (old == null || !old.cron.equals(cron)) {
                    // 取消旧定时任务(cancel(false):已触发的回调不中断)
                    if (old != null) old.future.cancel(false);
                    // 即便新 Cron 非法或无下次执行时刻，也不能保留已取消的旧条目。
                    entries.remove(task.getKey());
                    scheduleNext(task.getKey(), cron);
                }
            }
            // 清理已不在期望集合的条目(任务被删除/停用/改为手动后自动失效)。
            entries.entrySet().removeIf(entry -> {
                if (desired.containsKey(entry.getKey())) return false;
                // 取消其待触发的 Future
                entry.getValue().future.cancel(false);
                return true;
            });
        }
    }

    /**
     * 为任务排定下一次触发:解析 Cron → 计算下次时刻 → 交单线程调度器延迟执行。
     * 回调末尾复用本方法,实现逐次滚动排程。调用方必须持有 entries 监视器。
     */
    private void scheduleNext(long taskId, String cron) {
        final CronExpression expression;
        try {
            // 解析 Cron(此处已归一过;非法仅告警,不阻断其他任务)
            expression = CronExpression.parse(SyncService.normalizeCronExpression(cron));
        } catch (IllegalArgumentException e) {
            LOG.warn("event=sync.invalid_cron task_id={} cron={}", taskId, cron);
            return;
        }
        // 计算系统时区下的下一次执行时刻;已无后续时刻(如一次性)则不再排程
        ZonedDateTime next = expression.next(ZonedDateTime.now(clock));
        if (next == null) return;
        // 折算为距现在的毫秒延迟,至少 1ms
        // 兜底非负:若目标时刻已过(如上一次回调迟到占用了单线程),按最小延迟立即补触发
        long delay = Math.max(1, next.toInstant().toEpochMilli() - clock.millis());
        // 条目本身是本次排程的身份。先登记再提交；回调也先取 entries 锁，能看到完整条目。
        ScheduleEntry entry = new ScheduleEntry(cron,next.toInstant().toEpochMilli());
        entries.put(taskId, entry);
        try {
            entry.future = executor.schedule(() -> fire(taskId, entry), delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            entries.remove(taskId, entry);
            throw e;
        }
    }

    /** 旧回调不能覆盖新排程；入队时在任务行锁内复核配置，真正执行时再持任务会话锁复核。 */
    private void fire(long taskId, ScheduleEntry entry) {
        synchronized (entries) {
            if (stopped || entries.get(taskId) != entry) return;
        }
        boolean persisted=false;
        try {
            // 入队访问元数据库，不能持有全局调度表锁。
            syncService.enqueueScheduledRun(taskId, entry.cron,entry.scheduledAtMs);
            persisted=true;
        } catch (RuntimeException e) {
            LOG.warn("event=sync.scheduled_enqueue_failed task_id={} reason={}", taskId, e.getMessage());
        } finally {
            synchronized (entries) {
                if (!stopped && entries.get(taskId) == entry) {
                    if(persisted) {
                        entries.remove(taskId);
                        scheduleNext(taskId, entry.cron);
                    } else {
                        // 写库失败不前进触发游标，保留同一触发身份直到确认入队。
                        entry.future=executor.schedule(()->fire(taskId,entry),5,TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    /** 重启后继续扫描数据库等待记录；排队本身不创建等待线程或占用执行名额。 */
    @Scheduled(fixedDelay = 5000, initialDelay = 3000)
    public void dispatch() {
        if(xxlEnabled) return;
        synchronized(entries) { if(stopped) return; }
        try { syncService.dispatchScheduledQueue(); }
        catch(RuntimeException e) { LOG.warn("event=sync.queue_dispatch_failed reason={}",e.getMessage()); }
    }

    /** 应用关闭时回收调度线程并清空调度表。 */
    @PreDestroy
    public void stop() {
        synchronized (entries) {
            if (stopped) return;
            stopped = true;
            for (ScheduleEntry entry : entries.values()) entry.future.cancel(false);
            entries.clear();
        }
        executor.shutdownNow();
    }

    /** 某任务的当前调度条目:Cron 用于比对是否变化,Future 用于取消防旧重排。 */
    private static final class ScheduleEntry {
        // 该条目标记的 Cron;未变化则不必重排
        private final String cron;
        private final long scheduledAtMs;
        // 已排定的下一次执行句柄(可取消)
        private ScheduledFuture<?> future;

        /**
         * 创建一条调度记录。
         *
         * @param cron 当前生效的 Cron 表达式
         */
        private ScheduleEntry(String cron,long scheduledAtMs) {
            this.cron = cron;
            this.scheduledAtMs=scheduledAtMs;
        }
    }
}
