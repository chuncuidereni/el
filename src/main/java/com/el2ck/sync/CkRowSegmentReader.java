package com.el2ck.sync;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.util.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;

/**
 * 普通 MergeTree 全量表的非唯一键分页。物理排序键在前，其余列补齐确定的行值顺序；
 * 相同行值通过出现次数区分，不把手机号当唯一键。只有目标确认后，引擎才保存下一游标。
 *
 * <p>源历史行在本轮期间必须保持不变。后台合并不改变行值，不影响游标；UPDATE、DELETE、
 * TTL 或向已经经过的位置补写数据不属于一致快照语义。固定上界之后的新数据留给后续任务。
 * 不使用不稳定的 part/offset，也不做全表 OFFSET。相同值的 OFFSET 仅应用于精确相等组。
 */
final class CkRowSegmentReader extends SegmentReader {
    static final String MODE = "ck-row-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SETTINGS = ",max_result_rows=0,max_result_bytes=0,"
            + "optimize_read_in_order=1,prefer_column_name_to_alias=1,result_overflow_mode='throw',"
            + "read_overflow_mode='throw',timeout_overflow_mode='throw'";
    private final Supplier<CkClient> factory;
    private final String table;
    private final SyncTask task;
    private final Plan plan;
    private final String projection, tuple, order, settings;
    private String next;
    private CkClient client;

    static final class Plan {
        final List<Column> order;
        final String identity;
        final int prefixSize;
        Plan(List<Column> order, String identity, int prefixSize) { this.order=order; this.identity=identity; this.prefixSize=prefixSize; }
    }

    /** 只检查元数据；复杂排序表达式、会折叠数据的引擎和不支持精确往返的类型继续走原快照路径。 */
    static Plan inspect(CkClient client, String database, String table, List<Column> columns) {
        QueryResult result=client.query("SELECT engine,toString(uuid),sorting_key,toString(metadata_modification_time),create_table_query "
                + "FROM system.tables WHERE database=? AND name=? SETTINGS max_execution_time=5", Arrays.asList(database,table));
        if(result.getRows().size()!=1) throw new IllegalStateException("无法确认 ClickHouse 源表身份");
        List<Object> meta=result.getRows().get(0);
        if(!"MergeTree".equals(String.valueOf(meta.get(0))) || columns.isEmpty()
                || String.valueOf(meta.get(4)).matches("(?is).*\\b(TTL|ALIAS)\\b.*")) return null;
        Map<String,Column> remaining=new LinkedHashMap<>();
        for(Column c:columns) {
            if(!supported(c.getType()) || !Sql.validIdent(c.getName())
                    || SnapshotSegmentReader.ROUND.equals(c.getName()) || SnapshotSegmentReader.ROW.equals(c.getName())) return null;
            remaining.put(c.getName(),c);
        }
        String sorting=String.valueOf(meta.get(2)).trim();
        if(sorting.startsWith("tuple(") && sorting.endsWith(")")) sorting=sorting.substring(6,sorting.length()-1);
        else if(sorting.startsWith("(") && sorting.endsWith(")")) sorting=sorting.substring(1,sorting.length()-1);
        List<Column> ordered=new ArrayList<>();
        for(String name:sorting.split(",")) {
            String key=name.trim();
            if(key.startsWith("`") && key.endsWith("`")) key=key.substring(1,key.length()-1);
            Column column=remaining.remove(key);
            if(column==null) return null;
            ordered.add(column);
        }
        int prefixSize=ordered.size();
        ordered.addAll(remaining.values());
        return new Plan(ordered, SyncCheckpointStore.hash(encode(Arrays.asList(meta,columns))),prefixSize);
    }

    private static boolean supported(String type) {
        return isIntegerKeyType(type) || "String".equals(type) || "Date".equals(type) || "Date32".equals(type)
                || "DateTime".equals(type) || (type!=null && type.matches("DateTime\\('[A-Za-z0-9_+/:.-]+'\\)"));
    }

