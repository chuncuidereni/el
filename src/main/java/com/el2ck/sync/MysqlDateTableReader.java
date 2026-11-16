package com.el2ck.sync;

import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.service.datasource.DbSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * 多张日期表共享一个目标写入会话，但每张源表拥有独立水位。
 * 外层游标携带表名，避免每日主键重新从 1 开始时被前一天的水位过滤，或写入令牌发生碰撞。
 * 表清单在轮次开始时冻结；单表上界在首次进入该表时冻结。数据直接分批读写，不产生全表快照文件。
 */
final class MysqlDateTableReader extends SegmentReader {
    static final String MODE = "mysql-date-tables-v1";
    private static final String END = "mysql-date-tables-end";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SyncTask task;
    private final Map<String,Object> config;
    private JdbcSegmentReader reader;
    private Item current;
    private final Set<String> checkedWatermarks = new HashSet<>();
    private String next;
    private int skippedHistorical;

    /** 每轮清单与边界保存在现有 el_sync_checkpoints.state_json；只有确认写入后推进水位。 */
    public static class Item {
        public String table, lower, upper;
        /** 增量任务新表的时间型基线：lower 为空时只读该值之后的行，不补历史。 */
        public String baseline;
        public boolean captured, done;
        public Item() { }
        Item(String table, String lower) { this(table, lower, null); }
        Item(String table, String lower, String baseline) {
            this.table = table; this.lower = lower; this.baseline = baseline;
        }
    }

    MysqlDateTableReader(Map<String,Object> config, SyncTask task, SyncCheckpointStore.Session session, Long runId) {
        super(task.getSchema(), task, session, runId, false);
        this.task = task;
        this.config = config;
        if (columns == null || columns.isEmpty()) throw new IllegalStateException("日期分表缺少参考结构，请重新预览并保存任务");
    }

    private JdbcSegmentReader open(Item item) {
        if (current == item && reader != null) return reader;
        close();
        checkpoint.state.currentObject = item.table;
        checkpoint.state.phase = "校验日期分表结构";
        checkpoint.save();
        List<Column> actual = DbSource.describe("mysql", config, item.table);
        // 读写都使用固定列序，禁止结构不一致时拼接到同一目标导致错列。
        if (!columns.equals(actual)) throw new IllegalStateException("日期分表 " + item.table + " 的字段名称、顺序或类型与参考结构不一致，已保留断点");
        DbSource.validateSegmentKey("mysql", config, item.table, task.getStableKey(),
                incremental() ? task.getIncrColumn() : task.getStableKey());
        SyncTask single = new SyncTask();
        org.springframework.beans.BeanUtils.copyProperties(task, single);
        single.setSourceObject(item.table);
        single.setMysqlDateTables(null);
        reader = new JdbcSegmentReader("mysql", config, actual, single, checkpoint, runId);
        current = item;
        checkpoint.state.phase = "读取并写入日期分表";
        checkpoint.save();
        return reader;
    }

    @Override String upper() { return END; }
    @Override boolean incremental() { return SyncTask.MODE_INCREMENTAL.equals(task.getMode()); }
    @Override boolean clearsTarget() { return checkpoint.state.replaceTarget || (!incremental() && task.isTruncate()); }

