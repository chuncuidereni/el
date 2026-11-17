package com.el2ck.sync;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.QueryResult;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.WeakHashMap;

/**
 * 同步源/目标的只读物理身份检查。地址、协议和转发端口均不能证明两张表不同。
 * Atomic 表优先比较表 UUID；Ordinary 表使用持久化实例身份和库表名。
 * 21.8 没有 serverUUID() 时优先使用业务库的 Atomic UUID；Ordinary 库使用可见的 system 库 UUID。
 * 不用地址或主机名猜测身份，也不要求只读源账号获得写入或管理权限。
 * 无法读取身份时直接失败，绝不退回 host 字符串比较后继续清表。
 */
final class SyncTableIdentity {
    private static final String ZERO = "00000000-0000-0000-0000-000000000000";
    // 仅缓存一条客户端连接的函数能力，不缓存实例/表身份。弱引用避免连接池回收后残留。
    private static final Map<CkClient, Boolean> SERVER_UUID_SUPPORT = Collections.synchronizedMap(new WeakHashMap<CkClient, Boolean>());
    final String server, systemUuid, databaseUuid, uuid, engine, database, table;

    private SyncTableIdentity(List<Object> row, Instance instance, String database, String table) {
        if (!instance.identity().equals(normalize(row.get(0))))
            throw new IllegalStateException("目标连接切换到了其他 ClickHouse 实例，请使用稳定的单实例连接");
        server = instance.server;
        systemUuid = instance.systemUuid;
        databaseUuid = instance.databaseUuid;
        uuid = normalize(row.get(1));
        engine = String.valueOf(row.get(2));
        this.database = database;
        this.table = table;
        if (!validUuid(uuid))
            throw new IllegalStateException("无法取得可信的 ClickHouse 物理表身份，已停止同步");
    }

    static SyncTableIdentity read(CkClient client, String database, String table, boolean required) {
        return read(client, database, table, required, instance(client,database));
    }

    private static SyncTableIdentity read(CkClient client, String database, String table, boolean required, Instance instance) {
        // 元数据与实例身份在同一条查询里读取，复核探测后连接没有被代理切到其他实例。
        String expression = instance.server == null
                ? "(SELECT toString(uuid) FROM system.databases WHERE name=?)" : "toString(serverUUID())";
        QueryResult result = client.query("SELECT " + expression + ",toString(uuid),engine FROM system.tables WHERE database=? AND name=?",
                instance.server == null ? Arrays.asList(instance.databaseUuid == null ? "system" : database,database,table)
                        : Arrays.asList(database, table));
        if (!required && result.getRows().isEmpty()) return null;
        if (result.getRows().size() != 1 || result.getRows().get(0).size() != 3)
            throw new IllegalStateException("无法读取 ClickHouse 表身份：" + database + "." + table);
        return new SyncTableIdentity(result.getRows().get(0), instance, database, table);
    }

    void requireDifferent(SyncTableIdentity target) {
        // 视图、Distributed、Merge 等可能间接读取目标；未解析依赖前不允许作为可写同步的源。
        if (!engine.endsWith("MergeTree"))
            throw new IllegalStateException("ClickHouse 同步源需为独立 MergeTree 系列表；视图或转发表无法确认与目标隔离");
        if (target != null && ((!ZERO.equals(uuid) && uuid.equals(target.uuid))
                || (sameInstance(target) && database.equals(target.database) && table.equals(target.table))))
            throw new IllegalStateException("源表与目标表指向同一物理 ClickHouse 表，已阻止同步和清表（地址或端口别名不能绕过检查）");
    }