    CkRowSegmentReader(Supplier<CkClient> factory, String table, List<Column> columns, SyncTask task,
                       SyncCheckpointStore.Session session, Long runId, Plan plan) {
        super(columns,task,session,runId,false);
        this.factory=factory; this.table=table; this.task=task; this.plan=plan;
        // 数据分页跟随任务配置；0 不再隐式变成 60 秒。上面的元数据探测仍保留短超时。
        settings=" SETTINGS max_execution_time="+Math.max(0L,task.getTimeoutMinutes())*60L+SETTINGS;
        List<String> select=new ArrayList<>(), keys=new ArrayList<>();
        // 返回给映射的值与用作游标的值分开，时间游标用 epoch，字符串游标用 hex，避免时区/编码损失。
        for(Column c:columns) select.add("toString("+quote(c)+")");
        for(Column c:plan.order) {
            keys.add(quote(c));
            select.add(cursorExpression(c));
        }
        projection=String.join(",",select);
        order=String.join(",",keys);
        tuple="tuple("+order+")";
    }

    /** 实际计划必须走物理顺序读取，防止看似有 LIMIT 却每批全表排序。仅启动/恢复时检查。 */
    void verifyPlan() {
        List<List<String>> rows=query("EXPLAIN PIPELINE SELECT "+projection+" FROM "+table
                +" ORDER BY "+order+" LIMIT "+batchSize+settings,4096);
        String text=rows.toString();
        if(!text.contains("ReadPoolInOrder") && !text.contains("algorithm: InOrder")) {
            // 空表没有 MergeTree 读取节点，也应允许按原全量语义完成；只读一行，不 COUNT 全表。
            if(query("SELECT 1 FROM "+table+" LIMIT 1"+settings,1).isEmpty()) return;
            throw new IllegalStateException("源表查询未使用排序键顺序读取，不能安全启用大表分批续传；请检查源表 ORDER BY");
        }
    }

    /** 上界及末尾相同行值个数只保存一次，之后即使源持续追加也不会无休止追赶。 */
    @Override String upper() {
        checkpoint.state.phase="确定分批读取上界";
        List<String> descending=new ArrayList<>();
        for(Column c:plan.order) descending.add(quote(c)+" DESC");
        List<List<String>> rows=query("SELECT "+projection+" FROM "+table+" ORDER BY "
                +String.join(",",descending)+" LIMIT 1"+settings,1);
        if(rows.isEmpty()) return null;
        Position high=new Position(); high.values=values(rows.get(0));
        List<List<String>> count=query("SELECT toString(count()) FROM "+table+" WHERE "+equal(high)+settings,1);
        high.copies=Long.parseLong(count.get(0).get(0));
        if(high.copies<=0) throw new IllegalStateException("源上界在读取期间发生变化，请保持全量源历史数据不变");
        return encode(high);
    }

    /** 游标保存原始行值、该相同行值已经消费的个数、目标本轮行号；不依赖转换后的手机号。 */
    static final class Position {
        public List<String> values;
        public long copies, ordinal;
    }

    @Override List<List<String>> read(String last, String upper) {
        Position low=last==null ? null : decode(last), high=decode(upper);
        long ordinal=low==null ? 0 : low.ordinal;
        if(ordinal!=checkpoint.state.rows) throw new IllegalStateException("源行号与已确认写入行数不一致，停止续传");
        if(low!=null && low.values.equals(high.values) && low.copies>=high.copies) return Collections.emptyList();
        int limit=batchSize;
        // pending 的 ordinal 是待重放批次的精确末尾；固定全轮上界的 ordinal 为 0。
        if(high.ordinal>0) {
            long remaining=high.ordinal-ordinal;
            if(remaining<=0 || remaining>batchSize) throw new IllegalStateException("待重放批次边界无效");
            limit=(int)remaining;
        }
        checkpoint.state.phase="分批读取并写入";
        List<List<String>> input=new ArrayList<>();
        if(low!=null) {
            // OFFSET 只能落在精确相等组内。该组减少或消失也不会把 OFFSET 错用到下一组而漏行。
            input.addAll(query("SELECT "+projection+" FROM "+table+" WHERE "+equal(low)
                    +" ORDER BY "+order+" LIMIT "+limit+" OFFSET "+low.copies+settings,limit));
        }
        if(input.size()<limit && (low==null || !low.values.equals(high.values))) {
            int remaining=limit-input.size();
            input.addAll(readFollowing(low,high,remaining));
        }
        Position position=new Position();
        position.values=low==null ? null : low.values; position.copies=low==null ? 0 : low.copies; position.ordinal=ordinal;
        List<List<String>> output=new ArrayList<>();
        for(List<String> row:input) {
            List<String> keyValues=values(row);
            long copies=keyValues.equals(position.values) ? Math.addExact(position.copies,1) : 1;
            if(keyValues.equals(high.values) && copies>high.copies) break;
            position.values=keyValues; position.copies=copies; position.ordinal=Math.addExact(position.ordinal,1);
            List<String> data=new ArrayList<>(row.subList(0,columns.size()));
            data.add(checkpoint.state.token); data.add(Long.toString(position.ordinal)); output.add(data);
        }
        next=output.isEmpty() ? last : encode(position);
        return output;
    }

