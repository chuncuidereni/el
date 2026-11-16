package com.el2ck.sync;

/**
 * 【文件职责】实现一次性源快照读取，并区分全量替换与轮次标记，确保失败后可按稳定边界重放。
 */

import com.el2ck.ckpool.CkClient;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.*;
import com.el2ck.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** 无稳定键的源先形成持久快照，确认完整后才写目标；恢复写入不再依赖源游标。 */
final class SnapshotSegmentReader extends SegmentReader {
    // 快照源先把分页数据和哈希完整持久化，确认快照完整后才写目标；恢复只依赖快照，不重新读取源游标。
    static final String ROUND="_el_sync_round", ROW="_el_sync_row";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final SyncTask task;
    private final SftpResumeCache cache;
    private List<String> hashes;
    SnapshotSegmentReader(SyncTask task,SyncCheckpointStore.Session session,Long runId,SftpResumeCache cache) {
        super(session.state.snapshotColumns,task,session,runId,false);
        this.task=task; this.cache=cache;
    }
/**
 * 捕获一次性源快照上界和元数据，后续分页只在该固定范围内推进。
 *
 * @param task 本次操作使用的输入参数
 * @param session 本次操作使用的输入参数
 * @param cache 本次操作使用的输入参数
 * @param columns 本次操作使用的输入参数
 * @param source 本次操作使用的输入参数
 */
    static void capture(SyncTask task,SyncCheckpointStore.Session session,SftpResumeCache cache,
                        List<Column> columns,SyncEngine.PageSource source) {
        SyncCheckpointStore.State state=session.state;
        requireCaptureAllowed(state);
        if(state.snapshotRows!=0 || state.snapshotPageCount!=0 || !state.snapshotHashes.isEmpty())
            throw new IllegalStateException("重新采集前必须先清理失效快照页");
        state.snapshotColumns=columns;
        state.snapshotWatermark=state.snapshotBaseline; state.phase="采集源快照"; state.progressUnit="行";
        state.totalUnits=null; state.completedUnits=0L; session.save();
        int wi=-1; String wt=null;
        if(SyncTask.MODE_INCREMENTAL.equals(task.getMode())) {
            for(int i=0;i<columns.size();i++) if(columns.get(i).getName().equals(task.getIncrColumn())) wi=i;
            if(wi<0) throw new IllegalStateException("源增量字段不存在");
            for(SyncFieldMapping m:task.getMapping()) if(m.getSourceColumn().equals(task.getIncrColumn())) wt=SyncEngine.mappingType(m);
            if(wt==null) throw new IllegalStateException("增量字段必须映射");
        }
        source.watermark(null,null);
        List<List<String>> buffer=new ArrayList<>();
        try {
            while(true) {
                session.checkLock(); cancelled();
                List<List<String>> page=source.page(null,task.getBatchSize(),0,null);
                if(page.isEmpty()) break;
                for(List<String> row:page) {
                    if(row.size()!=columns.size()) throw new IllegalStateException("源快照字段数变化");
                    if(wi>=0) {
                        String value=row.get(wi);
                        if(value==null || value.trim().isEmpty()) throw new IllegalStateException("增量水位不能为空");
                        SyncWatermark.requireExact(value,wt);
                        if(state.snapshotBaseline!=null && compare(value,state.snapshotBaseline,wt)<=0) continue;
                        if(state.snapshotWatermark==null || compare(value,state.snapshotWatermark,wt)>0) state.snapshotWatermark=value;
                    }
                    buffer.add(row);
                    if(buffer.size()==task.getBatchSize()) { write(session,cache,buffer); buffer=new ArrayList<>(); }
                }
            }
            if(!buffer.isEmpty()) write(session,cache,buffer);
            // 清单已逐页刷盘；只有 EOF、所有数据页及清单都确认后才发布完整标记。
            state.snapshotManifestHash=cache.snapshotManifestHash();
            state.snapshotComplete=true; state.phase="源快照已保存";
            state.estimatedTotalRows=state.snapshotRows; state.estimateAttempted=true;
            state.totalUnits=state.snapshotRows; state.completedUnits=state.snapshotRows; session.save();
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("保存源快照失败",e); }
    }

