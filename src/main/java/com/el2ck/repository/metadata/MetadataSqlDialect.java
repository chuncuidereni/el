package com.el2ck.repository.metadata;

/**
 * 【文件职责】把业务层逻辑表名和 MySQL 语法转换为物理表名、保留字转义及 upsert 语句。
 */

import java.util.*;
import java.util.regex.*;

/**
 * 平台元数据库 SQL 的方言翻译:{@link #mysql(String)} 把旧 SQLite 写法的仓储 SQL 改写为 MySQL 可执行的形式。
 *
 * <p>调用点在 {@link MysqlMetadataDataSource#wrap},即驱动边界 —— 只作用于平台元数据库。
 * <b>绝不用于源端(MySQL/PG/Mongo 数据源)与 ClickHouse 的 SQL</b>:那些 SQL 由各自的连接直接执行,
 * 既不知道 {@code el_} 前缀,也不该被这里改写成除别名。
 */
public final class MetadataSqlDialect {
    // mysql() 只翻译平台元数据库仓储 SQL；逻辑表名统一映射为 el_ 前缀，冲突子句按 MySQL upsert 语义改写。
    private MetadataSqlDialect() {}

    /**
     * 逻辑表名 → 实际表名(el_ 前缀)的映射表。
     *
     * <p>⚠️ <b>新增平台表时必须在此同步登记</b>,否则该表的 SQL 会以未加前缀的名字发到 MySQL 而报表不存在。
     * 表结构由 {@code docs/mysql/} 下的手工 SQL 维护，两者应保持一致。
     */
    private static final Set<String> TABLES = new HashSet<>(Arrays.asList(
        "connections", "app_settings", "user_remarks", "data_sources", "sync_tasks", "sync_runs",
        "wf_tasks", "wf_runs", "wf_run_logs", "ds_groups", "db_groups", "group_dbs", "sync_files",
        "sync_checkpoints",
        "tbl_groups", "tbl_group_tables", "query_workflows", "query_workflow_runs", "sql_query_groups",
        "sql_queries", "wf_groups", "wide_table_tasks", "wide_table_runs", "arb_workflows",
        "arb_workflow_runs", "app_users"));

