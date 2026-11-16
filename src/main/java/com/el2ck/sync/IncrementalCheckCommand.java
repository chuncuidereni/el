package com.el2ck.sync;

/**
 * 【文件职责】执行增量同步前置检查，核对源表、目标表、水位和映射配置是否满足可恢复同步条件。
 */

import com.el2ck.config.AppConfig;
import com.el2ck.ckpool.*;
import com.el2ck.entity.sync.SyncFieldMapping;
import com.el2ck.service.datasource.DbSource;
import com.el2ck.util.Crypto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * 只读诊断命令，使用配置专用 Boot 容器，不迁移元数据、不播种账号、不启动调度器。
 *
 * <p>入口为 {@code --check-incremental}。默认只读平台元数据(MySQL 的 {@code el_*} 表)，
 * 静态检查每个增量任务的配置是否自洽；加 {@code --online} 才连数据源/目标做在线结构核对，
 * 加 {@code --counts} 再查行数与去重(须与 {@code --online} 同用)。
 *
 * <p>{@code --db PATH} 是历史遗留入口：指向旧的 SQLite 元数据库，仅用于读取旧部署做只读诊断；
 * MySQL 主路径则从 {@code app.metadata} 取连接，两者二选一(见 {@link #openMetadata})。
 * 退出码：0 已请求检查全部通过，1 检查未完成(参数/配置/连接失败或存在未完成项)，2 发现待处理项。
 */
public final class IncrementalCheckCommand {
    private static final ObjectMapper JSON = new ObjectMapper();
    private IncrementalCheckCommand() { }

    /** 在线探测抽象：把「读源结构」与「读目标结构」从诊断逻辑解耦，便于测试替换为桩实现。 */
    interface Probe {
        DbSource.IncrementalInspection source(Map<String, Object> source, String object, String column,
                                              String secret, boolean counts) throws Exception;
        TargetInspection target(Map<String, Object> connection, String database, String table,
                                String column, String primaryKey, String secret, boolean counts) throws Exception;
    }
    /** 单个目标表的在线检查结果：增量列实际类型、当前水位、行数、映射主键去重数。 */
    static final class TargetInspection {
        String type;
        String watermark;
        Long count;
        Long unique;
    }

    /** 入口：使用真实的在线探测实现。 */
    public static int run(String[] args, Map<String, String> env, PrintStream out) {
        return run(args, env, out, new OnlineProbe());
    }

    /**
     * 解析参数、启动只读配置容器并执行检查(包可见，供测试注入 {@link Probe})。
     *
     * <p>只接受本命令自己的开关，以及 {@code --spring./--app./--logging.} 前缀的 Spring 参数；
     * 其余参数(可能夹带敏感内容)一律判为参数错误，且错误提示不回显参数原文。
     */
    static int run(String[] args, Map<String, String> env, PrintStream out, Probe probe) {
        List<String> bootArgs = new ArrayList<>();
        String db = null; // --db 显式启用旧版离线 SQLite 诊断；不传时走 MySQL 元数据主路径
        String secretEnv = "APP_SECRET_KEY";
        Long taskId = null;
        boolean online = false, counts = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help":
                        // 帮助文本本身是退出码等约定的唯一书面说明，改逻辑时须同步更新这几行。
                        out.println("用法: java -jar el-to-ck-server.jar --check-incremental [--db PATH] [--task ID] [--online] [--counts] [--secret-env NAME]");
                        out.println("默认仅检查 MySQL 元数据；--online 查询 MySQL/PG 主键和源列、ClickHouse 目标列；--counts 另查行数/主键去重/水位，须配合 --online。");
                        out.println("由 Spring Boot 加载 application.yml、外部配置和 profile；支持 --spring.config.location=PATH、--spring.profiles.active=NAME。只启动配置容器；数据库取 app.metadata；--db 用于旧 SQLite 文件；密钥优先取指定环境变量，否则取 app.secret-key。退出码: 0 已请求检查通过，1 检查未完成，2 发现待处理项。");
                        return 0;
                    case "--db": db = args[++i]; break;
                    case "--task": taskId = Long.parseLong(args[++i]); if (taskId <= 0) throw new IllegalArgumentException(); break;
                    case "--secret-env": secretEnv = args[++i]; if (secretEnv.trim().isEmpty()) throw new IllegalArgumentException(); break;
                    case "--online": online = true; break;
                    case "--counts": counts = true; break;
                    default:
                        if (args[i].matches("--(spring|app|logging)\\..+=.*")) bootArgs.add(args[i]);
                        else throw new IllegalArgumentException();
                }
            }
            if (counts && !online) throw new IllegalArgumentException(); // 计数依赖 --online 建立的源/目标连接，单独用没有意义
        } catch (Exception invalid) {
            out.println("参数错误，请使用 --help 查看用法（不回显参数内容）。");
            return 1;
        }
        // 只启动配置容器：不跑迁移、不播种账号、不启动调度器，保证诊断本身不产生任何副作用。
        try (org.springframework.context.ConfigurableApplicationContext context =
                     com.el2ck.config.DiagnosticConfiguration.start(bootArgs.toArray(new String[0]))) {
            return inspect(db, taskId, online, counts, secretEnv, env, out, probe, context.getBean(AppConfig.class));
        } catch (RuntimeException invalid) {
            out.println("无法加载 Spring Boot 配置，请检查 application.yml 及配置参数。");
            return 1;
        }
    }

    /**
     * 核心诊断逻辑：在元数据的同一只读快照内遍历增量任务，逐项给出「待处理(问题)」与「未完成(无法判定)」。
     *
     * <p>两类结论语义不同：待处理 = 已能确定的问题(如目标表被多任务共用)，需要人去改配置；
     * 未完成 = 因密钥、连接、权限等无法判定，说明本次诊断没覆盖到。据此综合决定退出码
     * (有未完成优先返回 1，否则有待处理返回 2，全部通过返回 0)。
     */
    private static int inspect(String db, Long taskId, boolean online, boolean counts, String secretEnv,
                               Map<String, String> env, PrintStream out, Probe probe, AppConfig config) {
        // 密钥优先取 --secret-env 指定的环境变量，缺失才回落到配置 app.secret-key。
        String secret = env.getOrDefault(secretEnv, config.getSecretKey());
        if (online && (secret == null || secret.isEmpty())) {
            out.println("密钥环境变量为空，在线检查未执行。"); return 1;
        }
        if (online && AppConfig.DEFAULT_SECRET_KEY.equals(secret))
            out.println("提示：在线检查使用内置开发密钥；若部署已更换密钥，请通过环境变量提供实际密钥。");
        // 明确告知本次诊断的覆盖范围，避免把「仅元数据」的通过误当成「源与目标都已验证」。
        out.println(online ? "只读诊断：元数据与在线结构" + (counts ? "及计数" : "（不扫描行数）")
                : "只读诊断：" + (db == null ? "仅平台元数据" : "仅本地元数据") + "；未验证源主键、实际列结构或目标数据。");
        try {
            // 打开只读元数据连接：--db 走旧 SQLite 文件，否则走 MySQL 主库。
            try (java.sql.Connection connection = openMetadata(db, config)) {
                connection.setAutoCommit(false); // 同一只读快照内读取配置。
                List<Map<String, Object>> tasks = rows(connection, "sync_tasks");
                // 数据源/连接按 id 建索引，供任务按外键快速取用，避免每次遍历都全表扫描。
                Map<Long, Map<String, Object>> sources = index(rows(connection, "data_sources"));
                Map<Long, Map<String, Object>> targets = index(rows(connection, "connections"));
                int checked = 0, problems = 0, incomplete = 0;
                for (Map<String, Object> task : tasks) {
                    long id = id(task, "id");
                    // --task 指定时只看该任务；本命令只诊断增量任务，全量任务直接跳过。
                    if (taskId != null && id != taskId) continue;
                    if (!"incremental".equals(text(task, "mode"))) continue;
                    checked++;
                    out.println("任务 #" + id + " " + safe(text(task, "name")));
                    List<String> issues = new ArrayList<>();
                    List<String> unknown = new ArrayList<>();
                    // 按外键取任务引用的数据源与目标连接记录；缺失即无法继续在线核对。
                    Map<String, Object> source = sources.get(id(task, "data_source_id"));
                    Map<String, Object> target = targets.get(id(task, "connection_id"));
                    if (source == null) issues.add("数据源记录不存在");
                    if (target == null) issues.add("目标连接记录不存在");
                    String column = text(task, "incr_column");
                    List<SyncFieldMapping> mappings = new ArrayList<>();
                    SyncFieldMapping increment = null;
                    // 解析 mapping_json，同时找出「源列 == 增量列」的那条映射；增量列被重复映射说明配置有歧义。
                    try {
                        mappings = JSON.readValue(text(task, "mapping_json"), new TypeReference<List<SyncFieldMapping>>() { });
                        if (mappings == null) throw new IllegalArgumentException();
                        for (SyncFieldMapping mapping : mappings) {
                            if (mapping == null) throw new IllegalArgumentException();
                            if (column.equals(mapping.getSourceColumn())) {
                                if (increment != null) issues.add("增量源列存在重复映射");
                                increment = mapping;
                            }
                        }
                    } catch (Exception invalid) {
                        issues.add("字段映射 JSON 无法读取");
                        mappings = new ArrayList<>();
                        increment = null;
                    }
                    if (column.isEmpty()) issues.add("未配置增量键");
                    else if (increment == null) issues.add("增量键不在字段映射中");
                    // 增量键的映射类型必须落在引擎支持的数值/日期/时间戳范围内，否则同步无法按水位过滤。
                    if (increment != null && !SyncEngine.isIncrementalType(increment.getType()))
                        issues.add("增量目标映射类型不受当前引擎支持（支持数值、日期或时间戳；不支持 "
                                + safe(increment.getType()) + "）");
                    // 目标库优先用任务自身的 target_db，为空才回退到连接上配置的 database。
                    String database = text(task, "target_db");
                    if (database.isEmpty() && target != null) database = text(target, "database");
                    String table = text(task, "target_table");
                    // 标识符非法会让后面拼接的 DESCRIBE/SELECT 出问题，提前拦截(也是防注入)。
                    if (!ident(database) || !ident(table) || (increment != null && !ident(increment.getTargetColumn())))
                        issues.add("目标库表或增量目标列标识符非法");
                    // ⚠️ 目标表独占检查(本命令 O(n²) 的根源)：同一个目标表(同连接 + 同库 + 同表)只允许
                    // 一个增量任务写入。多个增量任务写同一张表时，各自用 max(增量列) 算水位，会互相把
                    // 对方写入的行当作自己的边界，导致重复写入或漏数，且现象零散、极难排查，故列为硬性待处理项。
                    // 增量任务数量通常只有几十个，双重循环的代价可以接受。
                    for (Map<String, Object> other : tasks) {
                        String otherDb = text(other, "target_db");
                        if (otherDb.isEmpty() && target != null) otherDb = text(target, "database");
                        // 与自身比较无意义；同连接 + 同库 + 同表即视为冲突。
                        if (id(other, "id") != id && id(other, "connection_id") == id(task, "connection_id")
                                && database.equals(otherDb) && table.equals(text(other, "target_table"))) {
                            issues.add("目标表与任务 #" + id(other, "id") + " 共用，不满足独占要求");
                        }
                    }
                    // 只有 --online 且本地配置已足够自洽时才去连源/目标，避免对必然失败的任务发无用查询。
                    if (online && source != null && target != null && increment != null && !column.isEmpty()
                            && ident(database) && ident(table) && ident(increment.getTargetColumn())) {
                        DbSource.IncrementalInspection sourceInfo = null;
                        String type = text(source, "type");
                        // 目前仅 MySQL/PG 数据源支持在线结构核对，其它源只能记为「未完成」。
                        if ("mysql".equals(type) || "pgsql".equals(type)) {
                            try {
                                sourceInfo = probe.source(source, text(task, "source_object"), column, secret, counts);
                                // 源端没有单列主键，后续就无法用主键映射做目标去重，先标记。
                                if (sourceInfo.primaryKey == null) issues.add("源表没有可用的单列主键");
                                if (sourceInfo.type == null) issues.add("源表没有配置的增量列");
                                else if (!SyncEngine.compatibleIncrementalTypes(sourceInfo.type, increment.getType()))
                                    issues.add("源增量列与目标映射类型不兼容");
                                // 可空的增量列：水位过滤(>/gt)天然排除 NULL 行，这些行永远不会被增量同步到，须人工确认。
                                if (sourceInfo.nullable) issues.add("源增量列可空；水位过滤不会选入 NULL，需核对空值处理");
                            } catch (Exception failed) { unknown.add("源检查失败：请核对密钥、连接、权限和表结构"); }
                        } else unknown.add("该源类型尚不支持在线检查，未验证源结构");
                        // 把源主键映射到目标列名，作为目标去重检查的依据；映射里没有或名字非法则放弃该检查。
                        String pkTarget = null;
                        if (sourceInfo != null && sourceInfo.primaryKey != null) {
                            for (SyncFieldMapping mapping : mappings) if (sourceInfo.primaryKey.equals(mapping.getSourceColumn())) pkTarget = mapping.getTargetColumn();
                        }
                        if (!ident(pkTarget)) pkTarget = null;
                        // 核对目标列：既要类型受支持，也要与任务里配置的映射类型兼容(改过表结构时容易不一致)。
                        try {
                            TargetInspection targetInfo = probe.target(target, database, table, increment.getTargetColumn(),
                                    pkTarget, secret, counts);
                            if (targetInfo.type == null) issues.add("目标表缺少增量映射列");
                            else if (!SyncEngine.isIncrementalType(targetInfo.type)) issues.add("目标实际增量列类型不受支持");
                            else if (!SyncEngine.compatibleIncrementalTypes(increment.getType(), targetInfo.type))
                                issues.add("目标实际增量列与任务映射类型不兼容");
                            if (counts) {
                                out.println("  目标行数=" + targetInfo.count + "，目标水位=" + safe(targetInfo.watermark)
                                        + "，映射主键去重数=" + targetInfo.unique);
                                // 去重数 != 行数说明同一主键有多行；若业务本就按主键保留更新版本则是正常的，
                                // 故措辞为「请核对业务语义」而非直接判错。
                                if (targetInfo.unique != null && !targetInfo.unique.equals(targetInfo.count))
                                    issues.add("目标映射主键存在重复（也可能是更新版本历史），请核对业务语义");
                                if (sourceInfo != null && sourceInfo.count != null) {
                                    out.println("  源行数=" + sourceInfo.count);
                                    // 源与目标是两次独立查询、中间可能有写入，行数不同不足以单独证明漏数，只作提示。
                                    if (!sourceInfo.count.equals(targetInfo.count)) issues.add("源/目标行数不同；查询非同一快照，差异不能单独证明漏数");
                                }
                                if (pkTarget == null) unknown.add("未取得主键映射，无法完成目标去重检查");
                            }
                        } catch (Exception failed) { unknown.add("目标检查失败：请核对密钥、连接、权限和表结构"); }
                    }
                    // 一个任务的问题分两栏输出：待处理(可确定的问题)与未完成(无法判定)，语义不同不可混计。
                    for (String issue : issues) out.println("  [待处理] " + issue);
                    for (String note : unknown) out.println("  [未完成] " + note);
                    if (issues.isEmpty() && unknown.isEmpty()) out.println("  已请求的检查项通过");
                    if (!issues.isEmpty()) problems++;
                    if (!unknown.isEmpty()) incomplete++;
                }
                // 显式指定了任务却一个都没检查到，说明任务号不存在或不是增量任务，按「未完成」返回 1。
                if (taskId != null && checked == 0) { out.println("指定任务不存在或不是增量任务。"); return 1; }
                out.println("检查 " + checked + " 个增量任务；待处理 " + problems + " 个；检查未完成 " + incomplete + " 个。");
                // 退出码取舍：诊断不完整(有未完成)优先返回 1；否则有待处理返回 2；全部通过返回 0。
                return incomplete > 0 ? 1 : problems > 0 ? 2 : 0;
            }
        } catch (Exception failed) {
            out.println("无法完成元数据检查：请核对数据库路径、读取权限和 Java 元数据表结构；未执行迁移。");
            return 1;
        }
    }

    /**
     * 打开只读元数据连接：{@code legacyFile} 非空时读旧 SQLite 文件(诊断用)，否则连 MySQL 元数据库。
     *
     * <p>两条路径都强制只读，确保诊断本身不改动元数据；SQLite 文件不存在时报错而非新建空库，
     * 避免误把路径写错当成「空元数据、无任务」通过检查。
     */
    private static java.sql.Connection openMetadata(String legacyFile, AppConfig config) throws Exception {
        if (legacyFile != null) {
            Path path = Paths.get(legacyFile).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Legacy database file missing");
            org.sqlite.SQLiteConfig sqlite = new org.sqlite.SQLiteConfig();
            sqlite.setReadOnly(true);
            return DriverManager.getConnection("jdbc:sqlite:" + path, sqlite.toProperties());
        }
        String url = config.getMetadata().getUrl();
        if (url == null || !url.startsWith("jdbc:mysql:")) throw new IllegalArgumentException("Expected MySQL URL");
        java.sql.Connection connection = DriverManager.getConnection(url,
                config.getMetadata().getUsername(), config.getMetadata().getPassword());
        connection.setReadOnly(true);
        return connection;
    }

    /**
     * 读取整张元数据表为「列名 → 值」的行列表。
     *
     * <p>MySQL 主路径下平台表统一带 {@code el_} 前缀(见 docs/MySQL部署说明.md)，旧 SQLite 库沿用
     * 无前缀的原始表名，故按连接 URL 判断是否补前缀。
     */
    private static List<Map<String, Object>> rows(java.sql.Connection connection, String table) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement query = connection.createStatement(); ResultSet result = query.executeQuery("SELECT * FROM " + (connection.getMetaData().getURL().startsWith("jdbc:mysql:") ? "el_" : "") + table)) {
            ResultSetMetaData md = result.getMetaData();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), result.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }
    /** 按 id 把行列表建成查找表，供任务按外键快速取数据源/连接记录。 */
    private static Map<Long, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) result.put(id(row, "id"), row);
        return result;
    }
    /** 取行的数值型 id 字段。 */
    private static long id(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    /** 取行的字符串字段并去空白；null 记为空串，便于统一用 isEmpty 判断「未配置」。 */
    private static String text(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? "" : value.toString().trim(); }
    /** 输出前的清洗：null 显示为「未提供」，并抹掉控制字符，防止元数据里的恶意内容污染终端。 */
    private static String safe(String value) { return value == null ? "未提供" : value.replaceAll("[\\p{Cntrl}]", " "); }
    /** 标识符校验：字母或下划线开头，后跟字母/数字/下划线。 */
    private static boolean ident(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*"); }
    /**
     * 校验标识符合法并加反引号，用于安全拼接 DESCRIBE/SELECT 的表名与列名。
     * {@code ident} 只放行字母/数字/下划线且首字符受限，杜绝把用户输入直接拼进 SQL 造成注入。
     */
    private static String quoted(String value) { if (!ident(value)) throw new IllegalArgumentException(); return "`" + value + "`"; }

    /** 真实探测实现：解密数据源/连接的密文配置，分别连源库与 ClickHouse 读取在线结构。 */
    private static final class OnlineProbe implements Probe {
        @Override public DbSource.IncrementalInspection source(Map<String, Object> source, String object, String column,
                                                                String secret, boolean counts) throws Exception {
            // 源配置以密文存储在 config_enc 中，先解密再交给 DbSource 做结构检查。
            Map<String, Object> cfg = JSON.readValue(Crypto.decrypt(secret, text(source, "config_enc")),
                    new TypeReference<Map<String, Object>>() { });
            return DbSource.inspectIncremental(DbSource.engineOf(text(source, "type")), cfg, object, column, counts);
        }

        @Override public TargetInspection target(Map<String, Object> connection, String database, String table,
                                                  String column, String primaryKey, String secret, boolean counts) throws Exception {
            // 用连接记录构造只读 ClickHouse 客户端：设查询超时，避免诊断在目标端挂死。
            CkConfig cfg = CkConfig.defaults();
            cfg.setHost(text(connection, "host")); cfg.setPort((int) id(connection, "port"));
            // 协议取自连接行;旧 SQLite 库没有该列,text() 返回空串→normalizeProtocol 归一到 native。
            cfg.setProtocol(CkConfig.normalizeProtocol(text(connection, "protocol")));
            cfg.setDatabase(database); cfg.setUsername(text(connection, "username"));
            cfg.setPassword(Crypto.decrypt(secret, text(connection, "password_enc")));
            cfg.setSecure("1".equals(text(connection, "secure")) || "true".equalsIgnoreCase(text(connection, "secure")));
            cfg.setReadOnly(true); cfg.setQueryTimeoutMs(30000L);
            String qualified = quoted(database) + "." + quoted(table);
            TargetInspection info = new TargetInspection();
            try (CkClient client = CkClient.open(cfg)) {
                // DESCRIBE 只读取列定义；从中挑出增量列的实际类型。
                for (List<Object> row : client.query("DESCRIBE TABLE " + qualified).getRows())
                    if (row.size() > 1 && column.equals(String.valueOf(row.get(0)))) info.type = String.valueOf(row.get(1));
                if (counts && info.type != null) {
                    // 一次查询同时取行数、最大水位与(有主键时的)去重数；空表用水位给 NULL 占位，避免误读成 0。
                    String sql = "SELECT count(), if(count()=0,NULL,toString(max(" + quoted(column) + ")))"
                            + (primaryKey == null ? "" : ", uniqExact(" + quoted(primaryKey) + ")") + " FROM " + qualified;
                    List<Object> row = client.query(sql).getRows().get(0);
                    info.count = ((Number) row.get(0)).longValue();
                    info.watermark = row.get(1) == null ? "空目标表/无水位" : row.get(1).toString();
                    if (primaryKey != null) info.unique = ((Number) row.get(2)).longValue();
                }
            }
            return info;
        }
    }
}
