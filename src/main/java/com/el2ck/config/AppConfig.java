package com.el2ck.config;

/**
 * 【文件职责】集中绑定应用级配置，包括数据目录、密钥、调度和运行参数。
 */

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用运行配置,统一绑定 application.yml 的 app.* 前缀配置项。
 * <p>由 Spring Boot {@code @ConfigurationProperties(prefix = "app")} 把 yml 中
 * app.data-dir / app.secret-key / app.web-dir / app.user /
 * app.password / app.default-ck.* 等自动装配进本类的字段(字段默认值即缺省值);
 * 首次启动可用 app.user / app.password 播种管理员账号；后续身份从 app_users 校验。
 */
// Lombok 注解:自动为全部字段生成 getter/setter(与 @ConfigurationProperties 绑定配合)
@Data
// 绑定 application.yml 中以 "app" 为前缀的配置项
@ConfigurationProperties(prefix = "app")
public class AppConfig {
    public static final String DEFAULT_SECRET_KEY = "el2ck-dev-secret-please-change-in-prod";
    // 本地数据目录(上传/缓存等);缺省 ./data
    private String dataDir = "./data";
    private Metadata metadata = new Metadata();

    @Data
    public static class Metadata {
        private String url;
        private String username;
        private String password;
        // 同步与 ARB 的命名锁会长期占用连接，保留足够的请求/进度写入余量。
        private int maximumPoolSize = 20;
    }

    // 用于加密连接口令/数据源密钥的对称密钥;缺省为开发用示例值(生产需修改)
    private String secretKey = DEFAULT_SECRET_KEY;
    // 前端构建产物目录;缺省 ./web/dist
    private String webDir = "./web/dist";
    // 首次管理员用户名;空值由播种逻辑回退为 admin
    private String user;
    // 首次管理员密码;空值由播种逻辑生成随机密码
    private String password;
    // 播种用的默认 ClickHouse 连接配置对象(内置默认值)
    private DefaultCk defaultCk = new DefaultCk();
    // 账号/授权管理专用 CK 身份。业务查询绝不复用这份凭据。
    private CkManagement ckManagement = new CkManagement();
    // 允许平台管理的业务库(逗号分隔)。默认只开放贴源层与加工层。
    private String ckDatabases = "ods_data,dwd_data";
    // 同步入库前的 mdn 明文校验(见 SyncEngine)
    private SyncMdnCheck syncMdnCheck = new SyncMdnCheck();

    @Data
    public static class CkManagement {
        // 管理账号用户名;为空表示未配置,初始化/授权会明确失败而不是回退到业务连接账号
        private String username;
        // 管理账号口令;仅从部署环境注入,不写入仓库
        private String password;
        // 是否启用真实 CK 账号初始化。关闭时仅保留平台侧权限校验,拒绝声称 CK 授权已完成。
        private boolean enabled = true;
    }

    // Lombok 注解:为嵌套的默认连接配置生成 getter/setter
    @Data
    public static class DefaultCk {
        // 默认连接的展示名称
        private String name = "本地ClickHouse";
        // ClickHouse 主机地址
        private String host;
        // 连接协议:native(默认)或 http;未配置按 native
        // ⚠️ 默认连接的 host/port/协议/口令在每次启动时都会被本配置覆盖(见 seedDefaultConnection),
        //    因此这几项必须在这里配得彼此一致(例如 port 填 8123 就要同时把 protocol 设为 http)。
        private String protocol;
        // ClickHouse 端口(native 协议通常 9000,HTTP 协议通常 8123)
        private Integer port;
        // 默认连接的数据库名
        private String database;
        // 登录用户名
        private String username;
        // 登录密码(空串表示无密码)
        private String password;
        // 连接说明文字
        private String description = "默认连接";
        private boolean secure;
    }

    /**
     * 同步入库前的 mdn 明文校验。
     *
     * <p>规则:拒绝归一化(去空白/分隔符、去国际区号 86)后匹配 11 位手机号的明文值
     * {@code ^1[3-9]\d{9}$}。源数据行数不超过 {@link #fullScanMaxRows} 时全量检查,
     * 超过时按 {@link #sampleSize} 等距抽样。发现任何明文手机号都会中止本次同步。
     */
    @Data
    public static class SyncMdnCheck {
        /** 是否启用;关闭后同步不再校验 mdn。 */
        private boolean enabled = true;
        /** 源行数不超过该值时全量校验(默认 10 万)。 */
        private long fullScanMaxRows = 100_000L;
        /** 源行数超过阈值时抽样的行数(默认 1 万)。 */
        private int sampleSize = 10_000;
    }
}
