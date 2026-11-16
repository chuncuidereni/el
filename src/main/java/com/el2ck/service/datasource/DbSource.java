/**
 * 【文件职责】本文件定义数据源管理模块的连接/数据源适配组件 DbSource。封装特定数据源或结果集的连接、读取和异常转换语义。
 */

package com.el2ck.service.datasource;

import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.util.CkColumns;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据库型数据源作为「同步源 / 工作流源」的统一 SQL 读取层(mysql / pgsql / clickhouse)。
 *
 * 数据源配置(cfg)含 host/port/database/username/password/secure 等(与数据源管理一致),
 * 目标对象 object 为表名,可带库前缀(如 default.tbl / myschema.tbl)。
 * 仅做行级读取(describe 列 / 分页 / 整表导出 CSV),不建任何中间状态。
 *
 * JDBC 同步源由本类分页读取；ClickHouse/MongoDB 同步源使用独立游标，SFTP 先下载为本地文件。
 * 单元格统一转成字符串回传:null → ""、时间 → yyyy-MM-dd[ HH:mm:ss[.fffffffff]]、
 * 布尔 → true/false、数字/其余 → 文本(与 DuckDB 文件路径逐行语义一致)。
 */
public final class DbSource {

    /**
     * 打开同步专用只读连接。0 表示语句不限时，不再折算为 30 分钟。
     * socketTimeout 仅限制网络长期无响应；不限时任务仍保留 1805 秒的读空闲保护。
     */
    public static Connection openSegmentConnection(String engine, Map<String,Object> cfg, int timeoutSeconds) throws java.sql.SQLException {
        if (!"mysql".equals(engine) && !"postgres".equals(engine)) throw new IllegalArgumentException("不支持的 JDBC 源类型");
        int seconds = Math.max(0, timeoutSeconds);
        long socketSeconds = seconds == 0 ? 1805L : (long)seconds + 5L;
        String url = "mysql".equals(engine) ? DataSourceService.mysqlUrl(cfg) : DataSourceService.pgUrl(cfg);
        java.util.Properties props = new java.util.Properties();
        // 移除 URL 中旧超时，避免 URL 优先级覆盖本次显式设置。
        url = url.replaceAll("([?&])socketTimeout=[^&]*&?", "$1").replace("?&", "?");
        props.setProperty("socketTimeout", String.valueOf("mysql".equals(engine) ? socketSeconds * 1000L : socketSeconds));
        Connection connection = DriverManager.getConnection(url, props);
        try { connection.setReadOnly(true); return connection; }
        catch (java.sql.SQLException | RuntimeException e) { try { connection.close(); } catch (java.sql.SQLException close) { e.addSuppressed(close); } throw e; }
    }

