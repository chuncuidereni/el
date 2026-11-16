package com.el2ck.ckpool;

import java.util.Locale;
import java.util.function.Function;

/**
 * 【文件职责】容器部署前的 ClickHouse 独立只读检查入口。
 *
 * <p>该入口不启动 Spring Web、数据库迁移或调度器，只从 Compose 注入的默认
 * ClickHouse 环境变量组装 {@link CkConfig}，通过 {@link CkClient#open(CkConfig)}
 * 完成建连、鉴权、协议握手和 {@code SELECT 1} 探测。{@code CkClient.open}
 * 对 9000/9001 native 与 8123/8132 HTTP 使用严格协议端口配对，能够阻止部署时
 * 把转发端口配给错误协议。
 *
 * <p>错误输出保持固定文案，不打印主机、账号、密码、JDBC URL 或驱动异常链，避免
 * 预检日志泄露凭据。退出码非 0 时由 deploy.sh 中止发布。
 */
public final class ClickHouseCheckCommand {

    private ClickHouseCheckCommand() {
    }

    /** 独立进程入口。 */
    public static void main(String[] args) {
        try {
            CkConfig config = configFromEnvironment();
            // open() 已完成 SET 与 SELECT 1；try-with-resources 确保驱动连接和 native TLS 隧道释放。
            try (CkClient ignored = CkClient.open(config)) {
                System.out.println("ClickHouse connection check passed.");
            }
        } catch (RuntimeException e) {
            System.err.println("ClickHouse preflight failed. Check CK connectivity, protocol/port, and credentials.");
            System.exit(1);
        }
    }

    /**
     * 从容器环境读取默认连接参数。
     *
     * <p>方法保留为包级可见，便于单元测试直接验证默认值与协议端口校验，无需启动独立 JVM。
     */
    static CkConfig configFromEnvironment() {
        return configFrom(System::getenv);
    }

    /** 用可替换的环境读取器组装配置，便于单元测试覆盖默认值与校验路径。 */
    static CkConfig configFrom(Function<String, String> environment) {
        CkConfig config = CkConfig.defaults();
        config.setHost(required(environment, "APP_DEFAULTCK_HOST"));
        config.setProtocol(required(environment, "APP_DEFAULTCK_PROTOCOL"));
        config.setPort(parsePort(required(environment, "APP_DEFAULTCK_PORT")));
        config.setDatabase(required(environment, "APP_DEFAULTCK_DATABASE"));
        config.setUsername(required(environment, "APP_DEFAULTCK_USERNAME"));
        config.setPassword(valueOrEmpty(environment.apply("APP_DEFAULTCK_PASSWORD")));
        config.setSecure(parseBoolean(required(environment, "APP_DEFAULTCK_SECURE"), "APP_DEFAULTCK_SECURE"));
        CkConfig.requireCompatibleProtocolPort(config.getProtocol(), config.getPort());
        return config;
    }

    private static String required(Function<String, String> environment, String name) {
        String value = environment.apply(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value.trim();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("APP_DEFAULTCK_PORT must be an integer", e);
        }
    }

    private static boolean parseBoolean(String value, String name) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }
}
