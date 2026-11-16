package com.el2ck.ckpool;

/**
 * 【文件职责】ClickHouse 查询、写入和 DDL 的底层客户端，负责 native/HTTP 协议选择、端口兼容、认证、超时和结果解析。
 */

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 一条 ClickHouse 连接的封装,对应 Go ckpool.Client。
 *
 * 按配置选择 housepower native/TCP 或官方 HTTP JDBC 驱动。
 * 由于 java.sql.Connection 非线程安全,凡触达底层连接的公开方法(query/exec/ping/
 * insertBatch/close)均加 synchronized,保证同一条连接上的操作串行;仅读 volatile 字段的
 * 轻量方法(expired/lastPingMs/markPing)不加锁。
 */
public class CkClient implements AutoCloseable {
    /**
     * 单条查询已经进入执行阶段后失败时使用的异常类型。
     *
     * <p>{@link #open(CkConfig)} 的建连、鉴权失败和连接失效仍使用普通
     * {@link IllegalStateException}。保留这个明确类型后，上层可以只对单条查询失败
     * 做兼容降级，而不会把连接级故障误判成系统表的权限或版本问题。
     */
    public static class QueryExecutionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public QueryExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    // 协议契约: 直连 native=9000、HTTP=8123；代理常映射 9001->9000、8132->8123，代码按连接协议而非端口数字猜协议。
    // 流式查询必须读到 EOF 或完整消费；断点批量最多 10000 行/约 1 MiB，按完整行拆分，分块全部成功后才可提交断点。

    /** 日期时间统一输出格式(与 Go "2006-01-02 15:04:05" 等价,不带时区后缀)。 */
    private static final String DT_FMT = "yyyy-MM-dd HH:mm:ss";

    /** 底层 JDBC 连接,同一条连接必须串行使用。 */
    private final Connection conn;
    /** 语句级查询超时(毫秒),构造时若 <=0 兜底为 60 秒。 */
    private final long queryTimeoutMs;
    /** 连接级最大存活时长(毫秒),供连接池在取用前判断是否回收。 */
    private final long maxLifetimeMs;
    /** 连接建立时刻(毫秒时间戳),用于计算连接已存活时长。 */
    private final long openedAtMs;
    /** 安全连接时随连接建立的本地 TLS 隧道(普通连接为 null);未显式 close,靠 JDBC 断开触发的 EOF 由隧道线程收尾。 */
    private final NativeTlsTunnel tlsTunnel;

    /** 上次 Ping 成功的时刻(毫秒时间戳),0 表示从未 Ping 过。Manager 据此做 TTL 判断。 */
    private volatile long lastPingMs = 0;

    /**
     * 本连接是否已被作废:一次失败污染了 native 协议流,这上面不能再执行任何语句。
     *
     * <p>为什么必须有它 —— housepower 驱动失败时会<b>留下没读完的响应</b>:结果集建立之后再出错
     * (典型是解析结果元数据时遇到不认识的类型,如 {@code SimpleAggregateFunction}),客户端与
     * 服务端的字节流就错位了;此后同一条连接上<b>任何</b>查询都会读到错位的字节,报出与事实
     * 无关的错(真机实测:后续查询连环报 {@code Unknown compression magic code: <随机数>},
     * 而真正的原因早已过去)。连接池会把这条连接一直复用下去,于是一次失败连累后面所有请求 ——
     * 所以失败的当下就要把它废掉,由池在下次取用时重建。
     */
    private volatile boolean invalidated;

    /**
     * 包住已连通的 JDBC 连接与连接级参数,正常一律由 {@link #open} 调用。
     *
     * <p>包私有而非 private:作废语义与「作废后不再往这条连接上写一个字节」这条不变式,
     * 只有注入一条假的 JDBC 连接才测得到(见 CkClientInvalidationTest)。与项目里把
     * {@code buildGroupExpr} 这类函数降为包私有直接单测是同一套理由。
     */
    CkClient(Connection conn, long queryTimeoutMs, long maxLifetimeMs, NativeTlsTunnel tlsTunnel) {
        // 保存底层 JDBC 连接。
        this.conn = conn;
        // queryTimeoutMs 为 0/负时兜底为 60 秒,保证语句超时始终有效。
        this.queryTimeoutMs = queryTimeoutMs > 0 ? queryTimeoutMs : 60_000L;
        // maxLifetimeMs 为 0/负时兜底为 30 分钟。
        this.maxLifetimeMs = maxLifetimeMs > 0 ? maxLifetimeMs : 30L * 60_000L;
        // 记录连接建立的时刻,供 expired() 做存活期判断。
        this.openedAtMs = System.currentTimeMillis();
        // 保存随连接创建的 TLS 隧道引用。
        this.tlsTunnel = tlsTunnel;
    }

    /**
     * 建立并校验一条 ClickHouse 连接。
     * 连接建立后按参数执行 session 级 SET(max_result_rows=10000 等),随后 Ping 一次。
     *
     * @return 已连通的客户端;失败时抛 RuntimeException(消息含 Chinese 提示,由上层映射 502)。
     */
    public static CkClient open(CkConfig cfg) {
        CkConfig.requireCompatibleProtocolPort(cfg.getProtocol(), cfg.getPort());
        try {
            return openProtocol(cfg);
        } catch (IllegalStateException first) {
            // 四个已知入口必须按「协议 + 端口」成对配置，不能靠握手失败猜协议。
            // 其它自定义转发端口仅在明确的协议握手错误时允许换驱动探测一次；
            // TLS、鉴权、业务 SQL 和写入失败均不在此重试，避免降级与重复写入。
            if (cfg.isSecure() || cfg.getPort() == 8123 || cfg.getPort() == 8132
                    || cfg.getPort() == 9000 || cfg.getPort() == 9001
                    || !isProtocolHandshakeFailure(first)) throw first;
            CkConfig alternative = cfg.copy();
            alternative.setProtocol(CkConfig.PROTOCOL_HTTP.equals(CkConfig.normalizeProtocol(cfg.getProtocol()))
                    ? CkConfig.PROTOCOL_NATIVE : CkConfig.PROTOCOL_HTTP);
            try {
                return openProtocol(alternative);
            } catch (IllegalStateException second) {
                IllegalStateException failure = new IllegalStateException("无法连接 " + cfg.addr()
                        + "，已尝试 " + CkConfig.normalizeProtocol(cfg.getProtocol()) + " 和 "
                        + alternative.getProtocol() + " 协议。首次: " + message(first)
                        + "；再次: " + message(second), second);
                failure.addSuppressed(first);
                throw failure;
            }
        }
    }