    /**
     * 先用物理排序前缀确定本批小窗口，再在窗口内按完整行值排序。部分旧版 CK 的 FinishSorting
     * 即使显示 InOrder，较大 LIMIT 仍可能读取远超一批的数据；显式窗口避免每批等待整表收尾。
     * 前缀窗口可能包含重复行组的尾部，但第二次查询始终重新按完整行值排序并限流，不会漏掉边界组。
     */
    private List<List<String>> readFollowing(Position low,Position high,int limit) {
        List<String> names=new ArrayList<>(), expressions=new ArrayList<>();
        for(int i=0;i<plan.prefixSize;i++) {
            names.add(quote(plan.order.get(i))); expressions.add(cursorExpression(plan.order.get(i)));
        }
        String prefix=String.join(",",names), where=range(low,high);
        List<List<String>> window=query("SELECT "+String.join(",",expressions)+" FROM "+table
                +" WHERE "+where+" ORDER BY "+prefix+" LIMIT "+limit+settings,limit);
        if(window.isEmpty()) return Collections.emptyList();
        Position edge=new Position(); edge.values=window.get(window.size()-1);
        List<String> literals=new ArrayList<>();
        for(int i=0;i<plan.prefixSize;i++) literals.add(literalValue(edge,i));
        where+=" AND "+names.get(0)+"<="+literals.get(0)
                +" AND tuple("+prefix+")<=tuple("+String.join(",",literals)+")";
        return query("SELECT "+projection+" FROM "+table+" WHERE "+where
                +" ORDER BY "+order+" LIMIT "+limit+settings,limit);
    }

    private List<String> values(List<String> row) {
        if(row.size()!=columns.size()+plan.order.size()) throw new IllegalStateException("源分页字段数发生变化");
        return new ArrayList<>(row.subList(columns.size(),row.size()));
    }
    private Position decode(String text) {
        try {
            Position p=JSON.readValue(text,Position.class);
            if(p.values==null || p.values.size()!=plan.order.size() || p.values.contains(null) || p.copies<=0 || p.ordinal<0)
                throw new IllegalStateException("源行值游标无效");
            return p;
        } catch(java.io.IOException e) { throw new IllegalStateException("无法解析源行值游标",e); }
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(java.io.IOException e) { throw new IllegalStateException("无法保存源行值游标",e); }
    }
    private static String quote(Column c) { return "`"+c.getName()+"`"; }
    private static String cursorExpression(Column c) {
        String q=quote(c), type=c.getType();
        if("String".equals(type)) return "hex("+q+")";
        if(type.startsWith("DateTime")) return "toString(toUInt32("+q+"))";
        if(type.startsWith("Date")) return "toString(toInt32("+q+"))";
        return "toString("+q+")";
    }
    private String literal(Position position) {
        List<String> values=new ArrayList<>();
        for(int i=0;i<plan.order.size();i++) values.add(literalValue(position,i));
        return "tuple("+String.join(",",values)+")";
    }
    private String literalValue(Position position,int i) {
        String type=plan.order.get(i).getType(), value=position.values.get(i);
        if("String".equals(type)) {
            if((value.length() & 1)!=0 || !value.matches("[0-9A-F]*")) throw new IllegalStateException("字符串游标编码无效");
            return "unhex("+Sql.quoteString(value)+")";
        }
        String number=number(value);
        // 日期从数值天数/epoch 恢复，避免 DateTime 字符串的时区和夏令时歧义。
        return "CAST("+(type.startsWith("Date") ? number : Sql.quoteString(number))+","+Sql.quoteString(type)+")";
    }
    /** 显式的排序键谓词确保主键索引裁剪。不能只依赖完整业务行 tuple 的比较优化。 */
    private String equal(Position position) {
        List<String> conditions=new ArrayList<>();
        for(int i=0;i<plan.prefixSize;i++) conditions.add(quote(plan.order.get(i))+"="+literalValue(position,i));
        conditions.add(tuple+"="+literal(position));
        return String.join(" AND ",conditions);
    }
    private String range(Position low,Position high) {
        String first=quote(plan.order.get(0));
        return first+"<="+literalValue(high,0)+" AND "+tuple+"<="+literal(high)
                +(low==null ? "" : " AND "+first+">="+literalValue(low,0)+" AND "+tuple+">"+literal(low));
    }

