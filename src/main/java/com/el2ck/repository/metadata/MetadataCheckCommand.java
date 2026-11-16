package com.el2ck.repository.metadata;

import com.el2ck.config.AppConfig;

/**
 * 【文件职责】容器部署前的独立只读检查入口，不启动 Web、调度器或账号初始化。
 * 通过 Spring Boot PropertiesLauncher 加载 JAR 依赖；只读取 Compose 已注入的环境变量。
 */
public final class MetadataCheckCommand {
    private MetadataCheckCommand() { }
    public static void main(String[] args) {
        try {
            AppConfig.Metadata config = new AppConfig.Metadata();
            config.setUrl(required("APP_METADATA_URL"));
            config.setUsername(required("APP_METADATA_USERNAME"));
            config.setPassword(required("APP_METADATA_PASSWORD"));
            config.setMaximumPoolSize(1);
            try (MysqlMetadataDataSource ignored = MysqlMetadataDataSource.open(config)) {
                System.out.println("Metadata connection and schema check passed.");
            }
        } catch (RuntimeException e) {
            // 不输出驱动异常链中的 URL 或凭据，只展示结构校验的操作建议。
            String message = e.getMessage();
            System.err.println(message != null && message.startsWith("MySQL 元数据结构")
                    ? message : "Metadata preflight failed. Check database connectivity and docs/mysql/README.md.");
            System.exit(1);
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
