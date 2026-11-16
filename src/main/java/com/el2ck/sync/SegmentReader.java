package com.el2ck.sync;

/**
 * 【文件职责】定义分段读取器的统一协议，约束游标、批次、目标区间和映射扩展行为。
 */

import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.entity.sync.SyncTask;
import java.math.BigInteger;
import java.util.List;

/** 可恢复源读取契约。读取不推进位置；只有目标写入确认后才能提交 cursor。 */
abstract class SegmentReader implements SyncEngine.PageSource {
    // 具体读取器可冻结源窗口或维护文件/分区位置，但统一遵守“读取不提交、目标确认后提交”的恢复契约。
    final String key, targetKey;
    final List<Column> columns;
    final SyncCheckpointStore.Session checkpoint;
    final Long runId;
    final int batchSize, keyIndex;

    SegmentReader(List<Column> columns, SyncTask task, SyncCheckpointStore.Session checkpoint, Long runId) {
        this(columns,task,checkpoint,runId,true);
    }

    SegmentReader(List<Column> columns, SyncTask task, SyncCheckpointStore.Session checkpoint, Long runId,boolean keyed) {
        this.columns=columns; this.checkpoint=checkpoint; this.runId=runId;
        key=task.getStableKey(); batchSize=task.getBatchSize();
        if(!keyed) { keyIndex=-1; targetKey=null; return; }
        int index=-1;
        for(int i=0;i<columns.size();i++) if(key.equals(columns.get(i).getName())) index=i;
        if(index<0 || !isIntegerKeyType(columns.get(index).getType()))
            throw new IllegalStateException("分段键必须是非空整数列: "+key);
        keyIndex=index;
        SyncFieldMapping mapping=task.getMapping().stream().filter(m->key.equals(m.getSourceColumn())).findFirst()
                .orElseThrow(()->new IllegalStateException("分段键必须映射到目标表"));
        if(!columns.get(index).getType().equals(mapping.getType()) || Boolean.TRUE.equals(mapping.getNullable())
                || !com.el2ck.util.Sql.validIdent(mapping.getTargetColumn()))
            throw new IllegalStateException("分段键目标映射必须保留原整数类型并设为不可空");
        targetKey=mapping.getTargetColumn();
    }

/**
 * 判断稳定键是否属于可用整数精确排序的类型。
 *
 * @param type 本次操作使用的输入参数
 * @return boolean 类型的结果
 */
    static boolean isIntegerKeyType(String type) { return type!=null && type.matches("U?Int(8|16|32|64|128|256)"); }
/**
 * 把稳定键转换为规范十进制文本，并拒绝非整数输入。
 *
 * @param value 本次操作使用的输入参数
 * @return String 类型的结果
 */
    static String number(String value) {
        if(value==null || !value.matches("-?[0-9]+")) throw new IllegalStateException("分段键不是有效整数");
        return new BigInteger(value).toString();
    }
/**
 * 返回本轮不可越过的快照上界；实现必须保证恢复后上界稳定。
 *
 * @return String 类型的结果
 */
    abstract String upper();

