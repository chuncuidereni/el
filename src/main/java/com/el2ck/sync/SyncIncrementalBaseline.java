package com.el2ck.sync;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.util.Sql;
import com.el2ck.util.SyncMappingTypes;
import com.el2ck.util.SyncWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 建立增量边界：原任务转换需证明旧全量指纹；旧任务已删除时由用户预览确认接管。调用者必须持有任务锁和目标锁。 */
final class SyncIncrementalBaseline {
    private SyncIncrementalBaseline() { }

    static final class Existing {
        final String identity,watermark,definitionHash;
        final long rows;
        Existing(String identity,String watermark,long rows,String definitionHash) {
            this.identity=identity;this.watermark=watermark;this.rows=rows;this.definitionHash=definitionHash;
        }
    }

    /**
     * 用户明确选择接管时的只读检查。旧任务已删除，无法再声称有历史全量证明；
     * 这里只验证当前表的来源、结构、非空水位，完整性由确认页面向用户明确说明。
     */
    static Existing inspectExisting(SyncTask task,String sourceCode,List<Column> columns,boolean snapshot,CkClient client) {
        QueryResult metadata=client.query("SELECT engine,toString(uuid),toString(metadata_modification_time),length(dependencies_table),create_table_query FROM system.tables WHERE database=? AND name=?",
                Arrays.asList((Object)task.getTargetDb(),task.getTargetTable()));
        if(metadata.getRows().size()!=1 || metadata.getRows().get(0).size()!=5
                || !"MergeTree".equals(String.valueOf(metadata.getRows().get(0).get(0)))
                || !"0".equals(String.valueOf(metadata.getRows().get(0).get(3))))
            throw new IllegalStateException("接管目标必须是已存在且没有依赖视图的普通 MergeTree 表");
        String identity=metadata.getRows().get(0).subList(0,3).toString();
        SyncFieldMapping wm=mapping(task,columns,task.getIncrColumn());
        boolean composite=!snapshot && !task.getStableKey().equals(task.getIncrColumn());
        SyncFieldMapping key=composite ? mapping(task,columns,task.getStableKey()) : wm;
        String w=ident(wm.getTargetColumn()),k=ident(key.getTargetColumn());
        String table=ident(task.getTargetDb())+"."+ident(task.getTargetTable());
        QueryResult schema=client.query("DESCRIBE TABLE "+table);
        validateStoredType(wm,schema); if(composite) validateStoredType(key,schema);
        String sourceColumn=task.isOds() ? "ods_source_id" : "source_id";
        if(schema.getRows().stream().noneMatch(c->c.size()>=2 && sourceColumn.equals(String.valueOf(c.get(0)))))
            throw new IllegalStateException("目标缺少来源字段，不能确认属于当前数据源");
        String projection=composite
                ? "count(),count("+w+"),count("+k+"),toString(argMax("+w+",tuple("+w+","+k+"))),toString(argMax("+k+",tuple("+w+","+k+")))"
                : "count(),count("+w+"),toString(max("+w+"))";
        // 在同一聚合查询中核对整个表的来源，不能只看 WHERE source_id 后的子集而忽略其他数据。
        QueryResult result=client.query("SELECT "+projection+",countIf("+ident(sourceColumn)+"="+Sql.quoteString(sourceCode)+") FROM "+table);
        if(result.getRows().size()!=1 || result.getRows().get(0).size()!=(composite ? 6 : 4))
            throw new IllegalStateException("无法读取目标表增量起点");
        List<Object> row=result.getRows().get(0);
        long count=Long.parseLong(String.valueOf(row.get(0)));
        if(count<=0) throw new IllegalStateException("目标表为空，无需接管，请直接执行增量任务");
        if(count!=Long.parseLong(String.valueOf(row.get(row.size()-1))))
            throw new IllegalStateException("目标表包含其他来源或来源为空的数据，不能自动接管；请核对原全量数据源");
        if(count!=Long.parseLong(String.valueOf(row.get(1))) || (composite && count!=Long.parseLong(String.valueOf(row.get(2)))))
            throw new IllegalStateException("目标增量字段存在空值，不能建立可信起点");
        String boundary=String.valueOf(row.get(composite ? 3 : 2));
        SyncWatermark.requireExact(boundary,wm.getType());
        if(!snapshot) {
            String id=SegmentReader.number(String.valueOf(row.get(composite ? 4 : 2)));
            if(composite) {
                try { boundary=new ObjectMapper().writeValueAsString(Arrays.asList(boundary,id)); }
                catch(Exception e) { throw new IllegalStateException("无法记录复合起点",e); }
            } else boundary=id;
        }
        return new Existing(identity,boundary,count,SyncBatchWriter.definitionHash(String.valueOf(metadata.getRows().get(0).get(4))));
    }

