package com.el2ck.util;

/**
 * 【文件职责】规范化数据源连接身份，生成稳定标识以支持缓存、去重和权限边界判断。
 */

import com.el2ck.entity.datasource.DataSource;
import com.el2ck.exception.ApiException;
import com.mongodb.ConnectionString;
import java.util.*;

/**
 * 数据源连接的「逻辑同一性」判定。
 *
 * <p>连接唯一性只取类型、主机和用户，端口、库名、密码及分组不参与。这样设计是为了按实例查重：
 * 同一台服务器即使换了端口、默认库或口令，仍被认作同一条连接，避免列表里堆出多条实际指向
 * 同一实例的记录；反之，不同用户即使连同一主机也视为不同连接，因为各自的权限和可见库可能完全不同。
 */
public final class DataSourceConnectionIdentity {
    // 连接类型(如 mysql/pgsql/clickhouse/mongodb/sftp)，参与唯一性比较。
    private final String type;
    // 登录用户名，参与唯一性比较；MongoDB 从 URI 的凭据段取。
    private final String username;
    // 规范化后的主机集合(已去端口/IPv6 方括号/尾点)；MongoDB 多主机连接串会全部纳入。
    private final Set<String> hosts;

    private DataSourceConnectionIdentity(String type, String username, Set<String> hosts) {
        this.type = type; this.username = username; this.hosts = hosts;
    }

    /**
     * 由数据源类型与配置构建连接标识；不支持的类型返回 {@code null}，调用方据此跳过查重。
     *
     * <p>MongoDB 允许把整条连接串填在 host 里：此时用户名和主机必须从 URI 内部取，
     * 因为 {@code mongodb://user@h1,h2,.../db} 可能是副本集/分片的多主机写法，
     * 只用配置里的单个 host 字符串无法正确参与查重。解析失败时抛校验异常，但不透传
     * 解析器的原始异常，避免连接串中携带的口令随异常进入日志或接口响应。
     */
    public static DataSourceConnectionIdentity of(String type, Map<String, Object> config) {
        if (!Arrays.asList(DataSource.MYSQL, DataSource.PGSQL, DataSource.CLICKHOUSE,
                DataSource.MONGODB, DataSource.SFTP).contains(type)) return null;
        String host = text(config, "host"), username = text(config, "username");
        Set<String> hosts = new HashSet<>();
        if (DataSource.MONGODB.equals(type) && (host.startsWith("mongodb://") || host.startsWith("mongodb+srv://"))) {
            try {
                ConnectionString uri = new ConnectionString(host);
                username = uri.getCredential() == null ? "" : uri.getCredential().getUserName();
                for (String endpoint : uri.getHosts()) hosts.add(normalizeHost(endpoint));
            } catch (IllegalArgumentException e) {
                // 不回传解析器异常，避免连接串中的口令泄露。
                throw ApiException.validation("MongoDB 连接串格式不正确");
            }
        } else if (!host.isEmpty()) hosts.add(normalizeHost(host));
        hosts.remove("");
        return new DataSourceConnectionIdentity(type, username, hosts);
    }

    /**
     * 判断两条连接是否指向同一实例：类型、用户名相同，且主机集合有交集。
     *
     * <p>用集合「相交」而非相等：多主机连接串可能只列出副本集节点的不同子集，
     * 只要有一台主机重合就足以认定二者是同一实例。
     */
    public boolean conflictsWith(DataSourceConnectionIdentity other) {
        return other != null && type.equals(other.type) && username.equals(other.username)
                && !Collections.disjoint(hosts, other.hosts);
    }

    private static String text(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 归一化单个主机端点，抹平同一主机的不同写法：转小写；{@code [::1]:27017} 这类 IPv6
     * 去方括号与端口；去掉 FQDN 结尾的点；再把纯数字 IPv6 压成规范形式。
     *
     * <p>只对数字 IPv6 做规范化，不对域名发起 DNS：解析结果依赖网络环境、会拖慢查重，
     * 且同一域名在不同时刻可能解析成不同地址，反而把同一主机误判成不同连接。
     */
    private static String normalizeHost(String endpoint) {
        String host = endpoint.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.contains("]")) host = host.substring(1, host.indexOf(']'));
        else if (host.indexOf(':') == host.lastIndexOf(':') && host.matches(".*:[0-9]+$"))
            host = host.substring(0, host.lastIndexOf(':'));
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        // 仅解析数字 IPv6，不对域名发起 DNS 查询。
        if (host.contains(":") && host.matches("[0-9a-f:.]+")) {
            try { host = java.net.InetAddress.getByName(host).getHostAddress(); }
            catch (java.net.UnknownHostException ignored) { }
        }
        return host;
    }
}