    /** 在恢复/新轮次读取前验证源上界；快照和文件游标不使用源数据库水位。 */
    void validateWatermark(String last, String upper) { }
/**
 * 读取一段有界数据；实现必须尊重 last/high 边界并在批次结束前不推进已确认断点。
 *
 * @param last 本次操作使用的输入参数
 * @param upper 本次操作使用的输入参数
 * @return List<List<String>> 类型的结果
 */
    abstract List<List<String>> read(String last,String upper);
/**
 * 估算本轮剩余行数，仅用于进度展示，不作为数据完整性依据。
 *
 * @return Long 类型的结果
 */
    Long estimateTotalRows() { return null; }
/**
 * 为最后一行生成下一批次游标，游标必须可持久化并可精确重放。
 *
 * @param row 本次操作使用的输入参数
 * @return String 类型的结果
 */
    String cursor(List<String> row) { return number(row.get(keyIndex)); }
/**
 * 返回最近一次读取后的下一游标，供引擎在写入成功后提交。
 *
 * @param rows 本次操作使用的输入参数
 * @return String 类型的结果
 */
    String nextCursor(List<List<String>> rows) { return cursor(rows.get(rows.size()-1)); }
    /** 日志只展示可识别的位置；行值游标可覆盖此方法，避免输出源业务数据。 */
    String describeCursor(String cursor) { return cursor; }
/**
 * 判断当前快照是否已经读到固定上界。
 *
 * @param rows 本次操作使用的输入参数
 * @return boolean 类型的结果
 */
    boolean exhausted(List<List<String>> rows) { return rows.isEmpty(); }
/**
 * 向进度对象补充源端读取统计；默认实现允许读取器不覆盖。
 *
 */
    void updateProgress() { }
/**
 * 向目标表追加读取器保留字段及映射，例如分区、offset、轮次或重试令牌。
 *
 * @param targets 本次操作使用的输入参数
 * @param indexes 本次操作使用的输入参数
 * @param mappings 本次操作使用的输入参数
 */
    void appendMappings(List<String> targets,List<Integer> indexes,List<SyncFieldMapping> mappings) { }
/**
 * 在写入前准备目标对象或校验目标状态，默认无需额外动作。
 *
 * @param client 本次操作使用的输入参数
 * @param table 本次操作使用的输入参数
 * @param actual 本次操作使用的输入参数
 * @param autoCreate 本次操作使用的输入参数
 */
    void prepareTarget(com.el2ck.ckpool.CkClient client,String table,List<Column> actual,boolean autoCreate) { }
/**
 * 在读取下一批前执行实现相关准备，默认无需额外动作。
 *
 * @param client 本次操作使用的输入参数
 * @param table 本次操作使用的输入参数
 * @param sourceColumn 本次操作使用的输入参数
 * @param sourceId 本次操作使用的输入参数
 */
    void beforeBatch(com.el2ck.ckpool.CkClient client,String table,String sourceColumn,String sourceId) { }
/**
 * 在确认本轮完成前执行最终校验，默认无需额外动作。
 *
 */
    void beforeComplete() { }
/**
 * 生成本轮读取摘要，用于运行记录和恢复一致性检查。
 *
 * @param stats 本次操作使用的输入参数
 */
    void summarize(SyncEngine.Stats stats) { }
/**
 * 构造目标表待替换区间条件；支持断点恢复的读取器必须与 read 的边界一致。
 *
 * @param last 本次操作使用的输入参数
 * @param upper 本次操作使用的输入参数
 * @return String 类型的结果
 */
    String targetRange(String last,String upper) {
        return "`"+targetKey+"` <= "+number(upper)+(last==null ? "" : " AND `"+targetKey+"` > "+number(last));
    }
/**
 * 校验目标表是否满足该读取器的恢复、列和轮次要求。
 *
 * @param targetColumns 本次操作使用的输入参数
 */
    void validateTarget(List<Column> targetColumns) {
        String type=columns.get(keyIndex).getType();
        if(targetColumns.stream().noneMatch(c->targetKey.equals(c.getName()) && type.equals(c.getType())))
            throw new IllegalStateException("目标分段键的实际类型必须与源整数列完全一致且不可空");
    }
/**
 * 指示读取器是否为增量模式。
 *
 * @return boolean 类型的结果
 */
    boolean incremental() { return false; }
/**
 * 指示本轮开始前是否应清理目标区间或整表。
 *
 * @return boolean 类型的结果
 */
    boolean clearsTarget() { return !incremental() || checkpoint.state.replaceTarget; }

    public final List<List<String>> page(String where,int limit,int offset,String orderBy) {
        throw new UnsupportedOperationException("分段读取请使用 read(last, upper)");
    }
}
