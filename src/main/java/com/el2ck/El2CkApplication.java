/**
 * 【文件职责】Spring Boot 进程入口，负责 CLI 增量检查分支、容器启动、启动摘要和诊断告警。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck;

import com.el2ck.config.AppConfig;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * el-to-ck 服务入口(Java 迁移版)。
 * <p>对应 Go cmd/server/main.go,启动流程:
 * ① new SpringApplication 并关闭启动 Banner、保留启动日志;
 * ② app.run(args) 刷新 Spring 容器——期间会按 application.yml 装配 MySQL
 * 元数据库、DuckDB 与 ClickHouse 连接池及全部业务服务(见
 * {@code com.el2ck.config.InfrastructureConfiguration}),并启动内嵌 Servlet 容器;
 * ③ 容器启动成功后调用 printStartup 打印启动摘要(监听地址/元数据库类型/鉴权模式)。
 * 所有配置来自 application.yml(app.* 前缀,见 {@link AppConfig})。
 */
// 标注为 Spring Boot 启动类:开启自动配置、组件扫描与内嵌服务器
@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class El2CkApplication {

    // 应用主入口:装配 Spring 容器并按结果决定是否退出
    public static void main(String[] args) {
        if (args.length > 0 && "--check-incremental".equals(args[0])) {
            System.exit(com.el2ck.sync.IncrementalCheckCommand.run(
                    java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(), System.out));
            return;
        }
        // 以当前启动类构建 SpringApplication 实例
        SpringApplication app = new SpringApplication(El2CkApplication.class);
        // 关闭控制台 Banner 打印(启动日志更简洁)
        app.setBannerMode(Banner.Mode.OFF);
        // 保留启动过程信息日志
        app.setLogStartupInfo(true);

        // 运行应用并拿到已就绪的容器上下文
        ConfigurableApplicationContext context = app.run(args);
        // 容器未能成功运行(启动失败)时以退出码 1 结束进程
        if (!context.isRunning()) {
            System.exit(1);
        }
        // 容器运行正常则打印启动摘要供运维确认
        printStartup(context);
    }

    /** 打印启动摘要(监听地址/元数据库类型/鉴权模式),便于运维确认。 */
    private static void printStartup(ConfigurableApplicationContext context) {
        // 摘要读取或打印失败不能影响已启动的服务,故捕获全部异常仅打印提示
        try {
            // 从容器取出应用配置对象
            AppConfig cfg = context.getBean(AppConfig.class);
            // 取容器环境对象以读取服务器监听属性
            Environment env = context.getEnvironment();
            // 未显式绑定时按监听全部网卡报告，与服务器缺省语义一致。
            String host = env.getProperty("server.address", "0.0.0.0");
            // 读取监听端口(缺省 17878)
            String port = env.getProperty("server.port", "17878");
            // 拼出 "host:port" 形式的访问地址
            String hostPort = host + ":" + port;
            // 依据是否启用鉴权生成鉴权模式的描述文本
            String authMode = "平台账号登录已启用";
            // 打印服务访问入口
            System.out.println("el-to-ck 数据同步平台启动: http://" + hostPort);
            // 打印元数据库类型，避免输出连接字符串中的敏感参数
            System.out.println("元数据库: MySQL（el_ 平台表）");
            // 打印当前鉴权模式
            System.out.println("鉴权模式: " + authMode);
            for (String warning : com.el2ck.config.StartupDiagnostics.warnings(cfg, host,
                    env.getProperty("server.ssl.enabled", Boolean.class, false))) {
                org.slf4j.LoggerFactory.getLogger(El2CkApplication.class).warn(warning);
            }
        } catch (Exception e) {
            // 配置读取或打印失败时仅提示,不影响已启动的服务
            System.out.println("el-to-ck 已启动(配置读取失败,忽略打印摘要): " + e.getMessage());
        }
    }
}
