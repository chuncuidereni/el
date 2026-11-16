package com.el2ck.config;

/**
 * 【文件职责】注册诊断相关 Bean，为启动和运行状态检查提供依赖。
 */

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * 只读诊断专用的最小 Boot 容器。
 *
 * <p>诊断命令({@code --check-incremental} 等)只读配置、连库检查,不应有副作用,故这里
 * <b>只加载配置类</b>:不做组件扫描、不装配数据库 bean、不启动调度器 —— 若照常 component-scan,
 * 会把元数据库初始化、账号播种、定时任务一并拉起来,与「只读诊断」的定位冲突。
 *
 * <p>{@link #start(String...)} 同时关掉 Web 容器类型、banner 与启动日志,让命令行的输出干净可读。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppConfig.class)
public class DiagnosticConfiguration {
    /** 以「无 Web 容器、无 banner、无启动日志」的方式启动配置容器,返回的上下文由调用方关闭。 */
    public static ConfigurableApplicationContext start(String... args) {
        SpringApplication application = new SpringApplication(DiagnosticConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        return application.run(args);
    }
}
