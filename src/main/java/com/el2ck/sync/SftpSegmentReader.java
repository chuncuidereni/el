package com.el2ck.sync;

/**
 * 【文件职责】读取 SFTP 远端文件并复用本地缓存，实现跨轮次恢复、文件校验和批次边界。
 */

import com.el2ck.ckpool.CkClient;
import com.el2ck.duck.Duck;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.*;
import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.util.RemoteSourceFile;
import com.el2ck.util.SourceFile;
import com.el2ck.util.Sql;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** CSV 目录：冻结文件清单，按文件增量更新，下载字节与已确认的文件内行号分别恢复。 */
final class SftpSegmentReader extends SegmentReader {
    // SFTP 首次运行冻结文件清单；恢复依赖文件 hash、文件内行位置和目标批次，完成文件登记后不得重复写。
    static final String ROW="_el_file_row";
    private static final ObjectMapper JSON=new ObjectMapper();
    /** 旧版只忽略额外尾列；v2 在同样条件下还会移除数据行开头的连续空占位列。 */
    private static final String CSV_READER_V1="header-records-v1";
    private static final String CSV_READER_V2="header-records-v2";
    private static final String CSV_READER_V3="commons-csv-v3";
    public static class Item {
        public String path,mtime;
        /** 首次读取前冻结，恢复时不得重新猜测编码/分隔符。 */
        public String csvEncoding,csvDelimiter;
        public long size;
        public boolean changed;
/**
 * 从当前文件快照读取指定页的远端文件条目。
 *
 * @return RemoteSourceFile.RemoteEntry 类型的结果
 */
        RemoteSourceFile.RemoteEntry entry() { return new RemoteSourceFile.RemoteEntry(path,size,mtime); }
    }
    public static class Window {
        public List<Item> files=new ArrayList<>();
        public int skipped;
        public boolean rebuildAll;
        /** 随冻结清单落库；旧断点缺省为空时沿用原读取器，避免改变待确认批次。 */
        public String csvReader;
        public String csvEncoding;
        /** 记录本次执行是否忽略结构外列；v2 同时启用行首空占位修复，但不改变逻辑记录边界。 */
        public boolean ignoreExtraCsvColumns;

        /** 缺少读取器标记的旧轮次必须沿用旧规则，不能因新默认值破坏已确认的批次。 */
        void applyCsvPolicy(boolean requested) {
            if (!CSV_READER_V3.equals(csvReader))
                ignoreExtraCsvColumns=requested && (CSV_READER_V1.equals(csvReader) || CSV_READER_V2.equals(csvReader));
        }
    }
    /**
     * 表示 SFTP 当前文件索引、文件内行偏移和 EOF 状态的复合位置。
     */
    public static class Position {
        public int file;
        public long row;
        public boolean eof;
        /** 创建空位置；恢复或新轮次会再用解码值或显式构造覆盖。 */
        public Position() { }
        Position(int file,long row,boolean eof) { this.file=file; this.row=row; this.eof=eof; }
    }
    private final SyncTask task;
    private final Map<String,Object> config;
    private final MetadataRepository store;
    private final Duck duck;
    private final Window window;
    private final SftpResumeCache cache;
    private RemoteSourceFile.SftpSession sftp;
    private Duck.FileCursor cursor;
    private int cursorFile=-1,currentFile=-1;
    private long cursorRow,lastProgress;
    private String next;