    private static boolean isProtocolHandshakeFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("authentication") || text.contains("access denied")
                    || text.contains("password is incorrect")) return false;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (cause instanceof java.io.EOFException || text.contains("unexpected end of file")
                    || text.contains("unexpected end of stream")
                    || text.contains("response that is not recognized")
                    || text.contains("unexpected packet")
                    || text.contains("is for clickhouse-client")) return true;
        }
        return false;
    }

    private static CkClient openProtocol(CkConfig cfg) {
        // 1. 解析超时默认值(与 Go Open 一致)。
        long dialTimeout = cfg.getDialTimeoutMs() > 0 ? cfg.getDialTimeoutMs() : 5000L;
        long connLifetime = cfg.getConnMaxLifetimeMs() > 0 ? cfg.getConnMaxLifetimeMs() : 30L * 60_000L;
        long queryTimeout = cfg.getQueryTimeoutMs() > 0 ? cfg.getQueryTimeoutMs() : 60_000L;

        // 2. 合并「内置兜底 + 用户自定义」的 session 参数(有序:内置先放,用户覆盖在后)。
        //    两种协议的下发方式不同:native 在连上之后逐条 SET;http 必须随请求下发(见第 5 步)。
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
        // 内置兜底:单次查询最多返回 10000 行。
        merged.put("max_result_rows", 10000);
        // 内置兜底:超出上限即中断,而非报错。
        merged.put("result_overflow_mode", "break");
        if (cfg.getSettings() != null) {
            merged.putAll(cfg.getSettings());
        }

        // 3. 按协议决定驱动与 URL 形态。协议只影响「怎么连」,不影响后续所有语句的执行方式。
        boolean http = CkConfig.PROTOCOL_HTTP.equals(CkConfig.normalizeProtocol(cfg.getProtocol()));

        Properties props = new Properties();
        if (cfg.getUsername() != null) {
            // 仅当配置了用户名才写入 user 属性。
            props.setProperty("user", cfg.getUsername());
        }
        if (cfg.getPassword() != null) {
            // 仅当配置了密码才写入 password 属性。
            props.setProperty("password", cfg.getPassword());
        }

        NativeTlsTunnel tlsTunnel = null;
        String connectHost = cfg.getHost();
        int connectPort = cfg.getPort();
        String driverClass;
        String url;

        if (http) {
            // ---- HTTP 接口(官方 com.clickhouse 驱动) ----
            driverClass = "com.clickhouse.jdbc.ClickHouseDriver";
            // 官方驱动的超时参数:mills。多余属性驱动会忽略,故与 native 分支各设各的。
            props.setProperty("socket_timeout", String.valueOf(queryTimeout));
            props.setProperty("connection_timeout", String.valueOf(dialTimeout));
            // ⚠️ HTTP 是无状态的:一次请求一个连接语义,建连时执行 SET 不会留给后续请求。
            //    因此 session 参数必须随【每个】请求下发,这里拼进 JDBC URL 的查询串交给驱动转发。
            //    (这是与 native 分支最本质的行为差异,也是本分支唯一需要实测确认的机制。)
            //
            // 显式指定 HTTP 传输为 JDK 内置实现:驱动默认首选 APACHE_HTTP_CLIENT,而那个实现需要
            // 额外的 org.apache.hc.client5:httpclient5 依赖(本工程未引入)。不显式指定的话,
            // 驱动【每次请求】都会先尝试建 Apache 客户端、失败、再回退到 JDK 传输 —— 日志里每条
            // 请求刷一遍 "Error when creating APACHE_HTTP_CLIENT, fall back to HTTP_URL_CONNECTION"
            // 的 WARN,且白费一次类加载尝试。显式指定只去掉这份浪费,传输方式与回退后完全一致。
            // (若日后需要 Apache 的连接池能力,改为引入 httpclient5 并去掉本参数即可。)
            java.util.Map<String, Object> urlParams = new java.util.LinkedHashMap<>();
            urlParams.put("http_connection_provider", "HTTP_URL_CONNECTION");
            // 保留驱动的 ClickHouse 协议内 LZ4 压缩,关闭外层 HTTP 压缩。
            // 部分服务端默认启用 HTTP 压缩;0.6.3 的 JDK 传输无法解析双重 LZ4 响应。
            // 0.6.3 不会转发任意顶层 URL 参数,用 custom_http_params 显式发送。
            // Pass settings as HTTP parameters. JDBC 0.6.3's custom_settings path
            // SQL-escapes string values again, changing backslashes and quotes.
            List<String> serverSettings = new ArrayList<>();
            for (java.util.Map.Entry<String, Object> entry : merged.entrySet()) {
                if (entry.getValue() != null) {
                    serverSettings.add(settingPart(entry.getKey()) + "=" + settingPart(String.valueOf(entry.getValue())));
                }
            }
            serverSettings.add("enable_http_compression=0");
            urlParams.put("custom_http_params", String.join(",", serverSettings));
            url = "jdbc:ch:" + (cfg.isSecure() ? "https" : "http") + "://"
                    + connectHost + ":" + connectPort + "/" + enc(cfg.getDatabase())
                    + urlSettings(urlParams);
            // 注意:HTTP 自带 TLS,不需要 NativeTlsTunnel(那个类是为 housepower 无 TLS 能力而写的)。
        } else {
            // ---- 原生 TCP(native 驱动) ----
            driverClass = "com.github.housepower.jdbc.ClickHouseDriver";
            // housepower 驱动网络连接超时/读取超时(毫秒),尽力设置。
            props.setProperty("connect_timeout", String.valueOf(dialTimeout));
            // housepower 驱动能识别 query_timeout(秒)并在协议层应用(向上取整秒)。
            props.setProperty("query_timeout", String.valueOf(Math.max(1L, (queryTimeout + 999L) / 1000L)));
            if (cfg.isSecure()) {
                // 安全模式:先在本地起 TLS 隧道转发到远程,JDBC 连本地隧道。
                // 方向是“本地监听、外连远程”:housepower 原生驱动自身不做 TLS,只能由隧道把明文
                // native 协议包进 TLS;因此 JDBC URL 里永远是 127.0.0.1:本地随机端口,真正的
                // 主机/端口只存在于隧道内部(也意味着 JDBC 侧看不到目标地址,报错要靠隧道侧日志)。
                tlsTunnel = NativeTlsTunnel.open(cfg.getHost(), cfg.getPort(), dialTimeout);
                connectHost = "127.0.0.1";
                connectPort = tlsTunnel.localPort();
            }
            url = "jdbc:clickhouse://" + connectHost + ":" + connectPort + "/" + enc(cfg.getDatabase());
        }

        // 4. 建立连接。
        Connection c = null;
        try {
            // 两个驱动都接受 jdbc:clickhouse://。DriverManager 按注册顺序尝试,
            // HTTP 驱动可能先向 native 端口发请求;必须直接使用选中的驱动。
            java.sql.Driver driver = (java.sql.Driver) Class.forName(driverClass).getDeclaredConstructor().newInstance();
            c = driver.connect(url, props);
            if (c == null) {
                throw new java.sql.SQLException("所选 ClickHouse 驱动不支持当前连接 URL");
            }
            // 可选:关闭自动提交 — 两种驱动都自动管理,不强制。
            c.setAutoCommit(true);
        } catch (Exception | LinkageError e) {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) { }
            }
            if (tlsTunnel != null) {
                tlsTunnel.close();
            }
            throw new IllegalStateException("初始化 ClickHouse 驱动失败 (" + CkConfig.normalizeProtocol(cfg.getProtocol())
                    + "://" + cfg.addr() + ", TLS=" + cfg.isSecure() + "): " + message(e), e);
        }

        CkClient client = new CkClient(c, cfg.getQueryTimeoutMs(), connLifetime, tlsTunnel);

        // 5. 会话参数下发 + Ping 验证。
        try {
            if (!http) {
                // native 保持原行为:连上后逐条 SET 写到会话级。
                try (Statement st = c.createStatement()) {
                    for (java.util.Map.Entry<String, Object> e : merged.entrySet()) {
                        st.execute("SET " + e.getKey() + " = " + valueLiteral(e.getValue()));
                    }
                }
            }
            // HTTP 分支已把参数拼进 URL(见第 3 步),此处无需再发 SET。
            // 两种协议都做一次 Ping:走的是完整路径(建立语句、下发、取回结果),因此它能同时验证
            // 鉴权、参数生效与 TLS 是否都正常,比只测 TCP 可达更有意义;失败由外层
            // 统一包成“无法连接 host:port”,连接池据此判定这条新连接不可用。
            // open 必须保留探测失败原因;ping() 的布尔返回值仅供连接池保活使用。
            client.checkConnection();
        } catch (Exception | LinkageError e) {
            // 主动关闭 JDBC 和 TLS 监听，不能假设所有失败路径都能触发 socket EOF。
            client.close();
            throw new IllegalStateException("无法连接 " + cfg.addr() + ": " + message(e), e);
        }
        return client;
    }

    /**
     * 把 session 参数拼成 JDBC URL 的查询串(仅 HTTP 协议使用)。
     *
     * <p>HTTP 协议下没有跨请求的会话,参数只能随请求下发,这里交给驱动把查询串里的
     * 自定义参数转成 ClickHouse settings。值按 URL 规则编码,避免含空格/中文/特殊字符的值破坏 URL。
     */
    private static String settingPart(String value) {
        return value.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=");
    }

    private static String urlSettings(java.util.Map<String, Object> settings) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Object> e : settings.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            try {
                sb.append(sb.length() == 0 ? "?" : "&")
                        .append(java.net.URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(java.net.URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignore) {
                // UTF-8 一定可用;真出现异常则跳过该参数,不影响建连
            }
        }
        return sb.toString();
    }

    /** JDBC URL 中的库名做简单净化(去空白/斜杠),避免 URL 语法破坏。 */
    private static String enc(String s) {
        if (s == null) {
            return "default";
        }
        String t = s.trim();
        return t.isEmpty() ? "default" : t.replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }

    /** 把 SET 值拼为字面量:数字/布尔原样,字符串加单引号并转义。 */
    private static String valueLiteral(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** 最近一次 Ping 时刻(供 Manager TTL 判断)。 */
    public long lastPingMs() {
        return lastPingMs;
    }

    /** 是否已超过连接级最大存活时间,由连接池在取用前检查。 */
    public boolean expired() {
        // 用"打开至今"与 maxLifetimeMs 比较,超时就视为过期。
        return System.currentTimeMillis() - openedAtMs >= maxLifetimeMs;
    }

    /**
     * 本条连接是否已作废(执行过一次失败、或已被关闭)。
     * 连接池据此在取用时直接摘除重建,而不是先 Ping 一次 —— 错位的连接上 Ping 的结果不可信。
     */
    public boolean invalidated() {
        return invalidated;
    }

    /** 入口守卫:已作废的连接上不再往协议流里写一个字节,直接报错让上层换连接重试。 */
    private void ensureUsable() {
        if (invalidated) {
            throw new IllegalStateException("连接已作废(上一次执行在驱动层失败并污染了协议流),请重试");
        }
    }

    /**
     * 作废并立即关掉底层连接。
     *
     * <p>立即 close 而不是只在池里标记,是为了让<b>已经持有本对象引用</b>的线程拿到一个明确的
     * {@code IllegalStateException},而不是"连接已关闭"之类掩盖真相的次要错误;close 本身幂等,
     * 池随后的 remove+close 退化成空操作。
     */
    private void invalidate() {
        close();
    }

    /**
     * 判断异常是否属于「服务端完整返回的错误」——权限不足、表不存在、语法错误等。
     *
     * <p>区分它是因为两类失败的后果完全不同:
     * <ul>
     *   <li><b>服务端错误</b>:ClickHouse 完整回了一条异常响应,协议流停在消息边界上,<b>连接可以继续复用</b>。
     *       把它也当失败作废掉,会让同一批查询里第二条起全部报「连接已作废」——
     *       典型场景是监控页连查 4 张 system 表,第一张因权限不足失败,后 3 张跟着陪葬;</li>
     *   <li><b>传输/协议层失败</b>:字节流错位、压缩 magic 不对、连接被重置——这种必须作废连接
     *       (见 {@link #invalidate()} 的说明),驱动那条「卡在等待插入」的老毛病属于这一类。</li>
     * </ul>
     *
     * <p>判据取服务端异常文本里的 {@code DB::Exception}(驱动会把 ClickHouse 的错误原样带出来),
     * 而不是按协议分流:HTTP 无字节流可言、native 也可能遇到干净的 SQL 错误,两者都该复用连接。
     */
    private static boolean isServerSideError(Throwable e) {
        Throwable t = e;
        // 驱动可能把服务端异常裹在自己的异常里,逐层追到根因;限制层数避免异常链成环
        for (int depth = 0; t != null && depth < 10; depth++) {
            String m = t.getMessage();
            if (m != null && m.contains("DB::Exception")) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    /** 记录本次 Ping 成功的时刻,供连接池做 TTL 新鲜度/保活判断(见 {@link #lastPingMs()})。 */
    public void markPing() {
        // 记录本次 Ping 成功的时刻。
        lastPingMs = System.currentTimeMillis();
    }

    /** Ping 连通性检查:执行 SELECT 1,成功则刷新 Ping 时刻。 */
    public synchronized boolean ping() {
        // 已作废的连接不再探活:它上面的字节流已错位,Ping 的结果不可信。
        if (invalidated) {
            return false;
        }
        try {
            checkConnection();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 执行完整查询验证连接,失败时保留驱动异常供初始化错误提示使用。 */
    private void checkConnection() throws java.sql.SQLException {
        try (Statement st = timedStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                if (!rs.next()) {
                    throw new java.sql.SQLException("ClickHouse 连通性查询未返回结果");
                }
            }
            markPing();
        }
    }

    /**
     * 执行只读查询并返回结果。
     * 参数通过 ? 占位符(内部调用 SQL 拼接占位) 传入。
     */
    public synchronized QueryResult query(String sql, List<Object> args) {
        // 已作废的连接上一个字节都不发。
        ensureUsable();
        // housepower 对 SELECT 的预编译占位支持有限,这里把参数内联为字面量再执行,
        // 参数全部来自内部(库表名/数字/已知键),转义单引号即可防注入。
        String realSql = sql;
        if (args != null && !args.isEmpty()) {
            realSql = substitute(sql, args);
        }
        try (Statement st = timedStatement()) {
            try (ResultSet rs = st.executeQuery(realSql)) {
                ResultSetMetaData md = rs.getMetaData();
                // 查询结果总列数。
                int n = md.getColumnCount();
                // 列名与列类型(用驱动数据库类型名,对应 Go DatabaseTypeName)。
                List<String> columns = new ArrayList<>(n);
                List<String> types = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    // 逐列取列名(下标从 1 开始)。
                    // 用 getColumnLabel 而非 getColumnName:只有前者会返回别名,像
                    // "SELECT max(`c`) FROM t" 这种表达式列才有可读列名(与 Go 版语义一致)。
                    columns.add(md.getColumnLabel(i));
                    // 逐列取数据库类型名。
                    types.add(md.getColumnTypeName(i));
                }
                QueryResult res = new QueryResult();
                res.setColumns(columns);
                res.setTypes(types);
                // Rows 初始化为空列表,保证 0 行结果序列化为 [] 而非 null(前端会 map/遍历)。
                List<List<Object>> rows = new ArrayList<>();
                int total = 0;
                while (rs.next()) {
                    // 为当前行分配一个单元格列表。
                    // 逐格过 normalize 是硬要求而非美化:驱动可能返回 Timestamp/BigDecimal/
                    // 数组等类型,直接交给 Jackson 会序列化成带时区的 ISO 串或对象结构,与
                    // Go 版返回的文本形态不一致;同时 total 按实际读到的行计,便于上层判断
                    // 是否被 max_result_rows 截断。
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        // 逐格归一化为可 JSON 序列化的表示。
                        row.add(normalize(rs.getObject(i)));
                    }
                    // 追加当前行。
                    rows.add(row);
                    total++;
                }
                res.setRows(rows);
                res.setTotal(total);
                return res;
            }
        } catch (Exception e) {
            // 服务端完整返回的错误(权限不足/表不存在/语法错误)协议流是干净的,连接可继续复用;
            // 只有传输/协议层失败才作废,否则一条查询失败会连累同批次后续查询(见 isServerSideError)。
            if (!isServerSideError(e)) {
                invalidate();
            }
            throw new QueryExecutionException("查询执行失败: " + message(e), e);
        }
    }

    /** 无参数查询快捷方法。 */
    public synchronized QueryResult query(String sql) {
        return query(sql, null);
    }

    /** 同步读取专用游标。调用方使用独立客户端，避免长结果集占住写入连接。 */
    public synchronized QueryCursor openQueryCursor(String sql) {
        ensureUsable();
        Statement statement = null;
        try {
            statement = timedStatement(true);
            ResultSet result = statement.executeQuery(sql);
            return new QueryCursor(statement, result, result.getMetaData().getColumnCount());
        } catch (Exception e) {
            if (statement != null) try { statement.close(); } catch (Exception ignored) { }
            invalidate();
            throw new IllegalStateException("读取源查询失败: " + message(e), e);
        }
    }

    public class QueryCursor implements AutoCloseable {
        private final Statement statement;
        private final ResultSet result;
        private final int count;
        private boolean closed;
        private boolean exhausted;
        private QueryCursor(Statement statement, ResultSet result, int count) {
            this.statement = statement; this.result = result; this.count = count;
        }

/**
 * 从当前服务端结果集中读取至多 {@code limit} 行。
 *
 * <p>每行按查询列顺序转换为字符串，空值转为空串。读取过程与同一客户端的其他
 * 协议操作通过客户端锁互斥。游标已关闭时返回空列表；读到结果集末尾时自动关闭
 * 游标，并在结果已耗尽的情况下保留底层连接复用能力。读取失败、线程中断或提前
 * 关闭会关闭结果集并使底层连接失效，避免残留未消费的协议响应。
 *
 * @param limit 本次最多读取的行数，必须大于 0
 * @return 按列顺序组织的字符串行；无剩余数据时为空列表
 * @throws IllegalArgumentException {@code limit} 小于等于 0 时抛出
 * @throws IllegalStateException 查询读取失败或执行线程被中断时抛出
 */
        public List<List<String>> next(int limit) {
            if (limit <= 0) throw new IllegalArgumentException("批次行数必须大于 0");
            List<List<String>> rows = new ArrayList<>(limit);
            synchronized (CkClient.this) {
                if (closed) return rows;
                try {
                    while (rows.size() < limit) {
                        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已超时或被中断");
                        if (!result.next()) { exhausted = true; close(); break; }
                        List<String> row = new ArrayList<>(count);
                        for (int i = 1; i <= count; i++) {
                            Object value = normalize(result.getObject(i));
                            row.add(value == null ? "" : String.valueOf(value));
                        }
                        rows.add(row);
                    }
                } catch (Exception e) {
                    close();
                    invalidate();
                    throw new IllegalStateException("读取源查询失败: " + message(e), e);
                }
            }
            return rows;
        }
        @Override public void close() {
            synchronized (CkClient.this) {
                if (closed) return;
                closed = true;
                try { result.close(); }
                catch (Exception e) { invalidate(); }
                finally {
                    try { statement.close(); } catch (Exception e) { invalidate(); }
                    // 未读完的原生协议响应不能继续复用。
                    if (!exhausted) invalidate();
                }
            }
        }
    }

    /**
     * 行级流式查询:先回调列名,再逐行回调数据,服务端不把结果集攒进内存。
     *
     * <p>导出专用(表导出 / SQL 结果导出)。{@link #query} 会把整个结果集装配成
     * {@code List<List<Object>>} 再返回 —— 200 万行的导出走那条路等于在服务端再存一份
     * 完整数据;这里读一行、交给调用方写一行,内存占用与结果规模无关。
     *
     * <p>⚠️ 驱动层是否真的不整份缓冲未实测:housepower 的行为取决于其 fetch 实现。
     * 能确定的是服务端不再自己攒第二份(CSV 缓冲区)。
     *
     * @param sql     待执行的只读语句
     * @param handler 列名与逐行数据的回调
     * @throws IllegalStateException 查询执行失败(与 query 一致的错误文案)
     * @throws java.io.IOException   回调侧抛出的写出错误(如响应连接已断开),原样上抛
     */
    public synchronized void queryStream(String sql, RowHandler handler) throws java.io.IOException {
        // 已作废的连接上一个字节都不发。
        ensureUsable();
        try (Statement st = timedStatement()) {
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                // 查询结果总列数。
                int n = md.getColumnCount();
                // 列名与 query() 同口径:用 getColumnLabel 才能拿到别名。
                List<String> columns = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    columns.add(md.getColumnLabel(i));
                }
                // 先给列名,调用方据此写表头、定导出投影。
                handler.columns(columns);
                // 逐行读、逐行交出去:不在这里累积。
                while (rs.next()) {
                    List<Object> row = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        // 与 query() 同一套归一化,保证导出与网格显示同一个值。
                        row.add(normalize(rs.getObject(i)));
                    }
                    handler.row(row);
                }
            }
        } catch (java.io.IOException e) {
            // 写出侧的失败(响应连接断开、客户端取消)不要伪装成「查询执行失败」:
            // 那会把一次正常的客户端断开记成目标库故障,排查时指错方向。
            // 但连接照样作废:中途放弃 == 结果集没读完 == 协议流上必然还留着残包。
            invalidate();
            throw e;
        } catch (Exception e) {
            // 失败即作废这条连接(理由见 query 里那处)。
            invalidate();
            throw new QueryExecutionException("查询执行失败: " + message(e), e);
        }
    }

    /** 流式查询的行回调:先 {@link #columns} 一次,再对每行调 {@link #row}。 */
    public interface RowHandler {
        /** 结果列名(仅调用一次,在任何数据行之前)。 */
        void columns(List<String> columns) throws java.io.IOException;

        /** 一行数据(单元格已 normalize 归一化)。 */
        void row(List<Object> row) throws java.io.IOException;
    }

    /** 执行非查询语句(DDL/DML)。 */
    public synchronized void exec(String sql) {
        // 已作废的连接上一个字节都不发。
        ensureUsable();
        // 用带超时的 Statement 执行 DDL/DML。
        try (Statement st = timedStatement()) {
            // ⚠️ 必须过 guardInlineValues:见该方法上的说明(驱动的内联 VALUES 解析有缺陷)。
            //    改写发生在【驱动边界】而不是调用方,所以运行历史/日志里记的仍是对外那条 SQL。
            st.execute(guardInlineValues(sql));
        } catch (Exception e) {
            // 与 query 同口径:服务端完整返回的 DML 错误(表不存在、约束/权限问题)不作废连接;
            // 驱动那条「卡在等待插入」的传输层老毛病不属于服务端错误,仍会走 invalidate。
            if (!isServerSideError(e)) {
                invalidate();
            }
            throw new IllegalStateException("执行失败: " + message(e), e);
        }
    }

    /**
     * 给写入语句加的守卫注释:让 housepower 驱动<b>不要</b>自己去解析这条 INSERT。
     *
     * <p>取值只要不是「INSERT 开头」即可,用块注释是为了在 SQL 里可读、可检索,
     * 且对 ClickHouse 而言注释等同于空白,语句语义不变(真机验证过)。
     */
    static final String VALUES_GUARD = "/*el2ck*/";

    /**
     * 把 INSERT 挡在 housepower 驱动的内联 VALUES 解析之外。
     *
     * <h3>为什么需要这么做</h3>
     * housepower 2.6.4 的 {@code ClickHouseStatement.executeUpdate} 里有这么一段:
     * 用正则 {@code [V|v][A|a][L|l][U|u][E|e][S|s]\s*\(} 找内联 VALUES,
     * 且语句去掉首尾空白后以 {@code INSERT} 开头时,就<b>自己解析</b> VALUES 文本
     * ({@code ValuesNativeInputFormat}),再用原生协议发数据块;两个条件只要有一个不满足,
     * 就把整条 SQL 原样交给服务端解析。
     *
     * <p>它自己那条解析路径处理不了字符串里的转义引号:遇到 {@code 'O''Brien'} 这类值会失败,
     * 于是只发出 {@code INSERT INTO t (cols) VALUES }(VALUES 体为空)的表头就中断,
     * 服务端报 {@code Attempt to read after eof};客户端这条连接还会卡在
     * {@code Connection is currently waiting for an insert operation},直到连接池的 Ping
     * 到点重建(真机实测约 6 秒),期间该连接上<b>所有</b>查询都失败。
     *
     * <p>影响面远不止 SQL 控制台:{@link #sqlLiteral} 正是把撇号转义成 {@code ''},
     * 而 {@code insertBatchAsSql} 是同步引擎与工作流写入的唯一路径 ——
     * <b>源数据里任何一个撇号都会让该批写入失败</b>。Go 版走原生 PrepareBatch 参数绑定,没有这个问题。
     *
     * <h3>为什么是「前置注释」而不是「在 VALUES 后面插一句」</h3>
     * 在 {@code VALUES} 与 {@code (} 之间插注释同样能让正则不命中,但那条路要先把 SQL 扫一遍,
     * 判断每一处 {@code VALUES(} 到底是不是关键字 —— 而驱动是<b>裸正则</b>找的,
     * {@code INSERT INTO t SELECT 'VALUES(' FROM s} 里字符串内的那一处它也会命中。
     * 我们若按关键字改写就会漏掉它(漏了 = 缺陷照旧),若照抄裸正则改写就会把字面量改掉
     * (那是数据损坏)。让整条语句走服务端解析没有这个两难:驱动一个字符都不解析。
     *
     * <p>代价是不再使用驱动那条插入快路径。它省下的其实只是「谁来解析这段文本」——
     * 快路径同样要把 VALUES 文本解析成数据块,只是解析在客户端做。服务端解析同样文本是 SQL 客户端的常态,
     * 也是 Go 版的行为,所以这里按「与 Go 一致 + 不丢数据」优先。
     *
     * <p>查询用不到这一步:{@code SELECT} 不满足「以 INSERT 开头」的前提,驱动本来就不拦,
     * 所以 {@link #query} / {@link #queryStream} 保持原样。
     *
     * @param sql 待执行的语句;非 INSERT(或已有前导注释)的一律原样返回
     * @return 加了守卫的语句;无需改动时返回原对象
     */
    static String guardInlineValues(String sql) {
        // null / 空串没有可挡的东西。
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 与驱动同一条前提:去掉首尾空白后必须以 INSERT 开头,否则它本来就不会走解析分支。
        // 也正因为有这条前提,重复调用天然幂等:加过一次之后就不以 INSERT 开头了。
        if (!sql.trim().regionMatches(true, 0, "INSERT", 0, 6)) {
            return sql;
        }
        // 前缀注释把「以 INSERT 开头」这个前提消掉,整条语句交给服务端解析。
        return VALUES_GUARD + " " + sql;
    }

    /** 创建按 queryTimeoutMs 设置语句超时的 Statement(不支持 JDBC 超时提示时静默降级)。 */
    private Statement timedStatement() throws java.sql.SQLException {
        return timedStatement(false);
    }

    private Statement timedStatement(boolean streamingSource) throws java.sql.SQLException {
        Statement st = conn.createStatement();
        // JDBC 的语句超时只能按秒设置,这里向上取整:宁可最多多等 1 秒,也不能因为截断
        // (如 500ms→0 秒)把超时变成“永不超时”。驱动侧另有 query_timeout 兜底(见 open)。
        long seconds = (queryTimeoutMs + 999L) / 1000L;
        try {
            // HTTP JDBC 将语句超时转换成服务端 max_execution_time。
            // 同步游标覆盖整张源表，不能套用普通查询的 60 秒上限：旧服务端会按
            // 全量耗时预估提前终止，并将异常文本混入已开始返回的二进制响应。
            // 0 仅移除这条语句的 JDBC 上限；网络读超时、服务端配置及同步任务超时仍有效。
            st.setQueryTimeout(streamingSource ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(1L, seconds)));
        } catch (java.sql.SQLFeatureNotSupportedException ignored) {
            // 旧版原生驱动未实现该 JDBC 提示时忽略;此时依赖 socket_timeout 兜底。
        }
        return st;
    }

    /**
     * 批量写入(同步引擎/工作流 ck_write 用)。每 1000 行分块提交。
     * 现统一经 {@link #insertBatchAsSql} 拼成 SQL 字面量多值 INSERT 执行,以绕开
     * housepower 驱动解析 Bool 列元数据时报 Unknown data type 的问题;方法体内
     * PreparedStatement + addBatch 的原生批量分支为保留的备选,当前不会执行。
     * 失败统一抛 IllegalStateException,由上层映射为 502。
     */
    public synchronized void insertBatch(String table, List<String> columns, List<List<Object>> rows) {
        // 无数据直接返回,避免生成空 INSERT。
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // housepower 2.6.x 用 PreparedStatement 批量写入时会解析列元数据里的 Bool,
        // 部分 ClickHouse 版本会因此报 Unknown data type: Bool。为规避该问题,现对
        // 所有写入统一转成 SQL 字面量多值 INSERT(布尔映射 1/0),不再按是否含布尔分流;
        // 因空行已在上方提前返回,此处 rows 恒非空、判断恒真,下方原生批量分支不会执行。
        if (rows != null) {
            // 恒真分支:统一走 SQL 字面量插入路径。
            // 保留其下的 PreparedStatement 分支不删,是为了等驱动修复 Bool 元数据问题后能一键
            // 切回原生批量(原生批量省去拼接与转义,吞吐更高);同时 containsBoolean 也仅供它使用。
            insertBatchAsSql(table, columns, rows);
            return;
        }
        // 拼列名列表(反引号包裹并转义)。
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            // 列之间用逗号+空格分隔。
            if (i > 0) {
                cols.append(", ");
            }
            // 用反引号包裹列名,内部反引号翻倍转义。
            cols.append('`').append(columns.get(i).replace("`", "``")).append('`');
        }
        // 拼占位符列表(每列一个 ?)。
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            // 占位符之间用逗号+空格分隔。
            if (i > 0) {
                marks.append(", ");
            }
            marks.append('?');
        }
        String sql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
        // 用 PreparedStatement + addBatch/executeBatch 走原生批量协议。
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // 按 queryTimeoutMs 折算秒数设置语句超时。
            long seconds = (queryTimeoutMs + 999L) / 1000L;
            try {
                ps.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1L, seconds)));
            } catch (java.sql.SQLFeatureNotSupportedException ignored) {
                // 不支持 JDBC 语句超时的驱动依赖 socket_timeout 兜底。
            }
            int count = 0;
            for (List<Object> row : rows) {
                // 按 Java 实际类型选择 setX,尽量贴合 ClickHouse 目标列类型。
                for (int i = 0; i < row.size(); i++) {
                    setParam(ps, i + 1, row.get(i));
                }
                // 把当前行加入本批。
                ps.addBatch();
                // 每 1000 行执行一批,避免单次内存过大。
                if (++count % 1000 == 0) {
                    ps.executeBatch();
                }
            }
            // 收尾:提交剩余不足 1000 行的那一批。
            ps.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("批量写入失败: " + message(e), e);
        }
    }

    /** 判断整个行集是否含任何布尔值。当前 insertBatch 已不据此分流,保留供未启用的原生批量分支使用。 */
    // 判据是“整体含布尔”而非逐行:布尔映射的选择必须按列类型统一,同一批里混用两种绑定方式
    // 会让同一列的元数据类型不一致。
    private static boolean containsBoolean(List<List<Object>> rows) {
        for (List<Object> row : rows) {
            // 跳过空行。
            if (row == null) {
                continue;
            }
            for (Object value : row) {
                // 一旦发现 Boolean 即整体判定为"含布尔"。
                if (value instanceof Boolean) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 把含布尔值的行集按纯 SQL 字面量拼 INSERT,分 1000 行一块执行(绕开驱动的 Bool 映射)。 */
    private synchronized void insertBatchAsSql(String table, List<String> columns, List<List<Object>> rows) {
        insertBatchAsSql(table, columns, rows, "");
    }

    /**
     * 分块写入只保证单次 INSERT 的原子性；块之间没有事务，调用方必须在方法完整返回后才推进 checkpoint。
     */
    public synchronized void insertCheckpointBatch(String table, List<String> columns, List<List<Object>> rows, String token) {
        if(token == null || !token.matches("el2ck_sync_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid checkpoint token");
        insertBatchAsSql(table, columns, rows, "/*"+token+"*/ ");
    }

    /** 一条可确定性重建的 INSERT；调用方在发送前保存摘要，在成功后保存 end。 */
    public static final class CheckpointChunk {
        public final String sql;
        public final int end;
        private CheckpointChunk(String sql,int end) { this.sql=sql; this.end=end; }
    }

    public static CheckpointChunk checkpointChunk(String table,List<String> columns,List<List<Object>> rows,
                                                   int start,String runToken,String blockToken) {
        if(runToken==null || !runToken.matches("el2ck_sync_[a-f0-9]{32}")
                || blockToken==null || !blockToken.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid checkpoint token");
        if(start<0 || start>=rows.size()) throw new IllegalArgumentException("Invalid checkpoint offset");
        StringBuilder sql=new StringBuilder("/*").append(runToken).append("*/ INSERT INTO ").append(table).append(" (");
        for(int i=0;i<columns.size();i++) {
            if(i>0) sql.append(',');
            sql.append('`').append(columns.get(i).replace("`","``")).append('`');
        }
        // 固定解析/分块设置；同一令牌的重试必须形成完全相同的块。每条 SQL 最多 10000 行，
        // 去重窗口大于单条 SQL 最坏的块数；每确认一条 SQL 就提交 MySQL 子批次位置。
        sql.append(") SETTINGS async_insert=0,insert_deduplicate=1,insert_deduplication_token='")
                .append(blockToken).append("',max_insert_block_size=10000,max_block_size=10000,")
                .append("min_insert_block_size_rows=10000,min_insert_block_size_bytes=0,input_format_parallel_parsing=0 VALUES ");
        int end=start;
        while(end<rows.size() && end-start<10000) {
            if(end>start && sql.length()>=1024*1024) break;
            List<Object> row=rows.get(end);
            if(row==null || row.size()!=columns.size()) throw new IllegalStateException("批量写入行字段数与列数不一致");
            if(end>start) sql.append(',');
            sql.append('(');
            for(int i=0;i<row.size();i++) { if(i>0) sql.append(','); sql.append(sqlLiteral(row.get(i))); }
            sql.append(')'); end++;
        }
        return new CheckpointChunk(sql.toString(),end);
    }

    private void insertBatchAsSql(String table, List<String> columns, List<List<Object>> rows, String tag) {
        // 拼列名列表(反引号包裹并转义)。
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                // 列之间用逗号+空格分隔。
                cols.append(", ");
            }
            // 用反引号包裹列名,内部反引号翻倍转义。
            cols.append('`').append(columns.get(i).replace("`", "``")).append('`');
        }
        // 断点任务已有批次区间清理/重放保护，合并至最多 10000 行，减少请求和小数据块。
        // 在完整行边界按约 1M 字符拆分大 SQL（单行可超限）；普通调用仍保持每次 1000 行。
        // 分块之间没有事务，断点只能在整批全部确认后推进。
        int maxRows=tag.isEmpty() ? 1000 : 10000;
        for (int start = 0; start < rows.size();) {
            if (!tag.isEmpty() && Thread.currentThread().isInterrupted())
                throw new IllegalStateException("同步已超时或被中断，未确认批次将从断点恢复");
            // 本块结束下标(不超过总行数)。
            int end = Math.min(rows.size(), start + maxRows);
            int next=start;
            // 开始拼 "INSERT INTO table (cols) VALUES" 前缀。
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                    .append(" (").append(cols).append(") VALUES ");
            for (int ri = start; ri < end; ri++) {
                if(!tag.isEmpty() && ri>start && sql.length()>=1024*1024) break;
                if(!tag.isEmpty() && Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("同步已超时或被中断，未确认批次将从断点恢复");
                // 除第一行外每行前用逗号分隔。
                if (ri > start) {
                    sql.append(", ");
                }
                List<Object> row = rows.get(ri);
                // 行缺失或列数不符视为数据错误。
                if (row == null || row.size() != columns.size()) {
                    throw new IllegalStateException("批量写入行字段数与列数不一致");
                }
                // 行首左括号。
                sql.append('(');
                for (int ci = 0; ci < row.size(); ci++) {
                    // 单元格之间用逗号分隔。
                    if (ci > 0) {
                        sql.append(", ");
                    }
                    // 单元格转成 SQL 字面量。
                    sql.append(sqlLiteral(row.get(ci)));
                }
                // 行尾右括号。
                sql.append(')');
                next=ri+1;
            }
            // 逐块执行拼好的 INSERT。
            exec(tag + sql.toString());
            start=next;
        }
    }

    /** 把单值转成 SQL 字面量:null→NULL,布尔→1/0,数字原样,byte[]→unhex(...),其余单引号转义。 */
    static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean) {
            // ClickHouse 无原生 Bool 字面量,布尔映射成 1/0。
            return Boolean.TRUE.equals(value) ? "1" : "0";
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof byte[]) {
            // 二进制用 unhex('...') 写入。
            return "unhex('" + bytesHex((byte[]) value) + "')";
        }
        // Timestamp.toString() 会给整秒追加 .0，DateTime 的严格 VALUES 解析不接受它。
        // 小数秒由上游按目标类型处理；DateTime64 的有效精度在这里完整保留。
        if (value instanceof Timestamp) {
            value = com.el2ck.util.SyncTime.format(((Timestamp) value).toLocalDateTime());
        } else if (value instanceof LocalDateTime) {
            value = com.el2ck.util.SyncTime.format((LocalDateTime) value);
        }
        // 其余一律单引号字符串,单引号翻倍转义。
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    /** 把字节数组转成小写十六进制串(用于 unhex)。 */
    private static String bytesHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) {
            // 每个字节按两位十六进制拼接(掩码去符号位)。
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    /** 把单个单元格设置为 PreparedStatement 参数。 */
    static void setParam(PreparedStatement ps, int idx, Object v) throws Exception {
        if (v == null) {
            // null → SQL NULL(Nullable 列正常落库;非 Nullable 列由服务端拒绝并报错)。
            ps.setObject(idx, null);
        } else if (v instanceof Boolean) {
            // clickhouse-native-jdbc 2.6.x maps setBoolean to the unsupported
            // native type "Bool" on some server versions. Bind it as UInt8.
            ps.setByte(idx, (byte) ((Boolean) v ? 1 : 0));
        } else if (v instanceof Byte) {
            // 小整数(对应 UInt8/Int8),按 Byte 原样绑定。
            ps.setByte(idx, (Byte) v);
        } else if (v instanceof Short) {
            // 小整数(对应 UInt16/Int16),按 Short 原样绑定。
            ps.setShort(idx, (Short) v);
        } else if (v instanceof Integer) {
            // Int32/UInt32(注:UInt32 上限 42.9 亿超 Integer 范围,超限值会以 Long 到达本分支)。
            ps.setInt(idx, (Integer) v);
        } else if (v instanceof Long) {
            // Int64/UInt64 大整数,按 Long 原样绑定。
            ps.setLong(idx, (Long) v);
        } else if (v instanceof java.math.BigInteger) {
            // BigInteger 无对应 setX,转为字符串绑定。
            ps.setString(idx, v.toString());
        } else if (v instanceof Float) {
            ps.setFloat(idx, (Float) v);
        } else if (v instanceof Double) {
            ps.setDouble(idx, (Double) v);
        } else if (v instanceof java.math.BigDecimal) {
            ps.setBigDecimal(idx, (java.math.BigDecimal) v);
        } else if (v instanceof Timestamp) {
            // java.sql.Timestamp → 对应 DateTime/DateTime64,按时间戳绑定。
            ps.setTimestamp(idx, (Timestamp) v);
        } else if (v instanceof Date) {
            ps.setDate(idx, (Date) v);
        } else if (v instanceof LocalDateTime) {
            // java.time 类型驱动不直接支持,先转成 Timestamp。
            ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) v));
        } else if (v instanceof LocalDate) {
            // LocalDate 先转成 java.sql.Date 再绑定。
            ps.setDate(idx, Date.valueOf((LocalDate) v));
        } else if (v instanceof byte[]) {
            ps.setBytes(idx, (byte[]) v);
        } else {
            // 兜底:一律按字符串绑定。
            ps.setString(idx, String.valueOf(v));
        }
    }

    /**
     * 将驱动返回的单元格值转换为可安全 JSON 序列化的表示(对应 Go normalizeValue)。
     * 关键:DateTime→"yyyy-MM-dd HH:mm:ss"(不带时区),Date→补 00:00:00,
     * BigDecimal/其它自定义对象→字符串表示,byte[]→UTF-8 字符串,null→null。
     */
    private static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        // 时间类统一成 "yyyy-MM-dd HH:mm:ss" 文本且不带时区:与 Go 版输出对齐,前端按字符串
        // 直接展示/排序即可;若保留 Timestamp,Jackson 会输成 UTC 或带偏移的 ISO 串,导致同一
        // 份数据在两端看起来不一致。下面按具体类型逐个判,是因为它们彼此没有继承关系。
        if (v instanceof Timestamp) {
            // 时间戳按统一格式输出,不带时区后缀。
            return com.el2ck.util.SyncTime.format(((Timestamp) v).toLocalDateTime());
        }
        if (v instanceof Date) {
            return new java.text.SimpleDateFormat(DT_FMT).format((Date) v);
        }
        if (v instanceof LocalDateTime) {
            return com.el2ck.util.SyncTime.format((LocalDateTime) v);
        }
        if (v instanceof LocalDate) {
            // Date 没有时分秒;补 00:00:00 保持与 DateTime 的输出格式一致。
            return ((LocalDate) v).atStartOfDay()
                    .format(java.time.format.DateTimeFormatter.ofPattern(DT_FMT));
        }
        if (v instanceof java.util.Date) {
            return new java.text.SimpleDateFormat(DT_FMT).format((java.util.Date) v);
        }
        if (v instanceof byte[]) {
            // 字节数组按 UTF-8 解码为字符串。
            return new String((byte[]) v, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (v instanceof java.math.BigDecimal) {
            // ClickHouse Decimal 在 Go 端走 String() → 输出字符串(非数字),保持一致。
            // toPlainString 而非 toString:后者对极小/极大标度会输出科学计数法,前端展示会失真。
            return ((java.math.BigDecimal) v).toPlainString();
        }
        if (v instanceof java.util.UUID) {
            return v.toString();
        }
        if (v instanceof Boolean || v instanceof String
                || v instanceof Byte || v instanceof Short || v instanceof Integer
                || v instanceof Long || v instanceof java.math.BigInteger
                || v instanceof Float || v instanceof Double) {
            // 这些类型本身可安全 JSON 序列化,原样返回。
            return v;
        }
        if (v.getClass().isArray()) {
            // 数组列:递归归一化每个元素。
            // 走到这里说明不是 byte[](byte[] 已在上面按 UTF-8 解码返回),而是 Array(T) 列;
            // 递归是必要的,因为元素本身也可能是时间/Decimal 等需要转换的类型。
            int len = java.lang.reflect.Array.getLength(v);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                out.add(normalize(java.lang.reflect.Array.get(v, i)));
            }
            return out;
        }
        // 其它未知类型一律用 toString 兜底,避免 Jackson 序列化异常。
        // 兜底优先于“报错”:查询结果里出现未预期的驱动专有类型时,宁可显示其字符串形式,
        // 也不能让整条查询因一格数据而失败。
        return String.valueOf(v);
    }

    /** 把 sql 中按序出现的 ? 替换为参数字面量(字符串单引号转义,数字原样)。 */
    private static String substitute(String sql, List<Object> args) {
        StringBuilder sb = new StringBuilder(sql.length());
        // idx 指向下一个要替换的参数下标。
        int idx = 0;
        // 是否处于 SQL 单引号字符串内(需跳过其中的 ?)。
        // 粗粒度判定:只按单引号的奇偶翻转追踪,不处理双引号标识符、注释与转义序列。够用的
        // 前提是调用方传入的 SQL 都是平台自己拼的(库表名/过滤条件),不含这类结构;
        // 若把本方法用于用户手写的 SQL,遇到含 ? 的字符串字面量会误替换。
        boolean inSQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                inSQuote = !inSQuote; // 粗粒度追踪单引号,用于跳过 SQL 自带字符串里的 ?。
                sb.append(ch);
                continue;
            }
            if (ch == '?' && !inSQuote) {
                // 字符串外的 ? 视为占位符,替换为参数字面量。
                sb.append(literal(args.get(idx++)));
            } else {
                // 其余字符原样保留。
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 生成一个参数字面量(用于内联 ?)。 */
    private static String literal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Number || v instanceof Boolean) {
            // 数字/布尔直接拼原文。
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        // 字符串加单引号,反斜杠与单引号转义。
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** 提取异常的"最内层有消息"信息,便于把底层错误原样带给上层。 */
    private static String message(Throwable e) {
        Throwable t = e;
        // 向上穿透 cause 链,直到找到带消息或到根异常。
        while (t.getCause() != null && t.getMessage() == null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        // 拿不到消息时退回 toString。
        return m == null || m.isEmpty() ? e.toString() : m;
    }

    /** 关闭 JDBC 连接和 TLS 监听/转发线程；无论驱动是否正常释放 socket 都主动清理隧道。 */
    // synchronized 与 query/exec 互斥:避免连接正在执行语句时被关掉(那会以“连接已关闭”这种
    // 次要错误掩盖真正的业务错误)。幂等是必要的——连接池的过期重建与 remove 都可能重复触发关闭。
    @Override
    public synchronized void close() {
        // 已关闭的连接永不再用:让持有旧引用的线程拿到明确报错,而不是"连接已关闭"这类
        // 掩盖真正原因(上一次失败)的次要异常。
        if (invalidated) return;
        invalidated = true;
        try {
            // 关闭底层 JDBC 连接。
            conn.close();
        } catch (Exception ignored) {
            // 忽略关闭异常
        } finally {
            if (tlsTunnel != null) tlsTunnel.close();
        }
    }
}
