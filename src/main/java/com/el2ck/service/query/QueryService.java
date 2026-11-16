/**
 * 【文件职责】本文件定义查询执行模块的业务服务 QueryService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.query;

import com.el2ck.service.common.RowOpener;
import com.el2ck.service.connection.ConnectionService;
import com.el2ck.service.table.TableService;

import com.el2ck.exception.ApiException;
import com.el2ck.util.CkColumns;
import com.el2ck.util.Csv;
import com.el2ck.util.Sql;
import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.table.ColumnAliasHint;
import com.el2ck.entity.query.ProgColumn;
import com.el2ck.entity.query.QueryExportRequest;
import com.el2ck.entity.query.QueryRequest;
import com.el2ck.entity.query.QueryResponse;
import com.el2ck.workflow.ProgOps;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 控制台执行服务(对应 Go internal/service/query_service.go)。
 *
 * 语义要点:
 *  1. 手写词法器按分号拆成多条语句,逐条执行;
 *  2. 只读白名单(SELECT/SHOW/DESCRIBE/DESC/EXPLAIN/EXISTS/WITH)返回结果集,
 *     写语句需显式 allowWrite 且连接非只读,否则拦截;
 *  3. 任一结果集行数超过 {@link #MAX_RESULT_ROWS}=1000 时截断并置 truncated=true(前端提示);
 *     传了 pageSize 则改为服务端分页(不截断,附带 count() 得到的总行数);
 *  4. 全部写语句成功时在 messages 追加「执行成功」;
 *  5. 可视化链路额外做两件事:按计划应用程序算子(SM3/3DES 等)、回填结果列的字段注释。
 */
public class QueryService {

    /** 单条只读语句返回的最大行数,超出则截断并标记。对应 Go maxResultRows。 */
    private static final int MAX_RESULT_ROWS = 1000;

    /**
     * 非分页读取把 LIMIT 下推到语句末尾,让引擎自己少吐数据(对齐 Go query_service.go 的 pushdownLimit)。
     *
     * <p>⚠️ 只对 SELECT / WITH({@link Sql#isWrappableSelect})动手:只读关键字还含
     * SHOW / DESCRIBE / DESC / EXPLAIN / EXISTS,给它们追加 LIMIT 会变语法错
     * (如 {@code DESCRIBE TABLE t LIMIT 1001})。已含 LIMIT、或以 FORMAT / SETTINGS 结尾的语句
     * 也不动 —— 追加会破坏语法(SETTINGS 必须排在最后)。
     */
    static String pushdownLimit(String st, int n) {
        if (st == null || !Sql.isWrappableSelect(st)) {
            return st;
        }
        String up = st.toUpperCase(java.util.Locale.ROOT);
        if (up.contains(" LIMIT ") || up.contains(" FORMAT ") || up.contains(" SETTINGS ")) {
            return st;
        }
        return st + " LIMIT " + n;
    }

    /** 分页模式下每页最大行数。对应 Go maxPageSize。 */
    private static final int MAX_PAGE_SIZE = 1000;

    /**
     * 深分页保护:OFFSET 超过此值时截断,避免 CK 扫描并丢弃海量前缀行。
     * 10000 行 ≈ 10 页 × 1000 或 100 页 × 100,覆盖绝大多数浏览场景。对应 Go maxOffset。
     */
    private static final int MAX_OFFSET = 10000;

    /** 分页取数的子查询别名。对应 Go 的 {@code __el2ck_pg}。 */
    private static final String PAGE_SUB_ALIAS = "__el2ck_pg";

    /** 总行数缓存的存活时间。短 TTL 是刻意的:数据可能随时变,但同一批翻页拿到的一致 total 是值得的。 */
    private static final long COUNT_TTL_MS = 30_000L;

    /** 连接服务:供取生效配置、只读判定与客户端。 */
    private final ConnectionService connSvc;

    /** 当前用户的真实 CK 业务身份;任意 SQL 也只拥有该账号的表级权限。 */
    private final com.el2ck.service.ck.CkIdentityService identities;

    /**
     * 分页时的总行数缓存。
     *
     * <p>为什么需要:分页每页都要先跑一次 {@code SELECT count() FROM (...)},而数据探索
     * 每次翻页都是同一份 SQL —— 等于每翻一页把用户的查询跑两遍。
     * 键含 SQL 全文 + 库 + 连接,TTL 很短,只用于吸收同一批翻页。
     */
    private final Map<String, long[]> countCache = new LinkedHashMap<String, long[]>();
    private final com.el2ck.service.table.TableReferenceService tableReferences;
    /**
     * 创建ClickHouse 查询执行服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param connSvc ClickHouse 连接服务，负责解析连接并按执行身份创建客户端
     * @param identities ClickHouse 用户身份服务，用于按任务所有者或当前用户解析执行身份
     * @param tableReferences 执行中的表引用与手动写操作保护
     */
    public QueryService(ConnectionService connSvc, com.el2ck.service.ck.CkIdentityService identities,
                         com.el2ck.service.table.TableReferenceService tableReferences) {
        // 保存连接服务引用
        this.connSvc = connSvc;
        this.identities = identities;
        this.tableReferences = tableReferences;
    }

