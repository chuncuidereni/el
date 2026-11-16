package com.el2ck.sync;

/**
 * 【文件职责】聚合同步批次进度并生成结构化进度文本，供运行记录和前端轮询展示。
 */

import com.el2ck.entity.sync.SyncRun;
import com.el2ck.entity.sync.SyncRunProgress;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 每次运行独占一个上报器；普通批次最多每两秒写一次，首批、阶段变化和收尾强制刷新。 */
final class SyncProgressReporter {
    // 进度上报只写页面展示用的运行快照，不承担断点提交语义；失败不能替代或推进同步断点。
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SyncProgressReporter.class);
    private final long taskId,runId,start;
    private final Consumer<SyncRun> sink;
    private final LongSupplier clock;
    private final SyncRunProgress progress=new SyncRunProgress();
    private long read,written,baselineRows,lastFlush;
    private String baselineKey;
    private boolean flushed,complete;
    SyncProgressReporter(long taskId,long runId,Consumer<SyncRun> sink) {
        this(taskId,runId,sink,System::nanoTime);
    }
    /** 单调时钟控制节流与速度；测试可注入时钟，无需真实等待。 */
    SyncProgressReporter(long taskId,long runId,Consumer<SyncRun> sink,LongSupplier clock) {
        this.taskId=taskId;this.runId=runId;this.sink=sink;
        this.clock=clock;this.start=clock.getAsLong();
        progress.setPhase("准备中");flush(true);
    }
/**
 * 设置当前源读取进度字段。
 *
 * @param type 本次操作使用的输入参数
 * @param object 本次操作使用的输入参数
 */
    void source(String type,String object) { progress.setSourceType(type);progress.setCurrentObject(object);flush(false); }
/**
 * 更新当前同步阶段名称。
 *
 * @param phase 本次操作使用的输入参数
 */
    void phase(String phase) {
        boolean changed=!Objects.equals(progress.getPhase(),phase);
        progress.setPhase(phase);progress.setNextRetryAtMs(null);flush(changed);
    }
/**
 * 记录当前处理的表或文件对象。
 *
 * @param object 本次操作使用的输入参数
 */
    void object(String object) { progress.setCurrentObject(object);flush(false); }
/**
 * 累计从源端读取的行数或字节数。
 *
 * @param rows 本次操作使用的输入参数
 */
    void read(long rows) { read+=rows;flush(false); }
/**
 * 累计已成功写入目标的行数或字节数。
 *
 * @param rows 本次操作使用的输入参数
 */
    void written(long rows) { boolean first=written==0 && rows>0;written+=rows;flush(first); }
/**
 * 设置本次统计的读取行数快照。
 *
 * @return long 类型的结果
 */
    long readRows() { return read; }
/**
 * 设置可估算的总量；未知总量时保持为空。
 *
 * @param rows 本次操作使用的输入参数
 */
    void total(long rows) { if(rows>=0) { progress.setTotalRows(rows);progress.setEstimated(false); }flush(false); }
/**
 * 指定进度单位，使前端按行或字节解释数值。
 *
 * @param unit 本次操作使用的输入参数
 * @param done 本次操作使用的输入参数
 * @param total 本次操作使用的输入参数
 */
    void units(String unit,long done,long total) {
        progress.setUnit(unit);progress.setCompletedUnits(done);progress.setTotalUnits(total);flush(false);
    }