    SftpSegmentReader(Map<String,Object> config,SyncTask task,SyncCheckpointStore.Session checkpoint,Long runId,
                      MetadataRepository store,Duck duck,Path cacheRoot,boolean targetMissing,boolean restart) {
        super(task.getSchema(),task,checkpoint,runId,false);
        this.task=task; this.config=config; this.store=store; this.duck=duck;
        if(columns==null || columns.isEmpty() || !duck.isReady()) throw new IllegalStateException("SFTP 续传需要已保存的 CSV 结构及可用的文件解析器");
        cache=new SftpResumeCache(cacheRoot,task.getId(),checkpoint.state.token);
        try {
            if(checkpoint.state.upper!=null) window=decode(checkpoint.state.upper);
            else {
                SyncCheckpointStore.State previous=checkpoint.previousState;
                if(previous!=null && previous.complete && !restart) {
                    if(!Objects.equals(previous.fingerprint,checkpoint.state.fingerprint))
                        throw new IllegalStateException("SFTP 任务配置已改变，不能沿用文件完成记录；请恢复原配置，或使用新的空目标表建立任务");
                    if(!targetMissing) checkpoint.state.inheritTarget(previous);
                }
                window=new Window(); window.rebuildAll=restart || targetMissing;
                window.csvReader=CSV_READER_V3;
                window.csvEncoding=com.el2ck.util.CsvEncoding.normalize(task.getCsvEncoding());
                window.ignoreExtraCsvColumns=task.isIgnoreExtraCsvColumns();
                Map<String,SyncFile> completed=window.rebuildAll ? Collections.emptyMap() : store.listSyncedFiles(task.getId());
                List<RemoteSourceFile.RemoteEntry> files=connection().list(task.getRemotePath(),task.getFilePattern());
                files.sort(Comparator.comparing(f->f.path));
                Set<String> unique=new HashSet<>();
                for(RemoteSourceFile.RemoteEntry file:files) {
                    if(!unique.add(file.path)) throw new IllegalStateException("SFTP 清单包含重复路径");
                    SyncFile old=completed.get(file.path);
                    if(old!=null && old.getSize()==file.size && Objects.equals(old.getMtime(),file.mtime)) { window.skipped++; continue; }
                    Item item=new Item(); item.path=file.path; item.mtime=file.mtime; item.size=file.size; item.changed=old!=null;
                    window.files.add(item);
                }
            }
            // v1 断点继续使用原尾列规则，避免已确认批次在升级后改变；v2 才修复行首空占位列。
            // 已有待确认批次仍由写入层校验原摘要，不能通过读取策略绕过。缺少版本标记的
            // 更旧轮次继续使用 DuckDB 原读取器，结束后的下一轮才会冻结为 v2。
            window.applyCsvPolicy(task.isIgnoreExtraCsvColumns());
            String requestedEncoding=com.el2ck.util.CsvEncoding.normalize(task.getCsvEncoding());
            if (CSV_READER_V3.equals(window.csvReader)) {
                if (!Objects.equals(requestedEncoding,window.csvEncoding) || task.isIgnoreExtraCsvColumns()!=window.ignoreExtraCsvColumns)
                    throw new IllegalStateException("本轮 CSV 编码或尾列策略已冻结，请恢复原配置继续；切换读取规则需明确重新传输，重新传输会全量重建目标");
            } else if (!"auto".equals(requestedEncoding) && !"UTF-8".equals(requestedEncoding)) {
                throw new IllegalStateException("旧 CSV 断点按 UTF-8 解析，不能中途切换编码；请使用原规则继续，或明确重新传输并全量重建目标");
            }
            if(checkpoint.state.upper!=null) { checkpoint.state.upper=upper(); checkpoint.save(); }
            updateProgress();
        } catch(RuntimeException e) { close(); throw e; }
    }
/**
 * 解析并校验持久化的 SFTP 文件/行位置。
 *
 * @param text 本次操作使用的输入参数
 * @return Window 类型的结果
 */
    private static Window decode(String text) {
        try {
            Window value=JSON.readValue(text,Window.class);
            if(value.files==null || value.skipped<0
                    || (value.csvReader!=null && !CSV_READER_V1.equals(value.csvReader) && !CSV_READER_V2.equals(value.csvReader) && !CSV_READER_V3.equals(value.csvReader))) throw new IllegalArgumentException();
            if (CSV_READER_V3.equals(value.csvReader) && (value.csvEncoding==null
                    || !value.csvEncoding.equals(com.el2ck.util.CsvEncoding.normalize(value.csvEncoding)))) throw new IllegalArgumentException();
            Set<String> paths=new HashSet<>();
            for(Item item:value.files) {
                if(item==null || item.path==null || item.path.isEmpty() || item.mtime==null || item.size<0 || !paths.add(item.path)) throw new IllegalArgumentException();
                if (CSV_READER_V3.equals(value.csvReader) && ((item.csvEncoding==null)!=(item.csvDelimiter==null)
                        || (item.csvEncoding!=null && ("auto".equals(item.csvEncoding)
                        || !item.csvEncoding.equals(com.el2ck.util.CsvEncoding.normalize(item.csvEncoding))
                        || item.csvDelimiter.length()!=1 || "\"\r\n\u0000".indexOf(item.csvDelimiter.charAt(0))>=0))))
                    throw new IllegalArgumentException();
            }
            return value;
        } catch(Exception e) { throw new IllegalStateException("SFTP 文件清单断点无效",e); }
    }
/**
 * 生成当前读取位置的规范文本，供断点持久化。
 *
 * @param value 本次操作使用的输入参数
 * @return Position 类型的结果
 */
    private Position position(String value) {
        if(value==null) return null;
        try {
            Position p=JSON.readValue(value,Position.class);
            if(p.file<0 || p.file>=window.files.size() || p.row<0) throw new IllegalArgumentException();
            return p;
        } catch(Exception e) { throw new IllegalStateException("SFTP 文件行号断点无效",e); }
    }
/**
 * 把文件索引和行偏移编码为可恢复的位置字符串。
 *
 * @param value 本次操作使用的输入参数
 * @return String 类型的结果
 */
    private String encode(Position value) {
        try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); }
    }
