package com.el2ck.repository.metadata;

/**
 * 【文件职责】创建 MySQL 元数据连接池，并负责连接配置、生命周期和启动期数据库可用性检查。
 */

import com.el2ck.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.lang.reflect.*;
import java.sql.*;

/**
 * 平台元数据库的数据源:MyBatis 与 JDBC 仓储共用的唯一 Hikari 连接池。
 *
 * <p>元数据库已从 SQLite 迁到 MySQL,但 Mapper XML 与仓储代码里的 SQL 仍是旧语法
 * (SQLite 方言:`` `database` `` 这类保留字、{@code datetime('now')}、{@code ON CONFLICT} …)。
 * 与其逐条改写几百条 SQL,这里选择**在驱动边界统一翻译** —— 返回的 {@link Connection} 是动态代理,
 * 凡是走进来的 SQL 都先过一遍 {@link MetadataSqlDialect#mysql(String)} 再交给真正的驱动。
 * 好处是 SQL 维护者不需要知道方言差异;代价是翻译只覆盖仓储层,
 * <b>源端(MySQL/PG/Mongo)与 ClickHouse 的 SQL 绝不经过这里</b>,它们各自直连。
 *
 * <p>池由 Spring 以单例持有({@code destroyMethod="close"}),MyBatis 与
 * {@link org.springframework.jdbc.core.JdbcTemplate} 共用同一实例,事务才能跨两者生效。
 */
public final class MysqlMetadataDataSource extends AbstractDataSource implements AutoCloseable {
    private final HikariDataSource pool;
    private MysqlMetadataDataSource(HikariDataSource pool) { this.pool = pool; }

    /**
     * 建立连接池并只读核对结构；MySQL 表结构由运维手工维护，不执行建表或迁移。
     *
     * <p>{@code connectionInitSql} 是这里唯一的隐式契约,两个设置都不可省:
     * <ul>
     *   <li>{@code time_zone='+00:00'} —— 库里时间列一律按 UTC 存取,避免部署机器时区不同
     *       导致同一条记录读出来的时间不一样;</li>
     *   <li>{@code PIPES_AS_CONCAT} —— 旧 SQL 里 {@code ||} 是字符串拼接(SQLite 语义),
     *       而 MySQL 默认把 {@code ||} 当逻辑或;不开这个模式,拼接会静默变成布尔运算。</li>
     * </ul>
     */
    public static MysqlMetadataDataSource open(AppConfig.Metadata config) {
        if (config.getUrl() == null || !config.getUrl().startsWith("jdbc:mysql:"))
            throw new IllegalArgumentException("app.metadata.url must use jdbc:mysql:");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.getUrl());
        hc.setUsername(config.getUsername());
        hc.setPassword(config.getPassword());
        hc.setMaximumPoolSize(config.getMaximumPoolSize());
        hc.setConnectionTimeout(3000);
        hc.setValidationTimeout(2000);
        hc.setPoolName("el-metadata");
        hc.setConnectionInitSql("SET SESSION time_zone='+00:00', SESSION sql_mode=CONCAT(@@sql_mode, ',PIPES_AS_CONCAT')");
        HikariDataSource pool = new HikariDataSource(hc);
        try {
            MetadataSchemaValidator.validate(pool);
            return new MysqlMetadataDataSource(pool);
        } catch (RuntimeException | Error e) { pool.close(); throw e; }
    }

    @Override public Connection getConnection() throws SQLException { return wrap(pool.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        return wrap(pool.getConnection(user, password));
    }
    @Override public void close() { pool.close(); }

    /**
     * 包一层代理,把 SQL 翻译插在调用方与驱动之间。
     *
     * <p>要在两处拦,是因为仓储层两条路径都会走:
     * <ul>
     *   <li>{@code prepareStatement}/{@code prepareCall} —— 带 {@code ?} 的预编译语句,
     *       参数值不经这里(它们是 {@code setXxx} 设进去的),所以翻译只作用于 SQL 文本本身;</li>
     *   <li>{@code createStatement} 返回的 Statement 上的 {@code execute*}/{@code addBatch} ——
     *       拼接式 SQL 走这条。因此对 Statement 也要再包一层代理。</li>
     * </ul>
     *
     * <p>Statement 代理里把 {@code getConnection()} 重定向回外层的 Connection 代理
     * (而非真实连接),否则调用方从 Statement 拿回原始连接,后续 SQL 就绕过了翻译。
     */
    private static Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(MysqlMetadataDataSource.class.getClassLoader(),
            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (args != null && args.length > 0 && args[0] instanceof String
                    && (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))) {
                    args = args.clone(); args[0] = MetadataSqlDialect.mysql((String) args[0]);
                }
                Object result = invoke(connection, method, args);
                if (method.getName().equals("createStatement")) {
                    Statement statement = (Statement) result;
                    return Proxy.newProxyInstance(MysqlMetadataDataSource.class.getClassLoader(),
                        new Class<?>[]{Statement.class}, (p, sm, sa) -> {
                            if (sm.getName().equals("getConnection")) return proxy;
                            if (sa != null && sa.length > 0 && sa[0] instanceof String
                                && (sm.getName().startsWith("execute") || sm.getName().equals("addBatch"))) {
                                sa = sa.clone(); sa[0] = MetadataSqlDialect.mysql((String) sa[0]);
                            }
                            return invoke(statement, sm, sa);
                        });
                }
                return result;
            });
    }

    /**
     * 转发调用并拆掉反射包装的异常。
     *
     * <p>代理必须走 {@code Method.invoke},它会把目标抛出的异常裹进
     * {@link InvocationTargetException};不拆开的话,调用方看到的是「反射异常」而不是
     * {@code SQLException},既无法按 SQL 错误处理,堆栈也指不到真正出错的那行。
     */
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
