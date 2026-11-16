/**
 * 【文件职责】XXL-JOB 同步处理器适配层，只解析任务 ID 并委托 SyncService 启动同步。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.job;

import com.el2ck.service.sync.SyncService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

/**
 * XXL-JOB 定时同步任务入口(处理器名 elToCkSyncJob)。
 * 本类只做薄封装：任务参数填写待执行的同步任务 ID，启动同步的实际逻辑委托给 {@link SyncService}，
 * 避免在 Job 层堆积业务代码，也便于调度中心与业务服务解耦。
 */
@Component
public class XxlSyncJob {
    private final SyncService syncService;

    public XxlSyncJob(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * XXL-JOB 回调的同步任务启动入口。
     * 任务参数即待同步任务 ID：先校验非空，再解析为 long，
     * 交给 {@link SyncService#startRun(long)} 启动并返回本次运行的 runId 供后续追踪。
     */
    @XxlJob("elToCkSyncJob")
    public void execute() {
        // 从 XXL-JOB 上下文读取调度中心配置的任务参数
        String param = XxlJobHelper.getJobParam();
        // 参数为空时立即抛出，让调度中心判定本次执行失败，而非静默跳过
        if (param == null || param.trim().isEmpty()) {
            throw new IllegalArgumentException("XXL-JOB 参数必须填写同步任务 ID");
        }
        // 参数为数字串形式的同步任务 ID，解析失败同样按异常上抛
        long taskId = Long.parseLong(param.trim());
        // 启动同步并拿到本次运行 id，写入 XXL-JOB 日志便于调度中心排查
        long runId = syncService.startScheduledRun(taskId);
        XxlJobHelper.log("同步任务已启动: taskId={}, runId={}", taskId, runId);
    }
}