/**
 * 建立经过任务配置校验的 SFTP 会话，调用方负责关闭。
 *
 * @return RemoteSourceFile.SftpSession 类型的结果
 */
    private RemoteSourceFile.SftpSession connection() {
        if(sftp==null) sftp=RemoteSourceFile.openSftpSession(config,60000);
        return sftp;
    }
    @Override String upper() {
        try { return JSON.writeValueAsString(window); } catch(Exception e) { throw new IllegalStateException("无法记录文件清单",e); }
    }
    @Override boolean clearsTarget() { return window.rebuildAll; }
    @Override List<List<String>> read(String last,String high) {
        checkpoint.checkLock();
        finalizeFile();
        Position start=position(last);
        currentFile=start==null ? 0 : start.file+(start.eof ? 1 : 0);
        next=null;
        if(currentFile>=window.files.size()) return Collections.emptyList();
        long from=start!=null && start.file==currentFile ? start.row : 0;
        Item item=window.files.get(currentFile);
        if(checkpoint.state.excludedFiles.containsKey(String.valueOf(currentFile))) {
            next=encode(new Position(currentFile,0,true)); return Collections.emptyList();
        }
        if(cursorFile!=currentFile || cursorRow!=from) {
            closeCursor();
            checkpoint.state.phase="校验或下载文件"; checkpoint.state.currentObject=item.path; checkpoint.save();
            SftpResumeCache.FileCopy copy=cache.obtain(currentFile,item.entry(),checkpoint.state.fileHashes.get(String.valueOf(currentFile)),
                    this::connection,n->downloadProgress(item,n),fileFormat(item.path));
            checkpoint.state.fileHashes.put(String.valueOf(currentFile),copy.hash); checkpoint.save();
            // 下载结束后切换口径，解析或定位阶段不能继续显示下载的 100%。
            checkpoint.state.phase="解析文件"; updateProgress(); checkpoint.save();
            String format=fileFormat(item.path);
            Map<String,Object> local=new LinkedHashMap<>(config); local.put("path",copy.path.toString());
            String readable=SourceFile.readableCsv(duck,format,local);
            if ("csv".equals(format) && CSV_READER_V3.equals(window.csvReader)) {
                if ((item.csvEncoding==null)!=(item.csvDelimiter==null)
                        || (item.csvEncoding==null && (from>0 || checkpoint.state.pending!=null)))
                    throw new IllegalStateException("待恢复 CSV 缺少已冻结的编码或分隔符，不能重新猜测读取规则");
                com.el2ck.util.ModernCsvTable csv=com.el2ck.util.ModernCsvTable.open(readable,item.path,task.isHasHeader(),
                        item.csvDelimiter==null ? SourceFile.directoryDelimiter(config) : item.csvDelimiter,
                        window.ignoreExtraCsvColumns,item.csvEncoding==null ? window.csvEncoding : item.csvEncoding);
                cursor=new Duck.FileCursor(csv);
                item.csvEncoding=csv.encoding(); item.csvDelimiter=csv.delimiter();
                checkpoint.state.upper=upper(); checkpoint.save();
            } else cursor="json".equals(format) ? duck.scanFile(readable,task.isHasHeader(),null,null,null,null)
                    : "csv".equals(format) && (CSV_READER_V1.equals(window.csvReader) || CSV_READER_V2.equals(window.csvReader))
                        ? duck.scanDirectoryCsv(readable,task.isHasHeader(),SourceFile.directoryDelimiter(config),window.ignoreExtraCsvColumns,
                            CSV_READER_V2.equals(window.csvReader))
                        : duck.scanStableCsv(readable,task.isHasHeader(),SourceFile.delimiter(config));
            cursorFile=currentFile; cursorRow=0;
            String schemaDifference=SyncEngine.sftpColumnDifference(columns,cursor.columns());
            if(schemaDifference!=null) {
                closeCursor();
                if(from!=0 || checkpoint.state.pending!=null) throw new IllegalStateException("待恢复文件 "+item.path+" 结构已变化："+schemaDifference+"；停止续传");
                checkpoint.state.excludedFiles.put(String.valueOf(currentFile),"文件 "+item.path+" 结构不一致："+schemaDifference+"；已排除，保留旧数据");
                checkpoint.save(); next=encode(new Position(currentFile,0,true)); return Collections.emptyList();
            }
            if(from>0) cursorRow=locateRows(cursor,from,checkpoint,System::nanoTime);
            checkpoint.state.phase="读取文件"; updateProgress(); checkpoint.save();
        }
        Position pending=checkpoint.state.pending==null ? null : position(high);
        int limit=batchSize;
        if(pending!=null) {
            if(pending.file!=currentFile || pending.row<from || pending.row-from>batchSize) throw new IllegalStateException("待恢复文件范围不合法");
            limit=(int)(pending.row-from);
        }
        List<List<String>> rows;
        try {
            rows=limit==0 ? new ArrayList<>() : cursor.next(limit);
        } catch(RuntimeException e) {
            throw new IllegalStateException("文件 "+item.path+" 读取失败："+e.getMessage(),e);
        }
        cursorRow+=rows.size();
        boolean eof=pending==null ? rows.size()<batchSize : pending.eof;
        if(pending!=null && (rows.size()!=limit || (eof && !cursor.next(1).isEmpty())))
            throw new IllegalStateException("待恢复文件批次与保存的行号范围不一致");
        for(int i=0;i<rows.size();i++) { rows.get(i).add(item.path); rows.get(i).add(String.valueOf(from+i+1)); }
        next=encode(new Position(currentFile,cursorRow,eof));
        checkpoint.state.phase="写入文件"; updateProgress();
        return rows;
    }

    /**
     * 从文件开头定位到已确认行号，每两秒及定位完成时保存展示进度。
     * 这里只移动本地读取游标，绝不推进 last、rows 或 pending，避免把跳过的旧行算成新写入。
     * 时钟可注入，便于用真实 CSV 游标验证节流和取消，无需等待大文件实际扫描。
     */
    static long locateRows(Duck.FileCursor cursor,long from,SyncCheckpointStore.Session checkpoint,
                           java.util.function.LongSupplier clock) {
        checkpoint.state.phase="定位文件行号"; checkpoint.state.progressUnit="行";
        checkpoint.state.completedUnits=0L; checkpoint.state.totalUnits=from; checkpoint.save();
        long row=0,lastReported=clock.getAsLong();
        while(row<from) {
            checkpoint.checkLock();
            List<List<String>> skipped=cursor.next((int)Math.min(10000,from-row));
            if(skipped.isEmpty()) throw new IllegalStateException("本地文件的行数少于已确认断点");
            row+=skipped.size();
            checkpoint.state.completedUnits=row;
            long now=clock.getAsLong();
            if(now-lastReported>=2_000_000_000L || row==from) {
                checkpoint.save(); lastReported=now;
            }
        }
        return row;
    }