    /** 建表前后使用同一把名称锁，避免从“不存在”变为“有 UUID”时锁名突变。 */
    static Target target(CkClient client, String database, String table) {
        Instance instance = instance(client,database);
        SyncTableIdentity identity = read(client,database,table,false,instance);
        String compatibility = instance.databaseUuid != null
                ? Arrays.asList("ck-target-db-v1",instance.databaseUuid,database,table).toString()
                : instance.systemUuid == null ? null : Arrays.asList("ck-target-system-v1",instance.systemUuid,database,table).toString();
        // 保留已有新版本断点的 ck-target-v1 锁。附加兼容锁让 21.8 升级后仍能识别原断点。
        String primary = instance.server == null ? compatibility
                : Arrays.asList("ck-target-v1",instance.server,database,table).toString();
        return new Target(primary, compatibility,
                identity == null || ZERO.equals(identity.uuid) ? null : identity.uuid);
    }

    private boolean sameInstance(SyncTableIdentity other) {
        return (server != null && server.equals(other.server))
                || (systemUuid != null && systemUuid.equals(other.systemUuid))
                || (databaseUuid != null && databaseUuid.equals(other.databaseUuid));
    }

    private static Instance instance(CkClient client, String database) {
        // 能力探测比版本号可靠；不执行未知函数，不把权限/网络错误吞成兼容降级。
        Boolean supported = SERVER_UUID_SUPPORT.get(client);
        if (supported == null) {
            String count = scalar(client.query("SELECT count() FROM system.functions WHERE name='serverUUID'"));
            if (!"0".equals(count) && !"1".equals(count))
                throw new IllegalStateException("ClickHouse 函数能力检查结果异常，已停止同步");
            supported = "1".equals(count);
            SERVER_UUID_SUPPORT.put(client, supported);
        }
        QueryResult result = client.query("SELECT " + (supported ? "toString(serverUUID())" : "''")
                + ",(SELECT toString(uuid) FROM system.databases WHERE name='system')"
                + ",(SELECT toString(uuid) FROM system.databases WHERE name=?)", Arrays.asList(database));
        if (result.getRows().size()!=1 || result.getRows().get(0).size()!=3)
            throw new IllegalStateException("无法读取 ClickHouse 库身份，请检查元数据可见性和读取权限");
        String system = optionalUuid(result.getRows().get(0).get(1));
        String databaseUuid = optionalUuid(result.getRows().get(0).get(2));
        String server = supported ? normalize(result.getRows().get(0).get(0)) : null;
        if (server != null && (!validUuid(server) || ZERO.equals(server)))
            throw new IllegalStateException("ClickHouse 实例 UUID 无效，已停止同步");
        if (server == null && system == null && databaseUuid == null)
            throw new IllegalStateException("当前 ClickHouse 不支持 serverUUID()，且业务库与 system 库没有可读取的持久化 UUID；请允许账号查看 system 库元数据，或使用支持实例 UUID 的 ClickHouse，原断点已保留");
        return new Instance(server, system, databaseUuid);
    }

    private static String scalar(QueryResult result) {
        if (result.getRows().size()!=1 || result.getRows().get(0).size()!=1)
            throw new IllegalStateException("无法读取 ClickHouse 实例元数据，请检查 system.functions、system.databases 的可见性和读取权限");
        return normalize(result.getRows().get(0).get(0));
    }

    private static String normalize(Object value) { return String.valueOf(value).toLowerCase(Locale.ROOT); }

    private static String optionalUuid(Object value) {
        if (value == null) return null;
        String uuid = normalize(value);
        if (!validUuid(uuid)) throw new IllegalStateException("ClickHouse 库 UUID 无效，已停止同步");
        return ZERO.equals(uuid) ? null : uuid;
    }

    private static final class Instance {
        final String server, systemUuid, databaseUuid;
        Instance(String server, String systemUuid, String databaseUuid) {
            this.server=server; this.systemUuid=systemUuid; this.databaseUuid=databaseUuid;
        }
        String identity() { return server != null ? server : databaseUuid != null ? databaseUuid : systemUuid; }
    }

    static final class Target {
        final String lockKey, compatibilityLockKey, uuid;
        Target(String lockKey, String compatibilityLockKey, String uuid) {
            this.lockKey=lockKey; this.compatibilityLockKey=compatibilityLockKey; this.uuid=uuid;
        }
    }

    private static boolean validUuid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    }
}