    /**
     * 执行 SQL(可含多条语句,自动拆分逐条执行)。
     *
     * @throws ApiException 400 SQL 为空 / 写操作未勾选;403 只读模式;502 目标库查询/执行失败
     */
    public QueryResponse execute(long connId, QueryRequest req) {
        // 请求体不允许为空
        if (req == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 取 SQL 文本,null 视作空串
        String sqlText = req.getSql() == null ? "" : req.getSql();
        // 1. 拆分语句,去掉尾部多余分号与空白,丢弃空语句。
        List<String> list = new ArrayList<>();
        // 用 Sql 手写词法器按分号拆成多条
        for (String st : Sql.splitStatements(sqlText)) {
            // 去掉该条语句尾部多余的分号
            st = Sql.trimTrailingSemicolons(st);
            rejectUnsafe(st);
            // 丢弃拆出的空语句
            if (!st.isEmpty()) {
                list.add(st);
            }
        }
        // 拆分后没有有效语句则报参数错误
        if (list.isEmpty()) {
            throw ApiException.validation("SQL 不能为空");
        }

        // 2. 取生效配置(判断只读模式)。Go 在这里叠加 query 超时,Java 侧由驱动超时兜底。
        CkConfig cfg = identities.config(connId);
        // 是否勾选「允许写操作」
        boolean allowWrite = Boolean.TRUE.equals(req.getAllowWrite());
        // 取得目标连接客户端
        CkClient client = identities.client(connId);

        // 初始化响应对象
        QueryResponse resp = new QueryResponse();
        // 默认整体非只读(有待读语句再置位)
        resp.setReadOnly(false);
        // 默认未发生行数截断
        resp.setTruncated(false);
        // 结果集列表
        resp.setResults(new ArrayList<QueryResult>());
        // 提示消息列表(写语句成功时追加)
        resp.setMessages(new ArrayList<String>());
        // 与结果集一一对应的原始语句(供单条结果导出时取回它的 SQL)
        resp.setResultStatements(new ArrayList<String>());

        // 是否走服务端分页:传了 pageSize 才启用,否则沿用「截断到 1000 行 + truncated 标记」的老语义
        boolean paged = req.getPageSize() > 0;

        // 3. 逐条执行。注意：不做事务/回滚，中途某条失败时前面已执行成功的语句仍然生效；
        // 此时直接抛出异常，已收集的结果集不会随响应返回。
        for (String st : list) {
            // 只读语句走查询分支
            if (Sql.isReadStatement(st)) {
                QueryResult res;
                if (paged && Sql.isWrappableSelect(st)) {
                    // 分页:包成子查询取一页,total 为全部行数(不截断)
                    res = queryPaged(client, st, req.getPage(), req.getPageSize(),
                            com.el2ck.config.AuthContext.userId() + "|" + connId + "|" + def(req.getDatabase()) + "|" + st);
                } else {
                    // 只读:执行并截断到 1000 行。
                    // 走 queryReadOnly 而非直接 query:结果列里有聚合状态列(SimpleAggregateFunction)时
                    // 要改写成显式投影,否则驱动解析元数据就抛 Unknown data type。
                    // 把 LIMIT 下推给引擎(对齐 Go pushdownLimit):非分页路径以前是全量载入(驱动侧
                    // max_result_rows=10000)再在内存里截到 MAX_RESULT_ROWS,宽表时白读一大截。
                    res = queryReadOnly(client, pushdownLimit(st, MAX_RESULT_ROWS + 1));
                    // 结果总行数超上限时需要截断
                    if (res.getTotal() != null && res.getTotal() > MAX_RESULT_ROWS) {
                        // 只保留前 MAX_RESULT_ROWS 行
                        res.setRows(new ArrayList<List<Object>>(res.getRows().subList(0, MAX_RESULT_ROWS)));
                        // total 改写为截断后的行数，前端据此展示；「被截断」的事实由 truncated 标记传达
                        res.setTotal(MAX_RESULT_ROWS);
                        // 任一条截断则整体标记 truncated
                        resp.setTruncated(true);
                    }
                }
                // 存在只读语句,整体响应标记为只读语义
                resp.setReadOnly(true);
                // 结果集加入响应
                resp.getResults().add(res);
                // 记录与该结果集对应的语句
                resp.getResultStatements().add(st);
            } else {
                // 写:只读连接与未勾选 allowWrite 双重闸门。
                // 只读连接禁止写操作
                if (cfg.isReadOnly()) {
                    throw ApiException.readOnly("当前连接为只读模式,不允许写操作");
                }
                // 未勾选允许写时拦截
                if (!allowWrite) {
                    throw ApiException.validation("检测到写操作(非 SELECT),请勾选「允许写操作」后执行");
                }
                try (com.el2ck.repository.metadata.TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
                    tableReferences.requireSqlModificationAllowed(connId);
                    gate.check();
                    exec(client, st);
                }
                // 追加执行成功提示
                resp.getMessages().add("执行成功");
            }
        }

        // 4. 程序算子后处理。
        //
        // ⚠️ 只作用于第一条结果集 —— 可视化构建器生成的是单条 SELECT。
        //    多语句时静默跳过其余结果集会让「配了 SM3 的列」出现在第 2 条结果里却是明文,
        //    所以这里显式拒绝,而不是悄悄放过。
        List<ProgColumn> progCols = req.getProgCols();
        if (progCols != null && !progCols.isEmpty()) {
            if (resp.getResults().size() > 1) {
                throw ApiException.validation(String.format(
                        "程序算子(SM3/3DES 等)只支持单条 SELECT,当前有 %d 条结果集", resp.getResults().size()));
            }
            if (!resp.getResults().isEmpty()) {
                QueryResult first = resp.getResults().get(0);
                ProgOps.applyProg(first.getColumns(), first.getRows(), progCols);
            }
        }

        // 5. 结果列的表字段注释(界面在列名旁展示)。
        //
        // ⚠️ 取注释失败【不当错误】(见 columnComments 的实现):它只是展示信息,
        //    不该因为取不到注释就让整个查询失败。
        if (!resp.getResults().isEmpty()) {
            List<String> cols = collectResultColumns(resp.getResults());
            if (!cols.isEmpty()) {
                resp.setColumnComments(columnComments(client, req.getDatabase(), cols));
            }
            // 可视化链路:再按配置里的「别名 → 源字段」精确取一遍,【覆盖】上面按列名猜的结果。
            // 改了别名的输出列(如 mdn → 手机号)只能靠这一步拿到注释。
            //
            // ⚠️ 必须判空再判空集:没有别名提示(手写 SQL)或者一条注释都没查到时,
            //    这个方法返回的是 null —— 直接 byField.isEmpty() 会让【SQL 工作台的每次查询】
            //    都 500(Cannot invoke "Map.isEmpty()" because "byField" is null)。
            Map<String, String> byField = columnCommentsByField(client, req.getColumnAliases());
            if (byField != null && !byField.isEmpty()) {
                if (resp.getColumnComments() == null) {
                    resp.setColumnComments(new LinkedHashMap<String, String>());
                }
                resp.getColumnComments().putAll(byField);
            }
        }
        return resp;
    }

    /** 汇总所有结果集的列名(去重、保持出现顺序)。 */
    private static List<String> collectResultColumns(List<QueryResult> results) {
        List<String> out = new ArrayList<String>();
        for (QueryResult r : results) {
            if (r.getColumns() == null) {
                continue;
            }
            for (String c : r.getColumns()) {
                if (c != null && !c.isEmpty() && !out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * 取这些列名在 ClickHouse 里的表字段注释(只返回有注释的)。
     *
     * <p>实现上用一条元数据查询:
     * <ul>
     *   <li>{@code name IN (...)} 把范围限到结果列上,不扫无关表的列;</li>
     *   <li>{@code argMax(comment, database = ?)} 在同名列出现在多个库时【优先当前库】的注释
     *       (同名不同注释时结果存在不确定性 —— 所以它只用于展示);</li>
     *   <li>列特别多时截断:IN 上千个既慢又没意义(界面也不过来同一屏看这么多列)。</li>
     * </ul>
     *
     * <p>⚠️ 实测踩过的坑:聚合结果的别名【不能叫 comment】。
     * ClickHouse 在 WHERE 里会先解析 SELECT 别名,于是
     * {@code SELECT argMax(comment, ...) AS comment ... WHERE comment != ''}
     * 的 WHERE 会被当成「聚合函数出现在 WHERE 里」,直接报
     * {@code Code: 184 ... ILLEGAL_AGGREGATION}。所以别名用 cmt。
     *
     * <p>任何失败都返回空结果 —— 注释只是展示信息,不该让查询失败。
     */
    private Map<String, String> columnComments(CkClient client, String database, List<String> cols) {
        final int maxLookup = 500;
        List<String> limited = cols.size() > maxLookup ? cols.subList(0, maxLookup) : cols;
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        args.add(database == null ? "" : database);
        for (String c : limited) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append("?");
            args.add(c);
        }
        String sql = "SELECT name, argMax(comment, database = ?) AS cmt FROM system.columns "
                + "WHERE comment != '' AND name IN (" + in + ") GROUP BY name";
        QueryResult res;
        try {
            res = client.query(sql, args);
        } catch (RuntimeException e) {
            return null;
        }
        if (res == null || res.getRows() == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (List<Object> row : res.getRows()) {
            if (row.size() < 2) {
                continue;
            }
            String name = String.valueOf(row.get(0));
            String cmt = row.get(1) == null ? "" : String.valueOf(row.get(1));
            if (!name.isEmpty() && !"null".equals(name) && !cmt.isEmpty() && !"null".equals(cmt)) {
                out.put(name, cmt);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 按【库.表.列】精确取注释,返回 别名 → 注释。
     *
     * <p>与 {@link #columnComments} 的区别:同一个列名可能出现在多张表里,注释还可能不同 ——
     * 按列名猜会挂错或挂不上;这里用的是配置里给出的【确切来源】,
     * 所以改了别名的输出列也能拿到注释。
     */
    private Map<String, String> columnCommentsByField(CkClient client, List<ColumnAliasHint> hints) {
        final int maxHints = 500;
        if (hints == null || hints.isEmpty()) {
            return null;
        }
        List<ColumnAliasHint> uniq = new ArrayList<ColumnAliasHint>();
        for (ColumnAliasHint h : hints) {
            if (isBlank(h.getAlias()) || isBlank(h.getDatabase()) || isBlank(h.getTable())
                    || isBlank(h.getColumn()) || uniq.contains(h)) {
                continue;
            }
            uniq.add(h);
            if (uniq.size() >= maxHints) {
                break;
            }
        }
        if (uniq.isEmpty()) {
            return null;
        }
        // 元组 IN 配合占位符:库表列都来自配置(非用户可自由输入的 SQL 片段),
        // 但仍旧走绑定参数,不让它们拼进语句。
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        for (ColumnAliasHint h : uniq) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append("(?,?,?)");
            args.add(h.getDatabase());
            args.add(h.getTable());
            args.add(h.getColumn());
        }
        QueryResult res;
        try {
            res = client.query("SELECT database, table, name, comment FROM system.columns "
                    + "WHERE comment != '' AND (database, table, name) IN (" + in + ")", args);
        } catch (RuntimeException e) {
            return null;
        }
        if (res == null || res.getRows() == null) {
            return null;
        }
        Map<String, String> byField = new HashMap<String, String>();
        for (List<Object> row : res.getRows()) {
            if (row.size() < 4 || row.get(3) == null) {
                continue;
            }
            String cmt = String.valueOf(row.get(3));
            if (cmt.isEmpty()) {
                continue;
            }
            byField.put(fieldKey(row.get(0), row.get(1), row.get(2)), cmt);
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (ColumnAliasHint h : uniq) {
            String cmt = byField.get(fieldKey(h.getDatabase(), h.getTable(), h.getColumn()));
            if (cmt != null) {
                out.put(h.getAlias(), cmt);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 拼 (库,表,列) 的查找键。
     *
     * <p>用 NUL 作分隔符:库名 / 表名 / 列名里不可能出现它,不会出现
     * ("a","bc") 与 ("ab","c") 撞成同一个键的边界问题。
     */
    private static final char FIELD_SEP = (char) 0;

    /**
     * 构造由库、表、列组成的无歧义字段键。
     *
     * <p>使用 NUL 字符分隔三个部分，避免普通拼接在名称边界处发生碰撞；{@code null} 按空串处理。
     *
     * @param db 库名
     * @param table 表名
     * @param col 列名
     * @return 可直接用于 Map 查找的字段键
     */
    private static String fieldKey(Object db, Object table, Object col) {
        return str(db) + FIELD_SEP + str(table) + FIELD_SEP + str(col);
    }

    /** null 安全的字符串化。 */
    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 平台不开放外部表函数与服务器文件写入;真实 CK 权限也不应为此放宽。
     * 用户仍可在已授权的表上读写/聚合,但不能借 URL/File/S3 等通道绕过表级隔离。
     */
    private static final java.util.regex.Pattern UNSAFE_SQL = java.util.regex.Pattern.compile(
            "(?is)(?:\\bINTO\\s+OUTFILE\\b|\\b(?:url|file|s3|s3queue|hdfs|remote|remotesecure|mysql|postgresql|mongodb|jdbc|odbc|executable)\\s*\\()");
    /**
     * 拒绝包含平台禁止的外部数据访问或文件写入语法的 SQL。
     *
     * <p>检查 {@code INTO OUTFILE} 及 URL、File、S3、Remote、MySQL 等表函数。命中时返回
     * 403，防止绕过已授权的表级访问边界；该检查是平台防护的一部分，不替代 ClickHouse
     * 自身权限控制。
     *
     * @param sql 待检查的 SQL
     */
    private static void rejectUnsafe(String sql) {
        if (UNSAFE_SQL.matcher(sql).find()) {
            throw new ApiException(403, "SQL 包含平台禁止的外部数据访问或文件写入子句");
        }
    }

    /** 判空(null 或全空白)。 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 分页取一页:包裹用户 SQL,并额外 count() 得总行数(Total = 全部行数)。 */
    private QueryResult queryPaged(CkClient client, String st, int page, int pageSize, String cacheKey) {
        int size = pageSize;
        if (size <= 0) {
            size = 100;
        }
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        int no = page <= 0 ? 1 : page;
        int offset = (no - 1) * size;
        if (offset > MAX_OFFSET) {
            offset = MAX_OFFSET;
        }
        // 包好的子查询(LIMIT/OFFSET 拼在它外面;用户 SQL 原样作为子查询执行)
        String sub = Sql.wrapSubquery(st, PAGE_SUB_ALIAS);

        // 总行数(带短 TTL 缓存 —— 同一批翻页不重复跑 count)
        Integer cached = countCached(cacheKey);
        int total;
        if (cached != null) {
            total = cached;
        } else {
            QueryResult totalRes = query(client, "SELECT count() FROM " + sub);
            total = 0;
            if (totalRes.getRows() != null && !totalRes.getRows().isEmpty()
                    && !totalRes.getRows().get(0).isEmpty()) {
                total = toInt(totalRes.getRows().get(0).get(0));
            }
            countStore(cacheKey, total);
        }
        // 结果列里若有聚合状态列,不能发 SELECT *(驱动解析元数据就抛 Unknown data type)——
        // 换成显式投影;没有则为 null,维持原来的 SELECT * 不变。
        String proj = aggregateStateProjection(client, st);
        QueryResult res = query(client, "SELECT " + (proj == null ? "*" : proj)
                + " FROM " + sub + " LIMIT " + size + " OFFSET " + offset);
        // Total 语义改为「全部行数」(而非本次返回行数),供前端分页计算
        res.setTotal(total);
        return res;
    }

    /** 读总行数缓存;未命中或已过期返回 null。 */
    private Integer countCached(String key) {
        synchronized (countCache) {
            long[] entry = countCache.get(key);
            if (entry == null || System.currentTimeMillis() > entry[1]) {
                if (entry != null) {
                    countCache.remove(key);
                }
                return null;
            }
            return (int) entry[0];
        }
    }

    /** 写总行数缓存;顺手清理过期项,避免 map 无界增长。 */
    private void countStore(String key, int total) {
        synchronized (countCache) {
            if (countCache.size() > 256) {
                long now = System.currentTimeMillis();
                List<String> expired = new ArrayList<String>();
                for (Map.Entry<String, long[]> e : countCache.entrySet()) {
                    if (now > e.getValue()[1]) {
                        expired.add(e.getKey());
                    }
                }
                for (String k : expired) {
                    countCache.remove(k);
                }
            }
            countCache.put(key, new long[]{total, System.currentTimeMillis() + COUNT_TTL_MS});
        }
    }

    /** 把驱动返回的数值转 int(ClickHouse count() 为 UInt64)。 */
    private static int toInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 只读查询:目标库失败 → 502。 */
    private QueryResult query(CkClient client, String sql) {
        try {
            // 在目标 ClickHouse 执行只读查询
            return client.query(sql);
        } catch (RuntimeException e) {
            // 查询失败统一包装为 502
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    // =====================================================================
    // 聚合状态列的自动改写(housepower 驱动不认识 SimpleAggregateFunction)
    // =====================================================================

    /**
     * 探出用户 SQL 的结果列(名字 + 类型),按结果顺序。
     *
     * <p>为什么不用 {@code SELECT * … LIMIT 0} 探列:那个 {@code *} 会把结果列的<b>类型</b>原样带
     * 回来,而 housepower 驱动不认识 {@code SimpleAggregateFunction}(多源仲裁工作流的聚合表业务列
     * 全是它)—— 驱动在解析结果元数据时就抛 {@code Unknown data type},连「先看看有哪些列」这一步
     * 都过不去。{@code DESCRIBE (子查询)} 的结果列恒为 String,绕开了这个坑(真机实测)。
     *
     * @return {@code {列名, 类型}} 清单;没有结果列时返回 null
     * @param alias 子查询别名,由调用方按其上下文给(与它真正执行时用的那个保持一致,便于对读)
     * @throws com.el2ck.exception.ApiException 预检失败(502) —— 由调用方决定当致命错误还是降级
     */
    private List<String[]> resultColumns(CkClient client, String st, String alias) {
        // 复用包裹写法:末尾留换行的坑(行尾注释吃掉右括号)已在那里处理过。
        QueryResult desc = query(client, "DESCRIBE " + Sql.wrapSubquery(st, alias));
        List<String[]> out = new ArrayList<>();
        for (List<Object> row : desc.getRows()) {
            // DESCRIBE 的每行是 name / type / default_type / … ,前两列就是我们需要的。
            if (row.size() >= 2) {
                out.add(new String[]{String.valueOf(row.get(0)), String.valueOf(row.get(1))});
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 用户 SQL 的结果列里有驱动读不了的聚合状态列时,返回<b>显式列投影</b>(把那些列 toString 掉);
     * 没有则返回 null —— 调用方据此决定是维持原来的 {@code SELECT *},还是换成这段投影。
     *
     * <p>返回 null 是常态(普通表没有这种列),此时连 SQL 文本都不变,不存在行为漂移。
     */
    private String aggregateStateProjection(CkClient client, String st) {
        List<String[]> cols;
        try {
            cols = resultColumns(client, st, PAGE_SUB_ALIAS);
        } catch (RuntimeException e) {
            // 预检失败(造不出计划、连接抖动都算)一律降级为「原样执行」:让真正的执行去报它自己的错,
            // 而不是把一次本来可能成功的查询判死。这也安全 —— 预检的结果列恒为 String,
            // 它自己不会踩到驱动盲点、也不会污染连接。
            return null;
        }
        // 预检失败 / 没有结果列:维持原样。
        if (cols == null) {
            return null;
        }
        boolean needed = false;
        for (String[] c : cols) {
            if (!CkColumns.selectExpr(Sql.quoteColRaw(c[0]), c[1]).equals(Sql.quoteColRaw(c[0]))) {
                needed = true;
                break;
            }
        }
        if (!needed) {
            return null;
        }
        // 逐列按类型改写(聚合状态列 → toString(列) AS 列,其余原样引用),列序与列名都不变。
        // 列名来自 DESCRIBE 的结果列(用户 SQL 的别名,内容任意),所以用会转义反引号的 quoteColRaw。
        List<String> parts = new ArrayList<>(cols.size());
        for (String[] c : cols) {
            parts.add(CkColumns.selectExpr(Sql.quoteColRaw(c[0]), c[1]));
        }
        return String.join(", ", parts);
    }

    /**
     * 执行单条只读语句:结果列含聚合状态列时改用显式投影,否则原样执行。
     *
     * <p>只有能当子查询的 SELECT / WITH 才走这条路;SHOW / DESCRIBE / EXPLAIN / EXISTS 的结果列
     * 恒为 String 或数值,本来就不会踩到聚合状态类型,包成子查询反而是语法错。
     */
    private QueryResult queryReadOnly(CkClient client, String st) {
        if (!Sql.isWrappableSelect(st)) {
            return query(client, st);
        }
        String proj = aggregateStateProjection(client, st);
        if (proj == null) {
            return query(client, st);
        }
        return query(client, "SELECT " + proj + " FROM " + Sql.wrapSubquery(st, PAGE_SUB_ALIAS));
    }

    // =====================================================================
    // CSV 导出(对应 Go PrepareExport / exportPlan)
    // =====================================================================

    /** 子查询别名:与用户 SQL 里的名字撞车概率极低,且只在包出来的语句里出现。 */
    private static final String EXPORT_SUB_ALIAS = "__el2ck_exp";

    /**
     * SQL 结果导出计划:先校验、后写出。对应 Go service.exportPlan。
     *
     * <p>拆两步的原因与表导出一样:流式响应写出第一个字节后状态码就定死了,
     * 「SQL 语法错」「列不存在」这类必须在准备阶段就抛出来。
     */
    public static final class QueryExportPlan implements AutoCloseable {

        /** 本次导出专用的连接。 */
        private final CkClient client;

        /** 已包好的子查询(带别名)。 */
        private final String sub;

        /** 已引用化的导出列。 */
        private final List<String> quoted;

        /** 导出列名(原始)。 */
        private final List<String> exportCols;

        /** CSV 表头(可被别名替换)。 */
        private final List<String> headerNames;

        /** 是否输出表头行。 */
        private final boolean withHeader;

        /** 分隔符(已归一)。 */
        private final String delim;

        /** 行数上限;0 = 不限(已封顶)。 */
        private final int limit;

        /**
         * 程序算子计划,已按【导出列投影】重映射。
         *
         * <p>⚠️ 导出也必须应用程序算子,否则脱敏 / 加密列会以明文导出。
         */
        private final List<ProgColumn> progCols;

        /** 行流开启器。 */
        private final RowOpener opener;

        /** 全参构造:仅由 prepareExport 与测试使用。 */
        QueryExportPlan(CkClient client, String sub, List<String> quoted, List<String> exportCols,
                        List<String> headerNames, boolean withHeader, String delim, int limit,
                        List<ProgColumn> progCols, RowOpener opener) {
            this.client = client;
            this.sub = sub;
            this.quoted = quoted;
            this.exportCols = exportCols;
            this.headerNames = headerNames;
            this.withHeader = withHeader;
            this.delim = delim;
            this.limit = limit;
            this.progCols = progCols == null ? new ArrayList<ProgColumn>() : progCols;
            this.opener = opener;
        }

        /** 导出列名(LIMIT 0 探出来的结果列,或用户挑的子集)。 */
        public List<String> columns() {
            return exportCols;
        }

        /** 执行查询并把 CSV 写进 w,返回写出的数据行数(不含表头)。 */
        public long writeTo(Writer w) throws IOException {
            return writeTo(w, opener);
        }

        /** 可注入行流的写法:表头开关、分隔符、转义、行数统计都在这里,单测直接调它。 */
        long writeTo(Writer w, RowOpener open) throws IOException {
            // 只取导出列:投影下推到子查询外面,ClickHouse 不用回吐用不到的列。
            StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", quoted))
                    .append(" FROM ").append(sub);
            if (limit > 0) {
                sql.append(" LIMIT ").append(limit);
            }
            // 关掉驱动的结果行数兜底(max_result_rows=10000),否则大导出会被悄悄截断。
            // SETTINGS 必须排在最后。
            sql.append(" SETTINGS max_result_rows = 0");

            // CSV 写出器。
            final Csv.Sink sink = new Csv.Sink(w, delim);
            // ⚠️ withHeader 与 headerNames 都要看:前者决定写不写,后者决定用什么名字。
            if (withHeader) {
                sink.header(headerNames);
            }
            // 程序算子按行应用时必须拿到【结果列名】才谈得上报错定位,而列名只有流回调给得出,
            // 故用一个长度 1 的容器在回调与行处理之间传递。
            final List<String> streamColumns = new ArrayList<String>();
            // 复用的单行容器:程序算子逐行应用,避免每行重新分配外层列表。
            final List<List<Object>> oneRow = progCols.isEmpty() ? null : new ArrayList<List<Object>>(1);
            if (oneRow != null) {
                oneRow.add(null);
            }
            // 逐行写数据。
            open.open(sql.toString(), new CkClient.RowHandler() {
                /**
                 * 接收流式查询返回的列定义。
                 *
                 * @param columns 列定义
                 */
                @Override
                public void columns(List<String> columns) {
                    // 导出列由计划定,不看流返回的列名;但程序算子的报错需要它,故留一份。
                    streamColumns.clear();
                    streamColumns.addAll(columns);
                }
                /**
                 * 接收流式查询返回的一行数据。
                 *
                 * @param row 当前行数据
                 */
                @Override
                public void row(List<Object> row) throws IOException {
                    // ⚠️ 导出也必须应用程序算子,否则脱敏 / 加密列会以明文导出
                    if (oneRow != null) {
                        oneRow.set(0, row);
                        ProgOps.applyProg(streamColumns, oneRow, progCols);
                    }
                    sink.row(row);
                }
            });
            return sink.rows();
        }

        /** 释放本次导出专用连接。 */
        @Override
        public void close() {
            client.close();
        }
    }

    /**
     * 导出前的准备:挑语句、探列、校验列、组装投影。对应 Go PrepareExport。
     *
     * <p>内存特征:只探一次 {@code DESCRIBE (子查询)} 拿列名与类型,不读取任何数据行。
     */
    public QueryExportPlan prepareExport(long connId, QueryExportRequest req) {
        // 请求体不允许为空。
        if (req == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 去掉尾部空白与多余分号。
        String st = Sql.trimTrailingSemicolons(def(req.getSql()).trim());
        if (st.isEmpty()) {
            throw ApiException.validation("SQL 不能为空");
        }
        // 容错:传了多条语句时取第一条可导出的 SELECT/WITH(前端本该传单条)。
        // 用 isWrappableSelect 而不是 isReadStatement:SHOW/DESCRIBE 包成子查询是语法错。
        if (!Sql.isWrappableSelect(st) || st.contains(";")) {
            String picked = "";
            for (String one : Sql.splitStatements(st)) {
                one = Sql.trimTrailingSemicolons(one.trim());
                if (Sql.isWrappableSelect(one)) {
                    picked = one;
                    break;
                }
            }
            if (picked.isEmpty()) {
                throw ApiException.validation("仅支持 SELECT / WITH 语句导出");
            }
            st = picked;
        }
        // 一条专用连接(导出可能跑很久,且允许指定库)。
        CkClient client = identities.openDedicated(connId, req.getDatabase());
        // 准备阶段失败必须把刚开的连接收掉。
        boolean ok = false;
        try {
            // 包成子查询:后续的探列与投影都基于它。
            String sub = Sql.wrapSubquery(st, EXPORT_SUB_ALIAS);
            // 1. 探结果列(名字 + 类型)。这里不能再用 SELECT * … LIMIT 0:聚合状态列会让驱动
            //    在解析元数据时就抛 Unknown data type(见 resultColumns 的说明)。
            List<String[]> head = resultColumns(client, st, EXPORT_SUB_ALIAS);
            if (head == null) {
                throw ApiException.validation("查询无结果列");
            }
            // 结果列名(按结果顺序)与「列名 → 类型」映射(同名列只认第一个)。
            List<String> allCols = new ArrayList<>(head.size());
            Map<String, String> colTypes = new LinkedHashMap<>();
            for (String[] c : head) {
                allCols.add(c[0]);
                if (!colTypes.containsKey(c[0])) {
                    colTypes.put(c[0], c[1]);
                }
            }
            // 2. 导出列:空 = 全部(按结果顺序)。
            List<String> exportCols = (req.getColumns() == null || req.getColumns().isEmpty())
                    ? new ArrayList<>(allCols) : req.getColumns();
            // 逐列校验,并按类型改写后引用 —— 与表导出走同一个 CkColumns:
            // 聚合状态列不转 toString 的话,导出会因为驱动不认该类型而整条失败。
            List<String> quoted = new ArrayList<>(exportCols.size());
            for (String c : exportCols) {
                if (!allCols.contains(c)) {
                    throw ApiException.validation(String.format("未知列 \"%s\"", c));
                }
                // 列名来自结果列(DESCRIBE),用会转义反引号的 quoteColRaw。
                quoted.add(CkColumns.selectExpr(Sql.quoteColRaw(c), colTypes.get(c)));
            }
            // 3. 分隔符 / 表头 / 条数上限,与表导出用同一套归一。
            String delim = TableService.normalizeDelimiter(req.getDelimiter());
            List<String> names = TableService.headerNames(exportCols, req.getHeaderNames());
            int limit = TableService.clampExportLimit(req.getLimit());
            // 3.5 程序算子计划 → 按【导出列投影】重映射(见 remapProgColsToProjection 的说明)
            List<ProgColumn> progCols = remapProgColsToProjection(exportCols, req.getProgCols());
            QueryExportPlan plan = new QueryExportPlan(client, sub, quoted, exportCols, names,
                    Boolean.TRUE.equals(req.getWithHeader()), delim, limit, progCols, RowOpener.of(client));
            ok = true;
            return plan;
        } finally {
            // 没走到「计划建好」就把连接收掉。
            if (!ok) {
                client.close();
            }
        }
    }

    /** null 兜空串。 */
    private static String def(String s) {
        return s == null ? "" : s;
    }

    /**
     * 把程序算子计划重新定位到【导出列投影】上。
     *
     * <p>⚠️ 导出执行的是 {@code SELECT <所选列> FROM (<生成 SQL>)},列的数量与顺序都会被投影改变;
     * 而计划里的 index 是「生成 SQL 时的全列下标」。直接套用会【对错列加密】,
     * 并让本该脱敏的列以明文进 CSV。
     * 导出弹窗里取消再勾选任意一列就会改变顺序(Element Plus 的 checkbox 把新勾选项接到末尾),
     * 所以这条路径很容易被触发。
     *
     * <p>按列名重新定位:名字对不上 = 该列没被导出,跳过(它本来也不会出现在 CSV 里)。
     */
    static List<ProgColumn> remapProgColsToProjection(List<String> exportCols, List<ProgColumn> plan) {
        List<ProgColumn> out = new ArrayList<ProgColumn>();
        if (plan == null || plan.isEmpty()) {
            return out;
        }
        Map<String, Integer> projIdx = new HashMap<String, Integer>();
        for (int i = 0; i < exportCols.size(); i++) {
            projIdx.put(exportCols.get(i), i);
        }
        for (ProgColumn pc : plan) {
            Integer at = projIdx.get(pc.getAlias());
            if (at == null) {
                continue;
            }
            ProgColumn moved = new ProgColumn();
            moved.setIndex(at);
            moved.setAlias(pc.getAlias());
            moved.setOps(pc.getOps());
            out.add(moved);
        }
        return out;
    }

    /** 写执行:目标库失败 → 502。 */
    private void exec(CkClient client, String sql) {
        try {
            // 在目标 ClickHouse 执行写语句
            client.exec(sql);
        } catch (RuntimeException e) {
            // 执行失败统一包装为 502
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }
}
