package com.el2ck.sync;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import java.util.Arrays;
import java.util.List;

/**
 * 同步源/目标的只读物理身份检查。地址、协议和转发端口均不能证明两张表不同。
 * Atomic 表优先比较表 UUID；Ordinary 表使用持久化 serverUUID 和库表名。
 * 无法读取身份时直接失败，绝不退回 host 字符串比较后继续清表。
 */
final class SyncTableIdentity {
    private static final String ZERO = "00000000-0000-0000-0000-000000000000";
    final String server, uuid, engine, database, table;

    private SyncTableIdentity(List<Object> row, String database, String table) {
        server = String.valueOf(row.get(0));
        uuid = String.valueOf(row.get(1));
        engine = String.valueOf(row.get(2));
        this.database = database;
        this.table = table;
        if (!validUuid(server) || ZERO.equals(server) || !validUuid(uuid))
            throw new IllegalStateException("无法取得可信的 ClickHouse 物理表身份，已停止同步");
    }

    static SyncTableIdentity read(CkClient client, String database, String table, boolean required) {
        QueryResult result = client.query("SELECT toString(serverUUID()),toString(uuid),engine FROM system.tables WHERE database=? AND name=?",
                Arrays.asList(database, table));
        if (!required && result.getRows().isEmpty()) return null;
        if (result.getRows().size() != 1 || result.getRows().get(0).size() != 3)
            throw new IllegalStateException("无法读取 ClickHouse 表身份：" + database + "." + table);
        return new SyncTableIdentity(result.getRows().get(0), database, table);
    }

    void requireDifferent(SyncTableIdentity target) {
        // 视图、Distributed、Merge 等可能间接读取目标；未解析依赖前不允许作为可写同步的源。
        if (!engine.endsWith("MergeTree"))
            throw new IllegalStateException("ClickHouse 同步源需为独立 MergeTree 系列表；视图或转发表无法确认与目标隔离");
        if (target != null && ((!ZERO.equals(uuid) && uuid.equals(target.uuid))
                || (server.equals(target.server) && database.equals(target.database) && table.equals(target.table))))
            throw new IllegalStateException("源表与目标表指向同一物理 ClickHouse 表，已阻止同步和清表（地址或端口别名不能绕过检查）");
    }

    /** 建表前后使用同一把名称锁，避免从“不存在”变为“有 UUID”时锁名突变。 */
    static Target target(CkClient client, String database, String table) {
        QueryResult result = client.query("SELECT toString(serverUUID())");
        if (result.getRows().size() != 1 || result.getRows().get(0).size() != 1)
            throw new IllegalStateException("无法确认目标 ClickHouse 实例身份");
        String server = String.valueOf(result.getRows().get(0).get(0));
        if (!validUuid(server) || ZERO.equals(server))
            throw new IllegalStateException("目标 ClickHouse 实例身份无效，不能取得同步锁");
        SyncTableIdentity identity = read(client,database,table,false);
        if (identity != null && !server.equalsIgnoreCase(identity.server))
            throw new IllegalStateException("目标连接切换到了其他 ClickHouse 实例，请使用稳定的单实例连接");
        return new Target(Arrays.asList("ck-target-v1",server.toLowerCase(java.util.Locale.ROOT),database,table).toString(),
                identity == null || ZERO.equals(identity.uuid) ? null : identity.uuid);
    }

    static final class Target {
        final String lockKey, uuid;
        Target(String lockKey, String uuid) { this.lockKey=lockKey; this.uuid=uuid; }
    }

    private static boolean validUuid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    }
}
