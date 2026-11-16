package com.el2ck.sync;

/**
 * 【文件职责】打开 CSV、MongoDB 或 JDBC 快照源，向同步引擎提供统一分页接口并统一关闭资源。
 */

import com.el2ck.duck.Duck;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.service.datasource.*;
import com.el2ck.util.*;
import java.sql.*;
import java.util.*;

/** 快照采集仅保留一批数据，资源由调用方统一关闭。 */
final class SnapshotSources implements AutoCloseable {
    final List<Column> columns;
    final SyncEngine.PageSource source;
    SnapshotSources(List<Column> columns,SyncEngine.PageSource source) { this.columns=columns; this.source=source; }
/**
 * 打开 CSV/文件型快照源并返回统一分页适配器；关闭动作由返回对象负责。
 *
 * @param duck 本次操作使用的输入参数
 * @param type 本次操作使用的输入参数
 * @param cfg 本次操作使用的输入参数
 * @return SnapshotSources 类型的结果
 */
    static SnapshotSources file(Duck duck,String type,Map<String,Object> cfg) {
        String path=SourceFile.readableCsv(duck,type,cfg);
        Duck.FileCursor cursor=duck.scanFile(path,SourceFile.header(cfg),SourceFile.names(cfg),SourceFile.delimiter(cfg),null,null);
        return new SnapshotSources(cursor.columns(),new SyncEngine.PageSource() {

            public List<List<String>> page(String w,int n,int o,String k) { return cursor.next(n); }

            public void close() { cursor.close(); }
        });
    }
/**
 * 打开 MongoDB 快照游标并转换为统一分页接口。
 *
 * @param cfg 本次操作使用的输入参数
 * @param task 本次操作使用的输入参数
 * @return SnapshotSources 类型的结果
 */
    static SnapshotSources mongo(Map<String,Object> cfg,SyncTask task) {
        MongoSyncSource mongo=MongoSyncSource.open(cfg,task.getSourceObject(),task.getMapping());
        return new SnapshotSources(mongo.columns(),new SyncEngine.PageSource() {

            public void watermark(String c,String w) { mongo.start(c,w); }

            public List<List<String>> page(String w,int n,int o,String k) { return mongo.next(n); }

            public void close() { mongo.close(); }
        });
    }
/**
 * 打开 JDBC/Hive 只读流式结果集并转换为统一分页接口，批次大小兼顾驱动流式语义。
 *
 * @param engine 本次操作使用的输入参数
 * @param cfg 本次操作使用的输入参数
 * @param task 本次操作使用的输入参数
 * @return SnapshotSources 类型的结果
 */
    static SnapshotSources jdbc(String engine,Map<String,Object> cfg,SyncTask task) {
        return jdbc(engine,cfg,task,null);
    }

    static SnapshotSources jdbc(String engine,Map<String,Object> cfg,SyncTask task,String baseline) {
        Connection connection=null; Statement statement=null;
        try {
            boolean hive="hive".equals(engine);
            int seconds=Math.toIntExact(Math.max(0L,task.getTimeoutMinutes())*60L);
            // 查询不限时与网络空闲超时分开；有持续数据返回的长快照不会因总时长超过 30 分钟被取消。
            int socketMillis=seconds==0 ? 1805000 : Math.toIntExact(((long)seconds+5L)*1000L);
            List<Column> columns=hive ? BigDataSource.columns(cfg,task.getSourceObject()) : DbSource.describe(engine,cfg,task.getSourceObject());
            connection=hive ? BigDataSource.hive(cfg,socketMillis) : DbSource.openSegmentConnection(engine,cfg,seconds);
            if("postgres".equals(engine)) connection.setAutoCommit(false);
            statement=connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
            statement.setQueryTimeout(seconds);
            statement.setFetchSize("mysql".equals(engine) ? Integer.MIN_VALUE : task.getBatchSize());
            List<String> projection=new ArrayList<>();
            for(Column column:columns) projection.add(DbSource.quoteIdent(hive ? "mysql" : engine,column.getName()));
            String table=hive ? "`"+task.getSourceObject()+"`" : DbSource.qualified(engine,cfg,task.getSourceObject());
            String where=incrementalWhere(engine,task,columns,baseline);
            if (SyncTask.MODE_INCREMENTAL.equals(task.getMode()) && baseline != null) {
                Column watermark=columns.stream().filter(c -> task.getIncrColumn().equals(c.getName())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("源增量字段不存在"));
                String w=DbSource.quoteIdent(engine,watermark.getName());
                // 必须在 WHERE > baseline 过滤之前检查；过滤后的空结果无法区分无新增与水位回退。
                try (ResultSet upper=statement.executeQuery("SELECT "+w+" FROM "+table+" WHERE "+w+" IS NOT NULL ORDER BY "+w+" DESC LIMIT 1")) {
                    String value=null;
                    if (upper.next()) value=SyncTime.isTimestamp(watermark.getType())
                            ? SyncTime.format(upper.getObject(1,java.time.LocalDateTime.class)) : upper.getString(1);
                    SyncWatermark.requireNotRegressed(value,baseline,watermark.getType());
                }
            }
            ResultSet result=statement.executeQuery("SELECT "+String.join(",",projection)+" FROM "+table+where);
            Connection owned=connection; Statement query=statement;
            return new SnapshotSources(columns,new SyncEngine.PageSource() {

                public List<List<String>> page(String w,int n,int o,String k) {
                    try {
                        List<List<String>> rows=new ArrayList<>();
                        while(rows.size()<n && result.next()) {
                            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已取消或超时");
                            List<String> row=new ArrayList<>();
                            for(int i=0;i<columns.size();i++) {
                                if(!hive && SyncTime.isTimestamp(columns.get(i).getType())) {
                                    java.time.LocalDateTime value=result.getObject(i+1,java.time.LocalDateTime.class);
                                    row.add(value==null ? null : SyncTime.format(value));
                                } else row.add(result.getString(i+1));
                            }
                            rows.add(row);
                        }
                        return rows;
                    } catch(SQLException e) { throw new IllegalStateException("采集源快照失败",e); }
                }

                public void close() { try { result.close(); } catch(SQLException ignored) { } try { query.close(); } catch(SQLException ignored) { } try { owned.close(); } catch(SQLException ignored) { } }
            });
        } catch(Exception e) {
            if(statement!=null) try { statement.close(); } catch(SQLException ignored) { }
            if(connection!=null) try { connection.close(); } catch(SQLException ignored) { }
            throw new IllegalStateException("打开源快照读取连接失败: "+e.getMessage(),e);
        }
    }

    public void close() { source.close(); }

    /** 让数据库按已确认水位筛选，避免每轮跨网络扫描全表；空水位行保留给采集器报错，不能静默漏掉。 */
    static String incrementalWhere(String engine,SyncTask task,List<Column> columns,String baseline) {
        if(baseline==null || !SyncTask.MODE_INCREMENTAL.equals(task.getMode()) || "hive".equals(engine)) return "";
        Column column=columns.stream().filter(c->task.getIncrColumn().equals(c.getName())).findFirst()
                .orElseThrow(()->new IllegalStateException("源增量字段不存在"));
        SyncMappingTypes.validateSource(task.getMapping(),columns,task.getIncrColumn());
        String name=DbSource.quoteIdent(engine,column.getName());
        return " WHERE ("+name+" > "+SyncEngine.incrementLiteral(baseline,column.getType())+" OR "+name+" IS NULL)";
    }
}
