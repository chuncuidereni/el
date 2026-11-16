package com.el2ck.duck;

/**
 * 【文件职责】管理 DuckDB 连接以及 CSV/JSON/XLSX 扫描、查询和导出能力，为文件型数据源提供统一访问层。
 */

import com.el2ck.entity.common.Column;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 平台内嵌 DuckDB(进程内 OLAP)，对应 Go internal/duckdb 的 cgo 实现(Java 版 JDBC 驱动)。
 *
 * 用途：用 read_csv/read_json 直接读取本地 CSV/JSON(不导入库)，做字段解析、预览/计数/分页；
 * 工作流中间结果物化为 files.db 里的临时表。
 * 关键约定:
 *  - 库文件:<dataDir>/duckdb/files.db;
 *  - Windows 路径 → SQL 需正斜杠 + 单引号转义;
 *  - DuckDB 单写者:所有操作全局加锁(与 Go 的 mutex 语义一致)，并串行执行读写;
 *  - 单单元格统一转字符串(nil→""，time→"yyyy-MM-dd HH:mm:ss")。
 */
public class Duck implements AutoCloseable {

    /** 合法标识符:字母/下划线开头，仅字母数字下划线。 */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 日期时间统一字符串格式。 */
    private static final String DT_FMT = "yyyy-MM-dd HH:mm:ss";

    /** 全局互斥锁:保证 DuckDB 单写者模型下所有读写操作串行执行 */
    // 锁只在每个 public 方法的“进入-退出”期间持有,不跨方法持有;因此像 materialize 这种
    // “先建表再 count”的复合操作,两段之间其它线程可以插入(它本身是两次独立的加锁),
    // 结果是 count 可能统计到别人写入后的表——物化表名按节点唯一,实际不会互相踩。
    private final ReentrantLock lock = new ReentrantLock();
    /** JDBC 连接:null 表示未初始化 */
    private Connection conn;
    /** 当前库文件路径(供缓存目录等使用) */
    private String dbFile;

    /**
     * 打开(必要时创建)DuckDB 文件库。失败返回 false(降级，不中断平台启动)，可在日志说明。
     */
    public synchronized boolean open(String dataDir) {
        // 先加全局锁，保证与后续读写操作串行
        lock.lock();
        try {
            // 已打开过则直接返回成功
            if (conn != null) {
                return true;
            }
            // 库目录固定位于 <dataDir>/duckdb
            File dir = new File(dataDir, "duckdb");
            // 目录不存在则创建；创建失败抛出异常
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("创建 duckdb 目录失败: " + dir);
            }
            // 记录库文件绝对路径(反斜杠统一换正斜杠，便于拼进 SQL)
            // 这里就把 Windows 反斜杠换掉:该路径既用于 JDBC URL 也(经 file())被上层用于
            // 推导缓存目录,统一成正斜杠可避免后续每个拼接点各转一次。
            dbFile = new File(dir, "files.db").getAbsolutePath().replace('\\', '/');
            // 显式加载 DuckDB JDBC 驱动类
            Class.forName("org.duckdb.DuckDBDriver");
            // 建立 JDBC 连接
            conn = DriverManager.getConnection("jdbc:duckdb:" + dbFile);
            return true;
        } catch (Exception e) {
            // 文件库打开失败:多为 DuckDB 单写者限制——files.db 正被另一实例占用。
            // 此处降级到进程内内存库:仍可读本地文件/预览/物化,但数据不落盘、进程退出即失效;
            // 使文件能力保持可用而非整体不可用。仅当内存库也无法打开才真正返回 false。
            // 边界:降级后 dbFile 仍指向磁盘路径(供缓存目录推导用),但连接其实是内存库——
            // 工作流临时表不落盘、跨进程不复用;调用方只能靠“无异常”感知,无法区分两种形态。
            try {
                conn = DriverManager.getConnection("jdbc:duckdb:");
                return true;
            } catch (Exception fallback) {
                // 内存库也初始化失败(如驱动缺失)才置空连接并返回 false。
                conn = null;
                return false;
            }
        } finally {
            // 无论成败都释放全局锁
            lock.unlock();
        }
    }