/**
 * 把远端文件下载字节数转换为结构化进度回调。
 *
 * @param item 本次操作使用的输入参数
 * @param bytes 本次操作使用的输入参数
 */
    private void downloadProgress(Item item,long bytes) {
        checkpoint.checkLock();
        boolean changed=!"下载文件".equals(checkpoint.state.phase)
                || !Objects.equals(checkpoint.state.currentObject,item.path);
        checkpoint.state.phase="下载文件"; checkpoint.state.currentObject=item.path;
        checkpoint.state.progressUnit="字节"; checkpoint.state.completedUnits=bytes; checkpoint.state.totalUnits=item.size;
        long now=System.nanoTime();
        if(changed || lastProgress==0 || now-lastProgress>=2_000_000_000L || bytes==item.size) { checkpoint.save(); lastProgress=now; }
    }
/**
 * 根据文件扩展名选择受支持的解析格式。
 *
 * @param path 本次操作使用的输入参数
 * @return String 类型的结果
 */
    private String fileFormat(String path) {
        String format=Objects.toString(config.get("format"),"").trim().toLowerCase(Locale.ROOT);
        if(format.isEmpty()) format=path.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? "xlsx" : path.toLowerCase(Locale.ROOT).matches(".*\\.(json|jsonl|ndjson)$") ? "json" : "csv";
        return format;
    }