    /** 有界 SQL 由分段读取器生成；本方法负责超时、取消检查和无损的单元格转换。 */
    public static List<List<String>> readSegment(Connection connection, String sql, int timeoutSeconds) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已中断");
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(0, timeoutSeconds));
            try (ResultSet result = statement.executeQuery(sql)) {
                List<List<String>> rows = new ArrayList<>();
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已中断");
                    List<String> row = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) row.add(render(result.getObject(i)));
                    rows.add(row);
                }
                return rows;
            }
        } catch (java.sql.SQLException e) { throw new IllegalStateException("读取源分段失败", e); }
    }

    /** 稳定键必须是单列主键；复合水位必须不可空，且有以水位、主键为前缀的索引。只查元数据。 */
    public static void validateSegmentKey(String engine, Map<String,Object> cfg, String object, String key, String watermark) {
        qualified(engine, cfg, object);
        quoteIdent(engine, key); quoteIdent(engine, watermark);
        try (Connection connection = openSegmentConnection(engine, cfg, 30)) {
            if (!key.equals(singlePrimaryKey(connection, engine, object)))
                throw new IllegalArgumentException("分段键必须是源表的单列主键");
            if (key.equals(watermark)) return;
            requireNonNullWatermark(connection, engine, cfg, object, watermark);
            String[] parts = object.split("\\.");
            String catalog = "mysql".equals(engine) && parts.length == 2 ? parts[0] : connection.getCatalog();
            String schema = "postgres".equals(engine) ? (parts.length == 2 ? parts[0] : "public") : null;
            Map<String,java.util.SortedMap<Integer,String>> indexes = new java.util.HashMap<>();
            try (ResultSet rs = connection.getMetaData().getIndexInfo(catalog, schema, parts[parts.length-1], false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    if (name != null) indexes.computeIfAbsent(name, ignored -> new java.util.TreeMap<>())
                            .put(rs.getInt("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"));
                }
            }
            for (java.util.SortedMap<Integer,String> index : indexes.values())
                if (watermark.equals(index.get(1)) && key.equals(index.get(2))) return;
            throw new IllegalArgumentException("源表需要以增量水位、分段主键为前两列的索引");
        } catch (java.sql.SQLException e) { throw new IllegalStateException("校验源表分段索引失败", e); }
    }

    /**
     * WHERE 水位比较会过滤 NULL，使读取器后续的逐行校验永远看不到这些行。
     * 因此在分页之前验证数据库的 NOT NULL 约束，不以目标映射的 nullable 标记替代源约束，
     * 也不扫描大表。驱动无法确认约束时同样停止，避免静默漏行。
     */
    private static void requireNonNullWatermark(Connection connection, String engine, Map<String,Object> cfg,
                                               String object, String watermark) throws java.sql.SQLException {
        String sql = "SELECT " + quoteIdent(engine, watermark) + " FROM " + qualified(engine, cfg, object) + " LIMIT 0";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery(sql)) {
                int nullable = rows.getMetaData().isNullable(1);
                if (nullable == ResultSetMetaData.columnNullable)
                    throw new IllegalArgumentException("源增量水位列「" + watermark
                            + "」允许 NULL，分段同步可能漏行；请补齐空值并将源列设为 NOT NULL，或选择非空水位列");
                if (nullable != ResultSetMetaData.columnNoNulls)
                    throw new IllegalArgumentException("无法确认源增量水位列「" + watermark + "」的非空约束，不能启用分段增量");
            }
        }
    }

    /** 时间值转字符串的统一格式:yyyy-MM-dd HH:mm:ss。 */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 单段标识符合法性:字母/下划线开头,仅含字母数字下划线(防注入)。 */
    private static final Pattern SEG = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    // 工具类私有构造器:禁止实例化
    /**
     * 工具类私有构造器，阻止外部创建实例。
     */
    private DbSource() {
    }

    // =====================================================================
    // 类型 / 引擎识别
    // =====================================================================

    /** 是否为可作为同步源读取的 SQL 型远程源。 */
    public static boolean isSqlSource(String t) {
        // 仅 clickhouse / mysql / postgres(pgsql) 三种视为 SQL 型远程源
        return DataSource.CLICKHOUSE.equals(t) || DataSource.MYSQL.equals(t) || DataSource.PGSQL.equals(t);
    }

    /** 数据源类型 → SQL 引擎标识(clickhouse / mysql / postgres)。 */
    public static String engineOf(String t) {
        // clickhouse 类型
        if (DataSource.CLICKHOUSE.equals(t)) {
            return "clickhouse";
        }
        // mysql 类型
        if (DataSource.MYSQL.equals(t)) {
            return "mysql";
        }
        // postgres(pgsql) 类型
        if (DataSource.PGSQL.equals(t)) {
            return "postgres";
        }
        // 其余类型不受本层支持
        throw new IllegalArgumentException("不支持的数据库数据源类型 \"" + t + "\"");
    }

    // =====================================================================
    // 源表结构 / 分页读取
    // =====================================================================

    /** 源表全部列(按表内顺序),name 供映射校验、type 供展示。 */
    public static List<Column> describe(String engine, Map<String, Object> cfg, String object) {
        // 列信息结果列表
        List<Column> out = new ArrayList<>();
        // clickhouse 走 native 驱动
        if ("clickhouse".equals(engine)) {
            // 组装 clickhouse 读取配置
            CkConfig ck = ckConfig(cfg);
            // 打开 CH 客户端执行 DESCRIBE,用完即关
            try (CkClient c = CkClient.open(ck)) {
                // 对带库前缀的全限定表名执行 DESCRIBE TABLE
                QueryResult res = c.query("DESCRIBE TABLE " + qualified("clickhouse", cfg, object));
                // 逐行取列信息
                for (List<Object> row : res.getRows()) {
                    // 每行封装一列
                    Column col = new Column();
                    // 首列为列名(null 归空串)
                    col.setName(row.isEmpty() ? "" : cell(row.get(0)));
                    // 次列为类型
                    col.setType(normalizeSourceType(row.size() > 1 ? cell(row.get(1)) : ""));
                    // 加入结果
                    out.add(col);
                }
            } catch (RuntimeException e) {
                // 保留驱动根因，供同步执行区分瞬时网络故障与 SQL/鉴权失败。
                throw new IllegalStateException("读取源表结构失败(clickhouse): " + message(e), e);
            }
            return out;
        }
        // mysql/pgsql:拼 JDBC url(mysql 或 postgres 二选一)
        String url = "mysql".equals(engine) ? DataSourceService.mysqlUrl(cfg) : DataSourceService.pgUrl(cfg);
        // 得到带引号/库前缀的全限定表名
        String q = qualified(engine, cfg, object);
        // 建连 + 建 Statement + 执行 LIMIT 0 空查询以取元数据
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + q + " LIMIT 0")) {
            // 从结果集元数据读列定义
            ResultSetMetaData md = rs.getMetaData();
            // 列总数
            int n = md.getColumnCount();
            // 逐列读取列名与类型
            for (int i = 1; i <= n; i++) {
                // 每列封装
                Column col = new Column();
                // 列名用标签(别名)而非物理列名
                col.setName(md.getColumnLabel(i));
                // 列类型为驱动给出的类型名
                col.setType(normalizeSourceType(md.getColumnTypeName(i), md, i));
                // 加入结果
                out.add(col);
            }
        } catch (Exception e) {
            // SQLState/连接异常不能只转成文案，否则自动续传无法识别可重试故障。
            throw new IllegalStateException("读取源表结构失败(" + engine + "): " + message(e), e);
        }
        return out;
    }

    public static final class IncrementalInspection {
        public String primaryKey;
        public String type;
        public boolean nullable;
        public Long count;
    }

    /** 诊断专用，只做元数据及可选计数查询，不执行同步或写入。 */
    public static IncrementalInspection inspectIncremental(String engine, Map<String, Object> cfg,
                                                           String object, String column, boolean count) {
        if (!"mysql".equals(engine) && !"postgres".equals(engine))
            throw new IllegalArgumentException("诊断源类型暂不支持");
        IncrementalInspection info = new IncrementalInspection();
        String table = qualified(engine, cfg, object);
        String url = "mysql".equals(engine) ? DataSourceService.mysqlUrl(cfg) : DataSourceService.pgUrl(cfg);
        if ("postgres".equals(engine)) url += "&socketTimeout=30";
        try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
            conn.setReadOnly(true);
            st.setQueryTimeout(30);
            info.primaryKey = singlePrimaryKey(conn, engine, object);
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (column.equals(md.getColumnLabel(i))) {
                        info.type = normalizeSourceType(md.getColumnTypeName(i), md, i);
                        info.nullable = md.isNullable(i) != ResultSetMetaData.columnNoNulls;
                    }
                }
            }
            if (count) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                    if (rs.next()) info.count = rs.getLong(1);
                }
            }
            return info;
        } catch (Exception e) {
            // 不把包含 JDBC URL/口令的驱动异常正文写到诊断输出。
            throw new IllegalStateException("源表检查失败，请核对连接、权限和表结构");
        }
    }

    /** JDBC 源使用单列主键做键集分页；无主键或复合主键返回空，由同步层拒绝运行。 */
    public static String singlePrimaryKey(String engine, Map<String, Object> cfg, String object) {
        if (!"mysql".equals(engine) && !"postgres".equals(engine)) return null;
        String url = "mysql".equals(engine) ? DataSourceService.mysqlUrl(cfg) : DataSourceService.pgUrl(cfg);
        try (Connection conn = DriverManager.getConnection(url)) {
            return singlePrimaryKey(conn, engine, object);
        } catch (Exception e) {
            throw new IllegalStateException("读取源表主键失败: " + message(e), e);
        }
    }

    /**
     * 通过 JDBC 元数据读取源表的单列主键；复合主键、无主键或非法表名返回 null。
     *
     * @param conn 已打开的 MySQL 或 PostgreSQL 连接
     * @param engine SQL 引擎标识，只处理 mysql/postgres
     * @param object 可带库或 schema 前缀的源表名
     * @return 唯一主键列名；不满足单列主键条件时为 null
     * @throws java.sql.SQLException 读取表元数据失败时抛出
     */
    private static String singlePrimaryKey(Connection conn, String engine, String object) throws java.sql.SQLException {
        String[] parts = object.trim().split("\\.");
        if (parts.length > 2 || parts.length == 0) throw new IllegalArgumentException("非法源表名: " + object);
        for (String part : parts) {
            if (!SEG.matcher(part).matches()) throw new IllegalArgumentException("非法源表名: " + object);
        }
        String table = parts[parts.length - 1];
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = "mysql".equals(engine) && parts.length == 2 ? parts[0] : conn.getCatalog();
        String schema = "postgres".equals(engine) ? (parts.length == 2 ? parts[0] : "public") : null;
        String key = null;
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                if (key != null || rs.getShort("KEY_SEQ") != 1) return null;
                key = rs.getString("COLUMN_NAME");
            }
        }
        return key;
    }

    /**
     * 分页读取源表(SELECT *),可带 where(增量过滤,标识符已按引擎引用)。
     * 每页独立建连后释放,避免长事务占用连接。
     */
    public static List<List<String>> page(String engine, Map<String, Object> cfg, String object,
                                          String where, int limit, int offset) {
        // 未传列结构:CH 分支需临时 DESCRIBE 一次才能拿到列类型来决定布尔投影(JDBC 引擎不需要)
        return page(engine, cfg, object, where, limit, offset,
                "clickhouse".equals(engine) ? describe(engine, cfg, object) : null);
    }

    /** 分页读取源表;columns 为已探明的列结构(CH 分支据此把布尔列转 toUInt8 投影,非 CH 引擎仅用于列序展示)。 */
    public static List<List<String>> page(String engine, Map<String, Object> cfg, String object,
                                          String where, int limit, int offset, List<Column> columns) {
        // 不带排序键:OFFSET 分页跨页顺序由源库决定,不稳定;需要稳定分页请用带 orderBy 的重载
        return page(engine, cfg, object, where, limit, offset, columns, null);
    }

    /** 源表行数(供同步前的 mdn 校验决定全量还是抽样);where 为调用方已按引擎引用好的表达式。 */
    public static long count(String engine, Map<String, Object> cfg, String object, String where) {
        String sql = "SELECT count(*) FROM " + qualified(engine, cfg, object)
                + (where == null || where.trim().isEmpty() ? "" : " WHERE " + where);
        if ("clickhouse".equals(engine)) {
            try (CkClient c = CkClient.open(ckConfig(cfg))) {
                QueryResult res = c.query(sql);
                if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) return -1L;
                Object v = res.getRows().get(0).get(0);
                return v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v));
            } catch (RuntimeException e) {
                throw new IllegalStateException("读取源表行数失败(clickhouse): " + message(e));
            }
        }
        String url = "mysql".equals(engine) ? DataSourceService.mysqlUrl(cfg) : DataSourceService.pgUrl(cfg);
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Exception e) {
            throw new IllegalStateException("读取源表行数失败(" + engine + "): " + message(e));
        }
    }

    /** 分页读取源表；orderBy 用于增量同步时保证 OFFSET 分页稳定。 */
    public static List<List<String>> page(String engine, Map<String, Object> cfg, String object,
                                          String where, int limit, int offset, List<Column> columns,
                                          String orderBy) {        // 拼 SELECT * FROM 全限定表名
        String projection = "*";
        // 仅 CH 且已探明列结构时才改写投影(布尔列包 toUInt8);JDBC 引擎的取值语义由 render 统一处理
        if ("clickhouse".equals(engine) && columns != null && !columns.isEmpty()) {
            projection = clickhouseProjection(columns);
        }
        StringBuilder sql = new StringBuilder("SELECT " + projection + " FROM " + qualified(engine, cfg, object));
        // 有增量过滤条件时追加 WHERE
        if (where != null && !where.trim().isEmpty()) {
            // where 为调用方已按引擎引用好的表达式,原样拼接
            sql.append(" WHERE ").append(where);
        }
        if (orderBy != null && !orderBy.trim().isEmpty()) {
            sql.append(" ORDER BY ").append(quoteIdent(engine, orderBy.trim()));
        }
        // 指定 limit 时追加 LIMIT
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        // 指定 offset 时追加 OFFSET
        if (offset > 0) {
            sql.append(" OFFSET ").append(offset);
        }
        // 行结果集(每行均为字符串列表)
        List<List<String>> out = new ArrayList<>();
        // clickhouse 走 native 驱动
        if ("clickhouse".equals(engine)) {
            // 组装 CH 读取配置
            CkConfig ck = ckConfig(cfg);
            // 打开 CH 客户端执行分页 SQL,用完即关
            try (CkClient c = CkClient.open(ck)) {
                // 执行拼好的分页 SQL
                QueryResult res = c.query(sql.toString());
                // 逐行取数据
                for (List<Object> row : res.getRows()) {
                    // 行数据先建字符串容器
                    List<String> r = new ArrayList<>(row.size());
                    // 单元格统一转字符串
                    for (Object v : row) {
                        r.add(cell(v));
                    }
                    // 加入结果
                    out.add(r);
                }
            } catch (RuntimeException e) {
                // CH 读取失败统一抛错
                throw new IllegalStateException("分页读取源表失败(clickhouse): " + message(e));
            }
            return out;
        }
        // mysql/pgsql:拼 JDBC url
        String url = "mysql".equals(engine) ? DataSourceService.mysqlUrl(cfg) : DataSourceService.pgUrl(cfg);
        // 建连并执行分页 SQL
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql.toString())) {
            // 从结果集元数据读列数
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            // 遍历每一行
            while (rs.next()) {
                // 行数据容器
                List<String> r = new ArrayList<>(n);
                // 逐列把 JDBC 原值转字符串
                for (int i = 1; i <= n; i++) {
                    r.add(render(rs.getObject(i)));
                }
                // 加入结果
                out.add(r);
            }
        } catch (Exception e) {
            // JDBC 读取失败统一抛错
            throw new IllegalStateException("分页读取源表失败(" + engine + "): " + message(e));
        }
        return out;
    }

    /**
     * 组装 CH 侧 SELECT 列投影:按列类型改写(见 {@link CkColumns})——布尔列包一层
     * {@code toUInt8(列) AS 列}(避开 Bool→文本读写不一致),聚合状态列
     * {@code SimpleAggregateFunction(...)} 转成 {@code toString(列) AS 列}(驱动不认该类型,
     * 不转的话读仲裁聚合表会直接报 Unknown data type),其余列原样引用。
     */
    static String clickhouseProjection(List<Column> columns) {
        List<String> expressions = new ArrayList<>(columns.size());
        for (Column column : columns) {
            String name = column == null ? "" : column.getName();
            String type = column == null ? "" : column.getType();
            expressions.add(CkColumns.selectExpr(quoteIdent("clickhouse", name), type));
        }
        return String.join(", ", expressions);
    }

    /**
     * 源表结构统一输出为目标映射可直接使用的类型。
     * 数据库驱动对布尔类型的名称并不一致，不能把 BOOLEAN/BIT/Bool 直接交给
     * ClickHouse 建表或 batch 写入，否则会在后续阶段才暴露 Unknown data type: Bool。
     */
    private static String normalizeSourceType(String type) {
        String v = type == null ? "" : type.trim();
        if ("bool".equalsIgnoreCase(v) || "boolean".equalsIgnoreCase(v)
                || "bit(1)".equalsIgnoreCase(v)) {
            return "UInt8";
        }
        return v;
    }

    static String normalizeSourceType(String type, ResultSetMetaData md, int index) {
        String v = normalizeSourceType(type);
        try {
            if (com.el2ck.util.SyncTime.isTimestamp(v) && !v.contains("(")) {
                int scale = md.getScale(index);
                if (scale >= 0 && scale <= 9) return v + "(" + scale + ")";
            }
            if (v.equalsIgnoreCase("decimal") || v.equalsIgnoreCase("numeric")) {
                int precision = md.getPrecision(index), scale = md.getScale(index);
                if (precision > 0 && precision <= 76 && scale >= 0 && scale <= precision)
                    return v + "(" + precision + ", " + scale + ")";
            }
        } catch (java.sql.SQLException ignored) { }
        // MySQL 常把 BOOLEAN 定义为 TINYINT(1)，JDBC 只返回 TINYINT；按一位布尔列归一化。
        if ("tinyint".equalsIgnoreCase(v)) {
            try {
                if (md.getPrecision(index) == 1 || md.getColumnDisplaySize(index) == 1) {
                    return "UInt8";
                }
            } catch (Exception ignored) {
                // 驱动元数据不完整时保留原类型，由后续映射校验处理。
            }
        }
        // JDBC 的 int8/float4 等名称是 SQL 别名，不能按 ClickHouse 的位宽名称推断。
        if ("int2".equalsIgnoreCase(v)) return "Int16";
        if ("int4".equalsIgnoreCase(v)) return "Int32";
        if ("int8".equalsIgnoreCase(v)) return "Int64";
        if ("float4".equalsIgnoreCase(v)) return "Float32";
        if ("float8".equalsIgnoreCase(v)) return "Float64";
        try {
            String prefix = v.toLowerCase(java.util.Locale.ROOT).contains("unsigned") ? "UInt" : "Int";
            switch (md.getColumnType(index)) {
                case java.sql.Types.TINYINT: return prefix + "8";
                case java.sql.Types.SMALLINT: return prefix + "16";
                case java.sql.Types.INTEGER: return prefix + "32";
                case java.sql.Types.BIGINT: return prefix + "64";
                case java.sql.Types.REAL: return "Float32";
                case java.sql.Types.DOUBLE: return "Float64";
                default: break;
            }
        } catch (java.sql.SQLException ignored) { }
        return v;
    }

    /** 整表流式导出源表为 CSV(含表头)到 writer;按批次分页拉取、攒批写出;返回导出的数据行数。 */
    public static long writeCsv(String engine, Map<String, Object> cfg, String object, Writer w) {
        // 每批 1 万行:在往返次数与单批内存之间取折中(整批会先拼进 StringBuilder 再写出)
        final int batch = 10000;
        // 先取表结构(列顺序与列名)
        List<Column> cols = describe(engine, cfg, object);
        // 表头(原始列名)。
        try {
            // 拼表头行的 StringBuilder
            StringBuilder head = new StringBuilder();
            // 遍历所有列拼逗号分隔的表头
            for (int i = 0; i < cols.size(); i++) {
                // 非首列前补逗号
                if (i > 0) {
                    head.append(',');
                }
                // 按 CSV 规则写入列名(含逗号/引号时加引号)
                appendCsvField(head, cols.get(i).getName());
            }
            // 表头行以换行收尾
            head.append('\n');
            // 表头写入 writer
            w.write(head.toString());
        } catch (IOException e) {
            // 写出表头失败统一抛错
            throw new IllegalStateException("写出源表 CSV 表头失败: " + e.getMessage(), e);
        }
        // 已导出的数据行数
        long count = 0;
        // 起始分页偏移
        int offset = 0;
        // 循环分页导出直到取不到更多行
        while (true) {
            // 按批次从当前偏移读一页数据
            List<List<String>> rows = page(engine, cfg, object, null, batch, offset, cols);
            // 本页为空表示已读完
            if (rows.isEmpty()) {
                break;
            }
            // 攒一批拼成 CSV 文本再一次性写出,减少 IO 次数
            StringBuilder sb = new StringBuilder();
            try {
                // 遍历本页每行拼 CSV
                for (List<String> row : rows) {
                    // 遍历本行每列
                    for (int i = 0; i < row.size(); i++) {
                        // 非首列前补逗号
                        if (i > 0) {
                            sb.append(',');
                        }
                        // 按 CSV 规则写入单元格
                        appendCsvField(sb, row.get(i));
                    }
                    // 行尾换行
                    sb.append('\n');
                }
                // 本批拼好的文本写入 writer
                w.write(sb.toString());
            } catch (IOException e) {
                // 写出失败统一抛错
                throw new IllegalStateException("写出源表 CSV 失败: " + e.getMessage(), e);
            }
            // 累计已导出行数
            count += rows.size();
            // 本页不足一批表示已到末尾
            if (rows.size() < batch) {
                break;
            }
            // 偏移前进一个批次
            offset += batch;
        }
        return count;
    }

    // =====================================================================
    // 标识符引用 / 值渲染
    // =====================================================================

    /** 单段标识符按引擎加引号(mysql/clickhouse 反引号,postgres 双引号)。 */
    public static String quoteIdent(String engine, String name) {
        // 非法标识符直接拒绝(防注入)
        if (!SEG.matcher(name).matches()) {
            throw new IllegalArgumentException("非法标识符 \"" + name + "\"");
        }
        // postgres 用双引号,内部双引号翻倍转义
        if ("postgres".equals(engine)) {
            return "\"" + name.replace("\"", "\"\"") + "\"";
        }
        // 其余引擎用反引号,内部反引号翻倍转义
        return "`" + name.replace("`", "``") + "`";
    }

    /** 源表名(可带点分段)解析为带引号全名;单段自动补库/模式前缀。 */
    public static String qualified(String engine, Map<String, Object> cfg, String object) {
        // 去首尾空白;空对象名按空串处理
        String o = object == null ? "" : object.trim();
        // 按点拆成多段(库.表 / 模式.表 / 纯表)
        String[] segs = o.split("\\.");
        // 校验通过的段列表
        List<String> parts = new ArrayList<>(segs.length);
        // 逐段去空白并校验
        for (String s : segs) {
            // 去该段首尾空白
            s = s.trim();
            // 段必须满足标识符白名单
            if (!SEG.matcher(s).matches()) {
                throw new IllegalArgumentException("非法表名 \"" + object + "\"");
            }
            // 加入校验通过的段
            parts.add(s);
        }
        // 拆完为空说明对象名为空
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("源表名不能为空");
        }
        // 单段表名需要自动补前缀
        if (parts.size() == 1) {
            // 记下唯一的表名
            String only = parts.get(0);
            // postgres 缺省补 public 模式
            if ("postgres".equals(engine)) {
                // 单段:默认 public 模式。
                parts.add(0, "public");
                // 补前缀后 parts 已是 [public, table],不会再走下面的“单表兜底”
            } else {
                // 其余引擎读取配置中的数据库名作为前缀
                String db = cfg.get("database") == null ? "" : String.valueOf(cfg.get("database")).trim();
                // 库名合法才补前缀
                if (!db.isEmpty() && SEG.matcher(db).matches()) {
                    parts.add(0, db);
                }
            }
            // 前缀不可补(无库名)时退化为仅引用该表名
            if (parts.size() == 1) {
                return quoteIdent(engine, only);
            }
        }
        // 拼接多段全限定名(段间用点连接,各段加引号)
        StringBuilder sb = new StringBuilder();
        // 逐段拼接
        for (int i = 0; i < parts.size(); i++) {
            // 非首段前补点
            if (i > 0) {
                sb.append('.');
            }
            // 该段加引号
            sb.append(quoteIdent(engine, parts.get(i)));
        }
        return sb.toString();
    }

    /** ClickHouse 读取配置(读取场合用较长查询超时;合并规则见 {@link DataSourceService#fromCkMap})。 */
    public static CkConfig ckConfig(Map<String, Object> cfg) {
        return DataSourceService.fromCkMap(cfg, "default", 60000L);
    }

    /** CH(native 驱动已归一化)单元格 → 字符串;布尔→true/false,null→""。 */
    private static String cell(Object v) {
        // null 归空串
        if (v == null) {
            return "";
        }
        // 布尔统一转 true/false 英文串
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        // 其余直接用 toString
        return String.valueOf(v);
    }

    /** JDBC 原始值 → 字符串(时间统一 yyyy-MM-dd[ HH:mm:ss],null→"")。 */
    private static String render(Object v) {
        // null 归空串
        if (v == null) {
            return "";
        }
        // 布尔统一转 true/false
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        // java.sql.Timestamp 统一格式化为日期时间
        if (v instanceof Timestamp) {
            return com.el2ck.util.SyncTime.format(((Timestamp) v).toLocalDateTime());
        }
        // LocalDateTime 统一格式化
        if (v instanceof LocalDateTime) {
            return com.el2ck.util.SyncTime.format((LocalDateTime) v);
        }
        // LocalDate 只含日期,直接输出
        if (v instanceof LocalDate) {
            return ((LocalDate) v).toString();
        }
        // java.sql.Date 直接输出
        if (v instanceof java.sql.Date) {
            return ((java.sql.Date) v).toString();
        }
        // java.util.Date 先转系统时区的 LocalDateTime 再格式化
        if (v instanceof java.util.Date) {
            return TS_FMT.format(((java.util.Date) v).toInstant()
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        }
        // 其余直接用 toString
        return String.valueOf(v);
    }

    /** RFC4180 风格 CSV 字段写入(含逗号/引号/换行时加引号)。 */
    private static void appendCsvField(StringBuilder sb, String v) {
        // null 归空串
        String s = v == null ? "" : v;
        // 命中逗号/引号/换行任一字符即需加引号
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        // 无需引号时原样输出
        if (!needQuote) {
            sb.append(s);
            return;
        }
        // 加引号包裹,内部引号翻倍转义
        sb.append('"').append(s.replace("\"", "\"\"")).append('"');
    }

    /** 剥壳取最深层非空异常消息(供错误提示)。 */
    private static String message(Exception e) {
        // 从当前异常开始向上溯源
        Throwable t = e;
        // 逐层下钻到有非空消息的最内层 cause
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            t = t.getCause();
        }
        // 取到该层消息
        String m = t.getMessage();
        // 仍为空则退回异常自身的 toString
        return m == null || m.isEmpty() ? String.valueOf(e) : m;
    }
}
