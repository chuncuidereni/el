/**
 * 【文件职责】本文件定义表管理与数据编辑模块的进程内缓存组件 TableStatsCache。缓存重复读取的统计结果，并通过过期或失效机制降低底层查询压力。
 */

package com.el2ck.service.table;

import com.el2ck.entity.table.TableStat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 表统计结果的 TTL 缓存,对应 Go table_service.go 里的 statsCache / statsCacheTTL。
 *
 * <p>为什么要有:行数与磁盘大小要扫 {@code system.columns} + {@code system.parts},
 * 比列个表名重得多,而列表页会反复取。按「连接 + 库」缓存 15 秒,手动刷新页面即可越过 TTL。
 *
 * <p>表整理(OPTIMIZE)和删除后主动失效当前用户的对应项,刷新列表时不再返回已删除表的统计。
 *
 * <p>时钟可注入是为了让测试不用 sleep:推进假时钟即可验证过期。
 */
final class TableStatsCache {

    /** 缓存有效期(毫秒),对应 Go statsCacheTTL。 */
    static final long TTL_MILLIS = 15_000L;

    /** key = "连接ID:库名" → 统计结果 + 写入时刻。 */
    private final Map<String, Entry> entries = new HashMap<>();

    /** 时钟;默认取系统毫秒,测试可注入假时钟。 */
    private final LongSupplier clock;

    /** 生产用构造器:取系统时钟。 */
    TableStatsCache() {
        this(System::currentTimeMillis);
    }

    /** 测试用构造器:注入可控时钟。 */
    TableStatsCache(LongSupplier clock) {
        this.clock = clock;
    }

    /** 取缓存;未命中或已过期返回 null(过期项顺手清掉)。 */
    synchronized List<TableStat> get(long connId, String db) {
        return get(0L, connId, db);
    }

    /** 按用户隔离取缓存:不同 CK 身份看到的表集合与统计不同。 */
    synchronized List<TableStat> get(long userId, long connId, String db) {
        Entry e = entries.get(key(userId, connId, db));
        if (e == null) {
            return null;
        }
        if (clock.getAsLong() - e.at >= TTL_MILLIS) {
            entries.remove(key(userId, connId, db));
            return null;
        }
        return e.value;
    }

    /** 写缓存。 */
    synchronized void put(long connId, String db, List<TableStat> stats) {
        put(0L, connId, db, stats);
    }

    /** 按用户隔离写缓存。 */
    synchronized void put(long userId, long connId, String db, List<TableStat> stats) {
        entries.put(key(userId, connId, db), new Entry(stats, clock.getAsLong()));
    }

    /** 使某个「连接 + 库」的缓存立即失效(表整理后调用)。 */
    synchronized void invalidate(long connId, String db) {
        invalidate(0L, connId, db);
    }

    /** 按用户使缓存立即失效。 */
    synchronized void invalidate(long userId, long connId, String db) {
        entries.remove(key(userId, connId, db));
    }

    /** 缓存键:用户、连接 ID 与库名共同定位,避免不同身份/连接的同名库串数据。 */
    private static String key(long userId, long connId, String db) {
        return userId + ":" + connId + ":" + db;
    }

    /** 一条缓存记录:值 + 写入时刻(毫秒)。 */
    private static final class Entry {
        private final List<TableStat> value;
        private final long at;

        /**
         * 创建不可变缓存项。
         *
         * @param value 表统计结果快照
         * @param at 写入时刻，毫秒时间戳
         */
        private Entry(List<TableStat> value, long at) {
            this.value = value;
            this.at = at;
        }
    }
}
