package com.el2ck.controller.system;

import com.el2ck.ckpool.CkConfig;
import com.el2ck.ckpool.CkPool;
import com.el2ck.config.AppConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康检查端点。
 *
 * <p>接口兼容旧调用方：
 * <ul>
 *   <li>{@code GET /api/health} 保持原有的轻量存活语义；</li>
 *   <li>{@code GET /api/health?deep=true} 保持原有的深度探测语义；</li>
 *   <li>新增 {@code /api/health/live} 与 {@code /api/health/ready} 供容器或编排平台分别使用。</li>
 * </ul>
 *
 * <p>深度探测只检查应用运行所必需的元数据 MySQL 和应用配置中的默认 ClickHouse，不遍历
 * 用户创建的连接或数据源。响应不包含底层异常信息，避免健康检查公开主机、账号或驱动细节。
 */
@RestController
public class HealthController {

    /** 深度探测的单项超时。Docker 健康检查总超时为 7 秒，两个依赖串行探测仍可留出余量。 */
    private static final long PROBE_TIMEOUT_MS = 2_000L;

    /** 元数据 MySQL 数据源；由 InfrastructureConfiguration 显式创建。 */
    private final DataSource dataSource;

    /** ClickHouse 连接池；复用现有建连、协议和 TLS 处理，不额外维护一套连接逻辑。 */
    private final CkPool ckPool;

    /** 应用配置；深度探测仅读取 app.default-ck。 */
    private final AppConfig appConfig;

    public HealthController(DataSource dataSource, CkPool ckPool, AppConfig appConfig) {
        this.dataSource = dataSource;
        this.ckPool = ckPool;
        this.appConfig = appConfig;
    }

    /**
     * 旧版统一入口。未传 deep 或 deep=false 时只表示进程可响应；deep=true 时执行完整依赖检查。
     */
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestParam(value = "deep", defaultValue = "false") String deep) {
        return "true".equalsIgnoreCase(deep) ? deepHealth() : liveHealth();
    }

    /**
     * liveness 只验证 HTTP 线程仍能响应，不访问 MySQL 或 ClickHouse。
     * 外部依赖短暂故障时不应触发容器重启，因此该端点始终返回 200。
     */
    @GetMapping("/api/health/live")
    public ResponseEntity<Map<String, Object>> liveness() {
        return liveHealth();
    }

    /**
     * readiness 验证应用能否处理依赖元数据 MySQL 和默认 ClickHouse 的业务请求。
     * 任一依赖不可用时返回 503，使负载均衡或编排平台停止向本实例转发流量。
     */
    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        return deepHealth();
    }

    /** 构造不访问外部依赖的存活响应。 */
    private ResponseEntity<Map<String, Object>> liveHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "up");
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /**
     * 执行完整依赖探测。两个依赖独立执行，即使 MySQL 失败也会继续检查 ClickHouse，
     * 从而让运维人员一次看到所有不可用组件。
     */
    private ResponseEntity<Map<String, Object>> deepHealth() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean degraded = false;

        try {
            checkMetadata();
            checks.put("mysql", "ok");
        } catch (RuntimeException e) {
            checks.put("mysql", "unavailable");
            degraded = true;
        }

        try {
            checkDefaultClickHouse();
            checks.put("clickhouse", "ok");
        } catch (RuntimeException e) {
            checks.put("clickhouse", "unavailable");
            degraded = true;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", degraded ? "degraded" : "up");
        result.put("checks", checks);
        return new ResponseEntity<>(result, degraded ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK);
    }

    /** 使用独立连接执行 SELECT 1；try-with-resources 保证连接、语句和结果集全部释放。 */
    private void checkMetadata() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout((int) Math.max(1L, PROBE_TIMEOUT_MS / 1_000L));
            try (ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException("Metadata probe returned no usable result");
                }
            }
        } catch (Exception e) {
            String message = e.getMessage();
            throw new IllegalStateException(
                    message == null || message.isEmpty() ? e.getClass().getSimpleName() : message, e);
        }
    }

    /**
     * 通过现有 CkPool.test 建立并关闭一条临时连接。它走正式连接的协议、认证、SET 和 Ping
     * 流程，但不会写入连接池，也不会遍历任何用户数据源。
     */
    private void checkDefaultClickHouse() {
        ckPool.test(toCkConfig(appConfig.getDefaultCk()));
    }

    /**
     * 将应用级默认配置转换为底层连接参数。端口为空时按协议选择标准端口；9000/9001 与
     * 8123/8132 的最终协议/端口兼容性仍由 CkClient.open 统一校验。
     */
    private static CkConfig toCkConfig(AppConfig.DefaultCk configured) {
        if (configured == null) {
            throw new IllegalStateException("Default ClickHouse configuration is missing");
        }
        String host = trim(configured.getHost());
        if (host.isEmpty()) {
            throw new IllegalStateException("Default ClickHouse host is not configured");
        }

        String protocol = CkConfig.normalizeProtocol(configured.getProtocol());
        int port = configured.getPort() == null
                ? CkConfig.defaultPort(protocol)
                : configured.getPort();

        CkConfig result = CkConfig.defaults();
        result.setHost(host);
        result.setProtocol(protocol);
        result.setPort(port);
        result.setDatabase(defaultIfEmpty(configured.getDatabase(), "default"));
        result.setUsername(defaultIfEmpty(configured.getUsername(), "default"));
        result.setPassword(configured.getPassword() == null ? "" : configured.getPassword());
        result.setSecure(configured.isSecure());
        result.setDialTimeoutMs(PROBE_TIMEOUT_MS);
        result.setQueryTimeoutMs(PROBE_TIMEOUT_MS);
        return result;
    }

    private static String defaultIfEmpty(String value, String fallback) {
        String normalized = trim(value);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