/**
 * 标记当前阶段完成并记录结束原因。
 *
 * @param phase 本次操作使用的输入参数
 */
    void finish(String phase) { progress.setPhase(phase);progress.setNextRetryAtMs(null);flush(true); }

    /**
     * 接收已保存的断点快照；pending 仍属未确认批次，绝不能算入 written。
     * 采集源快照时只增加读取数；续传速度只使用本次新增行数，不能把以前完成的行数算进去。
     */
    void checkpoint(SyncCheckpointStore.State state) {
        if(!Objects.equals(state.runId,runId)) return;
        String phase=state.complete ? "同步收尾" : state.phase;
        if(phase==null || phase.isEmpty()) phase="准备同步";
        boolean immediate=state.complete || !Objects.equals(progress.getPhase(),phase)
                || !Objects.equals(progress.getUnit(),state.progressUnit)
                || !Objects.equals(progress.getCurrentObject(),state.currentObject)
                || (state.totalUnits!=null && state.totalUnits.equals(state.completedUnits)
                    && !Objects.equals(progress.getCompletedUnits(),state.completedUnits))
                || (written==0 && state.rows>0);
        written=state.rows;read=Math.max(state.rows,state.snapshotRows);
        baselineRows=state.runStartRows;baselineKey=SyncCheckpointStore.positionLabel(state,state.runStartKey);complete=state.complete;
        progress.setSourceType(state.sourceType);progress.setPhase(phase);
        if(state.currentObject!=null) progress.setCurrentObject(state.currentObject);
        progress.setCurrentKey(SyncCheckpointStore.positionLabel(state,state.last));progress.setRetryCount(state.retryCount);
        progress.setNextRetryAtMs(null);
        progress.setUnit(state.progressUnit);progress.setCompletedUnits(state.completedUnits);progress.setTotalUnits(state.totalUnits);
        // 完整快照和结束时的行数是精确值；system.parts 等只是估算，不额外 COUNT 大表。
        progress.setTotalRows(complete ? Long.valueOf(state.rows) : state.estimatedTotalRows);
        progress.setEstimated(!complete && !state.snapshotComplete && state.estimatedTotalRows!=null);
        flush(immediate);
    }

    void retry(int count) { retry(count,0L); }
    /**
     * 重试计数和下次恢复时间必须一起立即落库。多次重试时阶段名不变，
     * 若继续依赖普通节流，页面会在退避等待期间一直看到上一次快照。
     */
    void retry(int count,long delayMs) {
        progress.setRetryCount(count);progress.setPhase("等待自动重试");
        progress.setNextRetryAtMs(delayMs>0 ? System.currentTimeMillis()+delayMs : null);
        flush(true);
    }
/**
 * 生成可持久化的结构化进度文本。
 *
 * @param force 本次操作使用的输入参数
 */
    private void flush(boolean force) {
        long now=clock.getAsLong();
        if(!force && flushed && now-lastFlush<2_000_000_000L) return;
        long runRows=Math.max(0,written-baselineRows);
        double speed=runRows*1_000_000_000.0/Math.max(1,now-start);
        progress.setConfirmedRows(written);progress.setRunRows(runRows);
        progress.setAverageRowsPerSecond(speed);
        progress.setPercent(null);progress.setEtaSeconds(null);
        Long total=progress.getTotalRows();
        if(complete) {
            progress.setPercent(100.0);progress.setEtaSeconds(0L);
        } else if("字节".equals(progress.getUnit()) || "表".equals(progress.getUnit()) || "定位文件行号".equals(progress.getPhase())) {
            // 下载/定位是当前阶段进度，不是目标写入完成度；不得用写入行速率估算其剩余时间。
            Long done=progress.getCompletedUnits(),units=progress.getTotalUnits();
            if(done!=null && units!=null && units>0)
                progress.setPercent(Math.min("表".equals(progress.getUnit()) ? 99.9 : 100.0,Math.max(0L,done)*100.0/units));
        } else if(total!=null && total>0) {
            // 只有真正读完并确认才显示 100%；估算总量偏小时不能提前宣告完成。
            progress.setPercent(Math.min(99.9,written*100.0/total));
            if(speed>0 && total>written) progress.setEtaSeconds((long)Math.ceil((total-written)/speed));
        }
        progress.setUpdatedAtMs(System.currentTimeMillis());
        SyncRun run=new SyncRun();run.setId(runId);run.setTaskId(taskId);
        run.setRowsRead(read);run.setRowsWritten(written);run.setProgress(progress);
        // 新增量轮次也可能继承旧水位键；只有已有确认行数时才据此标记续传。
        run.setResumed(baselineRows>0);
        run.setCheckpointFrom(baselineKey);run.setCheckpointRows(baselineRows);
        lastFlush=now;flushed=true;
        try { sink.accept(run); }
        catch(RuntimeException e) { log.warn("event=sync.progress.save_failed task_id={} run_id={}",taskId,runId,e); }
    }
}