    /** MySQL 保留字里被旧 SQL 当普通列名用的几个,需要加反引号才能解析。 */
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList("database", "rows", "sql", "key"));

    /** Native upsert syntax: KEY here is a keyword, not the settings table's key column. */
    private static final Pattern DUPLICATE_KEY_UPDATE = Pattern.compile("(?i)\\bON\\s+DUPLICATE\\s+(KEY)\\s+UPDATE\\b");

    /** 词法切分:字符串 / 双引号名 / 反引号名 / 行注释 / 块注释 / 标识符。 */
    private static final Pattern TOKEN = Pattern.compile("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`[^`]*`|--[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|[A-Za-z_][A-Za-z_0-9]*");

    /**
     * 把一条仓储 SQL 改写为 MySQL 方言。改写是纯文本层面、幂等的,不解析语法树。
     *
     * <p>顺序不能换,每一步都有理由:
     * <ol>
     *   <li><b>先把字符串与注释换成 {@code @@L0@@} 占位符</b> —— 否则后面几条按词法匹配的替换
     *       会改到字面量内部(例如某行数据里恰好含 "on conflict")。占位符在最后一步原样还原。</li>
     *   <li><b>表名加前缀</b> —— 逻辑名映射到 {@code `el_xxx`},见 {@link #TABLES}。</li>
     *   <li><b>保留字加反引号</b> —— 旧 SQL 里的 {@code database}/{@code rows}/{@code sql}/{@code key}
     *       在 MySQL 是保留字。{@code database(} 是内置函数、{@code ON DUPLICATE KEY UPDATE} 中的
     *       {@code KEY} 是语法关键字，这两处不能当成列名加反引号。</li>
     *   <li><b>{@code datetime('now')} → {@code UTC_TIMESTAMP()}</b> —— 只改参数确实是 {@code 'now'} 的调用,
     *       库里时间统一按 UTC 存(与连接的 {@code time_zone='+00:00'} 一致)。</li>
     *   <li><b>{@code ON CONFLICT(…) DO UPDATE SET} → {@code ON DUPLICATE KEY UPDATE}</b> —— upsert 语法差异。</li>
     *   <li><b>{@code excluded.x} → {@code VALUES(x)}</b> —— upsert 里引用待插入值的方式不同。</li>
     *   <li><b>{@code INSERT OR IGNORE}</b> —— MySQL 无对应语法。目前只有一张表用到了这种「忽略重复」语义,
     *       所以只对 {@code el_tbl_group_tables} 特判为目标表上的 no-op 更新;其他表出现这个写法<b>直接抛错</b>,
     *       而不是悄悄改成普通 INSERT —— 静默改变冲突语义比报错更危险。</li>
     * </ol>
     *
     * <p>注意:预编译语句的<b>参数值不经过这里</b>(它们由 {@code setXxx} 单独设置),
     * 所以不存在把用户数据当 SQL 改写的风险。
     */
    public static String mysql(String sql) {
        Set<Integer> keywordKeys = new HashSet<>();
        Matcher upsert = DUPLICATE_KEY_UPDATE.matcher(sql);
        while (upsert.find()) keywordKeys.add(upsert.start(1));
        // 步骤 1:先摘出字符串与注释,换成占位符,避免后续按词法做的替换改到字面量内部
        List<String> literals = new ArrayList<>();
        Matcher m = TOKEN.matcher(sql);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String token = m.group(), replacement = token;
            if (token.startsWith("'") || token.startsWith("--") || token.startsWith("/*")) {
                // 字符串/注释 → @@L{序号}@@,原文留在 literals 里待最后还原
                replacement = "@@L" + literals.size() + "@@";
                literals.add(token);
            } else {
                // 步骤 2/3:剥掉引号后按「表名」还是「保留字」决定是否加反引号
                String bare = token.replace("`", "").replace("\"", "");
                String lower = bare.toLowerCase(Locale.ROOT);
                // Match the exact unquoted token position, leaving quoted identifiers and literals untouched.
                boolean keyKeyword = token.equalsIgnoreCase("key") && keywordKeys.contains(m.start());
                // 契约: 仓储 SQL 使用无前缀逻辑表名，发送给 MySQL 前必须在这里统一补 el_；新表漏登记会直接报表不存在。
                if (TABLES.contains(lower)) replacement = "`el_" + lower + "`";
                // database( 是函数调用而非列名,不能加反引号;双引号在原写法里表示标识符,统一改成反引号
                else if ((RESERVED.contains(lower) && !keyKeyword && !(lower.equals("database") && sql.substring(m.end()).trim().startsWith("(")))
                        || token.startsWith("\"")) replacement = "`" + bare + "`";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        String result = out.toString();
        // 步骤 4:只把参数确为 'now' 的 datetime() 换成 UTC_TIMESTAMP(),占位符保证不会误伤普通字符串参数
        for (int i = 0; i < literals.size(); i++) {
            if ("'now'".equalsIgnoreCase(literals.get(i)))
                result = result.replaceAll("(?i)datetime\\(\\s*@@L" + i + "@@\\s*\\)", "UTC_TIMESTAMP()");
        }
        // 步骤 5:upsert 语法差异 —— 冲突子句写法不同
        // 契约: ON CONFLICT 的目标键由 MySQL 实际唯一键承担；改写只转换语法，不补建或校验唯一约束。
        result = result.replaceAll("(?i)ON\\s+CONFLICT\\s*\\([^)]*\\)\\s+DO\\s+UPDATE\\s+SET", "ON DUPLICATE KEY UPDATE");
        // 步骤 6:upsert 中引用待插入值的写法 —— excluded.x → VALUES(x)
        result = result.replaceAll("(?i)excluded\\.(`?[A-Za-z_][A-Za-z_0-9]*`?)", "VALUES($1)");
        // 步骤 7:INSERT OR IGNORE 无 MySQL 对应语法,仅放行用到该语义的组表,其余一律报错(不静默改语义)
        if (Pattern.compile("(?i)INSERT\\s+OR\\s+IGNORE").matcher(result).find()) {
            if (!result.contains("`el_tbl_group_tables`")) throw new IllegalArgumentException("Unsupported metadata INSERT OR IGNORE");
            result = result.replaceFirst("(?i)INSERT\\s+OR\\s+IGNORE", "INSERT")
                + " ON DUPLICATE KEY UPDATE group_id=group_id";
        }
        // 最后一步:把占位符按原样还原回字符串与注释
        Matcher placeholders = Pattern.compile("@@L(\\d+)@@").matcher(result);
        StringBuffer restored = new StringBuffer();
        while (placeholders.find()) placeholders.appendReplacement(restored,
            Matcher.quoteReplacement(literals.get(Integer.parseInt(placeholders.group(1)))));
        placeholders.appendTail(restored);
        return restored.toString();
    }
}