    /**
     * 一个批次只属于一张表。pending 恢复必须严格重放原表和边界，不能越过它去读下一张表。
     * 空表/读完的表可以提交 done，但绝不提前提交含数据批次的游标。
     */
    @Override List<List<String>> read(String last, String high) {
        String[] previous = decode(last);
        String[] pending = END.equals(high) ? null : decode(high);
        for (Item item : checkpoint.state.dateTablePlan) {
            if (item.done) continue;
            if (pending != null && !item.table.equals(pending[0]))
                throw new IllegalStateException("日期分表待确认批次与当前表不一致，停止续传");
            if (pending == null && !item.captured && historicalNewTable(item)) {
                // 新表日期早于上次扫描：属于历史数据，增量不补，也不建立无意义的源连接。
                item.captured = true;
                item.done = true;
                skippedHistorical++;
                updateProgress();
                checkpoint.save();
                continue;
            }
            JdbcSegmentReader source = open(item);
            // 每次恢复都复核当前源；captured 只表示上界已冻结，不代表源从未被清空。
            if (checkedWatermarks.add(item.table)) {
                String currentUpper = source.upper();
                String confirmed = previous != null && item.table.equals(previous[0]) ? previous[1] : item.lower;
                source.validateWatermark(confirmed, currentUpper);
                if (!item.captured) item.upper = currentUpper;
            }
            if (!item.captured) {
                // 检出主键回退/清表，不能把明显重建的日表当成无新增。
                if (item.lower != null && (item.upper == null || source.compare(item.lower, item.upper) > 0))
                    throw new IllegalStateException("日期分表 " + item.table + " 的水位发生回退，可能已清空或重建，请检查源数据");
                if (item.lower == null && incremental() && !checkpoint.state.replaceTarget) {
                    if (isNewTableFullScan()) {
                        // 显式选择“全量补齐”：新表从头读，丢弃可能遗留的基线。
                        item.baseline = null;
                    } else {
                        // 默认“只接基线之后”：按上次扫描时间起算，不补历史；数值水位无法映射日期时用发现时上界。
                        if (item.baseline == null) item.baseline = baselineAt(source);
                        if (item.baseline == null) item.lower = item.upper;
                    }
                }
                item.captured = true;
                checkpoint.save();
            }
            boolean firstBatch = previous == null || !item.table.equals(previous[0]);
            String from = firstBatch ? item.lower : previous[1];
            String to = pending == null ? item.upper : pending[1];
            List<List<String>> rows = firstBatch && item.lower == null && item.baseline != null
                    ? source.readAfter(item.baseline, to)
                    : source.read(from, to);
            if (!rows.isEmpty()) {
                next = encode(item.table, source.nextCursor(rows));
                return rows;
            }
            if (pending != null) return rows; // 外层会拒绝丢失的待确认数据。
            persistWatermark(item);
            item.done = true;
            updateProgress();
            checkpoint.save();
            close();
        }
        return Collections.emptyList();
    }

    @Override String nextCursor(List<List<String>> rows) {
        if (next == null) throw new IllegalStateException("日期分表缺少批次游标");
        return next;
    }

    @Override void updateProgress() {
        SyncCheckpointStore.State state = checkpoint.state;
        if (state.dateTableWatermarks == null) state.dateTableWatermarks = new LinkedHashMap<>();
        String[] confirmed = decode(state.last);
        if (confirmed != null) {
            state.dateTableWatermarks.put(confirmed[0], confirmed[1]);
            if (state.dateTableBaselines != null) state.dateTableBaselines.remove(confirmed[0]);
        }
        state.progressUnit = "表";
        state.totalUnits = (long) state.dateTablePlan.size();
        state.completedUnits = state.dateTablePlan.stream().filter(item -> item.done).count();
    }

    /** 新表显式选择“全量补齐历史”时为 true；默认空值按“只接基线之后”处理。 */
    private boolean isNewTableFullScan() {
        return "full".equals(task.getMysqlDateTables().getNewTablePolicy());
    }

    private java.time.ZoneId zone() {
        String tz = task.getMysqlDateTables().getTimeZone();
        return tz == null || tz.trim().isEmpty()
                ? java.time.ZoneId.of("Asia/Shanghai") : java.time.ZoneId.of(tz);
    }

    /** 上一次成功扫描的开始时间；首次运行用本轮开始时间。 */
    private long baselineMs() {
        SyncCheckpointStore.State state = checkpoint.state;
        return state.dateTableLastScanAtMs != null ? state.dateTableLastScanAtMs
                : state.runStartedAtMs > 0 ? state.runStartedAtMs : System.currentTimeMillis();
    }