/**
 * 切换远端文件时重置当前文件的行读取状态。
 *
 */
    private void resetRecords() {
        if(window.rebuildAll && !checkpoint.state.fileRecordsCleared) {
            checkpoint.checkLock(); store.clearSyncedFiles(task.getId());
            checkpoint.state.fileRecordsCleared=true; checkpoint.save();
        }
    }
    @Override void beforeBatch(CkClient client,String table,String sourceColumn,String sourceId) {
        resetRecords();
        if(checkpoint.state.excludedFiles.containsKey(String.valueOf(currentFile))) return;
        if(!Objects.equals(checkpoint.state.fileCleared,currentFile)) {
            checkpoint.state.pendingAction="sftp-file-reset-"+currentFile; checkpoint.save();
            client.exec("/*"+checkpoint.state.token+"*/ ALTER TABLE "+table+" DELETE WHERE `path` = "+Sql.quoteString(window.files.get(currentFile).path)
                    +" AND `"+sourceColumn+"` = "+Sql.quoteString(sourceId)+" SETTINGS mutations_sync=2");
            checkpoint.state.fileCleared=currentFile; checkpoint.state.pendingAction=null; checkpoint.save();
        }
    }
/**
 * 完成当前文件并决定是否前进到下一文件；只有完整校验和读取后才能推进。
 *
 */
    private void finalizeFile() {
        Position last=position(checkpoint.state.last);
        if(last==null || !last.eof || (checkpoint.state.fileFinalized!=null && checkpoint.state.fileFinalized>=last.file)) return;
        checkpoint.checkLock();
        Item item=window.files.get(last.file);
        if(!checkpoint.state.excludedFiles.containsKey(String.valueOf(last.file)))
            store.upsertSyncedFile(task.getId(),item.path,item.size,item.mtime);
        checkpoint.state.fileFinalized=last.file; updateProgress(); checkpoint.save();
        closeCursor(); cache.remove(last.file);
    }
    @Override void beforeComplete() { resetRecords(); finalizeFile(); }
    @Override String nextCursor(List<List<String>> rows) { return next; }
    @Override boolean exhausted(List<List<String>> rows) { return next==null; }
    @Override String targetRange(String last,String high) {
        Position end=position(high),start=position(last);
        long from=start!=null && start.file==end.file ? start.row : 0;
        return "`path` = "+Sql.quoteString(window.files.get(end.file).path)+" AND `"+ROW+"` > "+from+" AND `"+ROW+"` <= "+end.row;
    }
    @Override void appendMappings(List<String> targets,List<Integer> indexes,List<SyncFieldMapping> mappings) {
        String[] names={"path"},types={"String"};
        for(int i=0;i<names.length;i++) {
            SyncFieldMapping m=new SyncFieldMapping(); m.setSourceColumn(names[i]); m.setTargetColumn(names[i]); m.setType(types[i]); m.setNullable(false);
            targets.add(names[i]); indexes.add(columns.size()+i); mappings.add(m);
        }
    }
