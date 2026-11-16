package com.el2ck.config;

/**
 * 【文件职责】配置跨域请求策略，并确保预检请求不会进入业务处理链。
 */

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * CORS 过滤器(对应 Go internal/api/router.go 的 corsMiddleware)。
 *
 * 仅放行 Vite 开发服务器(localhost:17373 / 127.0.0.1:17373)的跨域请求;
 * 所有 OPTIONS 预检直接返回 204(与 Go 的 AbortWithStatus 一致),避免预检进入后续鉴权。
 * 顺序 @Order 最小 → 最先执行(在 BasicAuth 之前)。
 */
// 声明为 Spring 组件,由 Spring Boot 自动注册到 Servlet 过滤器链
@Component
// 排序值最小:保证本过滤器最先执行,先于 BasicAuth 过滤器
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorsFilter implements Filter {

    // 覆写 Filter.doFilter:放行 Vite 开发源的跨域访问,并以 204 短路 OPTIONS 预检
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        // 把通用请求/响应对象强转为 HTTP 类型
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;
        // 取请求头中的 Origin,判断是否为跨域请求
        String origin = request.getHeader("Origin");
        // 仅允许本地 Vite 开发源。
        // 白名单外的来源不回 CORS 头即可(浏览器自行拦截)，无需返回 403，免得误伤同源或无 Origin 的普通请求。
        if ("http://localhost:17373".equals(origin) || "http://127.0.0.1:17373".equals(origin)) {
            // 回写允许跨域的源地址(与请求 Origin 一致)
            response.setHeader("Access-Control-Allow-Origin", origin);
            // 声明允许的 HTTP 方法
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            // 声明允许携带的请求头
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
            // 缓存预检结果 86400 秒,减少重复预检请求
            response.setHeader("Access-Control-Max-Age", "86400");
        }
        // 预检请求直接放行(204),不再向下(避免 Basic Auth 拦截 OPTIONS)。
        // 注意:该短路不在上面的来源白名单之内——任意来源的 OPTIONS 都返回 204,真正的跨域拦截交给浏览器按缺失的 Allow-Origin 头判定。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // 预检通过:直接以 204 No Content 结束响应,不进入下游过滤器
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        // 非预检请求继续沿过滤器链向下传递
        chain.doFilter(req, resp);
    }
}