/** 返回 DuckDB 连接是否已经初始化。 */
    public boolean isReady() {
        // 连接非空即视为已就绪
        return conn != null;
    }


    public String file() {
        // 返回当前库文件路径
        return dbFile;
    }

    @Override
    public void close() {
        // 加全局锁后关闭连接
        lock.lock();
        try {
            // 连接存在才真正关闭
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
                // 关闭后置空连接
                conn = null;
            }
        } finally {
            lock.unlock();
        }
    }

    // =====================================================================
    // SQL 片段构造(read_csv/read_json)
    // =====================================================================

    /** Windows 路径 → SQL 安全(正斜杠 + 单引号转义)。 */
    private static String sqlPath(String p) {
        // 反斜杠换正斜杠，单引号翻倍转义(适配 SQL 字符串字面量)
        return p.replace('\\', '/').replace("'", "''");
    }

    /** 组装 read_csv 表达式(Header/Names 决定写法)。 */
    private static String buildReadCSV(String path, boolean header, List<String> names) {
        // 未提供分隔符时交给带 delimiter 的重载，取默认逗号
        return buildReadCSV(path, header, names, null);
    }

    /** 组装 read_csv 表达式(支持自定义分隔符 delimiter；null/逗号=默认)。 */
    private static String buildReadCSV(String path, boolean header, List<String> names, String delimiter) {
        // 先得到 SQL 安全的文件路径
        String p = sqlPath(path);
        // 生成可选的分隔符片段(可能为空串)
        String delimSql = csvDelimSql(delimiter);
        // 四种组合穷举而不是拼参数:auto_detect 始终开启(让 DuckDB 自行推断列类型),
        // header 与 names 的搭配语义差异较大——names 在 header=true 时是“重命名”,在
        // header=false 时是“命名”,两者都写进同一条 SQL 会让 DuckDB 按自己的规则解释,
        // 显式区分可以避免依赖这些细节。
        if (header) {
            // 有表头:第一行作表头;若提供 names 则覆盖列名(重命名)。
            if (names != null && !names.isEmpty()) {
                // 指定 names:在表头模式下手动重命名各列
                return "read_csv('" + p + "', auto_detect=true, header=true, names=[" + qlist(names) + "]" + delimSql + ")";
            }
            // 有表头是用户的明确配置，不能再次猜测；否则全字符串样本可能被当成数据，
            // 预览保存的 column0 等列名便与续传游标显式 header=true 的结果不一致。
            return "read_csv('" + p + "', auto_detect=true, header=true" + delimSql + ")";
        }
        // 无表头:header=false + 手动列名(未提供则 DuckDB 自动命名)。
        if (names != null && !names.isEmpty()) {
            // 指定 names:为无表头数据命名各列
            return "read_csv('" + p + "', auto_detect=true, header=false, names=[" + qlist(names) + "]" + delimSql + ")";
        }
        // 未指定 names:由 DuckDB 自动命名(如 COLUMN0、COLUMN1...)
        return "read_csv('" + p + "', auto_detect=true, header=false" + delimSql + ")";
    }

    /**
     * 自定义分隔符 → read_csv 的 delim 片段。null/空保留自动识别；显式逗号必须生效。
     * "\t" 与 "\\t" 统一为制表符转义(DuckDB 字符串字面量支持 \t)。
     */
    private static String csvDelimSql(String delimiter) {
        if (delimiter == null) {
            // null 表示未自定义，交给原 DuckDB 读取链路自动识别。
            return "";
        }
        // 真实制表符必须在 trim 之前识别。
        if ("\t".equals(delimiter)) return ", delim='\\t'";
        String d = delimiter.trim();
        if (d.isEmpty()) {
            return "";
        }
        if ("\\t".equals(d) || "\t".equals(d)) {
            // 制表符归一为 \\t 转义形式(供 DuckDB 字符串字面量识别)
            d = "\\t";
        }
        // 拼出 delim 片段(分隔符内单引号翻倍转义)
        return ", delim='" + d.replace("'", "''") + "'";
    }

    /** 组装 read_json 表达式(JSON 自带 key，无表头概念)。 */
    private static String buildReadJSON(String path) {
        // 同时兼容普通 JSON 数组和 MongoDB 导出的 JSONL；由 DuckDB 自动识别格式，
        // 按字段合并并容错异构文档，避免普通 JSON 数组被误当成 JSONL。
        return "read_json('" + sqlPath(path)
                + "', auto_detect=true, union_by_name=true)";
    }

    /** 名字列表 → 'a','b' 字面量片段。 */
    private static String qlist(List<String> names) {
        // 缓冲拼装字符串
        StringBuilder sb = new StringBuilder();
        // 逐个名字拼接，逗号分隔，单引号包裹
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                // 非首项前补一个逗号
                sb.append(", ");
            }
            // 每个名字用单引号包裹并转义内部单引号
            sb.append('\'').append(names.get(i).replace("'", "''")).append('\'');
        }
        return sb.toString();
    }

    /**
     * 按扩展名返回文件读取表达式。json/.ndjson/.jsonl → read_json；其余(默认 .csv) → read_csv。
     * 分隔符仅对 CSV 生效(见 {@link #dataFileReader(String, boolean, List, String)})。
     */
    public static String dataFileReader(String path, boolean header, List<String> names) {
        // 未提供分隔符时交给带 delimiter 的重载
        return dataFileReader(path, header, names, null);
    }

    /** 按扩展名返回文件读取表达式(CSV 支持自定义分隔符)。 */
    public static String dataFileReader(String path, boolean header, List<String> names, String delimiter) {
        // 转小写便于匹配扩展名
        String lower = path.toLowerCase();
        if (lower.endsWith(".json") || lower.endsWith(".ndjson") || lower.endsWith(".jsonl")) {
            // JSON 系扩展名走 read_json
            return buildReadJSON(path);
        }
        // 其余扩展名一律当作 CSV 处理(默认 .csv)
        return buildReadCSV(path, header, names, delimiter);
    }

    /** 双引号引用 DuckDB 标识符(内部双引号转义)。 */
    public static String quoteDuckIdent(String name) {
        // 首尾加双引号，内部双引号翻倍转义
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** 校验合法标识符(表名等)。 */
    public static boolean validIdent(String name) {
        // 非空且匹配标识符正则才算合法
        return name != null && IDENT_RE.matcher(name).matches();
    }

    // =====================================================================
    // 核心查询原语
    // =====================================================================

    /** 执行只读查询并返回字符串化结果(内部持有锁)。 */
    private DuckQueryResult query(String sql) {
        // 加全局锁，串行执行读查询
        lock.lock();
        try {
            // 连接未就绪时先校验
            checkReady();
            // 新建结果容器(列信息 + 行数据)
            DuckQueryResult res = new DuckQueryResult();
            // try-with-resources:自动关闭 Statement 与 ResultSet
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                // 取结果集元数据
                ResultSetMetaData md = rs.getMetaData();
                // 结果集列数
                int n = md.getColumnCount();
                // 逐列登记列名与数据库类型名
                for (int i = 1; i <= n; i++) {
                    res.getColumns().add(md.getColumnLabel(i));
                    res.getTypes().add(md.getColumnTypeName(i));
                }
                // 逐行读取查询结果
                while (rs.next()) {
                    // 当前行:各单元格统一转字符串
                    List<String> row = new ArrayList<>(n);
                    // 当前行:保留 JDBC 原始值(供工作流保留数字/日期类型)
                    List<Object> rawRow = new ArrayList<>(n);
                    // 逐列取原始值并分别存字符串形式与原始形式
                    // 两个列表在同一个循环里 append,保证行序与列序严格对齐——工作流 JS 节点
                    // 会用 rawRows 的下标去取 rows 的同一格(见 Engine.nodeCode),错位会静默串行。
                    for (int i = 1; i <= n; i++) {
                        Object value = rs.getObject(i);
                        rawRow.add(value);
                        row.add(stringify(value));
                    }
                    // 追加字符串化行
                    res.getRows().add(row);
                    // 追加原始值行
                    res.getRawRows().add(rawRow);
                }
            }
            // 返回结果对象
            return res;
        } catch (Exception e) {
            // 包装为运行时异常并附带可读根因
            throw new IllegalStateException("DuckDB 查询失败: " + msg(e), e);
        } finally {
            // 释放全局锁
            lock.unlock();
        }
    }

    /** 执行任意写 SQL(建表/INSERT/物化等)。 */
    private void exec(String sql) {
        // 加全局锁
        lock.lock();
        try {
            checkReady();
            // 建立 Statement 执行写语句(不关心返回结果集)
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
        } catch (Exception e) {
            // 包装为运行时异常并附带可读根因
            throw new IllegalStateException("DuckDB 执行失败: " + msg(e), e);
        } finally {
            lock.unlock();
        }
    }

    /** 查询单行首列值。 */
    private Object scalar(String sql) {
        // 加全局锁后执行标量查询
        lock.lock();
        try {
            checkReady();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                // 有结果则取首行首列
                if (rs.next()) {
                    return rs.getObject(1);
                }
                // 无结果返回 null
                return null;
            }
        } catch (Exception e) {
            // 包装为运行时异常并附带可读根因
            throw new IllegalStateException("DuckDB 计数失败: " + msg(e), e);
        } finally {
            lock.unlock();
        }
    }

    /** 校验连接已就绪，未就绪则抛异常。 */
    private void checkReady() {
        // 连接为空说明尚未初始化成功
        if (conn == null) {
            throw new IllegalStateException("duckdb 未初始化");
        }
    }

    // =====================================================================
    // 对外文件读取接口(直接读文件，不建表)
    // =====================================================================

    /** 解析数据文件字段(DESCRIBE SELECT ...)，返回(name,type)列。 */
    public List<Column> describeFile(String path, boolean header, List<String> names) {
        // 未提供分隔符时交给带 delimiter 的重载
        return describeFile(path, header, names, null);
    }

    /** 解析数据文件字段(CSV 支持自定义分隔符)。 */
    // 列类型完全由 DuckDB 的 auto_detect 采样推断(见 buildReadCSV),不是逐行完备扫描:
    // 采样窗口内全为数字的列会被推成整数类型,若后面出现文本,读取时可能报类型转换错误;
    // 反之全空的列会被推成 VARCHAR。这就是“不导入、直接读文件”的固有代价,需要精确类型时
    // 应由数据源的 names/类型配置或后续映射节点显式 CAST 兜住。
    public List<Column> describeFile(String path, boolean header, List<String> names, String delimiter) {
        // 用 DESCRIBE SELECT 让 DuckDB 推断文件各列的字段信息
        String sql = "DESCRIBE SELECT * FROM " + dataFileReader(path, header, names, delimiter);
        // 存放解析出的字段定义
        List<Column> out = new ArrayList<>();
        // 遍历 DESCRIBE 输出的每一行
        for (List<String> row : query(sql).getRows()) {
            // 每行对应一个列定义对象
            Column c = new Column();
            // DESCRIBE 输出前两列:列名、类型(Go 版同样只取前两列)。
            c.setName(row.size() > 0 ? row.get(0) : "");
            c.setType(row.size() > 1 ? row.get(1) : "");
            // 收集该列定义
            out.add(c);
        }
        return out;
    }

    /** 查询数据文件前 limit 行(limit<=0 或 >1000 时取 50)。 */
    public DuckQueryResult previewFile(String path, int limit, boolean header, List<String> names) {
        // 未提供分隔符时交给带 delimiter 的重载
        return previewFile(path, limit, header, names, null);
    }

    /** 查询数据文件前 limit 行(CSV 支持自定义分隔符)。 */
    public DuckQueryResult previewFile(String path, int limit, boolean header, List<String> names, String delimiter) {
        // 修正非法 limit(<=0 或过大统一取 50)
        if (limit <= 0 || limit > 1000) {
            limit = 50;
        }
        // 拼出 SELECT * FROM <文件读取> LIMIT n 并执行
        return query("SELECT * FROM " + dataFileReader(path, header, names, delimiter) + " LIMIT " + limit);
    }

    /** 统计数据文件总行数。 */
    public long countFile(String path, boolean header, List<String> names) {
        // 未提供分隔符时交给带 delimiter 的重载
        return countFile(path, header, names, null);
    }

    /** 统计数据文件总行数(CSV 支持自定义分隔符)。 */
    public long countFile(String path, boolean header, List<String> names, String delimiter) {
        // 用 count(*) 统计文件总行数
        Object v = scalar("SELECT count(*) FROM " + dataFileReader(path, header, names, delimiter));
        // 结果为 null 视作 0，否则取数值并转成 long
        return v == null ? 0L : ((Number) v).longValue();
    }

    /** 数据文件能否被解析(查 1 行，失败即抛异常)。 */
    public void fileReadable(String path, boolean header, List<String> names) {
        // 未提供分隔符时交给带 delimiter 的重载
        fileReadable(path, header, names, null);
    }

    /** 数据文件能否被解析(CSV 支持自定义分隔符)。 */
    public void fileReadable(String path, boolean header, List<String> names, String delimiter) {
        // 查询前 1 行即可验证文件可被解析
        previewFile(path, 1, header, names, delimiter);
    }

    /**
     * 分页按条件查询数据文件。where 为可选条件(列名双引号、字符串单引号)，为空查全部。
     * limit<=0 或 >100000 时默认 10000；offset<=0 省略 OFFSET。
     */
    public DuckQueryResult queryFile(String path, boolean header, List<String> names,
                                     String where, int limit, int offset) {
        // 未提供分隔符时交给带 delimiter 的重载
        return queryFile(path, header, names, null, where, limit, offset);
    }

    /** 分页按条件查询数据文件(CSV 支持自定义分隔符)。 */
    public DuckQueryResult queryFile(String path, boolean header, List<String> names, String delimiter,
                                     String where, int limit, int offset) {
        return queryFile(path, header, names, delimiter, where, limit, offset, null);
    }

    /** 分页查询文件，orderBy 用于增量同步时保证 OFFSET 分页稳定。 */
    public DuckQueryResult queryFile(String path, boolean header, List<String> names, String delimiter,
                                     String where, int limit, int offset, String orderBy) {
        // 修正非法 limit(<=0 或过大统一取 10000)
        if (limit <= 0 || limit > 100000) {
            limit = 10000;
        }
        // 拼接基础 SELECT(直接读取数据文件)
        StringBuilder q = new StringBuilder("SELECT * FROM " + dataFileReader(path, header, names, delimiter));
        // 有非空条件则追加 WHERE
        if (where != null && !where.trim().isEmpty()) {
            q.append(" WHERE ").append(where);
        }
        if (orderBy != null && !orderBy.trim().isEmpty()) {
            // 列名无法参数化(标识符不能走 ? 占位),只能拼进 SQL,所以必须先做白名单校验;
            // 用双引号包裹则保证列名里的特殊字符不会破坏语句结构。
            // 为什么需要 orderBy:OFFSET 分页只有在结果顺序确定时才“不重不漏”,无序读取时
            // 两次相同的查询可能给出不同行序,分页会重复或漏行;代价是大文件排序的开销。
            if (!validIdent(orderBy.trim())) {
                throw new IllegalArgumentException("非法排序列: " + orderBy);
            }
            q.append(" ORDER BY ").append(quoteDuckIdent(orderBy.trim()));
        }
        // 追加 LIMIT
        if (limit > 0) {
            q.append(" LIMIT ").append(limit);
        }
        // offset>0 时追加 OFFSET(实现分页偏移)
        if (offset > 0) {
            q.append(" OFFSET ").append(offset);
        }
        // 执行并返回查询结果
        return query(q.toString());
    }

    /** 文件同步专用游标：独立连接、一次查询，按批次取行，避免 OFFSET 重复扫描。 */
    public FileCursor scanFile(String path, boolean header, List<String> names, String delimiter,
                               String where, String orderBy) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + dataFileReader(path, header, names, delimiter));
        if (where != null && !where.trim().isEmpty()) sql.append(" WHERE ").append(where);
        if (orderBy != null && !orderBy.trim().isEmpty()) {
            if (!validIdent(orderBy.trim())) throw new IllegalArgumentException("非法排序列: " + orderBy);
            sql.append(" ORDER BY ").append(quoteDuckIdent(orderBy.trim()));
        }
        Connection reader;
        lock.lock();
        try {
            if (conn == null) throw new IllegalStateException("duckdb 未初始化");
            // 文件查询无需访问工作流库表。专用内存连接显式启用 JDBC 流式结果，
            // 避免默认模式把完整结果物化后才交给 next()。
            java.util.Properties props = new java.util.Properties();
            props.setProperty(org.duckdb.DuckDBDriver.JDBC_STREAM_RESULTS, "true");
            reader = DriverManager.getConnection("jdbc:duckdb:", props);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("创建文件读取连接失败: " + msg(e), e);
        } finally {
            lock.unlock();
        }
        // 不持有共享锁执行查询或等待下游写入，其他预览请求可继续使用主连接。
        return new FileCursor(reader, sql.toString());
    }

    /** 恢复读取使用原始文本和单线程 CSV 顺序；不把带引号换行误当成两条记录。 */
    public FileCursor scanStableCsv(String path,boolean header,String delimiter) {
        if(!isReady()) throw new IllegalStateException("duckdb 未初始化");
        Connection reader=null;
        try {
            java.util.Properties props=new java.util.Properties();
            props.setProperty(org.duckdb.DuckDBDriver.JDBC_STREAM_RESULTS,"true");
            reader=DriverManager.getConnection("jdbc:duckdb:",props);
            try(Statement statement=reader.createStatement()) {
                statement.execute("SET threads=1");
                statement.execute("SET preserve_insertion_order=true");
            }
            return new FileCursor(reader,"SELECT * FROM read_csv('"+sqlPath(path)+"', auto_detect=true, header="+header
                    +", all_varchar=true, parallel=false"+csvDelimSql(delimiter)+")");
        } catch(Exception e) {
            if(reader!=null) try { reader.close(); } catch(Exception ignored) { }
            throw new IllegalStateException("创建可恢复文件游标失败",e);
        }
    }

    /** 兼容入口保留旧 CSV 规则；新目录路径显式调用带编码参数的重载。 */
    public FileCursor scanDirectoryCsv(String path, boolean header, String delimiter) {
        return scanDirectoryCsv(path, header, delimiter, true);
    }

    public FileCursor scanDirectoryCsv(String path, boolean header, String delimiter, boolean ignoreExtraColumns) {
        return scanDirectoryCsv(path, header, delimiter, ignoreExtraColumns, true);
    }

    /** v2 读取器在忽略额外尾列时还会修复数据行开头连续的空占位列；v1 断点传 false 保持原输出。 */
    public FileCursor scanDirectoryCsv(String path, boolean header, String delimiter, boolean ignoreExtraColumns,
                                       boolean repairLeadingEmptyColumns) {
        return new FileCursor(com.el2ck.util.CsvTable.open(path, header, delimiter, ignoreExtraColumns,
                repairLeadingEmptyColumns));
    }

    /** 无旧断点的目录导入，与预览共用新版解析和编码策略。 */
    public FileCursor scanDirectoryCsv(String path, boolean header, String delimiter, boolean ignoreExtraColumns,
                                       String encoding) {
        return new FileCursor(com.el2ck.util.ModernCsvTable.open(path, header, delimiter, ignoreExtraColumns, encoding));
    }

    public static final class FileCursor implements AutoCloseable {
        private final Connection connection;
        private com.el2ck.util.CsvRows csv;
        private Statement statement;
        private ResultSet result;
        private int columns;
        private final List<Column> schema=new ArrayList<>();
        private boolean exhausted;
        private boolean closed;

        public FileCursor(com.el2ck.util.CsvRows csv) {
            connection = null;
            this.csv = csv;
            schema.addAll(csv.columns());
            columns = schema.size();
        }

        private FileCursor(Connection connection, String sql) {
            this.connection = connection;
            try {
                statement = connection.createStatement();
                result = statement.executeQuery(sql);
                columns = result.getMetaData().getColumnCount();
                for(int i=1;i<=columns;i++) {
                    Column column=new Column(); column.setName(result.getMetaData().getColumnLabel(i));
                    column.setType(result.getMetaData().getColumnTypeName(i)); schema.add(column);
                }
            } catch (Exception e) {
                close();
                throw new IllegalStateException("读取源文件失败: " + msg(e), e);
            }
        }


        public List<List<String>> next(int batchSize) {
            if (batchSize <= 0 || batchSize > 100000) throw new IllegalArgumentException("非法批次大小");
            if (exhausted) return java.util.Collections.emptyList();
            if (closed) throw new IllegalStateException("文件游标已关闭");
            List<List<String>> rows = new ArrayList<>();
            try {
                if (csv != null) {
                    rows = csv.next(batchSize);
                    if (rows.size() < batchSize) { exhausted = true; close(); }
                    return rows;
                }
                while (rows.size() < batchSize) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("文件读取已取消");
                    if (!result.next()) {
                        exhausted = true;
                        close();
                        break;
                    }
                    List<String> row = new ArrayList<>(columns);
                    for (int i = 1; i <= columns; i++) row.add(stringify(result.getObject(i)));
                    rows.add(row);
                }
                return rows;
            } catch (Exception e) {
                close();
                throw new IllegalStateException("读取源文件失败: " + msg(e), e);
            }
        }


        public List<Column> columns() { return new ArrayList<>(schema); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            for (AutoCloseable resource : new AutoCloseable[] {csv, result, statement, connection}) {
                if (resource != null) {
                    try { resource.close(); } catch (Exception ignored) { }
                }
            }
        }
    }

    /** 分页查询已存在的表(工作流物化结果读取用)。 */
    public DuckQueryResult queryTable(String table, String where, int limit, int offset) {
        // 表名不合法直接拒绝(防注入)
        // 工作流临时表名由引擎生成(wf_tmp_<序号>_<清洗后的节点ID>),一定落在合法标识符集合内,
        // 因此白名单校验不会误伤,却能挡住任何外部拼入的表名。
        if (!validIdent(table)) {
            throw new IllegalArgumentException("非法表名 \"" + table + "\"");
        }
        // 修正非法 limit(<=0 或过大统一取 10000)
        if (limit <= 0 || limit > 100000) {
            limit = 10000;
        }
        // 拼接基础 SELECT
        StringBuilder q = new StringBuilder("SELECT * FROM " + table);
        // 有非空条件则追加 WHERE
        if (where != null && !where.trim().isEmpty()) {
            q.append(" WHERE ").append(where);
        }
        // 追加 LIMIT
        if (limit > 0) {
            q.append(" LIMIT ").append(limit);
        }
        // offset>0 时追加 OFFSET(实现分页偏移)
        if (offset > 0) {
            q.append(" OFFSET ").append(offset);
        }
        // 执行并返回查询结果
        return query(q.toString());
    }

    // =====================================================================
    // 工作流 · 同步辅助
    // =====================================================================

    /** 把一条 SELECT 物化为表(CREATE OR REPLACE TABLE t AS ...)，返回物化行数。 */
    public long materialize(String table, String selectSql) {
        // 表名不合法直接拒绝
        if (!validIdent(table)) {
            throw new IllegalArgumentException("非法表名 \"" + table + "\"");
        }
        // 物化:建表(不存在则建，存在则替换)并写入 SELECT 结果
        // 用 OR REPLACE 而不是先 DROP 再 CREATE:重跑同一节点(同名表)时一条语句即覆盖,
        // 少一次往返也不会有“删了但没建成”的中间态;表名含节点 ID,不会覆盖到别的节点。
        exec("CREATE OR REPLACE TABLE " + table + " AS " + selectSql);
        // 统计物化后的总行数
        // 额外一次 count(*):行数要随 NodeResult 上报,物化本身不返回行数;这是对刚写入的
        // 本地表做统计,代价远小于上游那次计算。
        Object v = scalar("SELECT count(*) FROM " + table);
        // null 按 0 处理，否则取数值转 long
        return v == null ? 0L : ((Number) v).longValue();
    }

    /** 执行任意 SQL(建表/INSERT 等，工作流代码块输出用)。 */
    public void execRaw(String sql) {
        // 透传给内部 exec 执行
        exec(sql);
    }

    /** 删除表(存在才删)。 */
    public void dropTable(String table) {
        // 表名不合法则直接忽略
        if (!validIdent(table)) {
            return;
        }
        // 拼 DROP TABLE IF EXISTS 删除指定表
        exec("DROP TABLE IF EXISTS " + table);
    }

    /** 检查表是否存在。 */
    public boolean tableExists(String table) {
        // 表名不合法视为不存在
        if (!validIdent(table)) {
            return false;
        }
        try {
            // 查询系统目录表，使用参数化语句防注入
            // 这是本类里唯一用 PreparedStatement 的地方:表名是变量而非标识符位置,参数化既能
            // 防注入,也避免为非法表名抛异常(不合法直接返回 false,见上)。
            Object v = queryOneScalar("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", table);
            // 计数大于 0 说明表存在
            return v != null && ((Number) v).longValue() > 0;
        } catch (Exception e) {
            // 查询异常时保守返回 false
            return false;
        }
    }

    /** 带参查询单值，用于表存在性检查(DuckDB JDBC 支持参数化)。 */
    private Object queryOneScalar(String sql, String arg) {
        // 加全局锁执行
        lock.lock();
        try {
            checkReady();
            // 预编译语句，绑定参数防注入
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // 把第一个参数绑定为字符串
                ps.setString(1, arg);
                // 执行查询并取首行首列
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getObject(1);
                    }
                    // 无结果返回 null
                    return null;
                }
            }
        } catch (Exception e) {
            // 包装为运行时异常并附带可读根因
            throw new IllegalStateException("DuckDB 查询失败: " + msg(e), e);
        } finally {
            // 释放全局锁
            lock.unlock();
        }
    }

    // =====================================================================
    // CSV 行头自动检测(启发式，同 Go DetectCSVHeader)
    // =====================================================================

    /**
     * 自动检测 CSV 是否有表头:读前 2 行，第 1 行全为非数字文本且第 2 行含数字 → 有表头。
     * 单行文件:含数字更像数据，无表头；全文本保守判有表头；读取失败默认有表头。
     */
    public static boolean detectCsvHeader(String path) {
        // 存放读取到的文件头若干行
        // 只看前 2 行:这是个启发式判断,读全文件既慢又没必要;误判由用户显式配置 header 纠正。
        List<String> lines = new ArrayList<>();
        // 以 UTF-8 打开文件读取
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String ln;
            // 最多累积读取 2 行
            while (lines.size() < 2 && (ln = br.readLine()) != null) {
                lines.add(ln);
            }
        } catch (Exception e) {
            // 读取失败:保守认为有表头
            return true;
        }
        // 空文件同样保守判为有表头
        if (lines.isEmpty()) {
            return true;
        }
        // 解析首行字段
        List<String> row0 = parseCsvLine(lines.get(0));
        // 首行为空(空行或唯一字段为空)判有表头
        if (row0.isEmpty() || row0.size() == 1 && row0.get(0).isEmpty()) {
            return true;
        }
        if (lines.size() < 2) {
            // 单行:含数字→无表头(数据)；全文本→保守判有表头。
            return !hasNumeric(row0);
        }
        // 解析第二行字段
        List<String> row1 = parseCsvLine(lines.get(1));
        // 首行全为非数字文本且第二行含数字，判定存在表头
        return !hasNumeric(row0) && hasNumeric(row1);
    }

    /** 判断字段中是否至少有一个可解析为数值。 */
    private static boolean hasNumeric(List<String> fields) {
        // 逐个字段尝试解析
        for (String v : fields) {
            try {
                // 能解析成 Double 说明该字段是数值
                Double.parseDouble(v.trim());
                return true;
            } catch (NumberFormatException ignored) {
                // 该字段非数值，继续看下一个。
            }
        }
        // 全部字段均非数值
        return false;
    }

    /** 解析单行 CSV(支持双引号字段与内嵌逗号/换行语义的简化版)。 */
    // 边界:① 只认逗号,不认数据源配置的自定义分隔符——对 TSV 之类会整行当成一个字段,
    // 检测结果随之失真;② 按“单行”解析,引号内的换行不被支持(需要跨行状态);
    // ③ 不做 BOM 剥离。它只服务于“CSV 首行是否表头”的启发式判断,故按最小实现处理。
    private static List<String> parseCsvLine(String line) {
        // 存放拆分出的字段
        List<String> out = new ArrayList<>();
        // 累积当前字段内容
        StringBuilder cur = new StringBuilder();
        // 是否处于双引号字符串内部
        boolean inQuotes = false;
        // 逐字符扫描整行
        for (int i = 0; i < line.length(); i++) {
            // 取当前字符
            char ch = line.charAt(i);
            if (inQuotes) {
                // 引号串内部:处理引号结束
                if (ch == '"') {
                    // 引号内遇双引号:连续两个 " 表示转义引号。
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        // 单个引号表示字符串结束
                        inQuotes = false;
                    }
                } else {
                    // 普通字符直接累积进字段
                    cur.append(ch);
                }
            } else {
                // 引号串外:遇到引号开始新字符串
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    // 逗号分隔:收尾当前字段并清空缓冲
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    // 普通字符直接累积进字段
                    cur.append(ch);
                }
            }
        }
        // 行尾收尾最后一个字段
        out.add(cur.toString());
        return out;
    }

    // =====================================================================
    // 单单元格字符串化
    // =====================================================================

    /** 单单元格值转字符串:nil→"";[]byte→string;时间→"yyyy-MM-dd HH:mm:ss";其余 String.valueOf。 */
    // 与 CkClient.normalize 的差别在 null 的处理:这里把 null 统一成 ""(文件/CSV 语境下缺失值
    // 就是空文本,下游按字符串处理);CK 侧则保留 null,以便前端区分“空值”与“空字符串”。
    private static String stringify(Object v) {
        // null 值统一转空串
        if (v == null) {
            return "";
        }
        // 已是字符串则原样返回
        if (v instanceof String) {
            return (String) v;
        }
        // []byte 按 UTF-8 解码为字符串
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.UTF_8);
        }
        // java.sql.Timestamp 按统一日期时间格式格式化
        if (v instanceof Timestamp) {
            return new java.text.SimpleDateFormat(DT_FMT).format((Timestamp) v);
        }
        // java.sql.Date 按统一日期时间格式格式化
        if (v instanceof java.sql.Date) {
            return new java.text.SimpleDateFormat(DT_FMT).format((java.sql.Date) v);
        }
        // java.time.LocalDateTime 按统一格式格式化
        if (v instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) v).format(java.time.format.DateTimeFormatter.ofPattern(DT_FMT));
        }
        // DATE 没有时分秒，不能用包含 HourOfDay 的日期时间格式。
        if (v instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) v).toString();
        }
        // 通用 java.util.Date 也按统一格式格式化
        if (v instanceof java.util.Date) {
            return new java.text.SimpleDateFormat(DT_FMT).format((java.util.Date) v);
        }
        // 其余类型交给 String.valueOf 兜底
        return String.valueOf(v);
    }

    /** 提取异常链中第一条非空消息(必要时回退到 e.toString())。 */
    private static String msg(Exception e) {
        // 从当前异常开始向上追溯
        Throwable t = e;
        // 找到第一个带非空消息的根因
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            t = t.getCause();
        }
        // 取出该条消息
        String m = t.getMessage();
        // 消息仍为空则退回异常的 toString
        return m == null || m.isEmpty() ? e.toString() : m;
    }
}
