/**
 * 【文件职责】本文件定义表管理与数据编辑模块的业务服务 TableService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.table;

import com.el2ck.service.common.RowOpener;
import com.el2ck.service.connection.ConnectionService;

import com.el2ck.exception.ApiException;
import com.el2ck.util.CkColumns;
import com.el2ck.util.Csv;
import com.el2ck.util.DdlGuard;
import com.el2ck.util.Sql;
import com.el2ck.repository.metadata.TableChangeGate;
import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.table.ColumnAlter;
import com.el2ck.entity.table.DataEditRequest;
import com.el2ck.entity.table.DataEditResult;
import com.el2ck.entity.table.DataPage;
import com.el2ck.entity.table.ExportRequest;
import com.el2ck.entity.table.FilterNode;
import com.el2ck.entity.table.GroupBucket;
import com.el2ck.entity.table.GroupByRequest;
import com.el2ck.entity.table.GroupByResult;
import com.el2ck.entity.table.MaterializeRequest;
import com.el2ck.entity.table.MaterializeResult;
import com.el2ck.entity.table.RowUpdate;
import com.el2ck.entity.table.SortSpec;
import com.el2ck.entity.table.TableAlterRequest;
import com.el2ck.entity.table.TableCheckResult;
import com.el2ck.entity.table.TableInfo;
import com.el2ck.entity.table.TableKeys;
import com.el2ck.entity.table.TableStat;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ClickHouse 表层面的管理操作(对应 Go internal/service/table_service.go)。
 *
 * 覆盖:列表/结构/键/DDL、分页预览 + 过滤树、CSV 导出、建/删/清空/结构变更、
 * 单元格级数据编辑。所有到目标 ClickHouse 的执行失败统一 502;
 * 校验/只读分别 400/403;文案与 Go 版逐字一致。
 *
 * 防注入要点:标识符一律过 quoteIdent/quoteCol;过滤值与编辑值按列类型经 sqlLiteral
 * (数值列严格拒绝非数值、字符串列转义反斜杠与单引号、NULLABLE/LowCardinality 递归剥壳)。
 */