    /** 增量新表且表日期早于上次扫描日期：属于历史数据，不补也不读。 */
    private boolean historicalNewTable(Item item) {
        if (!incremental() || checkpoint.state.replaceTarget || isNewTableFullScan()) return false;
        if (item.lower != null || item.baseline != null) return false;
        java.time.LocalDate date = task.getMysqlDateTables().dateOf(item.table);
        return date != null && date.isBefore(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(baselineMs()), zone()).toLocalDate());
    }

    /** 时间型增量新表的基线取上一次成功扫描的开始时间；首次运行取本轮开始时间。 */
    private String baselineAt(JdbcSegmentReader source) {
        return source.baselineAt(baselineMs(), zone());
    }

    /** 表完成时持久化最终水位；新表没有读到任何行时保留基线供下一轮继续。 */
    private void persistWatermark(Item item) {
        SyncCheckpointStore.State state = checkpoint.state;
        if (state.dateTableWatermarks == null) state.dateTableWatermarks = new LinkedHashMap<>();
        if (state.dateTableBaselines == null) state.dateTableBaselines = new LinkedHashMap<>();
        String[] confirmed = decode(state.last);
        if (confirmed != null && item.table.equals(confirmed[0])) {
            state.dateTableWatermarks.put(item.table, confirmed[1]);
            state.dateTableBaselines.remove(item.table);
        } else if (item.lower != null) {
            state.dateTableWatermarks.put(item.table, item.lower);
            state.dateTableBaselines.remove(item.table);
        } else if (item.baseline != null) {
            // 时间型新表还没有确认行：下一轮仍从同一基线之后读，不能把基线改成新的当前时刻。
            state.dateTableBaselines.put(item.table, item.baseline);
        }
    }

    @Override void beforeComplete() {
        if (checkpoint.state.dateTablePlan.stream().anyMatch(item -> !item.done))
            throw new IllegalStateException("日期分表尚未全部读取完成");
        // 完整成功后，下一轮新发现的表以本轮开始时间为基线，保证两轮之间不漏行。
        if (checkpoint.state.runStartedAtMs > 0)
            checkpoint.state.dateTableLastScanAtMs = checkpoint.state.runStartedAtMs;
    }

    @Override void validateTarget(List<Column> targetColumns) {
        // 所有表使用同一份映射；借用既有 JDBC 类型检查，不产生任何目标辅助列。
        try (JdbcSegmentReader validator = new JdbcSegmentReader("mysql", config, columns, task, checkpoint, runId)) {
            validator.validateTarget(targetColumns);
        }
    }

    @Override String targetRange(String last, String upper) {
        throw new IllegalStateException("日期分表仅支持无辅助列的 MySQL 去重写入协议");
    }

    @Override void summarize(SyncEngine.Stats stats) {
        stats.addNote("MySQL 日期分表：已完成 " + checkpoint.state.dateTablePlan.size()
                + " 张表；" + (checkpoint.state.dateTableEndDate == null || checkpoint.state.dateTableEndDate.isEmpty()
                    ? "全部匹配日期" : "本轮截止日期 " + checkpoint.state.dateTableEndDate)
                + "；未发现 " + checkpoint.state.dateTableMissing + " 个日期的表，下次执行重新发现");
        if (skippedHistorical > 0)
            stats.addNote("按新表策略跳过 " + skippedHistorical + " 张历史日期表（表日期早于上次扫描，不补历史）");
    }

    @Override String describeCursor(String cursor) { return positionLabel(cursor); }
    static String positionLabel(String cursor) {
        if (cursor == null || END.equals(cursor)) return cursor == null ? null : "本轮分表结束";
        String[] value = decode(cursor);
        return value[0] + " / " + value[1];
    }
    private static String encode(String table, String cursor) {
        try { return JSON.writeValueAsString(Arrays.asList(table, cursor)); }
        catch (Exception e) { throw new IllegalStateException("无法保存日期分表游标", e); }
    }
    private static String[] decode(String cursor) {
        if (cursor == null) return null;
        try {
            String[] value = JSON.readValue(cursor, String[].class);
            if (value.length != 2 || value[0] == null || value[1] == null) throw new IllegalArgumentException();
            return value;
        } catch (Exception e) { throw new IllegalStateException("日期分表断点无效，请检查原任务配置", e); }
    }
    @Override public void close() {
        if (reader != null) reader.close();
        reader = null;
        current = null;
    }
}
