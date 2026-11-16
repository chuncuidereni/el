package com.el2ck.sync;

/**
 * 【文件职责】为 MySQL/PostgreSQL 等 JDBC 数据源实现稳定排序、复合水位和分页读取，并生成可持久化游标。
 */

import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.service.datasource.DbSource;
import com.el2ck.util.SyncTime;
import com.el2ck.util.SyncWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.*;

/** MySQL / PostgreSQL：主键或（水位、主键）有界读取，所有位置由已确认断点驱动。 */
final class JdbcSegmentReader extends SegmentReader {
    // JDBC 增量游标是 (watermark, stable key) 复合边界，避免同一水位多行在分页边界被跳过或重复确认。
    private static final ObjectMapper JSON=new ObjectMapper();
    private final String engine,table,watermark,watermarkType,targetWatermark,targetWatermarkType,projection;
    private final Map<String,Object> config;
    private final int watermarkIndex,timeoutSeconds;
    private final boolean incremental;
    private Connection connection;

    JdbcSegmentReader(String engine,Map<String,Object> config,List<Column> columns,SyncTask task,
                      SyncCheckpointStore.Session checkpoint,Long runId) {
        super(columns,task,checkpoint,runId);
        this.engine=engine; this.config=config; table=DbSource.qualified(engine,config,task.getSourceObject());
        incremental=SyncTask.MODE_INCREMENTAL.equals(task.getMode());
        watermark=incremental ? task.getIncrColumn() : key;
        int index=-1;
        for(int i=0;i<columns.size();i++) if(watermark.equals(columns.get(i).getName())) index=i;
        if(index<0) throw new IllegalStateException("源表缺少增量水位列");
        watermarkIndex=index; watermarkType=columns.get(index).getType();
        SyncFieldMapping mapping=task.getMapping().stream().filter(m->watermark.equals(m.getSourceColumn())).findFirst()
                .orElseThrow(()->new IllegalStateException("增量水位必须映射到目标表"));
        targetWatermark=mapping.getTargetColumn(); targetWatermarkType=mapping.getType();
        if(!com.el2ck.util.Sql.validIdent(targetWatermark)) throw new IllegalStateException("增量目标列名非法");
        if(!supportedWatermark(watermarkType) || !SyncEngine.compatibleIncrementalTypes(watermarkType,targetWatermarkType)
                || Boolean.TRUE.equals(mapping.getNullable()))
            throw new IllegalStateException("可恢复增量需要非空整数、Decimal、日期或时间水位，目标类型须兼容且不可空");
        // 0 原样传给 JDBC 表示不限时；任务的总时长另由 SyncService 控制。
        timeoutSeconds=Math.toIntExact(Math.max(0L,task.getTimeoutMinutes())*60L);
        List<String> names=new ArrayList<>();
        for(Column column:columns) names.add(DbSource.quoteIdent(engine,column.getName()));
        projection=String.join(",",names);
    }

/**
 * 判断源水位类型是否属于可排序、可精确序列化的整数、Decimal、日期或时间类型；不满足时调用方必须拒绝启用可恢复增量。
 *
 * @param type 本次操作使用的输入参数
 * @return boolean 类型的结果
 */
    static boolean supportedWatermark(String type) {
        String base=SyncTime.baseType(type);
        return base.matches("u?int(8|16|32|64|128|256)|decimal.*|numeric.*|date|date32|datetime.*|timestamp(\\(\\d+\\))?");
    }
    @Override boolean incremental() { return incremental; }
/**
 * 判断稳定主键是否与增量水位不同，从而决定游标是否需要保存二元边界。
 *
 * @return boolean 类型的结果
 */
    private boolean composite() { return !key.equals(watermark); }
/**
 * 生成按水位和稳定键排序的 SQL 片段；同向排序确保复合游标比较与分页读取使用一致顺序。
 *
 * @param descending 本次操作使用的输入参数
 * @return String 类型的结果
 */
    private String order(boolean descending) {
        String direction=descending ? " DESC" : " ASC";
        return DbSource.quoteIdent(engine,watermark)+direction+(composite() ? ","+DbSource.quoteIdent(engine,key)+direction : "");
    }
    @Override String cursor(List<String> row) {
        String id=number(row.get(keyIndex));
        if(!composite()) return id;
        String value=row.get(watermarkIndex);
        if(value==null || value.trim().isEmpty()) throw new IllegalStateException("增量水位不能为空");
        SyncWatermark.requireExact(value,targetWatermarkType);
        try { return JSON.writeValueAsString(Arrays.asList(value,id)); }
        catch(Exception e) { throw new IllegalStateException("无法记录复合水位",e); }
    }
/**
 * 把持久化游标解析为水位与稳定键两部分，并校验稳定键为合法整数；损坏游标会显式失败而不是静默重读。
 *
 * @param cursor 本次操作使用的输入参数
 * @return String[] 类型的结果
 */
    private String[] parts(String cursor) {
        if(!composite()) return new String[]{number(cursor),number(cursor)};
        try {
            String[] values=JSON.readValue(cursor,String[].class);
            if(values.length!=2 || values[0]==null || values[0].trim().isEmpty()) throw new IllegalArgumentException();
            number(values[1]); return values;
        } catch(Exception e) { throw new IllegalStateException("复合断点格式无效，请恢复原断点或使用新的空目标表建立任务",e); }
    }
/**
 * 根据游标和读取方向构造 SQL 比较条件，可同时用于源库分页和目标区间清理。
 *
 * @param cursor 本次操作使用的输入参数
 * @param after 本次操作使用的输入参数
 * @param target 本次操作使用的输入参数
 * @return String 类型的结果
 */
    private String boundary(String cursor,boolean after,boolean target) {
        String[] values=parts(cursor);
        String dialect=target ? "clickhouse" : engine;
        String k=DbSource.quoteIdent(dialect,target ? targetKey : key);
        if(!composite()) return k+(after ? " > " : " <= ")+number(values[1]);
        String w=DbSource.quoteIdent(dialect,target ? targetWatermark : watermark);
        String literal=SyncEngine.incrementLiteral(values[0],target ? targetWatermarkType : watermarkType);
        return "("+w+(after ? " > " : " < ")+literal+" OR ("+w+" = "+literal+" AND "+k
                +(after ? " > " : " <= ")+number(values[1])+"))";
    }
    @Override String targetRange(String last,String upper) {
        return boundary(upper,false,true)+(last==null ? "" : " AND "+boundary(last,true,true));
    }
    @Override void validateTarget(List<Column> targetColumns) {
        super.validateTarget(targetColumns);
        Column column=targetColumns.stream().filter(c->targetWatermark.equals(c.getName())).findFirst()
                .orElseThrow(()->new IllegalStateException("目标表缺少增量水位列"));
        if(!targetWatermarkType.equals(column.getType()))
            throw new IllegalStateException("目标表实际增量列类型与映射不一致，不能安全恢复未确认批次");
    }
    @Override String upper() {
        List<List<String>> rows=query("SELECT "+projection+" FROM "+table+" ORDER BY "+order(true)+" LIMIT 1");
        return rows.isEmpty() ? null : cursor(rows.get(0));
    }