    /** 只允许尚未开始目标写入的新任务（包括旧版遗留的纯采集快照）接管。 */
    static void requireUnwritten(SyncCheckpointStore.State state,String fingerprint) {
        String reason=unwrittenReason(state);
        if(reason!=null) throw new IllegalStateException(reason);
        if(state!=null && !fingerprint.equals(state.fingerprint))
            throw new IllegalStateException("任务、源连接或目标结构已改变，与原采集断点不一致；请恢复原配置后再核验接管");
    }

    /** 页面与确认接口共用已知状态限制；实际配置指纹仍由持锁预览/确认校验。 */
    static String unwrittenReason(SyncCheckpointStore.State state) {
        if(state==null) return null;
        if(state.pending!=null || state.pendingHash!=null || state.pendingAction!=null
                || (state.pendingStopTokens!=null && !state.pendingStopTokens.isEmpty()))
            return "存在待确认写入，请先使用原配置继续传输，不能接管覆盖";
        if(state.baselineAdoptedAtMs!=null || state.snapshotBaseline!=null || state.last!=null || state.targetIdentity!=null)
            return "已有有效增量起点或目标断点，请直接继续传输，无需重复接管";
        if(state.complete || state.initialized || state.rows!=0)
            return "已有写入进度或已完成轮次，请直接执行，不能接管覆盖";
        if(state.replaceTarget) return "任务处于重新传输轮次，请先完成或处理该轮次";
        return null;
    }

