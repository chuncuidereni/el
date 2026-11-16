package com.el2ck.config;

/**
 * 【文件职责】注册 MVC 拦截器和静态资源规则，把 Web 层基础设施接入 Spring MVC。
 */

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * Web MVC 静态资源配置(对应 Go internal/api/router.go 的 serveWeb)。
 *
 * 生产环境把前端构建产物(dist)原样托管:
 *  - /assets/**      → <webDir>/assets/ 下文件(Vue 打包产物);
 *  - /favicon.svg    → <webDir>/favicon.svg;
 *  - 其余非 /api 路由(HTML5 history 深链)→ 由 ApiAdvice 兜底返回 index.html。
 * 静态资源处理器始终注册,支持后端先启动、前端稍后构建的开发流程。
 */
// 声明为 Spring 配置类,实现 WebMvcConfigurer 以扩展静态资源映射
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    // 应用运行配置:读取前端产物目录 webDir
    private final AppConfig cfg;
    private final ResourceBoundaryInterceptor resourceBoundary;

    // 构造器:注入应用配置
    public WebMvcConfig(AppConfig cfg, ResourceBoundaryInterceptor resourceBoundary) {
        // 保存注入的应用配置
        this.cfg = cfg;
        this.resourceBoundary = resourceBoundary;
    }


    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(resourceBoundary).addPathPatterns("/api/**");
    }

    // 覆写 addResourceHandlers:注册前端构建产物的静态资源处理器
/**
 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 计算前端目录的绝对路径并把分隔符统一为正斜杠,得到资源根路径
        String base = new File(cfg.getWebDir()).getAbsolutePath().replace('\\', '/');
        // 前端打包产物 /assets/**(带目录斜杠结尾,Spring 要求)。
        String assets = base + "/assets/";
        // 把 URL /assets/** 映射到 file:<base>/assets/ 目录
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("file:" + assets);
        // 站点图标。
        // 把 URL /favicon.svg 映射到 file:<base>/favicon.svg
        registry.addResourceHandler("/favicon.svg")
                .addResourceLocations("file:" + base + "/");
    }
}