/**
 * 校验文件表头与任务列映射，避免按错误列顺序写入。
 *
 * @param actual 本次操作使用的输入参数
 * @param allowMissing 本次操作使用的输入参数
 */
    static void validateColumns(List<Column> actual,boolean allowMissing) {
        String[] names={"path"},types={"String"};
        for(int i=0;i<names.length;i++) {
            final String name=names[i]; Column c=actual.stream().filter(v->name.equals(v.getName())).findFirst().orElse(null);
            if(c==null && allowMissing) continue;
            if(c==null || !types[i].equals(c.getType())) throw new IllegalStateException("SFTP 续传需要系统列 "+name+" "+types[i]+"（不可空），缺列时请启用自动建表");
        }
    }
    @Override void validateTarget(List<Column> actual) { validateColumns(actual,false); }
    @Override void prepareTarget(CkClient client,String table,List<Column> actual,boolean autoCreate) {
        validateColumns(actual,autoCreate && checkpoint.state.targetIdentity==null);
        String[] names={"path"},types={"String"};
        for(int i=0;i<names.length;i++) {
            final String name=names[i];
            if(actual.stream().noneMatch(c->name.equals(c.getName()))) client.exec("ALTER TABLE "+table+" ADD COLUMN IF NOT EXISTS `"+name+"` "+types[i]);
        }
    }
    @Override void updateProgress() {
        checkpoint.state.progressUnit="文件";
        checkpoint.state.totalUnits=(long)window.files.size()+window.skipped;
        checkpoint.state.completedUnits=(long)window.skipped+(checkpoint.state.fileFinalized==null ? 0 : checkpoint.state.fileFinalized+1);
    }
/**
 * 汇总已处理文件和行数，供运行记录与恢复展示。
 *
 * @param state 本次操作使用的输入参数
 * @param stats 本次操作使用的输入参数
 */
    static void summarize(SyncCheckpointStore.State state,SyncEngine.Stats stats) {
        if(state.upper==null) return;
        Window window=decode(state.upper);
        int added=0,updated=0,changed=0;
        for(Item item:window.files) if(item.changed) changed++;
        for(int i=0;i<window.files.size() && state.fileFinalized!=null && i<=state.fileFinalized;i++) {
            if(state.excludedFiles.containsKey(String.valueOf(i))) continue;
            if(window.files.get(i).changed) updated++; else added++;
        }
        stats.fileSummary(added,updated,changed,window.skipped,new ArrayList<>(state.excludedFiles.values()));
        if(window.ignoreExtraCsvColumns) stats.noteIgnoredCsvTail();
        else if(window.csvReader==null) stats.noteLegacyCsvReader();
    }
    @Override void summarize(SyncEngine.Stats stats) { summarize(checkpoint.state,stats); }
/**
 * 关闭当前文件游标及关联解析资源。
 *
 */
    private void closeCursor() { if(cursor!=null) cursor.close(); cursor=null; cursorFile=-1; cursorRow=0; }
/**
 * 关闭所有 SFTP/文件资源；即使部分关闭失败也继续释放其余资源。
 *
 */
    void cleanup() { cache.clean(); }
    @Override public void close() {
        try { closeCursor(); } finally { try { if(sftp!=null) sftp.close(); } finally { cache.close(); } }
    }
}