    /** 一次仅持有有界查询，完整消费 EOF 后复用连接；瞬态错误交给引擎按已保存的断点重试。 */
    private List<List<String>> query(String sql,int limit) {
        checkpoint.checkLock();
        if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已取消或超时");
        if(client!=null && (client.invalidated() || client.expired())) close();
        if(client==null) client=factory.get();
        try(CkClient.QueryCursor cursor=client.openQueryCursor(sql)) {
            List<List<String>> rows=cursor.next(limit);
            if(!cursor.next(1).isEmpty()) throw new IllegalStateException("源分页返回超过限定行数");
            return rows;
        } catch(RuntimeException e) { close(); throw e; }
    }

    @Override Long estimateTotalRows() {
        try(CkClient client=factory.get()) {
            // table 已由 qualified() 引用；只查询元数据，统计无权限时不退化到扫描业务表。
            String[] names=table.replace("`", "").split("\\.");
            QueryResult rows=client.query("SELECT toString(sum(rows)) FROM system.parts WHERE active AND database=? AND table=? SETTINGS max_execution_time=3",
                    Arrays.asList(names[0],names[1]));
            return Long.valueOf(String.valueOf(rows.getRows().get(0).get(0)));
        } catch(RuntimeException e) { return null; }
    }
    @Override String nextCursor(List<List<String>> rows) { return next; }
    /** 未到达冻结的末尾不能成功收尾，避免源被截断后误报完成。 */
    @Override void beforeComplete() {
        if(checkpoint.state.upper==null) return;
        if(checkpoint.state.last==null) throw new IllegalStateException("源在本轮读取期间变为空，停止确认完成");
        Position high=decode(checkpoint.state.upper), last=decode(checkpoint.state.last);
        if(!last.values.equals(high.values) || last.copies!=high.copies)
            throw new IllegalStateException("源末尾与本轮固定上界不一致，请检查历史数据是否被删除或改写");
    }
    @Override String describeCursor(String cursor) { return cursor==null ? null : "row:"+decode(cursor).ordinal; }
    @Override String targetRange(String last,String upper) {
        return "`"+SnapshotSegmentReader.ROUND+"`="+Sql.quoteString(checkpoint.state.token)
                +" AND `"+SnapshotSegmentReader.ROW+"`>"+(last==null ? 0 : decode(last).ordinal)
                +" AND `"+SnapshotSegmentReader.ROW+"`<="+decode(upper).ordinal;
    }
    @Override boolean clearsTarget() { return checkpoint.state.replaceTarget || task.isTruncate(); }
    @Override void updateProgress() {
        checkpoint.state.progressUnit="行"; checkpoint.state.completedUnits=checkpoint.state.rows;
        checkpoint.state.totalUnits=checkpoint.state.estimatedTotalRows;
    }
    @Override void appendMappings(List<String> targets,List<Integer> indexes,List<SyncFieldMapping> mappings) {
        // 虚拟行号只用于源游标及兼容旧批次摘要，不扩展目标映射。
    }
    @Override void prepareTarget(CkClient client,String table,List<Column> actual,boolean autoCreate) {
        // 恢复位置保存在 MySQL，目标无需辅助列。
    }
    @Override void validateTarget(List<Column> actual) { prepareTarget(null,null,actual,false); }
    @Override public void close() { CkClient old=client; client=null; if(old!=null) old.close(); }
}