    @Override void validateWatermark(String last, String upper) {
        if (incremental && last != null && (upper == null || compare(last, upper) > 0))
            throw new IllegalStateException("源表水位发生回退或源表已清空，可能已重建；断点已保留，请核对源数据后继续，不能按无新增完成");
    }

    /**
     * 增量新表的时间型基线文本；数值/Decimal 水位无法映射日期，返回 null 由调用方改用发现时上界。
     *
     * <p>基线只用于新发现日期表的第一批读取：只接该时刻之后的行，不补历史。
     */
    String baselineAt(long epochMs, java.time.ZoneId zone) {
        String base=SyncTime.baseType(watermarkType);
        java.time.LocalDateTime time=java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMs), zone);
        if(SyncTime.isTimestamp(watermarkType)) return SyncTime.format(time.withNano(0));
        if(base.equals("date") || base.equals("date32")) return time.toLocalDate().toString();
        return null;
    }

    /** 时间型新表第一批：从基线之后读取，不构造复合游标；后续批次仍使用正常复合游标。 */
    List<List<String>> readAfter(String watermark,String upper) {
        if(upper==null) return new ArrayList<>();
        List<List<String>> rows=query("SELECT "+projection+" FROM "+table+" WHERE "+boundary(upper,false,false)
                +" AND "+DbSource.quoteIdent(engine,this.watermark)+" > "
                +SyncEngine.incrementLiteral(watermark,watermarkType)
                +" ORDER BY "+order(false)+" LIMIT "+(batchSize+1));
        String previous=null;
        for(List<String> row:rows) {
            String next=cursor(row);
            if(previous!=null && compare(previous,next)>=0) throw new IllegalStateException("源分段位置未严格递增，停止同步");
            previous=next;
        }
        if(rows.size()>batchSize) rows.remove(rows.size()-1);
        return rows;
    }

    @Override List<List<String>> read(String last,String upper) {
        if(upper==null) return new ArrayList<>();
        List<List<String>> rows=query("SELECT "+projection+" FROM "+table+" WHERE "+boundary(upper,false,false)
                +(last==null ? "" : " AND "+boundary(last,true,false))+" ORDER BY "+order(false)+" LIMIT "+(batchSize+1));
        // 同一水位跨批次时按主键继续；检查返回顺序，禁止静默跳过重复游标。
        String previous=last;
        for(List<String> row:rows) {
            String next=cursor(row);
            if(previous!=null && compare(previous,next)>=0) throw new IllegalStateException("源分段位置未严格递增，停止同步");
            previous=next;
        }
        if(rows.size()>batchSize) rows.remove(rows.size()-1);
        return rows;
    }
/**
 * 比较两个复合水位值，保持与 SQL 排序规则一致，用于判断分段是否仍有剩余数据。
 *
 * @param a 本次操作使用的输入参数
 * @param b 本次操作使用的输入参数
 * @return int 类型的结果
 */
    int compare(String a,String b) {
        String[] left=parts(a),right=parts(b);
        int order;
        if(SyncTime.isTimestamp(watermarkType) || SyncTime.baseType(watermarkType).startsWith("date"))
            order=SyncTime.parse(left[0]).compareTo(SyncTime.parse(right[0]));
        else order=new java.math.BigDecimal(left[0]).compareTo(new java.math.BigDecimal(right[0]));
        return order==0 ? new java.math.BigInteger(left[1]).compareTo(new java.math.BigInteger(right[1])) : order;
    }
/**
 * 执行一批有界查询并按列顺序转换成字符串行；调用方负责随后写入目标并提交游标。
 *
 * @param sql 本次操作使用的输入参数
 * @return List<List<String>> 类型的结果
 */
    private List<List<String>> query(String sql) {
        checkpoint.checkLock();
        if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已中断");
        try {
            if(connection==null) connection=DbSource.openSegmentConnection(engine,config,timeoutSeconds);
            return DbSource.readSegment(connection,sql,timeoutSeconds);
        } catch(java.sql.SQLException e) { throw new IllegalStateException("连接源数据库失败",e); }
    }
    @Override public void close() {
        if(connection!=null) try { connection.close(); } catch(java.sql.SQLException ignored) { }
    }
}