    private static void requireCaptureAllowed(SyncCheckpointStore.State state) {
        if(state.snapshotComplete || state.initialized || state.pending!=null || state.pendingHash!=null
                || state.pendingAction!=null || state.rows!=0 || state.last!=null || state.upperCaptured
                || state.chunkOffset!=0 || state.chunkHash!=null
                || (state.pendingStopTokens!=null && !state.pendingStopTokens.isEmpty()))
            throw new IllegalStateException("完整源快照或写入断点已存在，不能重新采集覆盖");
    }

    /** 先腾出失效页占用，再连接源；完整快照、待确认批次和已写入轮次绝不进入此路径。 */
    static void resetCapture(SyncCheckpointStore.Session session,SftpResumeCache cache) {
        requireCaptureAllowed(session.state); session.checkLock();
        cache.resetSnapshotPages(session::checkLock);
        SyncCheckpointStore.State state=session.state;
        state.snapshotHashes.clear(); state.snapshotPageCount=0; state.snapshotManifestHash=null;
        state.snapshotRows=0; state.snapshotColumns=null; state.completedUnits=0L;
        state.snapshotWatermark=state.snapshotBaseline; state.estimatedTotalRows=null; state.estimateAttempted=false;
        try { cache.writeSnapshotManifest(Collections.emptyList()); }
        catch(java.io.IOException e) { throw new IllegalStateException("初始化快照清单失败",e); }
        session.save();
    }
/**
 * 比较两个快照位置，判断是否仍有未读取数据。
 *
 * @param a 本次操作使用的输入参数
 * @param b 本次操作使用的输入参数
 * @param type 本次操作使用的输入参数
 * @return int 类型的结果
 */
    private static int compare(String a,String b,String type) {
        if(SyncTime.baseType(type).startsWith("date") || SyncTime.isTimestamp(type))
            return SyncTime.parse(a).compareTo(SyncTime.parse(b));
        return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b));
    }
