package com.el2ck.sync;

import com.el2ck.ckpool.CkConfig;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.sync.SyncTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 保持历史指纹的字段顺序，允许证明一次配置变化仅为“已完成全量 -> 增量”。 */
final class SyncCheckpointFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private SyncCheckpointFingerprint() { }

    /** 日期分表使用独立指纹版本，动态发现的表清单不进配置指纹，失败恢复使用已冻结清单。 */
    static String mysqlDateTables(String secret, SyncTask t, DataSource ds, Map<String,Object> cfg,
                                 CkConfig target, String mode, boolean truncate) {
        return digest(secret, Arrays.asList("mysql-date-tables-v1", new TreeMap<>(cfg), target,
                ds.getId(), ds.getCode(), ds.getType(), t.getMysqlDateTables(), t.getSchema(), t.getMapping(),
                t.getTargetDb(), t.getTargetTable(), t.isOds(), t.getPhoneColumn(), t.getStableKey(),
                t.getBatchSize(), mode, SyncTask.MODE_FULL.equals(mode) && truncate,
                SyncTask.MODE_INCREMENTAL.equals(mode) ? t.getIncrColumn() : "",
                t.getOrderKey(), t.getSortingKeys(), t.getPrimaryKeys(), t.getPartitionExpr()));
    }

    /**
     * 全量只按分段主键读取，新表策略在该轮没有参与筛选，因此转增量时允许重新选择新表策略。
     * 仍用旧版本完整指纹证明来源、日期范围、映射、分段键和目标未变；不修改已落库的旧指纹。
     */
    static boolean completedMysqlDateFull(String secret, SyncTask task, DataSource ds, Map<String,Object> cfg,
                                         CkConfig target, SyncCheckpointStore.State old) {
        if (!SyncEngine.dateTableBaselineReady(old) || task.getMysqlDateTables() == null
                || !Objects.equals(task.getStableKey(), task.getIncrColumn())) return false;
        SyncTask candidate = new SyncTask();
        org.springframework.beans.BeanUtils.copyProperties(task, candidate);
        com.el2ck.entity.sync.MysqlDateTableRule rule = new com.el2ck.entity.sync.MysqlDateTableRule();
        org.springframework.beans.BeanUtils.copyProperties(task.getMysqlDateTables(), rule);
        candidate.setMysqlDateTables(rule);
        for (String policy : new String[]{null, "full"}) {
            rule.setNewTablePolicy(policy);
            for (boolean truncate : new boolean[]{true, false}) {
                if (Objects.equals(old.fingerprint,
                        mysqlDateTables(secret, candidate, ds, cfg, target, SyncTask.MODE_FULL, truncate))) return true;
            }
        }
        return false;
    }

    static String snapshot(String secret, SyncTask t, DataSource ds, Map<String,Object> cfg, CkConfig target,
                           String mode, boolean truncate, String incrColumn) {
        return digest(secret, Arrays.asList("source-snapshot-v1", new TreeMap<>(cfg), target,
                ds.getId(), ds.getCode(), ds.getType(), t.getSourceObject(), t.getMapping(),
                t.getTargetDb(), t.getTargetTable(), t.isOds(), t.getPhoneColumn(), t.getBatchSize(),
                mode, truncate, incrColumn, t.getOrderKey(), t.getSortingKeys(), t.getPrimaryKeys(), t.getPartitionExpr()));
    }

    static String segment(String secret, SyncTask t, DataSource ds, Map<String,Object> cfg, CkConfig target,
                          List<Column> columns, String mode, String incrColumn) {
        String base = digest(secret, Arrays.asList(new TreeMap<>(cfg), target, t.getSourceObject(), columns,
                t.getMapping(), t.getTargetDb(), t.getTargetTable(), t.isOds(), t.getPhoneColumn(),
                t.getStableKey(), t.getBatchSize(), t.getOrderKey(), t.getSortingKeys(), t.getPrimaryKeys(), t.getPartitionExpr()));
        return DataSource.CLICKHOUSE.equals(ds.getType()) ? base : digest(base,
                Arrays.asList("jdbc-segment-v1", ds.getId(), ds.getCode(), ds.getType(), mode, incrColumn));
    }

    /**
     * 老版本没有单独保存 mode，必须重算完整全量指纹来证明来源、映射及目标未改变。
     * 仅枚举全量时允许的清表开关、空增量列或当前增量列；绝不接受任意旧指纹。
     */
    static boolean completedFull(String secret, SyncTask t, DataSource ds, Map<String,Object> cfg,
                                 CkConfig target, List<Column> columns, SyncCheckpointStore.State old) {
        if (old == null || !old.complete || !old.initialized || old.pending != null || old.pendingAction != null
                || old.targetIdentity == null || old.fingerprint == null) return false;
        for (String incr : new LinkedHashSet<>(Arrays.asList("", null, t.getIncrColumn()))) {
            if (old.snapshotComplete || CkRowSegmentReader.MODE.equals(old.readerMode)) {
                for (boolean truncate : new boolean[]{true, false})
                    if (old.fingerprint.equals(snapshot(secret, t, ds, cfg, target, SyncTask.MODE_FULL, truncate, incr)))
                        return true;
            } else if (columns != null && t.getStableKey() != null && !t.getStableKey().trim().isEmpty()
                    && (DataSource.MYSQL.equals(ds.getType()) || DataSource.PGSQL.equals(ds.getType())
                    || DataSource.CLICKHOUSE.equals(ds.getType()))) {
                if (old.fingerprint.equals(segment(secret, t, ds, cfg, target, columns, SyncTask.MODE_FULL, incr)))
                    return true;
            }
        }
        return false;
    }

    private static String digest(String prefix, Object fields) {
        try { return SyncCheckpointStore.hash(prefix + JSON.writeValueAsString(fields)); }
        catch (Exception e) { throw new IllegalStateException("无法计算断点配置指纹", e); }
    }
}
