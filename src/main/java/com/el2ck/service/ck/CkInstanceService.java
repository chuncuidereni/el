/**
 * 【文件职责】本文件定义ck模块的业务服务 CkInstanceService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.ck;

import com.el2ck.config.AppConfig;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.entity.connection.Connection;
import com.el2ck.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;

/**
 * ClickHouse 物理实例登记。
 *
 * <p>平台允许多个连接别名指向同一台 ClickHouse(例如 native/http 两个入口)。表资产与用户绑定
 * 必须以「物理实例」为单位去重,否则同一张表可被两个别名分别登记,授权检查也就形同虚设。
 *
 * <p>实例键按归一化主机与 TLS 生成，协议和端口作为入口别名。这里不尝试探测集群拓扑;集群部署需运维保证各节点账号与
 * 授权一致(见开发方案 7.1)。
 *
 * <p>刻意只依赖元数据 DataSource,不依赖 MetadataRepository:后者又依赖 ResourceAccessService,
 * 而那需要本实例,形成 Spring Bean 循环。
 */
@Service
public class CkInstanceService {

    private final JdbcTemplate jdbc;
    private final AppConfig config;
    /**
     * 创建ClickHouse 实例登记服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param ds 平台元数据 JDBC 数据源，用于读取账号安全、归属和授权记录
     * @param config 应用或数据源配置对象；具体语义由调用场景决定
     */
    public CkInstanceService(DataSource ds, AppConfig config) {
        this.jdbc = new JdbcTemplate(ds);
        this.config = config;
    }

    /** 启动时幂等登记全部连接所属实例,并回填连接与历史资产的实例 ID。 */
    public void syncInstances() {
        for (Connection c : connections()) {
            long instanceId = ensureInstance(c);
            jdbc.update("UPDATE el_connections SET ck_instance_id=? WHERE id=? AND (ck_instance_id IS NULL OR ck_instance_id<>?)",
                    instanceId, c.getId(), instanceId);
        }
        // 连接协议切换后遗留的重复实例:没有连接指向的实例标记为 duplicate,不再出现在列表/初始化中。
        try {
            jdbc.update("UPDATE el_ck_instances i SET status='duplicate',updated_at=CURRENT_TIMESTAMP"
                    + " WHERE i.status='active' AND NOT EXISTS(SELECT 1 FROM el_connections c WHERE c.ck_instance_id=i.id)");
        } catch (RuntimeException ignored) {
            // 旧库结构或并发启动时忽略;列表仍按「有连接」过滤。
        }
        try {
            jdbc.update("UPDATE el_data_assets a JOIN el_connections c ON c.id=a.backend_id"
                    + " SET a.ck_instance_id=c.ck_instance_id"
                    + " WHERE a.backend_type='clickhouse' AND a.ck_instance_id IS NULL AND c.ck_instance_id IS NOT NULL");
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 多个别名重复登记同一物理表时保留原记录,由管理员在历史资源页面核对;查询路径仍按 backend_id 回退。
        }
    }

