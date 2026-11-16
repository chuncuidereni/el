/**
 * 【文件职责】本文件定义数据库目录浏览模块的业务服务 DatabaseService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.database;

import com.el2ck.service.connection.ConnectionService;

import com.el2ck.exception.ApiException;
import com.el2ck.util.Sql;
import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.database.DatabaseInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ClickHouse 数据库层面的管理操作(对应 Go internal/service/database_service.go)。
 *
 * 列表过滤系统/内置库、default 恒置顶;新建支持 COMMENT 备注;删除禁止 default 库。
 * 本服务直连目标 ClickHouse 执行 DDL,失败统一视为下游错误(502)。
 */
public class DatabaseService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;

    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.ck.CkInstanceService instances;

    /** 连接服务:管理身份(建库/删库)与只读判定。 */
    private final ConnectionService connSvc;

    /** 当前用户的 CK 业务身份:列表按实际可见范围读取。 */
    private final com.el2ck.service.ck.CkIdentityService identities;

    /** 系统/内置数据库,默认不在列表中展示(仍可通过 SQL 查询访问)。对应 Go internalDBs。 */
    private static final Set<String> INTERNAL_DBS = new HashSet<>(Arrays.asList(
            "system", "information_schema", "INFORMATION_SCHEMA", "_object_schema_"));

    /**
     * 分层固定的两个目标库名(对应 Go ODSDataDB/DWDDataDB):
     * <ul>
     *   <li>{@code ods_data} 贴源层(数据源/映射同步任务写入)</li>
     *   <li>{@code dwd_data} 加工层(工作台/目标表库:对 ods 清洗聚合后的结果)</li>
     * </ul>
     */
    public static final String ODS_DATA_DB = "ods_data";
    public static final String DWD_DATA_DB = "dwd_data";
    private final com.el2ck.service.table.TableReferenceService tableReferences;
    /**
     * 创建ClickHouse 数据库浏览服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param connSvc ClickHouse 连接服务，负责解析连接并按执行身份创建客户端
     * @param identities ClickHouse 用户身份服务，用于按任务所有者或当前用户解析执行身份
     * @param tableReferences 执行中表引用与删库保护
     */
    public DatabaseService(ConnectionService connSvc, com.el2ck.service.ck.CkIdentityService identities,
                            com.el2ck.service.table.TableReferenceService tableReferences) {
        // 保存连接服务引用
        this.connSvc = connSvc;
        this.identities = identities;
        this.tableReferences = tableReferences;
    }

    /** 列出用户数据库(过滤系统/内置库,default 恒置顶,其余按字母序)。 */
    public List<DatabaseInfo> list(long connId) {
        // 库目录与表读取权限分开:空的业务库也要可选,但不因此获得库内其他用户的表。
        // 管理员可在尚未初始化业务绑定时查看配置的库和管理账号。
        com.el2ck.config.AuthContext.Principal principal = com.el2ck.config.AuthContext.get();
        if (principal == null || !"admin".equals(principal.getRole())) identities.requireBinding(connId);
        Set<String> visible = new HashSet<>(instances.allowedDatabases());
        visible.addAll(access.visibleDatabases(connId));
        // 通过连接服务取得目标连接客户端,并查询全部库的库名与备注
        QueryResult res;
        try {
            // 只通过管理连接探测明确允许的库名,不读取表或数据,不返回系统库。
            String placeholders = String.join(",", java.util.Collections.nCopies(visible.size(), "?"));
            res = identities.managementClient(connId).query(
                    "SELECT name, comment FROM system.databases WHERE name IN (" + placeholders + ") ORDER BY name",
                    new ArrayList<Object>(visible));
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
        // 结果列表
        List<DatabaseInfo> out = new ArrayList<>();
        // 逐行处理查询结果
        for (List<Object> row : res.getRows()) {
            // 行为空或首列为 null 时跳过
            if (row.isEmpty() || row.get(0) == null) {
                continue;
            }
            // 取出库名并转字符串
            String name = String.valueOf(row.get(0));
            // 过滤系统/内置库,不在列表中展示
            if (INTERNAL_DBS.contains(name) || !visible.contains(name)) {
                continue;
            }
            // 封装为展示用的数据库信息
            DatabaseInfo d = new DatabaseInfo();
            // 填充库名
            d.setName(name);
            // 备注列非空时一并填充
            if (row.size() > 1 && row.get(1) != null) {
                d.setRemark(String.valueOf(row.get(1)));
            }
            // 加入结果列表
            out.add(d);
        }
        // default 库恒置顶,其余按字母序(比较器与 Go sort.Slice 的语义一致)。
        out.sort((a, b) -> {
            // default 永远排最前
            if ("default".equals(a.getName())) {
                return -1;
            }
            if ("default".equals(b.getName())) {
                return 1;
            }
            // 其余按名称升序
            return a.getName().compareTo(b.getName());
        });
        return out;
    }

    /** 新建数据库;remark 非空则作为 COMMENT。 */
    public void create(long connId, String name, String remark) {
        String rawName = name == null ? "" : name.trim();
        if (!Sql.validIdent(rawName)) {
            throw ApiException.validation("数据库名仅允许字母/数字/下划线,且不能以数字开头");
        }
        // 只读连接禁止建库
        checkWritable(connId);
        // 库名按 ClickHouse 规则加引号转义
        String n = Sql.quoteIdent(rawName);
        // 拼装 CREATE DATABASE IF NOT EXISTS(幂等建库)
        StringBuilder sql = new StringBuilder("CREATE DATABASE IF NOT EXISTS " + n);
        // 备注非空时追加 COMMENT 子句
        if (remark != null && !remark.trim().isEmpty()) {
            // 备注字符串以单引号安全转义后拼接
            sql.append(" COMMENT ").append(Sql.quoteString(remark.trim()));
        }
        // 执行 DDL
        exec(connId, sql.toString());
    }

    /**
     * 启动时在某个连接上幂等确保分层库 {@code ods_data}/{@code dwd_data} 存在
     * (对应 Go EnsureTieredDatabases)。只读或建库失败仅忽略,<b>不抛错</b> ——
     * 避免某个连接异常(只读账号/目标库不可达)拖垮整个启动。
     */
    public void ensureTieredDatabases(long connId) {
        for (String name : tierDbs()) {
            try {
                create(connId, name, "");
            } catch (RuntimeException ignored) {
                // 只读/建库失败:忽略,启动不中断。
            }
        }
    }

    /** 分层两个固定库名(供启动播种/展示)。对应 Go TierDBs。 */
    public static List<String> tierDbs() {
        return Arrays.asList(ODS_DATA_DB, DWD_DATA_DB);
    }

    /** 删除数据库(default 禁止删除);危险操作需前端二次确认。 */
    public void drop(long connId, String name) {
        try (com.el2ck.repository.metadata.TableChangeGate.Lease gate = tableReferences.reserveChanges()) {
            gate.check();
            dropWhileIdle(connId, name);
        }
    }

    private void dropWhileIdle(long connId, String name) {
        // 只读连接禁止删库
        checkWritable(connId);
        tableReferences.requireDatabaseModificationAllowed(connId, name == null ? "" : name.trim());
        // 去掉库名首尾空白
        String rawName = name == null ? "" : name.trim();
        if (!Sql.validIdent(rawName)) {
            throw ApiException.validation("数据库名仅允许字母/数字/下划线,且不能以数字开头");
        }
        // 按规则加引号转义
        String n = Sql.quoteIdent(rawName);
        // 禁止删除 default 库
        if ("default".equalsIgnoreCase(rawName)) {
            throw ApiException.validation("默认库 default 不允许删除(库内表仍可删除)");
        }
        // 执行 DROP DATABASE IF EXISTS(幂等删除)
        exec(connId, "DROP DATABASE IF EXISTS " + n);
    }

    /** 只读连接禁止任何数据库级写操作(对应 Go checkWritable,文案一致)。 */
    private void checkWritable(long connId) {
        // 取该连接生效后的最终配置
        CkConfig cfg = connSvc.effectiveConfig(connId);
        // 配置为只读时拒绝写操作
        if (cfg.isReadOnly()) {
            throw ApiException.readOnly("当前连接为只读模式,不允许执行该操作");
        }
    }

    /** 执行 DDL:ClickHouse 失败归为下游错误 502(与 Go database_api badGateway 等价)。 */
    private void exec(long connId, String sql) {
        // 取得目标连接客户端
        CkClient client = identities.managementClient(connId);
        try {
            // 在目标 ClickHouse 上执行 DDL
            client.exec(sql);
        } catch (RuntimeException e) {
            // 下游执行失败统一包装为 502 网关错误
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }
}
