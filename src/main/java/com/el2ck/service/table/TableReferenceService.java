/**
 * 【文件职责】本文件定义表管理与数据编辑模块的业务服务 TableReferenceService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.table;

import com.el2ck.service.datasource.DataSourceService;

import com.el2ck.entity.arb.ArbWorkflow;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.sync.SyncTask;
import com.el2ck.exception.ApiException;
import com.el2ck.repository.metadata.MetadataRepository;
import com.el2ck.mapper.table.TableReferenceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表改名前的引用检查:只要仍有配置引用该表,就拒绝改名并列出引用方。
 *
 * <p>改名会连带破坏 ClickHouse 侧的物化视图与各处保存的配置,而平台无法确认这些引用的
 * 真实语义 —— 所以这里**只做「拦截 + 告知」,不替用户猜测改写配置**。宁可让人工确认后
 * 逐个修改,也不要自动改出一批看起来对、跑起来错的配置。
 *
 * <p>引用来源不限于平台元数据库(MySQL 的 {@code el_} 表):还要查 ClickHouse 库内表数据源
 * 与各类保存的配置(同步任务、工作流、仲裁工作流、已保存 SQL),详见
 * {@link #requireRenameAllowed(long, String, String)}。
 */
@Service
public class TableReferenceService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MetadataRepository store;
    private final DataSourceService sources;
    private final TableReferenceMapper mapper;
    private final com.el2ck.service.ck.CkInstanceService instances;
    private final com.el2ck.repository.metadata.TableChangeGate changeGate;
    /**
     * 创建表引用关系业务服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param store 元数据仓储，用于读取或写入平台业务记录
     * @param sources 数据源业务服务，用于解析来源配置、文件授权和对象元数据
     * @param mapper MyBatis 持久化映射器
     * @param instances 用于识别同一 CK 实例的连接别名
     * @param changeGate 协调手动修改与任务启动，避免状态检查后的并发窗口
     */
    public TableReferenceService(MetadataRepository store, DataSourceService sources, TableReferenceMapper mapper,
                                 com.el2ck.service.ck.CkInstanceService instances,
                                 com.el2ck.repository.metadata.TableChangeGate changeGate) {
        this.store = store;
        this.sources = sources;
        this.mapper = mapper;
        this.instances = instances;
        this.changeGate = changeGate;
    }

    /** 调用方必须持锁直到整次修改结束，再用运行状态检查确认表空闲。 */
    public com.el2ck.repository.metadata.TableChangeGate.Lease reserveChanges() { return changeGate.acquire(); }

    /** 手动表操作的统一闸门。运行状态从 MySQL 读取，覆盖定时执行、其他用户与其他应用实例。 */
    public void requireModificationAllowed(long connectionId, String database, String table) {
        requireIdle(connectionId, (db, name) -> database.equals(db) && table.equals(name));
    }

    /** 自由写 SQL 可能间接影响视图或整库，执行期间保守禁止该实例上的手动写 SQL。 */
    public void requireSqlModificationAllowed(long connectionId) {
        requireIdle(connectionId, (db, name) -> true);
    }

    /** 删库等操作需保护库内所有执行中表。 */
    public void requireDatabaseModificationAllowed(long connectionId, String database) {
        requireIdle(connectionId, (db, name) -> database.equals(db));
    }

    private void requireIdle(long wantedConn, java.util.function.BiPredicate<String, String> matches) {
        Map<Long, Long> instanceIds = new HashMap<>();
        for (Map<String, Object> task : mapper.runningSyncTasks()) {
            long conn = number(task.get("conn"));
            boolean target = sameInstance(conn, wantedConn, instanceIds)
                    && matches.test(text(task, "db"), text(task, "tbl"));
            DataSource source = new DataSource();
            source.setType(text(task, "source_type"));
            source.setConfigEnc(text(task, "config_enc"));
            if (target || sourceInUse(source, text(task, "source_object"), conn, wantedConn, matches, instanceIds)) {
                throw runningConflict("同步任务");
            }
        }
        for (Map<String, Object> row : mapper.runningConfigurations()) {
            String kind = text(row, "kind");
            long conn = number(row.get("conn"));
            JsonNode root = parse(text(row, "payload"), "执行中工作流");
            if ("arb".equals(kind) && sameInstance(conn, wantedConn, instanceIds)) {
                ArbWorkflow workflow = new ArbWorkflow();
                workflow.setTargetTable(text(row, "tbl"));
                String db = text(row, "db");
                if (matches.test(db, workflow.aggTableName()) || matches.test(db, workflow.svcTableName())) {
                    throw runningConflict("仲裁工作流");
                }
                for (JsonNode source : root.path("sources")) {
                    if (matches.test(db, "mv_" + text(row, "tbl") + "_" + source.path("id").asText())) {
                        throw runningConflict("仲裁工作流");
                    }
                }
            }
            if (activeReferences(root, conn, "arb".equals(kind) ? "ods_data" : "", wantedConn, matches, instanceIds)) {
                throw runningConflict(label(kind));
            }
        }
    }

    /** 比较实例而非连接编号：8123 / 9000 及同一实例的不同连接别名必须共享保护。 */
    private boolean sameInstance(long conn, long wanted, Map<Long, Long> ids) {
        if (conn == 0 || conn == wanted) return true;
        return ids.computeIfAbsent(conn, instances::instanceId).equals(ids.computeIfAbsent(wanted, instances::instanceId));
    }

    private boolean activeReferences(JsonNode node, long inheritedConn, String inheritedDb, long wantedConn,
                                     java.util.function.BiPredicate<String, String> matches, Map<Long, Long> ids) {
        if (node == null || !node.isContainerNode()) return false;
        long conn = node.has("connectionId") ? node.path("connectionId").asLong() : inheritedConn;
        String db = node.has("database") ? node.path("database").asText().trim()
                : node.has("db") ? node.path("db").asText().trim() : inheritedDb;
        String table = node.path("table").asText().trim();
        if (!table.isEmpty() && sameInstance(conn, wantedConn, ids) && matches.test(db, table)) return true;
        if (node.path("dataSourceId").asLong() > 0) {
            DataSource source = mapper.referencedSource(node.path("dataSourceId").asLong());
            if (source == null) throw new ApiException(409, "执行中任务的数据源不可读，暂不允许修改表");
            String object = node.path("sourceObject").asText(table);
            if (sourceInUse(source, object, conn, wantedConn, matches, ids)) return true;
        }
        for (JsonNode child : node) {
            if (activeReferences(child, conn, db, wantedConn, matches, ids)) return true;
        }
        return false;
    }

    /** CK 表数据源继承任务连接；外部 CK 数据源按实际主机识别，不把 MySQL 源误认为 CK 表。 */
    private boolean sourceInUse(DataSource source, String object, long conn, long wantedConn,
                                java.util.function.BiPredicate<String, String> matches, Map<Long, Long> ids) {
        boolean local = DataSource.CK_TABLE.equals(source.getType());
        if (!local && !DataSource.CLICKHOUSE.equals(source.getType())) return false;
        Map<String, Object> cfg = sources.decryptConfig(source);
        if (local) {
            if (!sameInstance(conn, wantedConn, ids)) return false;
        } else {
            long instance = ids.computeIfAbsent(wantedConn, instances::instanceId);
            boolean sameHost = instances.list().stream().anyMatch(i -> number(i.get("instanceId")) == instance
                    && text(i, "host").equalsIgnoreCase(text(cfg, "host")));
            if (!sameHost) return false;
        }
        String db = text(cfg, "database").trim();
        if (!local && db.isEmpty()) db = "default";
        String table = local || object.trim().isEmpty() ? text(cfg, "table").trim() : object.trim();
        String[] parts = table.replace("`", "").replace("\"", "").split("\\.", 2);
        if (parts.length == 2) { db = parts[0]; table = parts[1]; }
        return matches.test(db, table);
    }

    private static ApiException runningConflict(String kind) {
        return new ApiException(409, "该表正在被" + kind + "使用，执行期间不允许修改结构、表注释或数据，也不能改名、清空或删除；请等待任务结束后再操作");
    }

    private static long number(Object value) {
        return value == null ? 0 : value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
    }

    /**
     * 确认该表可以改名;若有引用则抛 400,并把全部引用方拼进提示文案。
     *
     * <p>四类引用逐一排查,**汇总后一次性报出**(而不是遇到第一个就抛):
     * <ol>
     *   <li><b>同步任务的目标表</b> —— 按连接 + 库 + 表名精确匹配;</li>
     *   <li><b>库内表数据源({@code ck_table})</b> —— 这类配置不持久化连接 ID(运行时借用任务的连接),
     *       所以只能按库表名保护,无法按连接收窄;配置不可读时同样拦下,要求先修复配置;</li>
     *   <li><b>各类保存的配置</b>(工作流 / 查询工作流 / 旧宽表任务 / 仲裁工作流)—— 载荷是 JSON,
     *       目标表可能藏在嵌套结构里,故用 {@link #references} 递归遍历,并匹配 ClickHouse 侧
     *       由平台自动派生出来的表名;</li>
     *   <li><b>已保存 SQL</b> —— 只能做词法层面的「可能引用」判断,见 {@link #sqlMayReference}。</li>
     * </ol>
     *
     * <p>只要命中任意一条就拒绝改名:即便引用只存在于某条保存的 SQL 里,静默改名也会让它在下次执行时失败。
     */
    public void requireRenameAllowed(long connectionId, String database, String table) {
        Set<String> refs = new LinkedHashSet<>();
        // 引用来源 1:同步任务把该表当目标表
        for (SyncTask task : store.listSyncTasks()) {
            if (task.getConnectionId() == connectionId && database.equals(task.getTargetDb()) && table.equals(task.getTargetTable())) {
                refs.add("同步任务 #" + task.getId() + "「" + task.getName() + "」(目标表)");
            }
        }
        // ck_table 不持久化连接 ID，运行时借用任务连接；保留可复用配置，按库表名保护。
        for (DataSource source : store.listDataSources()) {
            if (!DataSource.CK_TABLE.equals(source.getType())) continue;
            Map<String, Object> config;
            try {
                config = sources.decryptConfig(source);
                if (config == null || text(config, "database").trim().isEmpty() || text(config, "table").trim().isEmpty()) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                throw ApiException.validation("无法检查库内表数据源 #" + source.getId() + "「" + source.getName()
                        + "」的表引用，配置不可读或不完整，请先修复配置");
            }
            if (database.equals(text(config, "database").trim()) && table.equals(text(config, "table").trim())) {
                refs.add("库内表数据源 #" + source.getId() + "「" + source.getName() + "」(运行时选择连接)");
            }
        }
        // 引用来源 3:各类保存的配置(工作流/查询工作流/宽表/仲裁);载荷为 JSON,需递归查找
        for (Map<String, Object> row : mapper.configurations()) {
            String kind = text(row, "kind");
            String label = label(kind) + " #" + row.get("id") + "「" + row.get("name") + "」";
            long conn = Long.parseLong(text(row, "conn"));
            // conn=0 表示该配置未绑定连接(继承运行时选择),不能按连接排除
            if (conn > 0 && conn != connectionId) continue;
            JsonNode root = parse(text(row, "payload"), label);
            String target = text(row, "tbl");
            boolean targetMatch = table.equals(target);
            if ("arb".equals(kind)) {
                // 仲裁工作流除目标表外,还会派生出聚合表、服务表,以及每个源一张 mv_<目标表>_<源id> 物化视图
                ArbWorkflow workflow = new ArbWorkflow(); workflow.setTargetTable(target);
                targetMatch |= table.equals(workflow.aggTableName()) || table.equals(workflow.svcTableName());
                for (JsonNode source : root.path("sources")) {
                    targetMatch |= table.equals("mv_" + target + "_" + source.path("id").asText());
                }
            }
            if ((!target.isEmpty() && database.equals(text(row, "db")) && targetMatch)
                    || references(root, conn, "arb".equals(kind) ? "ods_data" : "", connectionId, database, table)) {
                refs.add(label);
            }
        }
        // 引用来源 4:已保存 SQL;只能词法判断,命中即视为「可能引用」
        for (Map<String, Object> row : mapper.savedSql()) {
            if (sqlMayReference(text(row, "sql"), text(row, "db"), database, table)) {
                refs.add("已保存 SQL #" + row.get("id") + "「" + row.get("name") + "」(可能引用，需人工核对)");
            }
        }
        if (!refs.isEmpty()) throw ApiException.validation("无法改表名，以下配置仍引用或可能引用该表："
                + String.join("；", refs) + "。请先修改或移除引用配置，再重试改名");
    }

    /**
     * 在配置载荷里递归查找对目标表的引用。
     *
     * <p>连接与库名沿用「就近优先」的继承规则:节点自己带 {@code connectionId} / {@code database}
     * (或 {@code db})就用节点的,否则继承父节点传来的值;{@code conn == 0} 视为未指定、
     * 与任意连接都算匹配。仲裁工作流的源位于 {@code ods_data} 库,故由调用方以
     * {@code inheritedDb} 传入,而不是在这里特判类型。
     */
    private static boolean references(JsonNode node, long inheritedConn, String inheritedDb,
                                      long wantedConn, String wantedDb, String wantedTable) {
        if (node == null) return false;
        long conn = node.has("connectionId") ? node.path("connectionId").asLong() : inheritedConn;
        String db = node.has("database") ? node.path("database").asText().trim() :
                node.has("db") ? node.path("db").asText().trim() : inheritedDb;
        if ((conn == 0 || conn == wantedConn) && wantedDb.equals(db)
                && wantedTable.equals(node.path("table").asText().trim())) return true;
        for (JsonNode child : node) {
            if (references(child, conn, db, wantedConn, wantedDb, wantedTable)) return true;
        }
        return false;
    }

    /**
     * 解析配置 JSON，并要求顶层为对象或数组等容器节点。
     *
     * <p>解析失败或结构不符合预期时统一抛出 400，提示调用方先修复对应配置；
     * 不回显原始配置内容，避免泄露其中可能存在的敏感信息。
     *
     * @param payload 配置 JSON
     * @param label 面向用户的配置类型名称
     * @return 已解析的容器节点
     */
    private static JsonNode parse(String payload, String label) {
        try {
            JsonNode node = JSON.readTree(payload);
            if (node == null || !node.isContainerNode()) throw new IllegalArgumentException();
            return node;
        } catch (Exception e) {
            throw ApiException.validation("无法检查" + label + "的表引用，配置损坏，请先修复配置");
        }
    }

    // 词法候选检查，不是 SQL 语义解析：跳过注释和字符串，保留带引号标识符。
    private static final Pattern SQL_TOKEN = Pattern.compile(
            "--[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|'(?:\\\\.|''|[^'\\\\])*'|`(?:``|[^`])*`|\"(?:\"\"|[^\"])*\"|[\\p{L}_][\\p{L}\\p{N}_$]*|[^\\s]");

    /**
     * 判断一段 SQL 是否**可能**引用该表 —— 刻意保守:匹配到就报,宁可让人工核对。
     *
     * <p>⚠️ 这是词法层面的候选检查,**不是 SQL 语义解析**:先把注释、字符串、带引号标识符
     * 切成 token(前三者直接丢弃,带引号的剥掉引号并按 SQL 规则还原转义),再看表名 token
     * 前面是不是 {@code 库名.} 限定 —— 没有限定时,只有「默认库正好是要改的库」才判为引用。
     *
     * <p>因此它的结论有两个方向都不精确:动态拼出来的表名、经视图间接引用的表会漏判;
     * 而恰好同名的局部变量、别名会误判。既然漏判的代价(改名后 SQL 静默失效)高于误判的代价
     * (多让人确认一次),这里就选择了偏保守的一侧,并在提示文案里标注「需人工核对」。
     */
    static boolean sqlMayReference(String sql, String defaultDb, String database, String table) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = SQL_TOKEN.matcher(sql);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("--") || token.startsWith("/*") || token.startsWith("'")) continue;
            if (token.startsWith("`") || token.startsWith("\"")) {
                String quote = token.substring(0, 1);
                token = token.substring(1, token.length() - 1).replace(quote + quote, quote);
            }
            tokens.add(token);
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (!table.equals(tokens.get(i))) continue;
            if (i >= 2 && ".".equals(tokens.get(i - 1))) {
                if (database.equals(tokens.get(i - 2))) return true;
            } else if (database.equals(defaultDb)) return true;
        }
        return false;
    }

    /**
     * 读取数据库行中的文本字段，null 转为空串。
     *
     * @param row 查询行
     * @param key 列名
     * @return 文本值或空串
     */
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? "" : value.toString();
    }
    /**
     * 将内部任务类型转换为前端可读的中文标签。
     *
     * @param kind 内部任务类型
     * @return 可读标签；未知类型按已保存可视化查询处理
     */
    private static String label(String kind) {
        switch (kind) {
            case "arb": return "仲裁工作流";
            case "wide": return "旧宽表任务";
            case "query": return "查询工作流";
            case "dag": return "工作流";
            default: return "已保存可视化查询";
        }
    }
}