    /**
     * 登记(或复用)一条连接对应的实例。
     *
     * <p>主机相同、TLS 相同即视为同一实例:HTTP(8123)与 native(9000)只是同一服务的两种入口,
     * 不能因为切换协议就产生新的实例并让已有用户绑定失效。若同一主机上确实部署了多个独立
     * ClickHouse 实例,需要后续引入显式的实例标识配置。
     */
    public long ensureInstance(Connection c) {
        if (c == null) throw ApiException.notFound("连接不存在");
        String host = normalizedHost(c);
        List<Long> byHost = jdbc.queryForList(
                "SELECT id FROM el_ck_instances WHERE LOWER(host)=? AND secure=? AND status='active' ORDER BY id LIMIT 1",
                Long.class, host, c.isSecure());
        if (!byHost.isEmpty()) {
            long id = byHost.get(0);
            // 协议/端口只是入口别名,更新展示信息但不改变实例 ID,已有绑定继续有效。
            jdbc.update("UPDATE el_ck_instances SET name=?,host=?,port=?,protocol=?,secure=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    c.getName(), c.getHost(), c.getPort(), CkConfig.normalizeProtocol(c.getProtocol()), c.isSecure(), id);
            return id;
        }
        String key = instanceKey(c);
        jdbc.update("INSERT INTO el_ck_instances(instance_key,name,host,port,protocol,secure,management_connection_id,status) VALUES(?,?,?,?,?,?,?,'active')",
                key, c.getName(), c.getHost(), c.getPort(), CkConfig.normalizeProtocol(c.getProtocol()),
                c.isSecure(), c.getId());
        return jdbc.queryForObject("SELECT id FROM el_ck_instances WHERE instance_key=?", Long.class, key);
    }

    /** 归一化的实例键:同一主机 + TLS 视为同一物理实例,协议与端口不作为区分条件。 */
    public static String instanceKey(Connection c) {
        return normalizedHost(c) + "?" + (c.isSecure() ? "tls" : "plain");
    }

    /**
     * 归一化连接主机名，用于计算稳定的物理实例键。
     *
     * @param c 连接配置
     * @return 去除首尾空白并转小写的主机名；null 转为空串
     */
    private static String normalizedHost(Connection c) {
        return c.getHost() == null ? "" : c.getHost().trim().toLowerCase(Locale.ROOT);
    }

    /** 连接所属实例 ID;缺失时同步一次后重试。 */
    public long instanceId(long connectionId) {
        List<Long> ids = jdbc.queryForList("SELECT ck_instance_id FROM el_connections WHERE id=?", Long.class, connectionId);
        if (ids.isEmpty()) throw ApiException.notFound("连接不存在");
        Long id = ids.get(0);
        if (id != null) return id;
        List<Connection> rows = connection(connectionId);
        if (rows.isEmpty()) throw ApiException.notFound("连接不存在");
        long created = ensureInstance(rows.get(0));
        jdbc.update("UPDATE el_connections SET ck_instance_id=? WHERE id=?", created, connectionId);
        return created;
    }

    /** 某实例上用于账号/授权管理的连接别名。 */
    public long managementConnectionId(long instanceId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM el_connections WHERE ck_instance_id=? ORDER BY (is_default=1) DESC, id ASC LIMIT 1",
                Long.class, instanceId);
        if (ids.isEmpty()) throw new ApiException(409, "该 ClickHouse 实例没有可用连接，无法执行账号与授权管理");
        return ids.get(0);
    }

    /** 实例展示信息(不含凭据)。 */
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT i.id AS instanceId,i.name,i.host,i.port,i.protocol,i.secure,i.status,"
                + "i.management_connection_id AS connectionId FROM el_ck_instances i"
                + " WHERE i.status='active' AND EXISTS(SELECT 1 FROM el_connections c WHERE c.ck_instance_id=i.id)"
                + " ORDER BY i.id");
    }

    /** 用户可见的实例名称。 */
    public String name(long instanceId) {
        List<String> names = jdbc.queryForList("SELECT name FROM el_ck_instances WHERE id=?", String.class, instanceId);
        return names.isEmpty() ? "" : names.get(0);
    }

    /**
     * 平台只在这些业务库内创建表、授权与登记资产。管理员可在 app.ck-databases 中调整。
     */
    public void requireAllowedDatabase(String database) {
        String db = database == null ? "" : database.trim();
        if (!allowedDatabases().contains(db.toLowerCase(Locale.ROOT))) {
            throw new ApiException(403, "仅允许操作 " + String.join("、", allowedDatabases()) + " 库");
        }
    }

    /** 允许操作的业务库(小写)。 */
    public Set<String> allowedDatabases() {
        String raw = config.getCkDatabases();
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String db = part.trim().toLowerCase(Locale.ROOT);
                if (!db.isEmpty()) out.add(db);
            }
        }
        if (out.isEmpty()) {
            out.add("ods_data");
            out.add("dwd_data");
        }
        return out;
    }

    /**
     * 读取全部连接的基础网络信息，不加载用户名、密码等敏感配置。
     *
     * @return 连接列表；无记录时为空列表
     */
    private List<Connection> connections() {
        List<Connection> out = new ArrayList<>();
        jdbc.query("SELECT id,name,host,port,protocol,secure FROM el_connections", rs -> {
            Connection c = new Connection();
            c.setId(rs.getLong("id"));
            c.setName(rs.getString("name"));
            c.setHost(rs.getString("host"));
            c.setPort(rs.getInt("port"));
            c.setProtocol(rs.getString("protocol"));
            c.setSecure(rs.getBoolean("secure"));
            out.add(c);
        });
        return out;
    }

    /**
     * 按 ID 读取一条连接的基础网络信息。
     *
     * @param id 连接 ID
     * @return 零或一条连接组成的列表
     */
    private List<Connection> connection(long id) {
        List<Connection> out = new ArrayList<>();
        jdbc.query("SELECT id,name,host,port,protocol,secure FROM el_connections WHERE id=?", rs -> {
            Connection c = new Connection();
            c.setId(rs.getLong("id"));
            c.setName(rs.getString("name"));
            c.setHost(rs.getString("host"));
            c.setPort(rs.getInt("port"));
            c.setProtocol(rs.getString("protocol"));
            c.setSecure(rs.getBoolean("secure"));
            out.add(c);
        }, id);
        return out;
    }
}