/**
 * 将当前批次写入目标并推进快照内位置。
 *
 * @param session 本次操作使用的输入参数
 * @param cache 本次操作使用的输入参数
 * @param rows 本次操作使用的输入参数
 */
    private static void write(SyncCheckpointStore.Session session,SftpResumeCache cache,List<List<String>> rows) throws Exception {
        cancelled(); session.checkLock();
        byte[] bytes=JSON.writeValueAsBytes(rows);
        if(session.state.snapshotPageCount==Integer.MAX_VALUE) throw new IllegalStateException("快照分页数量超过支持范围");
        Path path=cache.snapshotPage(session.state.snapshotPageCount);
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer data=ByteBuffer.wrap(bytes); while(data.hasRemaining()) file.write(data); file.force(true);
        }
        cache.appendSnapshotHash(SyncCheckpointStore.hash(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
        session.state.snapshotPageCount++;
        session.state.snapshotRows+=rows.size(); session.state.completedUnits=session.state.snapshotRows; session.save();
    }
/**
 * 读取固定快照的下一页，并保持稳定排序。
 *
 * @param index 本次操作使用的输入参数
 * @return List<List<String>> 类型的结果
 */
    private List<List<String>> page(int index) {
        try {
            byte[] bytes=Files.readAllBytes(cache.snapshotPage(index));
            if(hashes==null || !SyncCheckpointStore.hash(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).equals(hashes.get(index)))
                throw new IllegalStateException("源快照缓存校验失败，已停止恢复");
            List<List<String>> rows=JSON.readValue(bytes,new TypeReference<List<List<String>>>() {});
            long expected=Math.min((long)batchSize,checkpoint.state.snapshotRows-(long)index*batchSize);
            if(rows==null || expected<=0 || rows.size()!=expected)
                throw new IllegalStateException("源快照批次长度异常");
            for(List<String> row:rows) {
                if(row==null || row.size()!=columns.size()) throw new IllegalStateException("源快照字段数异常");
            }
            return rows;
        } catch(RuntimeException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("读取完整源快照失败，请恢复应用数据目录中的原缓存",e); }
    }
/**
 * 校验快照身份和读取进度，防止恢复时使用已变化的源数据。
 *
 */
    void verify() {
        cancelled(); checkpoint.checkLock();
        SyncCheckpointStore.State state=checkpoint.state;
        if(!state.snapshotComplete || state.snapshotRows<0 || columns==null || columns.isEmpty() || state.snapshotHashes==null)
            throw new IllegalStateException("源快照清单不完整");
        long pages=state.snapshotRows/batchSize+(state.snapshotRows%batchSize==0 ? 0 : 1);
        // 旧完整快照只迁移一次清单；不重新采集、不修改分页数据及已确认游标。
        if(state.snapshotManifestHash==null) {
            if(pages!=state.snapshotHashes.size()) throw new IllegalStateException("源快照总行数与分页清单不一致");
            for(String hash:state.snapshotHashes) {
                if(hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("源快照校验清单无效");
            }
            try { cache.writeSnapshotManifest(state.snapshotHashes); }
            catch(java.io.IOException e) { throw new IllegalStateException("保存独立快照清单失败",e); }
            state.snapshotManifestHash=cache.snapshotManifestHash(); state.snapshotPageCount=state.snapshotHashes.size();
            state.snapshotHashes.clear(); checkpoint.save();
        }
        if(pages!=state.snapshotPageCount || !state.snapshotManifestHash.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("源快照总行数与独立清单不一致");
        try {
            if(Files.size(cache.snapshotManifest())!=pages*65L
                    || !state.snapshotManifestHash.equals(cache.snapshotManifestHash()))
                throw new IllegalStateException("源快照摘要清单损坏，已停止恢复");
            hashes=Files.readAllLines(cache.snapshotManifest(),java.nio.charset.StandardCharsets.US_ASCII);
        } catch(java.io.IOException e) { throw new IllegalStateException("读取快照摘要清单失败，请恢复原缓存",e); }
        if(pages!=hashes.size()) throw new IllegalStateException("源快照摘要清单页数不一致");
        for(String hash:hashes) {
            if(hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("源快照校验清单无效");
        }
        // 这里只检查清单，避免写入前再读数十 GB 缓存。每页使用前仍核验文件、哈希、行数及列数。
        // 已确认写入的页不再访问；未确认页丢失/损坏时 read() 会停止，绝不当成 EOF 跳过。
        if(state.last!=null) {
            long last=Long.parseLong(state.last);
            if(last<0 || last>state.snapshotRows || (last!=state.snapshotRows && last%batchSize!=0))
                throw new IllegalStateException("源快照断点边界无效");
        }
    }
    @Override String upper() { return Long.toString(checkpoint.state.snapshotRows); }
    @Override List<List<String>> read(String last,String upper) {
        long from=last==null ? 0 : Long.parseLong(last);
        if(from>=checkpoint.state.snapshotRows) return Collections.emptyList();
        if(from%batchSize!=0) throw new IllegalStateException("源快照断点不在批次边界");
        List<List<String>> rows=page(Math.toIntExact(from/batchSize));
        for(int i=0;i<rows.size();i++) { rows.get(i).add(checkpoint.state.token); rows.get(i).add(Long.toString(from+i+1)); }
        checkpoint.state.phase="写入快照";
        return rows;
    }
    @Override String nextCursor(List<List<String>> rows) { return rows.get(rows.size()-1).get(columns.size()+1); }
    @Override String targetRange(String last,String upper) {
        return "`"+ROUND+"`="+Sql.quoteString(checkpoint.state.token)+" AND `"+ROW+"`>"+(last==null ? "0" : number(last))+" AND `"+ROW+"`<="+number(upper);
    }
    @Override boolean incremental() { return SyncTask.MODE_INCREMENTAL.equals(task.getMode()); }
    @Override boolean clearsTarget() { return checkpoint.state.replaceTarget || (!incremental() && task.isTruncate()); }
    @Override void updateProgress() { checkpoint.state.progressUnit="行"; checkpoint.state.completedUnits=checkpoint.state.rows; checkpoint.state.totalUnits=checkpoint.state.snapshotRows; }
    @Override void appendMappings(List<String> targets,List<Integer> indexes,List<SyncFieldMapping> mappings) {
        // 轮次与行号只留在读取游标/MySQL 断点，不写入业务表。
    }
    @Override void prepareTarget(CkClient client,String table,List<Column> actual,boolean autoCreate) {
        // 业务列由 SyncMappingTypes 统一校验；不修改目标字段。
    }
    @Override void validateTarget(List<Column> actual) { prepareTarget(null,null,actual,false); }
    @Override public void close() { }
/**
 * 在分页和写入边界检查取消请求。
 *
 */
    private static void cancelled() { if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已取消或超时"); }
}