public class TableService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;

    /** 连接服务(取客户端 + 只读判定)。 */
    private final ConnectionService connSvc;
    /** 当前用户绑定的真实 CK 业务身份。 */
    private final com.el2ck.service.ck.CkIdentityService identities;
    /** 表资产生命周期(建/删/改名)。 */
    private final com.el2ck.service.ck.TableLifecycleService lifecycle;

    /** 表分组服务:删表/改表名后同步清理或迁移该表的分组归属。 */
    private final TableGroupService tableGroups;
    private final TableReferenceService tableReferences;

    /** 表统计的 TTL 缓存(按「用户 + 连接 + 库」,15 秒)。表整理后可调 invalidate 让它立即失效。 */
    private final TableStatsCache statsCache = new TableStatsCache();

    /** 构造器:注入连接服务实例,供各操作获取客户端与连接配置。 */
    public TableService(ConnectionService connSvc, TableGroupService tableGroups, TableReferenceService tableReferences,
                        com.el2ck.service.ck.CkIdentityService identities,
                        com.el2ck.service.ck.TableLifecycleService lifecycle) {
        // 保存连接服务引用,供后续查询/校验/只读判定复用。
        this.connSvc = connSvc;
        // 保存表分组服务引用,供删表/改表名后的归属联动复用。
        this.tableGroups = tableGroups;
        this.tableReferences = tableReferences;
        this.identities = identities;
        this.lifecycle = lifecycle;
    }

    // =====================================================================
    // 概要信息(列表/结构/键/DDL)
    // =====================================================================

    /** 列出数据库下的表(排除物化视图等临时对象)。 */
    public List<TableInfo> list(long connId, String db) {
        // 先用 quoteIdent 白名单校验库名(非法标识符在此抛错),杜绝注入。
        Sql.quoteIdent(db);
        // 普通用户合并本人和有效共享;内置 admin 补充全库资产信息,最终以业务 CK 身份的可见表为准。
        java.util.Map<String, java.util.Map<String, Object>> metas = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> m : access.tableMetas(connId, db)) {
            metas.put(String.valueOf(m.get("table_name")), m);
        }
        boolean admin = access.adminDatabase(db);
        if (metas.isEmpty() && !admin) return java.util.Collections.emptyList();
        // 再用当前用户的 CK 身份核对物理对象;权限受限或已删除的表不会出现在结果里。
        QueryResult res = query(identities.client(connId),
                "SELECT name, engine, comment FROM system.tables WHERE database = ? AND is_temporary = 0 ORDER BY name",
                java.util.Collections.<Object>singletonList(db));
        List<TableInfo> out = new ArrayList<>();
        for (List<Object> row : res.getRows()) {
            if (row.isEmpty() || row.get(0) == null) continue;
            String name = String.valueOf(row.get(0));
            java.util.Map<String, Object> meta = metas.get(name);
            if (meta == null) {
                if (!admin) continue;
                meta = access.unregisteredAdminTable(db,name);
            }
            TableInfo t = new TableInfo();
            t.setName(name);
            if (row.size() > 1 && row.get(1) != null) t.setEngine(String.valueOf(row.get(1)));
            if (row.size() > 2 && row.get(2) != null) t.setRemark(String.valueOf(row.get(2)));
            t.setAssetId(meta.get("assetId") == null ? null : ((Number) meta.get("assetId")).longValue());
            t.setOwnerUserId(meta.get("ownerUserId") == null ? null : ((Number) meta.get("ownerUserId")).longValue());
            t.setCreatorUserId(meta.get("creatorUserId") == null ? null : ((Number) meta.get("creatorUserId")).longValue());
            t.setCreatorName(meta.get("creatorName") == null ? "历史数据" : String.valueOf(meta.get("creatorName")));
            t.setAccessMode(String.valueOf(meta.get("accessMode")));
            t.setCanRead(Boolean.TRUE.equals(meta.get("canRead")));
            // View / MaterializedView 是只读引擎,即使所有者也不展示写操作。
            boolean view = t.getEngine() != null && t.getEngine().toLowerCase(java.util.Locale.ROOT).contains("view");
            t.setCanWrite(!view && Boolean.TRUE.equals(meta.get("canWrite")));
            t.setCanManage(Boolean.TRUE.equals(meta.get("canManage")));
            t.setCanDrop(Boolean.TRUE.equals(meta.get("canDrop")));
            t.setCanShare(Boolean.TRUE.equals(meta.get("canShare")));
            t.setPermissionStatus(String.valueOf(meta.get("permissionStatus")));
            out.add(t);
        }
        return out;
    }

    /** 查看表结构(DESCRIBE TABLE)。 */
    public QueryResult schema(long connId, String db, String table) {
        access.table(connId, db, table, false);
        // 取目标连接执行 DESCRIBE,返回各列的名称/类型/默认值等结构信息。
        return query(identities.client(connId), "DESCRIBE TABLE " + qualified(db, table));
    }

    /**
     * 某库下每张表的列数 / 行数 / 磁盘大小,对应 Go TableStats。
     *
     * <p>聚合 system.columns 并读取 system.tables 的统计,按「连接 + 库」做 15 秒 TTL 缓存;
     * 前端提供手动刷新,刷新的间隔由页面决定,服务端不额外强刷。
     */
    public List<TableStat> tableStats(long connId, String db) {
        // 先用 quoteIdent 白名单校验库名(非法标识符在此抛错),杜绝注入。
        Sql.quoteIdent(db);
        // 没有平台可见表时直接返回空:不要为了空列表去建立 CK 用户连接,
        // 否则未初始化绑定的用户打开目标库表页会看到「账号不可用」而不是空列表。
        java.util.Set<String> visible = access.visibleTables(connId, db);
        boolean admin = access.adminDatabase(db);
        if (visible.isEmpty() && !admin) return java.util.Collections.emptyList();
        // 命中未过期的缓存直接返回,避免频繁扫 system.columns/parts。
        long statsUser = com.el2ck.config.AuthContext.userId();
        List<TableStat> cached = statsCache.get(statsUser, connId, db);
        if (cached != null) {
            return cached;
        }
        // 使用表自身的统计,避免菜单依赖 system.parts 的额外 SELECT 权限。
        // 部分引擎无法快速给出行数或大小,保留 NULL,前端显示「-」而非误报 0。
        String sql = "SELECT t.name,"
                + " COALESCE(c.cnt, 0), t.total_rows, t.total_bytes"
                + " FROM system.tables t"
                + " LEFT JOIN (SELECT database, table, count() AS cnt FROM system.columns"
                + " WHERE database = ? GROUP BY database, table) c"
                + " ON c.database = t.database AND c.table = t.name"
                + " WHERE t.database = ? AND t.is_temporary = 0 ORDER BY t.name";
        QueryResult res = query(identities.client(connId), sql, java.util.Arrays.<Object>asList(db, db));
        // 0 行时返回空列表而非 null(前端直接迭代)。
        List<TableStat> out = new ArrayList<>();
        for (List<Object> row : res.getRows()) {
            TableStat st = new TableStat();
            // 第一列:表名。
            if (!row.isEmpty() && row.get(0) != null) {
                st.setName(String.valueOf(row.get(0)));
            }
            // 第二列:列数;数值包装类型(驱动可能回 BigInteger/BigDecimal)统一走 rowCountOf。
            if (row.size() > 1 && row.get(1) != null) {
                st.setCols((int) rowCountOf(row.get(1)));
            }
            // 第三列:总行数。
            if (row.size() > 2 && row.get(2) != null) {
                st.setRows(rowCountOf(row.get(2)));
            }
            // 第四列:磁盘字节数。
            if (row.size() > 3 && row.get(3) != null) {
                st.setBytes(rowCountOf(row.get(3)));
            }
            // 只返回平台登记且对当前用户可见的表,避免 CK 侧遗留授权导致未登记对象出现在统计里。
            if (!admin && !visible.contains(st.getName())) continue;
            out.add(st);
        }
        // 写入缓存后返回。
        statsCache.put(statsUser, connId, db, out);
        return out;
    }

    /**
     * 整理表:OPTIMIZE TABLE ... FINAL 合并该表的数据 parts,让表更紧凑、查询更快。对应 Go Optimize。
     *
     * <p>合并会改变行数与磁盘占用,所以先失效该库的统计缓存 —— 否则用户点完「整理」再刷新,
     * 15 秒内看到的还是旧的行数/大小,会以为整理没生效。
     */
    public void optimize(long connId, String db, String table) {
        try (TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            optimizeWhileIdle(connId, db, table);
        }
    }

    private void optimizeWhileIdle(long connId, String db, String table) {
        access.table(connId, db, table, true);
        checkWritable(connId);
        tableReferences.requireModificationAllowed(connId, db, table);
        // 拼接校验后的 "库.表" 限定名(非法标识符在此抛 400)。
        String q = qualified(db, table);
        // 取目标连接客户端(连接不可用在此抛错)。
        CkClient client = identities.client(connId);
        // 与 Go 一致:先清缓存再执行 —— 执行失败时缓存也清掉,代价只是重查一次。
        statsCache.invalidate(com.el2ck.config.AuthContext.userId(), connId, db);
        // 执行合并;目标库失败统一转 502。
        exec(client, "OPTIMIZE TABLE " + q + " FINAL");
    }

    /**
     * 对一张已存在的表做体检(只读:DESCRIBE + system.tables),对应 Go CheckTable。
     * 具体规则与结论文案见 {@link TableCheckAnalyzer};本方法只负责取数。
     */
    public TableCheckResult checkTable(long connId, String db, String table) {
        access.table(connId, db, table, false);
        // 拼接校验后的 "库.表" 限定名(非法标识符在此抛 400)。
        String q = qualified(db, table);
        // 取目标连接客户端。
        CkClient client = identities.client(connId);
        // 列定义:名称/类型/默认值/注释/编码/TTL 都在这里。
        QueryResult desc = query(client, "DESCRIBE TABLE " + q);
        // 表级元数据:引擎与三种键。
        QueryResult meta = query(client,
                "SELECT engine, partition_key, sorting_key, primary_key FROM system.tables WHERE database = ? AND name = ?",
                java.util.Arrays.asList((Object) db, table));
        // system.tables 查不到时(如视图、权限受限)四个元数据字段留空,体检仍按列信息出结论。
        String engine = "";
        String partitionKeyExpr = "";
        String sortingKeyExpr = "";
        String primaryKeyExpr = "";
        if (!meta.getRows().isEmpty()) {
            List<Object> r = meta.getRows().get(0);
            engine = TableCheckAnalyzer.cellAt(r, 0);
            partitionKeyExpr = TableCheckAnalyzer.cellAt(r, 1);
            sortingKeyExpr = TableCheckAnalyzer.cellAt(r, 2);
            primaryKeyExpr = TableCheckAnalyzer.cellAt(r, 3);
        }
        // 纯逻辑分析:不在本方法里堆规则,便于单测直接打靶。
        return TableCheckAnalyzer.analyze(db, table, engine, partitionKeyExpr, sortingKeyExpr, primaryKeyExpr, desc);
    }

    /** 查看建表语句(SHOW CREATE TABLE)。 */
    public QueryResult ddl(long connId, String db, String table) {
        // 结构查看对有效共享开放;修改仍要求所有者。
        access.table(connId, db, table, false);
        // 取目标连接执行 SHOW CREATE TABLE,返回完整建表 DDL 文本。
        return query(identities.client(connId), "SHOW CREATE TABLE " + qualified(db, table));
    }

    /** 表的主键/分区键/排序键,并把表达式解析成属于该表的真实列名。对应 Go Keys。 */
    public TableKeys keys(long connId, String db, String table) {
        access.table(connId, db, table, false);
        // 拼接校验后的 "库.表" 限定名。
        String q = qualified(db, table);
        // 获取目标连接客户端。
        CkClient client = identities.client(connId);
        // 1. 取表的实际列名,用于从键表达式中筛出真正的列。
        // 执行 DESCRIBE 拿到该表全部真实列。
        QueryResult desc = query(client, "DESCRIBE TABLE " + q);
        // 以列名 → TRUE 形式记录,保持描述顺序。
        Map<String, Boolean> cols = new LinkedHashMap<>();
        // 逐行读取列名。
        for (List<Object> row : desc.getRows()) {
            // 首列非空时登记该列名。
            if (!row.isEmpty() && row.get(0) != null) {
                cols.put(String.valueOf(row.get(0)), Boolean.TRUE);
            }
        }
        // 2. 读 system.tables 的键表达式文本。
        // 按库名+表名定位,取主键/分区键/排序键三个表达式。
        QueryResult res = query(client,
                "SELECT primary_key, partition_key, sorting_key FROM system.tables WHERE database = ? AND name = ?",
                java.util.Arrays.asList((Object) db, table));
        // 结果封装对象。
        TableKeys keys = new TableKeys();
        // 逐行读取键表达式文本(实际仅一行)。
        for (List<Object> row : res.getRows()) {
            // 第一列:主键表达式。
            if (!row.isEmpty() && row.get(0) != null) {
                keys.setPrimaryKeyExpr(String.valueOf(row.get(0)));
            }
            // 第二列:分区键表达式。
            if (row.size() > 1 && row.get(1) != null) {
                keys.setPartitionKeyExpr(String.valueOf(row.get(1)));
            }
            // 第三列:排序键表达式。
            if (row.size() > 2 && row.get(2) != null) {
                keys.setSortingKeyExpr(String.valueOf(row.get(2)));
            }
        }
        // 从主键表达式中解析出真实列名。
        keys.setPrimaryKey(keyColumnsOf(keys.getPrimaryKeyExpr(), cols.keySet()));
        // 从分区键表达式中解析出真实列名。
        keys.setPartitionKey(keyColumnsOf(keys.getPartitionKeyExpr(), cols.keySet()));
        // 从排序键表达式中解析出真实列名。
        keys.setSortingKey(keyColumnsOf(keys.getSortingKeyExpr(), cols.keySet()));
        // 返回三种键的列名结果。
        return keys;
    }

    // =====================================================================
    // 分页预览与过滤树(对应 Go Data / DataPage / FilterNode)
    // =====================================================================

    /**
     * 分页预览表数据;filter 为过滤条件树,cols 指定要查询的列(空=全部)。
     * withCount=true 时额外查询真实总行数；false 时 total=当前页行数，hasMore 判断还有下一页。
     * orders 为排序列(空=不排序)；与导出共用同一份渲染，所以「看到的顺序」=「导出的顺序」。
     */
    public DataPage data(long connId, String db, String table, int offset, int size,
                         FilterNode filter, List<String> cols, boolean withCount, List<SortSpec> orders) {
        access.table(connId, db, table, false);
        // 偏移量为负时归零,保证从首行开始取。
        if (offset < 0) {
            offset = 0;
        }
        // 页码非法或超出上限时回退为每页 100 行。
        if (size <= 0 || size > 1000) {
            size = 100;
        }
        // 拼接校验后的 "库.表" 限定名。
        String q = qualified(db, table);
        // 获取目标连接客户端。
        CkClient client = identities.client(connId);

        // 列类型映射无条件先读一次(DESCRIBE):过滤转义、指定列校验、Bool→toUInt8 转写都依赖它。
        // 未指定列时默认取全部列(Bool 列会按需转写)。
        Map<String, String> types = readColumnTypes(client, q);
        String selCols = safeSelectColumns(types, null);
        // 默认无过滤条件。
        FilteredRows filtered = filteredRows(types, q, filter, orders);
        String where = filtered.where;
        // 无过滤且未指定列时,上面的默认 SELECT 即最终结果,无需再加工。
        if (hasFilter(filter) || (cols != null && !cols.isEmpty())) {
            // 用户显式指定了列集合:列式库只读这些列可减少 IO,但列名必须先过表结构校验。
            if (cols != null && !cols.isEmpty()) {
                // 按数量预分配保存引用后的列名。
                List<String> quoted = new ArrayList<>(cols.size());
                // 逐个校验并引用列名。
                for (String c : cols) {
                    // 列不存在于表结构中则直接报未知字段。
                    if (!types.containsKey(c)) {
                        throw ApiException.validation(String.format("未知字段 \"%s\"", c));
                    }
                    // 合法列套上反引号引用。
                    quoted.add(safeSelectColumn(c, types.get(c)));
                }
                // 逗号拼接成 SELECT 的列清单。
                selCols = String.join(", ", quoted);
            }
        }

        // 拼装查询主体:SELECT 列清单 FROM 限定表名。
        String sel = "SELECT " + selCols + " FROM " + filtered.source;
        // 有过滤条件时拼入 WHERE 段。
        if (!where.isEmpty()) {
            sel += " WHERE " + where;
        }
        // 排序(与导出同一份渲染):列不在表里会被 orderBySql 跳过,不会拼出坏 SQL。
        String orderBy = filtered.orderBy;
        if (!orderBy.isEmpty()) {
            sel += " ORDER BY " + orderBy;
        }
        // 追加分页限制:先 LIMIT 后 OFFSET(占位符参数化)。
        sel += " LIMIT ? OFFSET ?";
        // 以 size/offset 作为参数执行分页查询。
        QueryResult res = query(client, sel, java.util.Arrays.asList((Object) size, offset));

        // 组装分页结果对象,逐个回填列名/类型/数据/分页参数。
        DataPage page = new DataPage();
        // 回填结果集的列名。
        page.setColumns(res.getColumns());
        // 回填结果集的列类型。
        page.setTypes(res.getTypes());
        // 回填当前页的数据行。
        page.setRows(res.getRows());
        // 回填本次偏移量。
        page.setOffset(offset);
        // 回填每页行数。
        page.setSize(size);
        // 满页即视为可能还有下一页(恰好取完末页时也会为 true,故只是近似判断)。
        page.setHasMore(res.getRows().size() == size);

        // 不要求精确总数时提前返回:省掉一次全表 count,前端靠 hasMore 翻页。
        if (!withCount) {
            // total 退化为当前页行数,仅用于展示(不是真实总数)。
            page.setTotal((long) res.getRows().size());
            // 直接返回(不再查全表总数)。
            return page;
        }

        // withCount=true 时返回真实总数，供分页器正确计算页数。
        // 总数初值先用当前页行数兜底。
        long total = res.getRows().size();
        StringBuilder cntSql = new StringBuilder("SELECT count() FROM ").append(filtered.source);
        // 有过滤时追加 WHERE。
        if (!where.isEmpty()) {
            cntSql.append(" WHERE ").append(where);
        }
        // 单独执行精确计数查询；失败不影响整页返回。
        try {
            QueryResult cnt = client.query(cntSql.toString());
            // 结果首行首列存在时取数值作为总数。
            if (!cnt.getRows().isEmpty() && !cnt.getRows().get(0).isEmpty() && cnt.getRows().get(0).get(0) != null) {
                total = rowCountOf(cnt.getRows().get(0).get(0));
            }
        } catch (RuntimeException ignored) {
            // count 失败不回滚整页,保留当前页行数作为 total(与 Go err==nil 判断一致)。
        }
        // 回填最终总数。
        page.setTotal(total);
        // 返回含总数的分页结果。
        return page;
    }

    // =====================================================================
    // 维度分布统计(画像查询,对应 Go GroupBy)
    // =====================================================================

    /**
     * 按某个维度做分布统计:值 + 条数,按条数从多到少。
     *
     * <p>用途:画像查询(「这批里各城市分别多少」)与圈选后的分布确认。
     * 为什么必须后端做:分布必须 GROUP BY 全表算,前端只拿到了当前页的行,
     * 自己数会数成「这一页的分布」(大表上完全错)。
     *
     * <p>过滤条件与数据页<b>同源同结构</b> —— 「看到的行」与「统计的条数」口径必须一致,
     * 否则圈选条数与画像分布对不上。
     */
    public GroupByResult groupBy(long connId, String db, String table, GroupByRequest req) {
        access.table(connId, db, table, false);
        // 请求体为空时按空请求处理(仅必填的 column 会因此报错)。
        GroupByRequest in = req == null ? new GroupByRequest() : req;
        // 维度列名去空白。
        String col = in.getColumn() == null ? "" : in.getColumn().trim();
        if (col.isEmpty()) {
            throw ApiException.validation("未指定维度列");
        }
        // limit 默认 50、上限 500 —— 前端只画得了这么多,再多的分布也没法看。
        int limit = in.getLimit() == null ? 0 : in.getLimit();
        if (limit <= 0) {
            limit = 50;
        }
        if (limit > 500) {
            limit = 500;
        }
        // 拼接校验后的 "库.表" 限定名。
        String q = qualified(db, table);
        // 取目标连接客户端。
        CkClient client = identities.client(connId);
        // 列类型表:既用于校验维度列是否存在,也用于生成分组表达式与过滤条件。
        Map<String, String> types = columnTypes(client, q);
        if (!types.containsKey(col)) {
            throw ApiException.validation(String.format("未知字段 \"%s\"", col));
        }
        // 与数据页同一套过滤树渲染,保证口径一致。
        FilteredRows filtered = filteredRows(types, q, in.getFilter(), in.getOrders());
        String where = filtered.where;

        GroupByResult out = new GroupByResult();
        out.setColumn(col);
        // buckets 已在实体里初始化为空列表(前端直接迭代)。

        // ① 维度分布。值统一 toString:NULL / 数字 / 日期都能在界面上原样显示。
        String[] expr = buildGroupExpr(col, types.get(col), in);
        StringBuilder sel = new StringBuilder("SELECT ").append(expr[0])
                .append(", count() AS cnt FROM ").append(filtered.source);
        if (!where.isEmpty()) {
            sel.append(" WHERE ").append(where);
        }
        sel.append(" GROUP BY value ORDER BY ").append(expr[1]).append(" LIMIT ?");
        out.setSql(sel.toString());
        // LIMIT 走参数绑定,不拼进 SQL 文本。
        QueryResult res = query(client, out.getSql(), java.util.Collections.<Object>singletonList(limit));
        for (List<Object> row : res.getRows()) {
            if (row.size() < 2) {
                continue;
            }
            GroupBucket b = new GroupBucket();
            b.setValue(row.get(0) == null ? "" : String.valueOf(row.get(0)));
            b.setCount(rowCountOf(row.get(1)));
            out.getBuckets().add(b);
        }

        // ② 占比的分母(同一过滤条件)。失败不影响分布本身 —— 界面只是算不出百分比。
        StringBuilder cntSql = new StringBuilder("SELECT count() FROM ").append(filtered.source);
        if (!where.isEmpty()) {
            cntSql.append(" WHERE ").append(where);
        }
        try {
            QueryResult cres = client.query(cntSql.toString());
            if (!cres.getRows().isEmpty() && !cres.getRows().get(0).isEmpty()) {
                out.setTotal(rowCountOf(cres.getRows().get(0).get(0)));
            }
        } catch (RuntimeException ignored) {
            // 分母查不出来就保持 0:分布本身是好的,不能因为占比算不了就把整个请求判失败。
        }
        return out;
    }

    /** 分桶方式是否合法(对应 Go bucketSpec)。 */
    private static void checkBucketSpec(String bucket) {
        if (bucket == null) {
            return;
        }
        switch (bucket) {
            case "":
            case "raw":
            case "number":
            case "day":
            case "month":
            case "year":
                return;
            default:
                throw ApiException.validation(String.format("不支持的分组方式 \"%s\"", bucket));
        }
    }

    /**
     * 由「列 + 列类型 + 分组方式」生成 SELECT 里的分组表达式与排序子句,对应 Go buildGroupExpr。
     *
     * <p>返回的表达式形如 {@code xxx AS value}(结果列固定叫 value),第二个元素是 ORDER BY 子句。
     *
     * <p>几个约定:
     * <ul>
     *   <li>数值分桶用 {@code floor(x/步长)*步长} 取左边界,标签写成 "10-20"(左闭右开);
     *       排序按<b>边界数值</b>升序(标签是变长字符串,直接按标签排会出现 10-20 在 5-10 前面)。</li>
     *   <li>时间分桶用 toDateTimeOrZero 再对齐到天/月/年;排序按标签升序(ISO 格式的字典序就是时间序)。</li>
     *   <li>String 列(仲裁列在目标表里都是 String)里的空串很常见:转换后是 0 / 1970,
     *       会被算进 "0-10" 或 "1970-01" 这种<b>看起来正常</b>的桶里 —— 单独标成 "(空)" 更诚实。</li>
     * </ul>
     *
     * <p>⚠️ <b>与 Go 的一处有意分歧</b>:Go 直接对列调用 {@code toFloat64OrZero(col)} /
     * {@code toDateTimeOrZero(col)},而这两个函数在 ClickHouse 上要求 <b>String 参数</b> ——
     * 对 Int/DateTime/Date 列会报 {@code Illegal type ... should take String argument}(真机实测)。
     * 于是 Go 的分桶只在 String 列上能跑,可前端 {@code guessBucket} 恰恰会自动给数值列选 number、
     * 给时间列选 month,用户看到的就是一段 ClickHouse 堆栈。
     * 这里统一先 {@code toString(列)} 再转换:对 String 列是空操作(结果与 Go 逐行一致),
     * 对真实类型列则从报错变成可用。Go 侧同样需要这一行修正。
     */
    static String[] buildGroupExpr(String col, String colType, GroupByRequest req) {
        String bucket = req == null || req.getBucket() == null ? "" : req.getBucket();
        checkBucketSpec(bucket);
        // 反引号引用列名(列名来自表结构,已保证安全)。
        String qc = Sql.quoteCol(col);
        // ⚠️ 转成数值/时间前必须先 toString:OrZero 系列只收 String 参数(见方法头的分歧说明)。
        String asText = "toString(" + qc + ")";
        String t = colType == null ? "" : colType;
        boolean isStr = t.startsWith("String") || t.contains("LowCardinality(String)");
        if ("".equals(bucket) || "raw".equals(bucket)) {
            return new String[]{"toString(" + qc + ") AS value", "cnt DESC"};
        }
        if ("number".equals(bucket)) {
            // 步长 <=0 时回退默认 10。
            double size = req.getBucketSize() == null ? 0 : req.getBucketSize();
            if (size <= 0) {
                size = 10;
            }
            // 步长原样参与除法(与 Go 的 FormatFloat('f',-1,64) 同为最短往返表示)。
            String sizeStr = trimDouble(size);
            // 桶左边界:floor(x/步长)*步长。
            String lo = "toInt64(floor(toFloat64OrZero(" + asText + ") / " + sizeStr + ")) * " + (long) size;
            // 标签 "左-右"(左闭右开)。空串单列成 "(空)"。
            String inner = "concat(toString(" + lo + "), '-', toString(" + lo + " + " + (long) size + "))";
            String expr = isStr ? "if(toString(" + qc + ") = '', '(空)', " + inner + ")" : inner;
            // 按边界数值升序 —— 变长标签按字符串排会乱序。
            return new String[]{expr + " AS value", "min(toFloat64OrZero(" + asText + ")) ASC"};
        }
        // day / month / year:对齐到天/月/年的起点。
        String fn;
        switch (bucket) {
            case "day":
                fn = "toStartOfDay";
                break;
            case "month":
                fn = "toStartOfMonth";
                break;
            default:
                fn = "toStartOfYear";
        }
        String inner = "toString(" + fn + "(toDateTimeOrZero(" + asText + ")))";
        String expr = isStr ? "if(toString(" + qc + ") = '', '(空)', " + inner + ")" : inner;
        return new String[]{expr + " AS value", "value ASC"};
    }

    /** 按最短往返表示输出 double(与 Go strconv.FormatFloat(size,'f',-1,64) 同口径):10.0 → "10"。 */
    private static String trimDouble(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // =====================================================================
    // 圈选落表(「数据包」,对应 Go Materialize)
    // =====================================================================

    /**
     * 把当前圈选(过滤条件)的结果落成一张表 —— 就是「数据包」。
     *
     * <p>用途:圈出来的这批人要拿去投放 / 再分析 / 被别的工作流当源表。
     * 只导出 CSV 是不够的(下次还得重新圈),落成表之后它就是一个稳定的数据产物,
     * 而且会自动出现在「全部库表」里。
     *
     * <p>注释、人话条件、建表语句的生成在 {@link CrowdTableBuilder} 里(纯函数),本方法只负责取数与执行。
     */
    public MaterializeResult materialize(long connId, String db, String table, MaterializeRequest req) {
        try (TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            return materializeWhileIdle(connId, db, table, req);
        }
    }

    private MaterializeResult materializeWhileIdle(long connId, String db, String table, MaterializeRequest req) {
        // 请求体为空时按空请求处理(必填的 targetTable 会因此报错)。
        MaterializeRequest in = req == null ? new MaterializeRequest() : req;
        // 源表必须对当前用户可见;共享表允许据此保存查询结果(只读不等于禁止复制)。
        access.table(connId, db, table, false);
        // 1. 目标表名。空 → 直接拒;非法标识符(含空格/中文/注入片段)→ 拒,不等到拼 DDL 报看不懂的语法错。
        String targetTable = def(in.getTargetTable()).trim();
        if (targetTable.isEmpty()) {
            throw ApiException.validation("数据包表名不能为空");
        }
        String tableErr = tryQuoteIdent(targetTable);
        if (tableErr != null) {
            throw ApiException.validation("数据包表名非法: " + tableErr);
        }
        // 2. 目标库。空 = 与源表同库。
        String targetDb = def(in.getTargetDb()).trim();
        if (targetDb.isEmpty()) {
            targetDb = db;
        }
        String dbErr = tryQuoteIdent(targetDb);
        if (dbErr != null) {
            throw ApiException.validation("目标库名非法: " + dbErr);
        }
        // 3. 源表限定名 + 客户端。
        String srcQ = qualified(db, table);
        CkClient client = identities.client(connId);
        // 落表是写操作:只读连接直接拒。
        checkWritable(connId);
        // 4. 源表列类型表:列存在性校验、值渲染、号码列探测都靠它。
        Map<String, String> types = columnTypes(client, srcQ);
        // 列名必须在表里存在(否则拼进 SELECT 就是语法错误,报错还不好懂)。
        List<String> cols = in.getColumns() == null ? new ArrayList<String>() : in.getColumns();
        for (String c : cols) {
            if (!types.containsKey(c)) {
                throw ApiException.validation(String.format("未知字段 \"%s\"", c));
            }
        }
        // 排序键列同样校验:不在表里就直接拒。
        String orderBy = def(in.getOrderBy()).trim();
        if (!orderBy.isEmpty() && !types.containsKey(orderBy)) {
            throw ApiException.validation(String.format("未知排序字段 \"%s\"", orderBy));
        }
        if (orderBy.isEmpty()) {
            // 默认按号码列排序 —— 数据包基本都按号码用。
            // 候选名单与表体检共用同一份(TableCheckAnalyzer.phoneColumnOf),免得两处漂移。
            orderBy = TableCheckAnalyzer.phoneColumnOf(types);
        }
        // 圈选条件与数据页同源:「看到的行」=「存进去的行」。
        FilteredRows filtered = filteredRows(types, srcQ, in.getFilter(), in.getOrders());
        String where = filtered.where;
        // 两个名字都过了 quoteIdent,可以直接拼(与 Go 一致,不再加引号)。
        String dstQ = targetDb + "." + targetTable;
        tableReferences.requireModificationAllowed(connId, targetDb, targetTable);
        // 同名表探测:查不出来(权限/版本)不算错,当作不存在。
        boolean exists = false;
        try {
            QueryResult eres = client.query("EXISTS TABLE " + dstQ);
            if (!eres.getRows().isEmpty() && !eres.getRows().get(0).isEmpty()) {
                exists = rowCountOf(eres.getRows().get(0).get(0)) == 1;
            }
        } catch (RuntimeException ignored) {
            // 探测失败就当不存在:真正建表时还能报出来,不必在这里拦
        }
        // 表注释带上来源/时间/条件 —— DWD 层「数据包」页就是按它认人的。
        // ⚠️ 来源只写【表名】、条件说成人话:这两段都会直接显示在界面上(前台不显示物理库名;
        //    而「圈选条件」列是给业务同学看的,SQL 他们读不懂)。
        String comment = CrowdTableBuilder.buildCrowdComment(table, java.time.LocalDateTime.now(),
                CrowdTableBuilder.humanFilter(in.getFilter(), columnComments(client, db, table)));
        String sql = CrowdTableBuilder.buildMaterializeSql(targetDb, targetTable, filtered.source,
                cols, types, orderBy, where, comment);
        MaterializeResult out = new MaterializeResult();
        // Table 只回表名:它是给界面提示用的,带库名等于把库名露到前台。
        out.setTable(targetTable);
        out.setSql(sql);
        out.setExists(exists);
        // 预览:只回 SQL,不落库(落库是写操作,先让人看清楚要跑什么)。
        if (Boolean.TRUE.equals(in.getDryRun())) {
            return out;
        }
        if (exists && !Boolean.TRUE.equals(in.getDropIfExists())) {
            throw ApiException.validation(String.format("数据包 %s 已存在(勾选「覆盖同名表」可先删除)", targetTable));
        }
        long assetId;
        if (exists) {
            // 覆盖同名表走受控生命周期,旧共享不继承。
            lifecycle.drop(connId, targetDb, targetTable);
            tableGroups.removeTableMembers(connId, targetDb, targetTable);
        }
        assetId = lifecycle.registerTarget(connId, targetDb, targetTable, "workflow", null);
        boolean createGranted = false;
        try {
            lifecycle.grantCreate(connId, targetDb, targetTable);
            createGranted = true;
            client.exec(sql);
            lifecycle.revokeCreate(connId, targetDb, targetTable);
            createGranted = false;
            lifecycle.activateTarget(assetId);
            // 授权会失效旧连接;后续统计改用重新登录后的用户连接。
            client = identities.client(connId);
        } catch (RuntimeException e) {
            lifecycle.failTarget(assetId, Sql.messageOf(e));
            // 失败不留半张表:只清理本次精确目标名,由管理身份执行。
            lifecycle.dropPartial(connId, targetDb, targetTable);
            throw ApiException.badGateway("创建数据包失败: " + Sql.messageOf(e));
        } finally {
            if (createGranted) {
                try { lifecycle.revokeCreate(connId, targetDb, targetTable); } catch (RuntimeException ignored) { }
            }
        }
        // 回填实际落库行数。查不出来不算失败(表已经建好了),界面显示 0 行而已。
        try {
            QueryResult cres = client.query("SELECT count() FROM " + dstQ);
            if (!cres.getRows().isEmpty() && !cres.getRows().get(0).isEmpty()) {
                out.setRows(rowCountOf(cres.getRows().get(0).get(0)));
            }
        } catch (RuntimeException ignored) {
            // 同上
        }
        return out;
    }

    /**
     * 取列注释(工作流里配的业务名),用于把圈选条件说成人话。对应 Go columnComments。
     * 取不到不算错 —— 退回显示字段名即可,不能因为没注释就让数据包建不出来。
     */
    private Map<String, String> columnComments(CkClient client, String db, String table) {
        Map<String, String> out = new LinkedHashMap<>();
        QueryResult res;
        try {
            res = client.query(
                    "SELECT name, comment FROM system.columns WHERE database = ? AND table = ?",
                    java.util.Arrays.<Object>asList(db, table));
        } catch (RuntimeException e) {
            return out;
        }
        for (List<Object> row : res.getRows()) {
            if (row.size() < 2 || row.get(0) == null) {
                continue;
            }
            String name = String.valueOf(row.get(0));
            if (row.get(1) != null) {
                String c = String.valueOf(row.get(1)).trim();
                if (!c.isEmpty()) {
                    out.put(name, c);
                }
            }
        }
        return out;
    }

    /** 校验标识符:通过返回 null,否则返回错误文案(用于把 quoteIdent 的异常转成带前缀的提示)。 */
    private static String tryQuoteIdent(String name) {
        try {
            Sql.quoteIdent(name);
            return null;
        } catch (ApiException e) {
            return e.getMessage();
        }
    }

    /** 查询、导出、画像和落表共享同一批筛选并去重后的完整行。 */
    private static final class FilteredRows {
        final String source;
        final String where;
        final String orderBy;

        FilteredRows(String source, String where, String orderBy) {
            this.source = source;
            this.where = where;
            this.orderBy = orderBy;
        }
    }

    /**
     * 生成应用过滤、排序和去重规则后的数据源表达式。
     *
     * <p>没有 distinct 条件时直接返回原数据源、WHERE 和 ORDER BY。存在 distinct
     * 时使用 ClickHouse {@code LIMIT 1 BY} 子查询，并追加整行稳定排序作为并列
     * 兜底，确保翻页、导出和画像在重复行之间选择一致记录。
     *
     * @param types 列名到 ClickHouse 类型的映射
     * @param source 原始表或查询表达式
     * @param filter 过滤树
     * @param orders 排序规则
     * @return 过滤后的数据源及剩余 WHERE/ORDER BY 片段
     */
    private static FilteredRows filteredRows(Map<String, String> types, String source,
                                              FilterNode filter, List<SortSpec> orders) {
        String where = buildWhere(types, filter);
        String order = orderBySql(types, orders);
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        collectDistinctColumns(types, filter, keys);
        if (keys.isEmpty()) return new FilteredRows(source, where, order);

        // 排序并列时按完整行兜底，避免翻页、导出和画像各自挑中不同记录。
        List<String> row = new ArrayList<>();
        for (Map.Entry<String, String> col : types.entrySet()) {
            row.add(CkColumns.refExpr(Sql.quoteCol(col.getKey()), col.getValue()));
        }
        String tie = "toString(tuple(" + String.join(", ", row) + ")) ASC";
        order = order.isEmpty() ? tie : order + ", " + tie;
        String selected = "(SELECT * FROM " + source
                + (where.isEmpty() ? "" : " WHERE " + where)
                + " ORDER BY " + order + " LIMIT 1 BY " + String.join(", ", keys) + ")";
        return new FilteredRows(selected, "", order);
    }

    /** 去重属于结果集操作：从任意条件组提取去重键，其余条件继续按 AND/OR 筛选。 */
    private static void collectDistinctColumns(Map<String, String> types, FilterNode node,
                                               java.util.Set<String> keys) {
        if (node == null) return;
        if (node.getChildren() != null) {
            for (FilterNode child : node.getChildren()) collectDistinctColumns(types, child, keys);
            return;
        }
        if (!"distinct".equals(node.getOp())) return;
        String col = def(node.getColumn()).trim();
        if (col.isEmpty()) return;
        if (!types.containsKey(col)) throw ApiException.validation("未知去重字段 \"" + col + "\"");
        keys.add(CkColumns.refExpr(Sql.quoteCol(col), types.get(col)));
    }

    /** 是否存在过滤节点(允许直接提交单个叶子条件)。 */
    private static boolean hasFilter(FilterNode root) {
        return root != null;
    }

    /**
     * 由过滤条件树生成 WHERE 子句(根为空组时返回空串)。递归,分组自动加括号支持任意嵌套。
     * 对应 Go buildWhere/buildNodeWhere。
     */
    static String buildWhere(Map<String, String> types, FilterNode root) {
        // 根节点为空或没有子条件时返回空串(即无条件)。
        if (root == null) {
            return "";
        }
        // 递归构建根节点下的 WHERE 表达式。
        return buildNodeWhere(types, root);
    }

    // 递归地按节点层级构建 WHERE 片段(组节点加括号、条件节点生成单条表达式)。
    /**
     * 递归生成过滤树的 SQL WHERE 片段。
     *
     * <p>分组节点忽略空子条件，以 {@code AND} 为默认逻辑，显式 {@code or} 时使用 {@code OR}，
     * 多子项整体加括号以保留嵌套优先级。叶子节点要求列名非空；{@code distinct} 不生成布尔
     * 条件，而由上层对筛选结果统一去重。该方法只构造表达式，不执行查询。
     *
     * @param types 列名到 ClickHouse 类型的映射
     * @param n 当前过滤节点
     * @return 可拼接到 WHERE 的表达式；空树或无效节点返回空串
     */
    private static String buildNodeWhere(Map<String, String> types, FilterNode n) {
        if (n == null) return "";
        // 取出当前节点的子条件列表。
        List<FilterNode> children = n.getChildren();
        // 是分组节点:递归拼接其下所有子条件。
        if (children != null) {
            // 存放每个子条件生成的非空片段。
            List<String> parts = new ArrayList<>();
            // 逐个递归子节点。
            for (FilterNode ch : children) {
                // 递归得到子节点片段。
                String sub = buildNodeWhere(types, ch);
                // 空片段(无条件组)跳过。
                if (sub.isEmpty()) {
                    continue;
                }
                // 有效片段加入集合。
                parts.add(sub);
            }
            // 所有子条件皆空则本组为空。
            if (parts.isEmpty()) {
                return "";
            }
            // 只有一个有效子条件时直接透传该片段,免去多余括号。
            if (parts.size() == 1) {
                return parts.get(0);
            }
            // 默认组内逻辑连接词为 AND。
            String logic = "AND";
            // 显式声明 or 时改用 OR。
            if (n.getLogic() != null && "or".equalsIgnoreCase(n.getLogic())) {
                logic = "OR";
            }
            // 多个子条件用逻辑词拼接并整体加括号,保证嵌套优先级。
            return "(" + String.join(" " + logic + " ", parts) + ")";
        }
        // 是叶子条件:列名为空时视为无效条件,返回空串。
        if (n.getColumn() == null || n.getColumn().trim().isEmpty()) {
            return "";
        }
        // 去重不参与布尔比较，由 filteredRows 在普通条件筛选后统一执行。
        if ("distinct".equals(n.getOp())) return "";
        // 按 列/操作符/值 生成单条比较表达式(numeric=true 时 String 列按数值比较)。
        return buildCond(types, n.getColumn(), n.getOp(), n.getValue(), Boolean.TRUE.equals(n.getNumeric()));
    }

    /** 生成单条过滤条件表达式。对应 Go buildCond(numeric=false)。 */
    private static String buildCond(Map<String, String> types, String column, String op, Object value) {
        return buildCond(types, column, op, value, false);
    }

    /**
     * 生成单条过滤条件表达式(列名先引用;值按列类型转字面量)。对应 Go buildCondNum。
     *
     * @param numeric true 时把 String 列按数值比较(左边 toFloat64OrZero,右边字面量按数值渲染)
     */
    static String buildCond(Map<String, String> types, String column, String op, Object value, boolean numeric) {
        // 去掉列名两侧空白。
        String col = column.trim();
        // 取该列的类型。
        String typ = types.get(col);
        // 列不在表结构中则报未知字段。
        if (typ == null) {
            throw ApiException.validation(String.format("未知字段 \"%s\"", col));
        }
        // 列名套反引号引用,防注入;再按类型改写引用 —— 聚合状态列裸比会在 ClickHouse 侧报
        // Cannot parse input: expected '(' …,只能比它 toString 出来的文本(与网格显示一致)。
        String qc = CkColumns.refExpr(Sql.quoteCol(col), typ);
        // 字符串列 + 数值比较 → 左边转数值,右边的字面量也按数值渲染(对齐 Go buildCondNum)。
        if (numeric && isStringTypeFilter(typ)) {
            qc = "toFloat64OrZero(" + qc + ")";
            typ = "Float64";
        }
        // 操作符缺省视为空串。
        String o = op == null ? "" : op;
        // 按操作符分发到对应表达式生成。
        switch (o) {
            // 相等判断。
            case "eq":
                return qc + " = " + sqlLiteral(typ, value);
            // 不等判断。
            case "ne":
                return qc + " != " + sqlLiteral(typ, value);
            // 大于判断。
            case "gt":
                return qc + " > " + sqlLiteral(typ, value);
            // 大于等于判断。
            case "gte":
                return qc + " >= " + sqlLiteral(typ, value);
            // 小于判断。
            case "lt":
                return qc + " < " + sqlLiteral(typ, value);
            // 小于等于判断。DateTime 列的"只到天"上界补到当天 23:59:59,否则漏掉当天数据。
            case "lte": {
                Object v = value;
                if (isTimeBearingType(typ) && value instanceof String) {
                    v = expandRangeBound((String) value, true);
                }
                return qc + " <= " + sqlLiteral(typ, v);
            }
            // LIKE 类模糊匹配(含排除与前后缀)。
            case "like":
            case "nlike":
            case "starts":
            case "ends": {
                // 默认 LIKE 关键字。
                String opKw = "LIKE";
                // not like 需反转排除匹配。
                if ("nlike".equals(o)) {
                    opKw = "NOT LIKE";
                }
                // LIKE 模式必须是字符串字面量 + 显式 ESCAPE,避免值里的 %/_ 被当通配符。
                // patternOf 先给值里的 %/_ 加反斜杠,quoteString 再把反斜杠自身二次转义(CH 里 \ 是转义符);
                // 两层叠加后配合末尾的 ESCAPE 子句(转义符即反斜杠),用户输入的 %/_ 只按字面量匹配,不会被当成通配符。
                return qc + " " + opKw + " " + Sql.quoteString(patternOf(o, value)) + " ESCAPE '\\\\'";
            }
            // 判空。String 列的空值是空串 '' 而非 NULL(尤其工作流摊平出的非空 String 列),
            // 用 empty()/notEmpty() 同时覆盖 '' 与 NULL;IS NOT NULL 对空串恒为真会让「不为空」变成不过滤。
            case "isnull":
                return isStringTypeFilter(typ) ? "empty(" + qc + ")" : qc + " IS NULL";
            // 判非空。
            case "isnotnull":
                return isStringTypeFilter(typ) ? "notEmpty(" + qc + ")" : qc + " IS NOT NULL";
            // 值列表包含判断。
            case "in":
            // 值列表排除判断。
            case "notin": {
                // 把值拆成列表。
                List<String> vals = splitList(value);
                // 值列表为空则报错提示缺值。
                if (vals.isEmpty()) {
                    throw ApiException.validation(String.format(
                            "字段 \"%s\" 的 %s 条件缺少值", col, o.toUpperCase()));
                }
                // 存放每个值转好的字面量。
                List<String> lits = new ArrayList<>(vals.size());
                // 逐个值按列类型转字面量。
                for (String v : vals) {
                    lits.add(sqlLiteral(typ, v));
                }
                // 根据 in/notin 取对应 SQL 关键字。
                String opKw = "in".equals(o) ? "IN" : "NOT IN";
                // 生成 "col IN (v1, v2)" 表达式。
                return qc + " " + opKw + " (" + String.join(", ", lits) + ")";
            }
            // 区间范围判断。
            case "between": {
                // 拆出上下界两值。
                String[] pair = splitBetween(value);
                // 不足两个值则报错。
                if (pair == null) {
                    throw ApiException.validation(String.format(
                            "字段 \"%s\" 的 BETWEEN 条件需两个值(逗号分隔)", col));
                }
                // DateTime 列:下界补到当天 00:00:00、上界补到当天 23:59:59,否则漏掉边界当天数据。
                String lo = pair[0];
                String hi = pair[1];
                if (isTimeBearingType(typ)) {
                    lo = expandRangeBound(lo, false);
                    hi = expandRangeBound(hi, true);
                }
                // 生成 "col BETWEEN lo AND hi" 表达式。
                return qc + " BETWEEN " + sqlLiteral(typ, lo) + " AND " + sqlLiteral(typ, hi);
            }
            // 未知操作符一律拒绝。
            default:
                throw ApiException.validation(String.format("不支持的操作符 \"%s\"", def(op)));
        }
    }

    /** 只到天的日期字面量正则:2026-01-31。对应 Go dateOnlyRe。 */
    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** 是否 String/FixedString 列(剥 Nullable/LowCardinality)。对应 Go isStringType。 */
    private static boolean isStringTypeFilter(String ctype) {
        String lower = ctype == null ? "" : ctype.toLowerCase();
        lower = stripWrap(lower, "nullable(");
        lower = stripWrap(lower, "lowcardinality(");
        return lower.startsWith("string") || lower.startsWith("fixedstring");
    }

    /**
     * 是否【带时分秒】的时间列(DateTime/DateTime64)。对应 Go isDateTimeType(表过滤版)。
     * ⚠️ 不含 Date/Date32:天粒度列补 " 00:00:00" 后 CK 无法与 Date 比较,整次筛选 502。
     */
    private static boolean isTimeBearingType(String ctype) {
        String lower = ctype == null ? "" : ctype.toLowerCase();
        lower = stripWrap(lower, "nullable(");
        lower = stripWrap(lower, "lowcardinality(");
        return lower.startsWith("datetime");
    }

    /** 若 s 形如 {@code name(...)} 则返回括号内内容,否则原样返回(单层)。 */
    private static String stripWrap(String s, String prefix) {
        if (s.startsWith(prefix) && s.endsWith(")")) {
            return s.substring(prefix.length(), s.length() - 1);
        }
        return s;
    }

    /**
     * 把"只到天"的边界补全成当天的起止时刻。对应 Go expandRangeBound。
     * 不补会静默漏数据:日期范围选到 2026-01-31 时,该值被当成当天 00:00:00,整整一天被排除。
     */
    static String expandRangeBound(String v, boolean upper) {
        String s = v == null ? "" : v.trim();
        if (!DATE_ONLY.matcher(s).matches()) {
            return v;
        }
        return upper ? s + " 23:59:59" : s + " 00:00:00";
    }

    /** 转义 LIKE 模式里的通配符,使输入按字面量匹配(配合 ESCAPE '\')。对应 Go escapeLike。 */
    private static String escapeLike(String s) {
        // 顺序不能换:必须先转义反斜杠本身,否则后两步为 %/_ 添加的反斜杠会被再转义一次。
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 计算 LIKE 通配模式(值里的 %/_ 先转义,再包裹通配)。对应 Go patternOf。 */
    private static String patternOf(String op, Object value) {
        // 值为 null 时模式为空;like 下最终成为 '%%'(匹配所有),属前端漏填值的退化结果。
        if (value == null) {
            return "";
        }
        // 先把值里的通配符转义掉。
        String s = escapeLike(String.valueOf(value));
        // 按匹配类型生成前后缀包裹模式。
        switch (op) {
            // 包含式匹配:值两侧加 %。
            case "like":
            case "nlike":
                return "%" + s + "%";
            // 前缀匹配:尾部加 %。
            case "starts":
                return s + "%";
            // 后缀匹配:头部加 %。
            case "ends":
                return "%" + s;
            // 其它操作符原样返回。
            default:
                return s;
        }
    }

    /**
     * 把 IN/BETWEEN 的值拆成列表(支持 JSON 数组或逗号分隔字符串)。对应 Go splitList。
     * null → 空;数组元素逐个字符串化(不去空白、空元素保留);字符串才按逗号切分并去空白与空项。
     */
    @SuppressWarnings("unchecked")
    static List<String> splitList(Object v) {
        // 结果列表容器。
        List<String> out = new ArrayList<>();
        // null 输入直接返回空列表。
        if (v == null) {
            return out;
        }
        // 输入是集合:直接遍历各元素(与下面的字符串分支不同,不去空白也不丢空元素,保持"逐个值"语义)。
        if (v instanceof List) {
            for (Object e : (List<Object>) v) {
                // null 元素记为空串,否则字符串化。
                out.add(e == null ? "" : String.valueOf(e));
            }
        // 输入是字符串:按逗号切分。
        } else if (v instanceof String) {
            // 逐个逗号分隔片段处理。
            for (String p : ((String) v).split(",")) {
                // 去除片段空白。
                p = p.trim();
                // 非空片段才加入结果。
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        // 其它类型整体作为单元素。
        } else {
            out.add(String.valueOf(v));
        }
        // 返回拆分后的值列表。
        return out;
    }

    /** 把 BETWEEN 的值拆成 [lo, hi];不足两个返回 null。对应 Go splitBetween。 */
    private static String[] splitBetween(Object v) {
        // 先按通用逻辑拆分值。
        List<String> vals = splitList(v);
        // 不足两项无法构成区间,返回 null 交由上层报错。
        if (vals.size() < 2) {
            return null;
        }
        // 只取前两项作下界/上界:多给的值静默忽略,不报错。
        return new String[]{vals.get(0), vals.get(1)};
    }

    // =====================================================================
    // CSV 导出(对应 Go PrepareExport / TableExportPlan)
    // =====================================================================

    /** 单次导出的硬上限,与 SQL 结果导出共用(对应 Go maxExportRows)。 */
    public static final int MAX_EXPORT_ROWS = 2_000_000;

    /**
     * 组装 ORDER BY(列必须在表结构里,防注入)。对应 Go orderBySQL。
     *
     * <p>未知列<b>跳过而不是报错</b>:排序是展示层面的偏好,前端换过列之后旧排序残留在请求里
     * 很常见,为此把整次查询打回去不划算(与 Go 一致)。全被跳过时返回空串,即不排序。
     */
    static String orderBySql(Map<String, String> types, List<SortSpec> orders) {
        // 没有排序要求就直接不加 ORDER BY。
        if (orders == null || orders.isEmpty()) {
            return "";
        }
        // 逐个排序列渲染;校验不过的跳过。
        List<String> parts = new ArrayList<>(orders.size());
        for (SortSpec o : orders) {
            // 空项或没给列名 → 跳过。
            if (o == null || o.getColumn() == null) {
                continue;
            }
            // 列不在表结构里 → 跳过(拼进 SQL 轻则语法错,重则注入)。
            String typ = types.get(o.getColumn());
            if (typ == null) {
                continue;
            }
            // 列名套反引号引用。
            String col = Sql.quoteCol(o.getColumn());
            // String 列 + 要求按数值排 → 先转数值(见 SortSpec.numeric)。
            if (Boolean.TRUE.equals(o.getNumeric())
                    && typ.toLowerCase(java.util.Locale.ROOT).contains("string")) {
                col = "toFloat64OrZero(" + col + ")";
            }
            // 方向:未声明即升序。
            parts.add(col + (o.isDesc() ? " DESC" : " ASC"));
        }
        return String.join(", ", parts);
    }

    /** 分隔符归一:{@code \t} / {@code tab} → 制表符,空 → 逗号。对应 Go 的两处 switch。 */
    public static String normalizeDelimiter(String delimiter) {
        // null 兜空串。
        String d = def(delimiter);
        if (d.isEmpty()) {
            return ",";
        }
        // 前端把制表符写成 "\t" 或 "tab",两种都认。
        if ("\\t".equals(d) || "tab".equals(d)) {
            return "\t";
        }
        return d;
    }

    /** CSV 表头名:逐列取用户别名,该位缺失或为空则用原列名。对应 Go 的表头组装。 */
    public static List<String> headerNames(List<String> exportCols, List<String> reqHeaders) {
        // 结果容器。
        List<String> out = new ArrayList<>(exportCols.size());
        // 逐个导出列确定表头文本。
        for (int i = 0; i < exportCols.size(); i++) {
            // 默认原列名。
            String h = exportCols.get(i);
            // 用户在对应位置给了非空别名则替换(按下标对齐,不按列名查)。
            if (reqHeaders != null && i < reqHeaders.size()
                    && reqHeaders.get(i) != null && !reqHeaders.get(i).isEmpty()) {
                h = reqHeaders.get(i);
            }
            out.add(h);
        }
        return out;
    }

    /** 导出条数封顶:0/负 = 不限;超过硬上限按上限处理。对应 Go clampExportLimit。 */
    public static int clampExportLimit(Integer limit) {
        // null 或非正视为不限。
        int n = (limit == null || limit <= 0) ? 0 : limit;
        // 超过硬上限就压到上限 —— 两条导出路径必须同一口径,
        // 否则「一条静默截到 200 万、一条照发」最容易让人以为它没截断。
        return n > MAX_EXPORT_ROWS ? MAX_EXPORT_ROWS : n;
    }

    /**
     * 表数据导出计划:先校验、后写出。对应 Go TableExportPlan。
     *
     * <p>为什么拆两步:流式响应一旦写出第一个字节,状态码与响应头就定死了 ——
     * 「列不存在」这类错误必须在准备阶段抛出,那时还能返回正常的 JSON 错误对象。
     */
    public static final class TableExportPlan implements AutoCloseable {

        /** 本次导出专用的连接(不占用连接池里那条,见 ConnectionService.openDedicated)。 */
        private final CkClient client;

        /** 基础查询(列 + WHERE + ORDER BY);LIMIT 与 SETTINGS 在写出时再拼。 */
        private final String sel;

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

        /** 行流开启器(生产实现 = 真查询;单测换成内存假流)。 */
        private final RowOpener opener;

        /** 全参构造:仅由 prepareExport 与测试使用。 */
        TableExportPlan(CkClient client, String sel, List<String> exportCols, List<String> headerNames,
                        boolean withHeader, String delim, int limit, RowOpener opener) {
            // 逐项保存,不做加工。
            this.client = client;
            this.sel = sel;
            this.exportCols = exportCols;
            this.headerNames = headerNames;
            this.withHeader = withHeader;
            this.delim = delim;
            this.limit = limit;
            this.opener = opener;
        }

        /** 导出列名(供调用方回显/日志)。 */
        public List<String> columns() {
            return exportCols;
        }

        /** 执行查询并把 CSV 写进 w,返回写出的数据行数(不含表头)。 */
        public long writeTo(Writer w) throws IOException {
            return writeTo(w, opener);
        }

        /** 可注入行流的写法:表头开关、分隔符、转义、行数统计都在这里,单测直接调它。 */
        long writeTo(Writer w, RowOpener open) throws IOException {
            // 拼最终语句:LIMIT 在前,SETTINGS 必须排在最后。
            StringBuilder sql = new StringBuilder(sel);
            if (limit > 0) {
                sql.append(" LIMIT ").append(limit);
            }
            // 关掉驱动的结果行数兜底(max_result_rows=10000),否则大导出会被悄悄截断。
            sql.append(" SETTINGS max_result_rows = 0");

            // CSV 写出器(逐行写,不在服务端攒整份)。
            final Csv.Sink sink = new Csv.Sink(w, delim);
            // ⚠️ 表头两个条件都要看:withHeader 决定写不写,headerNames 决定用哪些名字。
            //    以前只判了表头名非空就写,于是前端关掉「包含表头」也照样导出表头。
            if (withHeader) {
                sink.header(headerNames);
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
                    // 导出列由计划定,不看流返回的列名。
                }
                /**
                 * 接收流式查询返回的一行数据。
                 *
                 * @param row 当前行数据
                 */
                @Override
                public void row(List<Object> row) throws IOException {
                    // 写一行、计数一次。
                    sink.row(row);
                }
            });
            return sink.rows();
        }

        /** 释放本次导出专用连接。 */
        @Override
        public void close() {
            // 这条连接只归本计划所有,用完即关(池里那条不受影响)。
            client.close();
        }
    }

    /**
     * 导出前的准备:列校验、WHERE / ORDER BY 组装、分隔符与表头归一。对应 Go PrepareExport。
     *
     * <p>只探列(DESCRIBE),不读任何数据行;真正读数据发生在 {@link TableExportPlan#writeTo}。
     */
    public TableExportPlan prepareExport(long connId, String db, String table, ExportRequest req) {
        access.table(connId, db, table, true);
        // 请求为空时按空请求处理(全列、无过滤、无排序)。
        ExportRequest in = req == null ? new ExportRequest() : req;
        // 库表名先过标识符白名单。
        String q = qualified(db, table);
        // 为本次导出开一条专用连接。
        CkClient client = identities.openDedicated(connId, null);
        // 准备阶段失败时必须把刚开的连接关掉,否则每次失败的导出都漏一条连接。
        boolean ok = false;
        try {
            // 列类型表:过滤转义、指定列校验、排序校验都靠它。
            // 注意它是有序的 LinkedHashMap,键序就是 DESCRIBE 的列序,可直接当「未指定列」的默认列序。
            Map<String, String> types = readColumnTypes(client, q);
            // 导出列:未指定时按表结构顺序(不能用哈希序,否则导出的列序每次都可能不同)。
            List<String> exportCols = in.getColumns();
            if (exportCols == null || exportCols.isEmpty()) {
                exportCols = new ArrayList<>(types.keySet());
            }
            // 逐列校验存在性并按列类型改写后引用。这里必须和数据页走同一个 safeSelectColumn,
            // 不能像以前那样直接 Sql.quoteCol:否则聚合表(SimpleAggregateFunction 列)能导出失败
            // 报 Unknown data type,而 Bool 列也会与网格显示的口径不一致(true/false vs 1/0)。
            List<String> quoted = new ArrayList<>(exportCols.size());
            for (String c : exportCols) {
                if (!types.containsKey(c)) {
                    throw ApiException.validation(String.format("未知字段 \"%s\"", c));
                }
                quoted.add(safeSelectColumn(c, types.get(c)));
            }
            // WHERE:与数据页同源,所以「看到的行」=「导出的行」。
            FilteredRows filtered = filteredRows(types, q, in.getFilter(), in.getOrders());
            String where = filtered.where;
            // 组装基础 SELECT。
            StringBuilder sel = new StringBuilder("SELECT ").append(String.join(", ", quoted))
                    .append(" FROM ").append(filtered.source);
            if (!where.isEmpty()) {
                sel.append(" WHERE ").append(where);
            }
            // ORDER BY:与数据页同一份渲染,所以「看到的顺序」=「导出的顺序」。
            String orderBy = filtered.orderBy;
            if (!orderBy.isEmpty()) {
                sel.append(" ORDER BY ").append(orderBy);
            }
            // 分隔符、表头、条数上限的归一。
            String delim = normalizeDelimiter(in.getDelimiter());
            List<String> names = headerNames(exportCols, in.getHeaderNames());
            int limit = clampExportLimit(in.getLimit());
            // 计划建好,连接的所有权移交出去(由 close() 负责关)。
            TableExportPlan plan = new TableExportPlan(client, sel.toString(), exportCols, names,
                    Boolean.TRUE.equals(in.getWithHeader()), delim, limit, RowOpener.of(client));
            ok = true;
            return plan;
        } finally {
            // 没走到「计划建好」就把连接收掉,不留悬挂连接。
            if (!ok) {
                client.close();
            }
        }
    }

    // 建/删/清空/结构变更
    // =====================================================================

    /** 删除表(危险,前端二次确认)。 */
    public void drop(long connId, String db, String table) {
        try (TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            dropWhileIdle(connId, db, table);
        }
    }

    private void dropWhileIdle(long connId, String db, String table) {
        // 只读连接先拒绝该 DDL。
        checkWritable(connId);
        tableReferences.requireModificationAllowed(connId, db, table);
        // 受控生命周期:所有者校验、撤权核对、精确 DROP、释放表名。
        lifecycle.drop(connId, db, table);
        statsCache.invalidate(com.el2ck.config.AuthContext.userId(), connId, db);
        // 分组归属联动:表已经不在了,分组里若还留着这个名字,侧栏就会显示一张点不开的死表,
        // 分组的 count 也会多算一个。对应 Go 的 RemoveTableGroupMembers。
        tableGroups.removeTableMembers(connId, db, table);
    }

    /** 清空表(危险,前端二次确认)。 */
    public void truncate(long connId, String db, String table) {
        try (TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            truncateWhileIdle(connId, db, table);
        }
    }

    private void truncateWhileIdle(long connId, String db, String table) {
        access.table(connId, db, table, true);
        // 只读连接先拒绝该 DDL。
        checkWritable(connId);
        tableReferences.requireModificationAllowed(connId, db, table);
        // 执行 TRUNCATE TABLE IF EXISTS(带 SYNC 同步清空落盘)。
        exec(identities.client(connId), "TRUNCATE TABLE IF EXISTS " + qualified(db, table) + " SYNC");
    }

    /**
     * 按用户提供的建表 DDL 创建表。
     * 只读连接拒绝;且仅允许 CREATE,防止借建表接口执行 DROP/INSERT 等。
     */
    public void createBySQL(long connId, String db, String ddl) {
        try (TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            createBySQLWhileIdle(connId, db, ddl);
        }
    }

    private void createBySQLWhileIdle(long connId, String db, String ddl) {
        // 只读连接禁止建表。
        checkWritable(connId);
        // 去掉用户 DDL 首尾多余分号,null 兜底空串。
        ddl = Sql.trimTrailingSemicolons(def(ddl));
        // 建表语句为空则报错。
        if (ddl.isEmpty()) {
            throw ApiException.validation("建表 DDL 不能为空");
        }
        // 首关键字必须是 CREATE,拦截 DROP/INSERT 等借道执行。
        if (!"CREATE".equals(Sql.firstKeyword(ddl))) {
            throw ApiException.validation("仅支持 CREATE 建表语句");
        }
        // 仅允许单条语句,按分号切分数量须为 1。
        if (Sql.splitStatements(ddl).size() != 1) {
            throw ApiException.validation("建表接口仅允许执行一条 CREATE 语句");
        }
        // 受控生命周期:占用表名 → 临时精确 CREATE 权限 → 以用户身份执行 → 核对 → 授权。
        tableReferences.requireSqlModificationAllowed(connId);
        lifecycle.create(connId, db, ddl, "manual", null);
    }

    /** 按当前业务身份读取单表注释，编辑失败时不能用空值覆盖已有注释。 */
    public String comment(long connId, String db, String table) {
        access.table(connId, db, table, false);
        QueryResult result = query(identities.client(connId),
                "SELECT comment FROM system.tables WHERE database = ? AND name = ?",
                java.util.Arrays.<Object>asList(db, table));
        if (result.getRows().isEmpty()) throw ApiException.notFound("目标表不存在或不可见");
        return def((String) result.getRows().get(0).get(0));
    }

    /**
     * 执行表结构变更:先改表名(可选),再逐条列变更及表注释变更。
     * 对应 Go Alter:Action=rename/modify/add/drop;类型大类不可变(前端锁定),仅参数可调。
     */
    public void alter(long connId, String db, String table, TableAlterRequest req) {
        try (TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            alterWhileIdle(connId, db, table, req, gate);
        }
    }

    private void alterWhileIdle(long connId, String db, String table, TableAlterRequest req, TableChangeGate.Lease gate) {
        // 请求为空则直接报错。
        if (req == null) {
            throw ApiException.validation("表结构变更内容不能为空");
        }
        // 只允许所有者修改结构;共享表只能查看。
        access.table(connId, db, table, true);
        // 只读连接禁止结构变更。
        checkWritable(connId);
        tableReferences.requireModificationAllowed(connId, db, table);
        // 当前生效的表名,随改名步骤更新。
        String curTable = table;

        // 1. 改表名(先执行,后续列变更引用新表名)。
        // 期望的新表名,null 兜底空串。
        String newTable = def(req.getNewTableName());
        // 提供了不同的新表名才需要改名。
        if (!newTable.isEmpty() && !newTable.equals(table)) {
            // 引用新表名。
            String newT = Sql.quoteIdent(newTable);
            tableReferences.requireRenameAllowed(connId, db, table);
            // 受控改名:同步迁移表资产与 CK 授权对象名。
            lifecycle.rename(connId, db, table, newT);
            // 分组归属联动:归属记录里存的还是旧表名,不迁移的话这张表会掉进「未分组」、
            // 而分组里留着一个点不开的旧名。对应 Go 的 RenameTableGroupMembers。
            tableGroups.renameTableMembers(connId, db, curTable, newT);
            // 后续列变更改用新表名。
            curTable = newT;
        }
        // 获取目标连接客户端(改名列变更使用用户身份)。
        CkClient client = identities.client(connId);

        // 2. 逐条列变更。
        // 请求携带了列变更清单。
        if (req.getColumns() != null) {
            // 逐条处理列变更。
            for (ColumnAlter c : req.getColumns()) {
                gate.check();
                // 变更项为空则报错。
                if (c == null) {
                    throw ApiException.validation("字段变更项不能为空");
                }
                // 按当前表名拼限定名。
                String q = qualified(db, curTable);
                // 变更动作名,null 兜底空串。
                String action = def(c.getAction());
                // 分动作执行,目标库失败统一转 502。
                try {
                    // 按动作类型分发。
                    switch (action) {
                        // 改列名。
                        case "rename": {
                            // 引用旧列名。
                            String oldN = Sql.quoteIdent(c.getOldName());
                            // 引用新列名。
                            String newN = Sql.quoteIdent(c.getNewName());
                            // 执行 RENAME COLUMN。
                            client.exec("ALTER TABLE " + q + " RENAME COLUMN " + oldN + " TO " + newN);
                            break;
                        }
                        // 修改列定义。
                        case "modify": {
                            // 构造列定义片段。
                            String def2 = buildColumnDef(c);
                            // 执行 MODIFY COLUMN。
                            client.exec("ALTER TABLE " + q + " MODIFY COLUMN " + def2);
                            break;
                        }
                        // 新增列。
                        case "add": {
                            // 构造列定义片段。
                            String def2 = buildColumnDef(c);
                            // 执行 ADD COLUMN。
                            client.exec("ALTER TABLE " + q + " ADD COLUMN " + def2);
                            break;
                        }
                        // 删除列。
                        case "drop": {
                            // 引用待删列名。
                            String n = Sql.quoteIdent(c.getOldName());
                            // 执行 DROP COLUMN。
                            client.exec("ALTER TABLE " + q + " DROP COLUMN " + n);
                            break;
                        }
                        // 未知动作拒绝。
                        default:
                            throw ApiException.validation(String.format("不支持的变更类型 \"%s\"", action));
                    }
                } catch (ApiException e) {
                    // 业务校验异常原样上抛。
                    throw e;
                } catch (RuntimeException e) {
                    // 其余运行时异常(目标库失败)统一转 502。
                    throw ApiException.badGateway(Sql.messageOf(e));
                }
            }
        }
        if (req.getTableComment() != null) {
            gate.check();
            lifecycle.modifyComment(connId, db, curTable, req.getTableComment());
        }
    }

    /**
     * 构造 MODIFY/ADD COLUMN 的列定义片段。
     * 名称/类型先校验;Nullable 包裹 + DEFAULT + COMMENT 按需拼接。对应 Go buildColumnDef。
     */
    private static String buildColumnDef(ColumnAlter c) {
        // 取新列名,null 兜底空串。
        String name = def(c.getNewName());
        // 新列名为空时回退用旧列名(如 modify 场景)。
        if (name.isEmpty()) {
            name = def(c.getOldName());
        }
        // 引用列名。
        String n = Sql.quoteIdent(name);
        // 取并去除类型两侧空白。
        String typ = def(c.getType()).trim();
        // 类型缺失报错。
        if (typ.isEmpty()) {
            throw ApiException.validation(String.format("列 \"%s\" 缺少类型", name));
        }
        // 列类型走与表体检同一套护栏(DdlGuard,对应 Go validateColumnType):
        // 按括号深度判顶层逗号,既放行 DateTime64(3, 'Asia/Shanghai') 这类合法类型,
        // 又拦住 "Int32, DROP COLUMN y" 这种夹带第二个动作的片段。
        // ⚠️ 不要退回字符集白名单:两套规则并存时,体检说「这一列在平台上无法编辑」而建表放行,
        //    报告就是在说假话。
        String typeErr = DdlGuard.validateColumnType(typ);
        if (typeErr != null) {
            throw ApiException.validation(typeErr);
        }
        // 基础列定义:引用列名 + 类型。
        String def2 = n + " " + typ;
        // 需要可空且类型尚未被 Nullable 包裹时,整体包一层 Nullable()。
        if (Boolean.TRUE.equals(c.getNullable()) && !typ.toUpperCase().startsWith("NULLABLE(")) {
            def2 = n + " Nullable(" + typ + ")";
        }
        // 配置了非空默认值则拼接 DEFAULT 子句。默认值同样是用户输入、会直接进 DDL,必须过护栏。
        String dv = def(c.getDefaultValue());
        if (!dv.isEmpty()) {
            String dvErr = DdlGuard.validateDefaultExpr(dv);
            if (dvErr != null) {
                throw ApiException.validation(dvErr);
            }
            def2 += " DEFAULT " + dv;
        }
        // 配置了非空注释则拼 COMMENT。用 Sql.quoteString:它同时转义反斜杠与单引号,
        // 手写 replace("'", "''") 漏了反斜杠(而 ClickHouse 里 \ 本身就是转义符)。
        if (c.getComment() != null && !c.getComment().isEmpty()) {
            def2 += " COMMENT " + Sql.quoteString(c.getComment());
        }
        // 返回完整列定义片段。
        return def2;
    }

    // =====================================================================
    // 数据增删改(单元格级,对应 Go EditData)
    // =====================================================================

    /**
     * 执行表数据的插入/更新/删除,`mutations_sync=2` 等待同步完成(持久化)。
     * KeyColumns 为定位行的键列。值可为字符串或 null(按列类型格式化)。
     */
    public DataEditResult editData(long connId, String db, String table, DataEditRequest req) {
        try (TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            return editDataWhileIdle(connId, db, table, req);
        }
    }

    private DataEditResult editDataWhileIdle(long connId, String db, String table, DataEditRequest req) {
        // 请求为空直接报错。
        if (req == null) {
            throw ApiException.validation("数据编辑内容不能为空");
        }
        access.table(connId, db, table, true);
        tableReferences.requireModificationAllowed(connId, db, table);
        // 拼接校验后的 "库.表" 限定名。
        String q = qualified(db, table);
        // 待插入的行集合(可能为 null)。
        List<Map<String, Object>> inserts = req.getInserts();
        // 待更新的行集合(键值对+新值)。
        List<RowUpdate> updates = req.getUpdates();
        // 待删除的行定位集合。
        List<Map<String, Object>> deletes = req.getDeletes();
        // 是否存在插入操作。
        boolean hasI = inserts != null && !inserts.isEmpty();
        // 是否存在更新操作。
        boolean hasU = updates != null && !updates.isEmpty();
        // 是否存在删除操作。
        boolean hasD = deletes != null && !deletes.isEmpty();
        // 三种操作都为空时返回零统计结果。
        if (!hasI && !hasU && !hasD) {
            // 空请求直接返回零统计。
            // 构造空结果对象。
            DataEditResult empty = new DataEditResult();
            // 插入数置 0。
            empty.setInserted(0);
            // 更新数置 0。
            empty.setUpdated(0);
            // 删除数置 0。
            empty.setDeleted(0);
            // 直接返回。
            return empty;
        }
        // 只读模式拦截。
        // 取该连接的有效配置。
        CkConfig cfg = connSvc.effectiveConfig(connId);
        // 只读连接禁止任何数据改动。
        if (cfg.isReadOnly()) {
            throw ApiException.readOnly("当前连接为只读模式,不允许修改数据");
        }
        // 获取目标连接客户端。
        CkClient client = identities.client(connId);
        // 列类型映射,用于格式化字面量。
        // 读取全表 列名 → 类型 映射。
        Map<String, String> types = columnTypes(client, q);

        // 定位键完整性先校验(任何一条不合规就整单拒绝,不留改一半的中间状态)。
        // 键列来源:请求声明了就用它(前端总会带);没声明就从表结构取主键/排序键兜底 ——
        // 这条兜底不能省:直连接口只要「少给一个键列」就会命中所有同键行。
        checkDataEditKeys(types, req, tableKeyColumns(client, db, table, types));

        // 构造操作结果对象。
        DataEditResult res = new DataEditResult();
        // 记录预计插入行数。
        res.setInserted(inserts == null ? 0 : inserts.size());
        // 记录预计更新行数。
        res.setUpdated(updates == null ? 0 : updates.size());
        // 记录预计删除行数。
        res.setDeleted(deletes == null ? 0 : deletes.size());

        // 有插入操作时批量执行插入。
        if (hasI) {
            execInserts(client, q, types, inserts);
        }
        // 有更新操作时逐行执行更新。
        if (hasU) {
            for (RowUpdate u : updates) {
                // 更新行条目为空报错。
                if (u == null) {
                    throw ApiException.validation("更新行不能为空");
                }
                // 单行更新走 mutations 语句。
                execUpdate(client, q, types, u);
            }
        }
        // 有删除操作时批量执行删除。
        if (hasD) {
            execDeletes(client, q, types, deletes);
        }
        // 返回操作统计结果。
        return res;
    }

    /** 批量插入：按实际填写的字段集合分组，未填写列继续使用 ClickHouse 默认值。 */
    private void execInserts(CkClient client, String q, Map<String, String> types, List<Map<String, Object>> rows) {
        // 按各行使字段集合一致的分组。
        Map<List<String>, List<Map<String, Object>>> groups = groupInsertRows(types.keySet(), rows);
        // 逐组执行批量插入。
        for (Map.Entry<List<String>, List<Map<String, Object>>> group : groups.entrySet()) {
            // 每组字段集合一致,生成一条多行 VALUES 插入。
            execInsertGroup(client, q, types, group.getKey(), group.getValue());
        }
    }

    // 把插入行按"实际出现的字段集合"分组(字段集合相同的行可合并为一条多行 INSERT)。
    static Map<List<String>, List<Map<String, Object>>> groupInsertRows(
            Iterable<String> allowedColumns, List<Map<String, Object>> rows) {
        // 分组结果:字段集合 → 该集合的行列表。
        Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        // 空行集合直接返回空分组。
        if (rows == null) {
            return groups;
        }
        // 逐行归类。
        for (Map<String, Object> r : rows) {
            // 空行对象报错。
            if (r == null) {
                throw ApiException.validation("新增行不能为空");
            }
            // 记录该行实际出现的字段(按 allowedColumns 顺序收集)。
            List<String> present = new ArrayList<>();
            // 遍历全表列判断该行是否填写。
            for (String c : allowedColumns) {
                // 该行显式包含了此列才记入字段集合。
                if (r.containsKey(c)) {
                    present.add(c);
                }
            }
            // 一行都未填写任何字段则无法插入,报错。
            if (present.isEmpty()) {
                throw ApiException.validation("没有可插入的字段");
            }
            // 同字段集合分到一组;该组不存在则新建后追加行。
            groups.computeIfAbsent(present, ignored -> new ArrayList<Map<String, Object>>()).add(r);
        }
        // 返回分组结果。
        return groups;
    }

    // 执行单组(字段集合一致)的多行 VALUES 插入。
    /**
     * 执行一组字段集合一致的多行 INSERT。
     *
     * <p>列名统一引用，单元格按列类型转换为 SQL 字面量，再合并为单条多行 VALUES 语句以减少
     * ClickHouse 往返次数。该方法不开启应用层事务；执行失败由底层 {@code exec} 统一传播。
     *
     * @param client 目标 ClickHouse 客户端
     * @param q 已安全引用的目标表表达式
     * @param types 列名到类型的映射
     * @param cols 本组所有行共同包含的列
     * @param rows 待插入的行
     */
    private void execInsertGroup(CkClient client, String q, Map<String, String> types,
                                 List<String> cols, List<Map<String, Object>> rows) {
        // 存放引用后的列名清单。
        List<String> quotedCols = new ArrayList<>(cols.size());
        // 逐个字段名套反引号。
        for (String c : cols) {
            quotedCols.add(Sql.quoteCol(c));
        }
        // 存放每组行对应的 "(v1, v2)" 元组。
        List<String> tuples = new ArrayList<>(rows.size());
        // 逐行生成 VALUES 元组。
        for (Map<String, Object> r : rows) {
            // 存放该行各列的字面量。
            List<String> vals = new ArrayList<>(cols.size());
            // 逐列把值按类型转字面量。
            for (String c : cols) {
                vals.add(sqlLiteral(types.get(c), r.get(c)));
            }
            // 拼成带括号的单行元组。
            tuples.add("(" + String.join(", ", vals) + ")");
        }
        // 拼装整条 INSERT:列清单 + 多行 VALUES。
        String sql = "INSERT INTO " + q + " (" + String.join(", ", quotedCols)
                + ") VALUES " + String.join(", ", tuples);
        // 执行插入(mutations_sync 语义对 INSERT 立即生效)。
        exec(client, sql);
    }

    /** 单行更新:ALTER TABLE ... UPDATE ... WHERE ... SETTINGS mutations_sync = 2。 */
    private void execUpdate(CkClient client, String q, Map<String, String> types, RowUpdate u) {
        // 定位行的键列 → 值。
        Map<String, Object> keys = u.getKeys();
        // 待修改的字段 → 新值。
        Map<String, Object> values = u.getValues();
        // 缺少定位键或修改字段时无法更新,报错。
        if ((keys == null || keys.isEmpty()) || (values == null || values.isEmpty())) {
            throw ApiException.validation("更新缺少定位键或修改字段");
        }
        // 存放 SET 段的 "列 = 字面量"。
        List<String> sets = new ArrayList<>();
        // 逐个修改字段生成 SET 表达式。
        for (Map.Entry<String, Object> en : values.entrySet()) {
            // 修改的列名。
            String c = en.getKey();
            // 列确实存在时才纳入 SET(避免拼入未知列)。
            if (types.containsKey(c)) {
                sets.add(Sql.quoteCol(c) + " = " + sqlLiteral(types.get(c), en.getValue()));
            }
        }
        // 存放 WHERE 段的 "列 = 字面量"。
        List<String> conds = new ArrayList<>();
        // 逐个定位键生成等值条件。
        for (Map.Entry<String, Object> en : keys.entrySet()) {
            // 定位键的列名。
            String c = en.getKey();
            // 键列确实存在时才纳入 WHERE。
            if (types.containsKey(c)) {
                conds.add(Sql.quoteCol(c) + " = " + sqlLiteral(types.get(c), en.getValue()));
            }
        }
        // SET 或 WHERE 全被过滤掉则无法安全更新,报错。
        if (sets.isEmpty() || conds.isEmpty()) {
            throw ApiException.validation("更新字段或定位键无效");
        }
        // 拼装 ALTER ... UPDATE ... WHERE ... 并开启 mutations_sync=2 同步落盘。
        String sql = "ALTER TABLE " + q + " UPDATE " + String.join(", ", sets)
                + " WHERE " + String.join(" AND ", conds) + " SETTINGS mutations_sync = 2";
        // 执行更新 mutation。
        exec(client, sql);
    }

    /** 批量删除:多行条件 OR 拼接。 */
    private void execDeletes(CkClient client, String q, Map<String, String> types, List<Map<String, Object>> deletes) {
        // 存放每行定位条件的 "(...)" 片段。
        List<String> conds = new ArrayList<>();
        // 逐行构造删除定位条件。
        for (Map<String, Object> d : deletes) {
            // 空定位条件行报错。
            if (d == null) {
                throw ApiException.validation("删除行定位条件不能为空");
            }
            // 存放当前行的等值条件片段。
            List<String> parts = new ArrayList<>();
            // 逐个键值对生成 "列 = 字面量"。
            for (Map.Entry<String, Object> en : d.entrySet()) {
                // 定位键的列名。
                String c = en.getKey();
                // 列存在时才纳入条件。
                if (types.containsKey(c)) {
                    parts.add(Sql.quoteCol(c) + " = " + sqlLiteral(types.get(c), en.getValue()));
                }
            }
            // 本行条件非空时整体加括号后加入集合。
            if (!parts.isEmpty()) {
                conds.add("(" + String.join(" AND ", parts) + ")");
            }
        }
        // 没有任何有效定位条件时直接返回(不误删全表)。
        if (conds.isEmpty()) {
            return;
        }
        // 各行动作视为"删除该行",行间用 OR 连接。
        String sql = "ALTER TABLE " + q + " DELETE WHERE " + String.join(" OR ", conds)
                + " SETTINGS mutations_sync = 2";
        // 执行删除 mutation 并同步落盘。
        exec(client, sql);
    }

    // =====================================================================
    // 类型判定与字面量(对应 Go needsQuote/isNumeric/sqlLiteral/rowCountOf)
    // =====================================================================

    /** 判断该 ClickHouse 类型是否需要用单引号包裹字符串字面量。 */
    static boolean needsQuote(String ctype) {
        // 类型转小写便于比较。
        String lower = ctype.toLowerCase();
        // 字符串族类型(String/FixedString)须加引号。
        if (lower.contains("string") || lower.contains("fixedstring")) {
            return true;
        }
        // 日期/时间类型须加引号。
        if (lower.contains("date") || lower.contains("time")) {
            return true;
        }
        // UUID/Enum/IP 地址类型按文本处理须加引号。
        if (lower.contains("uuid") || lower.contains("enum")
                || lower.contains("ipv4") || lower.contains("ipv6")) {
            return true;
        }
        // Nullable/LowCardinality 外层需递归判断内层。
        if (lower.contains("nullable(") || lower.contains("lowcardinality(")) {
            // 取内层类型递归判断(先 nullable,后 lowcardinality,与 Go 一致)。
            // 逐层剥掉 nullable 外壳。
            String inner = stripOuter(lower, "nullable(");
            // 再剥 lowcardinality 外壳。
            inner = stripOuter(inner, "lowcardinality(");
            // 对剩余内层类型递归判定。
            return needsQuote(inner);
        }
        // 其余数值等类型不加引号。
        return false;
    }

    /** 去掉外层 nullable(...)/lowcardinality(...) 一层包裹(前缀后缀必须同时存在才剥)。 */
    private static String stripOuter(String t, String prefix) {
        // 前缀匹配才尝试剥壳。
        if (t.startsWith(prefix)) {
            // 去掉前缀得到剩余部分。
            String after = t.substring(prefix.length());
            // 剩余部分以右括号收尾则一并去掉。
            if (after.endsWith(")")) {
                return after.substring(0, after.length() - 1);
            }
        }
        // 不满足剥壳条件原样返回。
        return t;
    }

    /** 判断类型是否为数值类型(Int/UInt/Float/Double/Decimal 前缀)。 */
    static boolean isNumeric(String ctype) {
        // 先剥掉 Nullable/LowCardinality 外壳得到基础类型。
        String lower = unwrapType(ctype);
        // 数值类型前缀集合。
        String[] prefixes = {"int", "uint", "float", "double", "decimal"};
        // 逐个前缀比对。
        for (String p : prefixes) {
            // 以某数值前缀开头即判定为数值类型。
            if (lower.startsWith(p)) {
                return true;
            }
        }
        // 都不匹配则非数值。
        return false;
    }

    /** 去掉 Nullable/LowCardinality 外层，供基础类型判定复用。 */
    private static String unwrapType(String ctype) {
        // 空值兜底空串并转小写去空白。
        String value = ctype == null ? "" : ctype.trim().toLowerCase();
        // 本轮是否发生了剥壳。
        boolean changed;
        // 循环直到无可剥的外壳。
        do {
            // 重置本轮改动标记。
            changed = false;
            // 尝试剥 nullable 与 lowcardinality 两类外壳。
            for (String wrapper : new String[]{"nullable(", "lowcardinality("}) {
                // 前后缀同时匹配才剥掉一层。
                if (value.startsWith(wrapper) && value.endsWith(")")) {
                    // 截取外壳内部并去空白。
                    value = value.substring(wrapper.length(), value.length() - 1).trim();
                    // 标记本轮有改动,继续循环。
                    changed = true;
                }
            }
        } while (changed);
        // 返回剥净外壳后的基础类型。
        return value;
    }

    /**
     * 按列类型把值格式化为 SQL 字面量。对应 Go sqlLiteral:
     * 字符串类型转义 \ 与 ';数值列严格校验非数值即报错;Date/UUID/Enum/IP 等走 needsQuote 加引号;null→NULL。
     */
    static String sqlLiteral(String ctype, Object v) {
        // null 值一律转 SQL 的 NULL。
        if (v == null) {
            return "NULL";
        }
        // 值字符串化。
        String s = String.valueOf(v);
        // 文本类类型:转义反斜杠与单引号后加引号。
        if (needsQuote(ctype)) {
            s = s.replace("\\", "\\\\").replace("'", "''");
            return "'" + s + "'";
        }
        // 数值列严格校验。
        if (isNumeric(ctype)) {
            // 拒绝非数值字符串,防止 "1 OR 1=1" 注入数值列。
            // 非数字文本直接抛校验异常。
            if (!isNumberText(s)) {
                throw ApiException.validation(String.format("数值列 \"%s\" 收到非数值值: %s", ctype, s));
            }
        }
        // 剥壳取基础类型判定布尔。
        String baseType = unwrapType(ctype);
        // 布尔列只接受 true/false/1/0 并归一化为 SQL 布尔字面量。
        if ("bool".equals(baseType) || "boolean".equals(baseType)) {
            // 1 或 true 归一化为 true。
            if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
                return "true";
            }
            // 0 或 false 归一化为 false。
            if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
                return "false";
            }
            // 其余值一律拒绝。
            throw ApiException.validation(String.format("布尔列 \"%s\" 仅接受 true/false/1/0: %s", ctype, s));
        }
        // 其它数值/普通类型原样输出文本。
        return s;
    }

    /** 文本是否可解析为数值(对应 Go strconv.ParseFloat 语义的宽松版)。 */
    private static boolean isNumberText(String s) {
        // 空文本不算数值。
        if (s == null || s.isEmpty()) {
            return false;
        }
        // 尝试按 double 解析。
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            // 解析失败视为非数值。
            return false;
        }
    }

    /** 把 count() 返回的数值转成 long(兼容各种数值包装)。对应 Go rowCountOf。 */
    public static long rowCountOf(Object v) {
        // 已是数值对象直接取其 long 值。
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        // 否则按字符串解析;失败归零兜底。
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 只读连接禁止任何 DDL/DML(文案对应 Go TableService.checkWritable)。 */
    private void checkWritable(long connId) {
        // 用当前用户的绑定配置判断只读;管理连接账号不参与业务写判定。
        CkConfig cfg = identities.config(connId);
        // 只读连接抛 403 只读异常。
        if (cfg.isReadOnly()) {
            throw ApiException.readOnly("当前连接为只读模式,不允许执行该操作");
        }
    }

    /**
     * 用 quoteIdent 校验 db/table 标识符并拼 "db.table"。
     * 反引号由 quoteCol 负责;此处只做白名单校验(对应 Go qualified)。
     */
    String qualified(String db, String table) {
        // 校验并引用库名。
        String d = Sql.quoteIdent(db);
        // 校验并引用表名。
        String t = Sql.quoteIdent(table);
        // 拼接成 "库.表" 限定名。
        return d + "." + t;
    }

    /** 把待选列拼成 SELECT 列清单:每列经 safeSelectColumn 引用并按需转写(Bool→toUInt8)。selected 为空时默认取全部列;最终无有效列时返回 '*' 兜底。 */
    private static String safeSelectColumns(Map<String, String> types, List<String> selected) {
        List<String> names = selected == null || selected.isEmpty()
                ? new ArrayList<>(types.keySet()) : selected;
        // 表结构为空(理论上不会)时兜底 *:让执行去报它自己的错,而不是拼出一个空投影。
        return names.isEmpty() ? "*" : CkColumns.projection(names, types);
    }

    /**
     * 单列的 SELECT 表达式:反引号引用列名,再按列类型改写(见 {@link CkColumns})——
     * 聚合状态列 {@code SimpleAggregateFunction(...)} 转成 {@code toString(列) AS 列},
     * Bool 转成 {@code toUInt8(列) AS 列}(供网格/导出按数值读取)。
     *
     * <p>包私有而非 private:项目惯例是把这类 SQL 拼接纯函数降为包私有直接单测
     * (先例:同类的 {@code buildGroupExpr} 与其测试 TableGroupByExprTest)。
     */
    static String safeSelectColumn(String name, String type) {
        // 引用列名(来自表结构,安全可直接拼接),类型改写交给公共工具。
        return CkColumns.selectExpr(Sql.quoteCol(name), type);
    }

    // Views can declare DateTime but return SimpleAggregateFunction(max, DateTime).
    // Inspect the actual projection without reading rows; preserve all declared columns.
    /**
     * 读取表或查询表达式的实际投影列类型。
     *
     * <p>先取得声明类型；若视图把 {@code DateTime} 暴露为聚合状态类型等场景需要以实际查询
     * 投影为准，则只对列执行 {@code DESCRIBE (SELECT ...)}，不读取数据行。映射保留声明列
     * 顺序，并仅用投影结果更新已知列，避免引入额外列。
     *
     * @param client ClickHouse 客户端
     * @param q 已安全引用的表或查询表达式
     * @return 有序的列名到实际类型映射；声明结果为空时返回空映射
     */
    private Map<String, String> readColumnTypes(CkClient client, String q) {
        Map<String, String> types = columnTypes(client, q);
        if (types.isEmpty()) {
            return types;
        }
        List<String> columns = new ArrayList<>();
        for (String name : types.keySet()) {
            columns.add(Sql.quoteCol(name));
        }
        QueryResult actual = query(client, "DESCRIBE (SELECT " + String.join(", ", columns) + " FROM " + q + ")");
        for (List<Object> row : actual.getRows()) {
            if (row.size() >= 2 && types.containsKey(String.valueOf(row.get(0)))) {
                types.put(String.valueOf(row.get(0)), String.valueOf(row.get(1)));
            }
        }
        return types;
    }

    /**
     * 对表或查询表达式执行 DESCRIBE，返回有序的列类型映射。
     *
     * @param client ClickHouse 客户端
     * @param q 已构造的表或子查询表达式
     * @return 列名到类型名的有序映射
     */
    private Map<String, String> columnTypes(CkClient client, String q) {
        // 执行 DESCRIBE 取结构。
        QueryResult desc = query(client, "DESCRIBE TABLE " + q);
        // 保持顺序的 列名 → 类型 映射容器。
        Map<String, String> types = new LinkedHashMap<>();
        // 逐行取列名与类型。
        for (List<Object> row : desc.getRows()) {
            // 至少有名称+类型两列才登记。
            if (row.size() >= 2) {
                types.put(String.valueOf(row.get(0)), String.valueOf(row.get(1)));
            }
        }
        // 返回列类型映射。
        return types;
    }

    /** 从键表达式中提取属于该表的列名(去重,保持表达式顺序)。对应 Go keyColumnsOf。 */
    private static List<String> keyColumnsOf(String expr, java.util.Collection<String> cols) {
        // 结果列表容器。
        List<String> out = new ArrayList<>();
        // 空表达式直接返回空列表。
        if (expr == null || expr.isEmpty()) {
            return out;
        }
        // 去重集合(以列名 → TRUE 记录)。
        Map<String, Boolean> seen = new LinkedHashMap<>();
        // 用正则逐个匹配表达式中的标识符。
        java.util.regex.Matcher m = IDENT_IN_EXPR.matcher(expr);
        // 遍历每个标识符。
        while (m.find()) {
            // 取当前标识符文本。
            String id = m.group();
            // 属于该表真实列且未出现过才收录。
            if (cols.contains(id) && !seen.containsKey(id)) {
                seen.put(id, Boolean.TRUE);
                out.add(id);
            }
        }
        // 返回解析出的键列名列表。
        return out;
    }

    /** 匹配 SQL 表达式中的标识符(函数名/列名等),再与实际列名求交。对应 Go keyIdentRe。 */
    private static final Pattern IDENT_IN_EXPR = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 取表的键列(主键优先,没有则退回排序键)。对应 Go tableKeyColumns。
     *
     * <p>用途:数据编辑请求没声明 keyColumns 时,用它兜底做「定位键完整性」校验 ——
     * 表自己的键才是"什么算同一行"的唯一依据,不能让"少给一个键列"退化成"改掉所有同键行"。
     * types 是刚查出来的表列集合,这里不再重复 DESCRIBE。查不到(视图/权限受限)返回空。
     */
    private List<String> tableKeyColumns(CkClient client, String db, String table, Map<String, String> types) {
        QueryResult res;
        try {
            res = query(client,
                    "SELECT primary_key, sorting_key FROM system.tables WHERE database = ? AND name = ?",
                    java.util.Arrays.asList((Object) db, (Object) table));
        } catch (RuntimeException e) {
            return java.util.Collections.emptyList();
        }
        if (res.getRows().isEmpty() || res.getRows().get(0).isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Object> row = res.getRows().get(0);
        String pk = row.size() > 0 && row.get(0) != null ? String.valueOf(row.get(0)) : "";
        String sk = row.size() > 1 && row.get(1) != null ? String.valueOf(row.get(1)) : "";
        List<String> ks = keyColumnsOf(pk, types.keySet());
        return ks.isEmpty() ? keyColumnsOf(sk, types.keySet()) : ks;
    }

    /**
     * 校验「定位键完整性」。对应 Go checkDataEditKeys。
     *
     * <p>⚠️ 以前只要求 keys 非空,于是一个键值就能命中所有同键行(实测两行同号码、请求只给
     * {@code {"mdn":...}} 时都被改掉,接口还只报 updated:1)。请求带 keyColumns 时每条 update/delete
     * 必须把键列给全;没带就用表的主键/排序键兜底;两者都为空(表无键)才保持旧「按行内 keys 定位」行为。
     */
    static void checkDataEditKeys(Map<String, String> types, DataEditRequest req, List<String> tableKeyCols) {
        List<String> src = req.getKeyColumns();
        if (src == null || src.isEmpty()) {
            src = tableKeyCols;
        }
        List<String> keyCols = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (src != null) {
            for (String c0 : src) {
                String c = c0 == null ? "" : c0.trim();
                if (c.isEmpty() || seen.contains(c)) {
                    continue;
                }
                if (!types.containsKey(c)) {
                    throw ApiException.validation("定位键列 \"" + c + "\" 不是表的列");
                }
                seen.add(c);
                keyCols.add(c);
            }
        }
        if (keyCols.isEmpty()) {
            return;
        }
        if (req.getUpdates() != null) {
            for (RowUpdate u : req.getUpdates()) {
                checkEditKeysComplete("更新", u == null ? null : u.getKeys(), keyCols);
            }
        }
        if (req.getDeletes() != null) {
            for (Map<String, Object> d : req.getDeletes()) {
                checkEditKeysComplete("删除", d, keyCols);
            }
        }
    }

    /** 单条 update/delete 的定位键必须覆盖全部键列,否则抛 400。对应 Go checkDataEditKeys 里的 check 闭包。 */
    private static void checkEditKeysComplete(String action, Map<String, Object> keys, List<String> keyCols) {
        List<String> given = new ArrayList<>();
        for (String c : keyCols) {
            if (keys != null && keys.containsKey(c)) {
                given.add(c);
            }
        }
        if (given.size() == keyCols.size()) {
            return;
        }
        String actual = given.isEmpty() ? "一个都没给" : String.join(", ", given);
        throw ApiException.validation(action + "的定位键不完整:需要 " + String.join(", ", keyCols)
                + ",实际只给了 " + actual);
    }

    /** null 兜底空串。 */
    private static String def(String s) {
        // null 返回空串,否则原样返回。
        return s == null ? "" : s;
    }
    /** 只读查询(目标库失败 → 502)。 */
    private static QueryResult query(CkClient client, String sql) {
        // 无参数版本委托给带参版本(参数为 null)。
        return query(client, sql, null);
    }

    // 带可选参数的查询:成功直返,失败统一包装为 502 网关错误。
    /**
     * 执行只读 ClickHouse 查询，并统一网关错误语义。
     *
     * @param client ClickHouse 客户端
     * @param sql 查询 SQL
     * @param args 可选参数；{@code null} 表示调用无参数查询
     * @return ClickHouse 查询结果
     * @throws com.el2ck.exception.ApiException 查询失败时转换为 502
     */
    private static QueryResult query(CkClient client, String sql, List<Object> args) {
        // 目标 ClickHouse 执行查询。
        try {
            // 按是否有参数选择调用形式。
            return args == null ? client.query(sql) : client.query(sql, args);
        } catch (RuntimeException e) {
            // 目标库异常统一转为 502。
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /** 执行写语句(目标库失败 → 502)。 */
    private static void exec(CkClient client, String sql) {
        // 目标 ClickHouse 执行写语句。
        try {
            client.exec(sql);
        } catch (RuntimeException e) {
            // 目标库异常统一转为 502。
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }
}