    static void establish(SyncTask task, String sourceCode, List<Column> sourceColumns,
                          SyncCheckpointStore.State old, SyncCheckpointStore.State next,
                          boolean snapshot, CkClient client) {
        if (!old.complete || !old.initialized || old.pending != null || old.pendingAction != null || old.targetIdentity == null)
            throw new IllegalStateException("全量任务尚未确认完成，请恢复原配置并完成全量后再切换增量");
        QueryResult identity = client.query("SELECT engine,toString(uuid),toString(metadata_modification_time),length(dependencies_table),create_table_query FROM system.tables WHERE database=? AND name=?",
                Arrays.asList((Object) task.getTargetDb(), task.getTargetTable()));
        if (identity.getRows().size() != 1 || identity.getRows().get(0).size() != 5
                || !"MergeTree".equals(String.valueOf(identity.getRows().get(0).get(0)))
                || !"0".equals(String.valueOf(identity.getRows().get(0).get(3)))
                || !old.targetIdentity.equals(identity.getRows().get(0).subList(0, 3).toString()))
            throw new IllegalStateException("目标表已被重建或修改，不能从原全量结果建立增量起点");
        String ddl=String.valueOf(identity.getRows().get(0).get(4));
        SyncBatchWriter.requireDefinition(old,ddl);

        SyncFieldMapping watermark = mapping(task, sourceColumns, task.getIncrColumn());
        boolean composite = !snapshot && !task.getStableKey().equals(task.getIncrColumn());
        SyncFieldMapping key = composite ? mapping(task, sourceColumns, task.getStableKey()) : watermark;
        String w = ident(watermark.getTargetColumn()), k = ident(key.getTargetColumn());
        String table = ident(task.getTargetDb()) + "." + ident(task.getTargetTable());
        QueryResult schema = client.query("DESCRIBE TABLE " + table);
        validateStoredType(watermark, schema);
        if (composite) validateStoredType(key, schema);
        String where = ident(task.isOds() ? "ods_source_id" : "source_id") + "=" + Sql.quoteString(sourceCode);
        // 快照全量可能采用追加模式，只使用该已完成轮次的行；分段全量原本要求清表。
        if (!SyncBatchWriter.MODE.equals(old.writerMode)
                && (old.snapshotComplete || CkRowSegmentReader.MODE.equals(old.readerMode)))
            where += " AND " + ident(SnapshotSegmentReader.ROUND) + "=" + Sql.quoteString(old.token);
        String projection = composite
                ? "count(),count(" + w + "),count(" + k + "),toString(argMax(" + w + ",tuple(" + w + "," + k
                    + "))),toString(argMax(" + k + ",tuple(" + w + "," + k + ")))"
                : "count(),count(" + w + "),toString(max(" + w + "))";
        // 行数、空值和最大边界在同一查询内取得，避免独立查询观察到不同版本的数据。
        QueryResult result = client.query("SELECT " + projection + " FROM " + table + " WHERE " + where);
        if (result.getRows().size() != 1 || result.getRows().get(0).size() != (composite ? 5 : 3))
            throw new IllegalStateException("无法读取已完成全量的增量起点");
        List<Object> row = result.getRows().get(0);
        long count = Long.parseLong(String.valueOf(row.get(0)));
        if (count != old.rows || (old.snapshotComplete && count != old.snapshotRows))
            throw new IllegalStateException("目标数据行数与已完成全量断点不一致，不能自动切换增量；请检查目标数据是否被清理或改写");
        if (count != Long.parseLong(String.valueOf(row.get(1)))
                || (composite && count != Long.parseLong(String.valueOf(row.get(2)))))
            throw new IllegalStateException("已完成全量的增量字段存在空值，请选择非空且持续递增的字段");

        String boundary = null;
        if (count > 0) {
            boundary = String.valueOf(row.get(composite ? 3 : 2));
            SyncWatermark.requireExact(boundary, watermark.getType());
            if (!snapshot) {
                String id = SegmentReader.number(String.valueOf(row.get(composite ? 4 : 2)));
                if (composite) {
                    try { boundary = new ObjectMapper().writeValueAsString(Arrays.asList(boundary, id)); }
                    catch (Exception e) { throw new IllegalStateException("无法保存增量起点", e); }
                } else boundary = id;
            }
        }
        // 查询/校验全部成功后才修改新状态；由调用者在首次采集或写入前保存进原 checkpoint JSON。
        if (snapshot) next.snapshotBaseline = boundary;
        else next.last = boundary;
        next.inheritTarget(old);
        next.targetDefinitionHash=SyncBatchWriter.definitionHash(ddl);
        next.phase = "已从全量结果建立增量起点";
    }

    private static SyncFieldMapping mapping(SyncTask task, List<Column> columns, String name) {
        SyncFieldMapping mapping = task.getMapping().stream().filter(m -> name.equals(m.getSourceColumn())).findFirst()
                .orElseThrow(() -> new IllegalStateException("增量字段必须包含在原全量字段映射中"));
        Column source = columns.stream().filter(c -> name.equals(c.getName())).findFirst()
                .orElseThrow(() -> new IllegalStateException("原全量结果缺少增量字段"));
        // 全量允许类型转换；不能将已截断/舍入的目标值当作原始水位。
        // 使用统一无损规则，兼容 BIGINT -> Int64、TIMESTAMP(3) -> DateTime64(3) 等跨库别名。
        if (name.equals(task.getPhoneColumn()) || !SyncEngine.isIncrementalType(mapping.getType())
                || SyncMappingTypes.error(source.getType(), mapping.getType(), true) != null
                || (mapping.getDefaultValue() != null && !mapping.getDefaultValue().trim().isEmpty()))
            throw new IllegalStateException("全量转增量要求增量字段无损映射且不经过号码或默认值转换，请选择无损映射的递增字段");
        return mapping;
    }

    private static void validateStoredType(SyncFieldMapping mapping, QueryResult schema) {
        for (List<Object> column : schema.getRows()) {
            if (column.size() >= 2 && mapping.getTargetColumn().equals(String.valueOf(column.get(0)))) {
                if (SyncMappingTypes.error(mapping.getType(), String.valueOf(column.get(1)), true) == null) return;
                break;
            }
        }
        throw new IllegalStateException("原全量目标列缺失或精度不足，无法建立可信增量起点: " + mapping.getTargetColumn());
    }

    private static String ident(String value) {
        if (!Sql.validIdent(value)) throw new IllegalStateException("增量字段或目标表标识非法");
        return "`" + value + "`";
    }
}
