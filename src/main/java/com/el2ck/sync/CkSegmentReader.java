package com.el2ck.sync;

/**
 * 【文件职责】从 ClickHouse 源表按稳定键或复合水位分段读取，保证断点边界可恢复。
 */

import com.el2ck.ckpool.*;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.*;
import com.el2ck.util.SyncTime;
import com.el2ck.util.CkColumns;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Supplier;

/**
 * 按稳定键分段的 keyset 读取:查询始终带 {@code key > last AND key <= upper} 与 LIMIT,
 * 不用 OFFSET,也不会在写目标表期间长期持有一个游标。
 */
final class CkSegmentReader extends SegmentReader {
    // ClickHouse 源使用 keyset 游标且固定首次上界；即使 SQL 带 LIMIT，也必须持续读取到 EOF 才算完整。
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CkSegmentReader.class);
    final Supplier<CkClient> factory;
    final String table;
    private CkClient client;
    CkSegmentReader(Supplier<CkClient> factory, String table, List<Column> columns,
                    SyncTask task, SyncCheckpointStore.Session checkpoint, Long runId) {
        super(columns,task,checkpoint,runId);
        this.factory=factory; this.table=table;
    }
    /** 上界只捕获一次；新增且键大于上界的行留到下一轮，空表返回 null。 */
    String upper() {
        // 利用排序键倒序读取一行，避免 max() 聚合扫描大表。
        List<List<String>> rows=readQuery("SELECT toString(`"+key+"`) FROM "+table
                +" ORDER BY `"+key+"` DESC LIMIT 1"
                +" SETTINGS max_execution_time=0,max_result_rows=0,max_result_bytes=0,optimize_read_in_order=1"
                +",result_overflow_mode='throw',read_overflow_mode='throw',timeout_overflow_mode='throw'",1);
        return rows.isEmpty() || rows.get(0).isEmpty() ? null : maxValue(rows.get(0).get(0));
    }
    /** max() 的空值形态归一:null、"null"、空串都表示空表。 */
    static String maxValue(String value) {
        if(value==null) return null;
        String text=value.trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : number(text);
    }

    /** 只查 MergeTree 分片元数据；没有权限或统计不可用时放弃估算，不扫描业务表。 */
    Long estimateTotalRows() {
        String[] parts=table.replace("`", "").split("\\.");
        String predicate=parts.length==2 ? "database=? AND table=?" : "database=currentDatabase() AND table=?";
        List<Object> args=new ArrayList<>();
        for(String part:parts) args.add(part);
        try(CkClient c=factory.get()) {
            QueryResult result=c.query("SELECT toString(sum(rows)) FROM system.parts WHERE active AND "+predicate
                    +" SETTINGS max_execution_time=3",args);
            return result.getRows().isEmpty() ? null : Long.valueOf(String.valueOf(result.getRows().get(0).get(0)));
        } catch(RuntimeException e) {
            log.debug("event=sync.progress.total_unavailable run_id={}",runId);
            return null;
        }
    }
    List<List<String>> read(String last, String upper) {
        List<String> select=new ArrayList<>();
        for(Column col:columns) {
            String q="`"+col.getName().replace("`","``")+"`";
            select.add(SyncTime.isTimestamp(col.getType()) ? "toString("+q+") AS "+q : CkColumns.selectExpr(q,col.getType()));
        }
        String where="`"+key+"` <= "+number(upper)+(last==null ? "" : " AND `"+key+"` > "+number(last));
        String sql="SELECT "+String.join(", ",select)+" FROM "+table+" WHERE "+where
                +" ORDER BY `"+key+"` LIMIT "+(batchSize+1)
                // 同 SyncEngine.ckSourceSelect:必须覆盖驱动默认的 max_execution_time=3600,
                // 否则旧版 ClickHouse 的耗时估算会中途中止查询并污染行二进制流。
                +" SETTINGS max_execution_time=0,max_result_rows=0,max_result_bytes=0,prefer_column_name_to_alias=1,optimize_read_in_order=1"
                +",result_overflow_mode='throw',read_overflow_mode='throw',timeout_overflow_mode='throw'";
        List<List<String>> rows=readQuery(sql,batchSize+1);
        BigInteger previous=last==null ? null : new BigInteger(last);
        for(List<String> row:rows) {
            BigInteger next=new BigInteger(number(row.get(keyIndex)));
            if(previous!=null && next.compareTo(previous)<=0)
                throw new IllegalStateException("分段键存在重复值或未严格递增，停止同步: "+key);
            previous=next;
        }
        if(rows.size()>batchSize) rows.remove(rows.size()-1);
        return rows;
    }

    /** 只重试完整的只读批次；丢弃失败批次的部分结果，不推进断点。 */
    private List<List<String>> readQuery(String sql,int limit) {
        for(int attempt=0;;attempt++) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已超时或被中断");
            checkpoint.checkLock();
            try {
                if(client!=null && (client.invalidated() || client.expired())) closeClient();
                if(client==null) client=factory.get();
                try(CkClient.QueryCursor cursor=client.openQueryCursor(sql)) {
                    List<List<String>> rows=cursor.next(limit);
                    // SQL 带 LIMIT，必须读到 EOF 才能复用原生协议连接，也能发现尾部服务端错误。
                    if(!cursor.next(1).isEmpty()) throw new IllegalStateException("分段查询返回行数超过限定值");
                    return rows;
                }
            } catch(RuntimeException e) {
                closeClient();
                if(Thread.currentThread().isInterrupted() || attempt>=3 || !SyncRetry.transientFailure(e)) throw e;
                long delay=1000L << attempt;
                log.warn("event=sync.checkpoint.read_retry run_id={} attempt={} delay_ms={} last={}",
                        runId,attempt+1,delay,checkpoint.state.last);
                SyncRetry.pause(delay);
            }
        }
    }

    private void closeClient() {
        CkClient old=client; client=null;
        if(old!=null) old.close();
    }

    public void close() {
        try { closeClient(); } finally { checkpoint.close(); }
    }
}
