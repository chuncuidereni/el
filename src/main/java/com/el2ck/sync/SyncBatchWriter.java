package com.el2ck.sync;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.sync.SyncTask;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无目标辅助列的同步写入协议。MySQL 持久化读取游标、转换时刻、子批次位置和 SQL 摘要；
 * ClickHouse 以确定的 block token 识别响应丢失后的重试。不会把业务数据复制到 MySQL。
 * 同一目标有未完成任务时禁止其他平台任务写入；平台外也不得并发改写该表或清理去重日志。
 */
final class SyncBatchWriter {
    static final String MODE="mysql-dedup-v1";
    private static final int WINDOW=20000;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SyncBatchWriter.class);
    private static final Pattern WINDOW_SETTING=Pattern.compile("\\bnon_replicated_deduplication_window\\s*=\\s*(\\d+)");
    private SyncBatchWriter() { }

    /** 只修改 MergeTree 去重设置，不 ADD COLUMN，也不删除已有列。 */
    static String prepare(CkClient client,String table,SyncTask task,SyncCheckpointStore.Session session,Runnable enableDedup) {
        SyncCheckpointStore.State state=session.state;
        List<Object> metadata=preflight(client,task,state);
        String identity=metadata.subList(0,3).toString(), uuid=String.valueOf(metadata.get(1));
        String ddl=String.valueOf(metadata.get(4));
        String schema=withoutWindow(ddl);
        if(needsDedupSetup(metadata)) {
            if(MODE.equals(state.writerMode))
                throw new IllegalStateException("目标去重窗口已被关闭或缩小，无法确认旧写入；断点已保留，请检查目标配置");
            state.dedupSetupSchema=schema; state.dedupSetupUuid=uuid; session.save();
            enableDedup.run();
            metadata=metadata(client,task);
            if(!uuid.equals(String.valueOf(metadata.get(1))) || !schema.equals(withoutWindow(String.valueOf(metadata.get(4)))))
                throw new IllegalStateException("设置去重窗口期间目标表发生变化，停止同步");
            identity=metadata.subList(0,3).toString();
            Matcher applied=WINDOW_SETTING.matcher(maskQuoted(String.valueOf(metadata.get(4))));
            if(!applied.find() || Long.parseLong(applied.group(1))<WINDOW)
                throw new IllegalStateException("目标去重设置未生效，停止同步");
        }
        if(state.targetIdentity!=null) state.targetIdentity=identity;
        state.targetDefinitionHash=definitionHash(String.valueOf(metadata.get(4)));
        session.preparedWriterVersion=String.valueOf(metadata.get(5));
        // 没有在途子批次时可使用当前版本；旧 pending 缺少版本不能伪造成已证明来自当前版本。
        if(state.chunkHash==null) state.writerServerVersion=session.preparedWriterVersion;
        else if(state.writerServerVersion==null)
            LOG.warn("event=sync.legacy_chunk_version_unknown task_id={} recovery_uses_legacy_dedup_contract=true",task.getId());
        state.dedupSetupSchema=null; state.dedupSetupUuid=null;
        // 旧版 pending 仍需先按旧字段清理一次；清理完成后才切换写入协议。
        if(state.pending==null) state.writerMode=MODE;
        session.save();
        return identity;
    }

    /**
     * 读取源之前检查可确定的写入条件。只读元数据，不设置去重窗口、不保存断点、不建表。
     * prepare 在真正写入前再次调用，防止采集期间目标被修改。
     */
    static List<Object> preflight(CkClient client,SyncTask task,SyncCheckpointStore.State state) {
        if(state.writerMode!=null && !MODE.equals(state.writerMode))
            throw new IllegalStateException("不支持此断点写入协议，请使用匹配的程序版本");
        requireSynchronous(client);
        List<Object> metadata=metadata(client,task);
        String identity=metadata.subList(0,3).toString(), uuid=String.valueOf(metadata.get(1));
        String ddl=String.valueOf(metadata.get(4)), schema=withoutWindow(ddl);
        boolean setupRecovery=state.dedupSetupSchema!=null && state.dedupSetupSchema.equals(schema)
                && uuid.equals(state.dedupSetupUuid);
        if(state.dedupSetupSchema!=null && !setupRecovery)
            throw new IllegalStateException("配置去重期间目标结构发生变化，已保留断点，请检查目标表");
        if(state.targetIdentity!=null && !state.targetIdentity.equals(identity) && !setupRecovery)
            throw new IllegalStateException("目标表已被重建或修改，不能使用旧断点；请恢复原目标或重新传输");
        if(!setupRecovery) requireDefinition(state,ddl);
        requireWriterVersion(state,String.valueOf(metadata.get(5)));
        if(MODE.equals(state.writerMode) && needsDedupSetup(metadata))
            throw new IllegalStateException("目标去重窗口已被关闭或缩小，无法确认旧写入；断点已保留，请检查目标配置");
        return metadata;
    }

    /** 预检与实际设置共用条件，已有足够窗口时不额外要求设置流程的权限。 */
    static boolean needsDedupSetup(List<Object> metadata) {
        Matcher window=WINDOW_SETTING.matcher(maskQuoted(String.valueOf(metadata.get(4))));
        return !window.find() || Long.parseLong(window.group(1))<WINDOW;
    }

    static void requireSynchronous(CkClient client) {
        QueryResult settings=client.query("SELECT name,value FROM system.settings WHERE name IN ('async_insert','insert_deduplication_token')");
        boolean hasToken=false;
        for(List<Object> row:settings.getRows()) {
            if(row.size()!=2) throw new IllegalStateException("目标 ClickHouse 写入能力检查结果异常");
            String name=String.valueOf(row.get(0));
            if("insert_deduplication_token".equals(name)) hasToken=true;
            if("async_insert".equals(name) && !"0".equals(String.valueOf(row.get(1))))
                throw new IllegalStateException("断点续传需要目标连接关闭 async_insert");
        }
        // 21.8 没有异步写入设置，但也没有自定义去重令牌。仅允许默认内容去重会吞掉
        // 合法的重复行，不能把它当作相同能力；在采集源数据前明确提示目标要求。
        if(!hasToken)
            throw new IllegalStateException("目标 ClickHouse 缺少 insert_deduplication_token，无法保证断点重试不重复写入；请使用支持去重令牌的目标库（如内置 25.3），21.8 可继续作为源库");
    }

    private static List<Object> metadata(CkClient client,SyncTask task) {
        QueryResult result=client.query("SELECT engine,toString(uuid),toString(metadata_modification_time),length(dependencies_table),create_table_query,version() "
                +"FROM system.tables WHERE database=? AND name=?",Arrays.asList(task.getTargetDb(),task.getTargetTable()));
        if(result.getRows().size()!=1 || result.getRows().get(0).size()!=6
                || !"MergeTree".equals(String.valueOf(result.getRows().get(0).get(0)))
                || !"0".equals(String.valueOf(result.getRows().get(0).get(3))))
            throw new IllegalStateException("断点续传目标必须是没有依赖视图的独立普通 MergeTree 表");
        List<Object> row=result.getRows().get(0);
        if(row.get(5)==null || row.get(5).toString().trim().isEmpty())
            throw new IllegalStateException("无法确认 ClickHouse 写入版本，停止同步");
        return row;
    }

    static String definitionHash(String ddl) {
        if(ddl==null || ddl.trim().isEmpty() || "null".equals(ddl))
            throw new IllegalStateException("无法读取完整目标建表定义，停止同步");
        return SyncCheckpointStore.hash(ddl.trim());
    }

    static void requireDefinition(SyncCheckpointStore.State state,String ddl) {
        String current=definitionHash(ddl);
        if(state.targetDefinitionHash!=null && !state.targetDefinitionHash.equals(current))
            throw new IllegalStateException("目标建表定义与已保存断点不一致（字段、默认值、分区或表设置可能已修改），已停止续传并保留断点");
    }

    /** 仅有未确认 INSERT 时限制版本切换；正常轮次完成后的升级不因该版本字段被拦截。 */
    static void requireWriterVersion(SyncCheckpointStore.State state,String current) {
        if(state.chunkHash!=null && state.writerServerVersion!=null && !state.writerServerVersion.equals(current))
            throw new IllegalStateException("ClickHouse 版本已改变，无法确认未完成子批次仍使用相同去重规则；断点已保留，请核对旧批次写入结果后处理，不能直接跨版本重放");
    }

    /** 仅忽略本协议修改的设置，其余 DDL（包括列、排序、TTL 和其他设置）必须相同。 */
    private static String withoutWindow(String ddl) {
        String masked=maskQuoted(ddl);
        Matcher setting=WINDOW_SETTING.matcher(masked);
        if(!setting.find()) return ddl.trim();
        int start=setting.start(), end=setting.end(), before=start-1;
        while(before>=0 && Character.isWhitespace(masked.charAt(before))) before--;
        if(before>=0 && masked.charAt(before)==',') start=before;
        else {
            while(end<masked.length() && Character.isWhitespace(masked.charAt(end))) end++;
            if(end<masked.length() && masked.charAt(end)==',') {
                end++;
                while(end<masked.length() && Character.isWhitespace(masked.charAt(end))) end++;
            } else {
                int settings=masked.lastIndexOf("SETTINGS",start);
                if(settings<0) throw new IllegalStateException("无法解析目标去重设置");
                start=settings;
            }
        }
        return (ddl.substring(0,start)+ddl.substring(end)).trim();
    }

    /** 不把列注释、默认字符串或引用标识中的设置名称误当成真实表设置；保留字符下标。 */
    private static String maskQuoted(String sql) {
        StringBuilder out=new StringBuilder(sql);
        char quote=0;
        for(int i=0;i<sql.length();i++) {
            char ch=sql.charAt(i);
            if(quote!=0) {
                out.setCharAt(i,' ');
                if(ch=='\\' && i+1<sql.length()) out.setCharAt(++i,' ');
                else if(ch==quote) {
                    if(i+1<sql.length() && sql.charAt(i+1)==quote) out.setCharAt(++i,' ');
                    else quote=0;
                }
            } else if(ch=='\'' || ch=='`' || ch=='"') { quote=ch; out.setCharAt(i,' '); }
        }
        return out.toString();
    }

    /** 每次只允许一个未确认的子批次；成功响应后先保存位置，再允许发出下一条 INSERT。 */
    static void write(CkClient client,String table,List<String> targets,List<List<Object>> rows,
                      SyncCheckpointStore.Session session) {
        SyncCheckpointStore.State state=session.state;
        if(!MODE.equals(state.writerMode) || state.pending==null || state.batchTime==null
                || state.chunkOffset<0 || state.chunkOffset>rows.size()
                || (state.chunkOffset==rows.size() && state.chunkHash!=null))
            throw new IllegalStateException("MySQL 写入批次断点不完整，停止续传");
        while(state.chunkOffset<rows.size()) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已取消，已保存子批次断点");
            session.checkLock();
            String token=SyncCheckpointStore.hash(state.token+":"+state.pending+":"+state.pendingHash+":"+state.chunkOffset);
            CkClient.CheckpointChunk chunk=CkClient.checkpointChunk(table,targets,rows,state.chunkOffset,state.token,token);
            String hash=SyncCheckpointStore.hash(chunk.sql);
            if(state.chunkHash!=null && !state.chunkHash.equals(hash))
                throw new IllegalStateException("未确认批次的转换结果或写入配置已变化，停止续传，避免错误去重");
            if(session.preparedWriterVersion==null)
                throw new IllegalStateException("缺少本次写入版本校验，停止续传");
            if(state.chunkHash==null) state.writerServerVersion=session.preparedWriterVersion;
            requireWriterVersion(state,session.preparedWriterVersion);
            state.chunkHash=hash; session.save();
            client.exec(chunk.sql);
            state.chunkOffset=chunk.end; state.chunkHash=null;
            state.writerServerVersion=session.preparedWriterVersion; session.save();
        }
    }
}
