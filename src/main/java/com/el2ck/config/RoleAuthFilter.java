package com.el2ck.config;

/**
 * 【文件职责】根据请求路径和当前角色限制管理类接口，认证通过后继续执行授权判断。
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
 * 角色授权过滤器:role=user 时,对「隐藏页独占且不与放行页共享」的敏感 API 返回 403。
 *
 * <p>为什么后端也要拦(而不只前端隐藏菜单):前端隐藏只挡界面,user 仍可直接 curl 那些接口。
 * 页面级可见性由前端路由守卫负责;这里做的是敏感操作的安全兜底。
 *
 * <p>限权对象(user 被 403):
 * <ul>
 *   <li>连接管理写操作:{@code POST /api/connections}、{@code POST /api/connections/test}、
 *       {@code PUT|DELETE /api/connections/{id}}、{@code /api/connections/{id}/test|test-config|default};</li>
 *   <li>{@code /api/connections/{id}/users/**}(ClickHouse 用户管理);</li>
 *   <li>{@code /api/connections/{id}/server-stats}(系统监控-目标库);</li>
 *   <li>{@code /api/system/**}(系统监控-本机);</li>
 *   <li>{@code /api/tools/**}(工具:哈希/加解密/DDL 体检);</li>
 *   <li>{@code PUT /api/settings}(全局设置写);</li>
 *   <li>{@code /api/app-users/…}(平台用户管理,仅管理员)。</li>
 * </ul>
 *
 * <p>放行(与放行页共享,必须可用):{@code GET /api/connections}、{@code /api/connections/status}、
 * {@code GET /api/settings}、{@code /api/connections/{id}/databases/…}、{@code /api/connections/{id}/query}、
 * {@code /api/datasources/…}、{@code /api/sync-tasks/…}、{@code /api/query-workflows/…}、
 * {@code /api/arb-workflows/…}、{@code /api/sql-queries/…}、{@code /api/wide-table-tasks} 等。
 *
 * <p>注:字典页 {@code /dict} 没有独立后端接口(DictView 不调 /api/dict),故无需在此拦截。
 * 顺序 @Order 在 {@link BasicAuthFilter} 之后 —— 依赖它先把角色写进 {@link AuthContext}。
 */
public class RoleAuthFilter implements Filter {

    /**
     * 限权入口:只对已认证的普通用户生效 —— 按「方法 + 路径」判定({@link #blockedForUser}),
     * 命中受限规则即返回 403;管理员一律放行,未登录请求也不在这里处理
     * (那属于 {@link BasicAuthFilter} 的职责,本过滤器依赖它先写入角色)。
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        // 仅对已认证的 user 角色生效;未登录(BasicAuthFilter 已拦)或 admin 一律放行。
        if (AuthContext.isUser() && blockedForUser(request.getMethod(), request.getRequestURI())) {
            BasicAuthFilter.writeJson(response, HttpServletResponse.SC_FORBIDDEN, "无权访问该功能");
            return;
        }
        chain.doFilter(req, resp);
    }

    /** 给定方法与路径,判断普通用户是否应被 403。包级可见,供单测直接断言。 */
    static boolean blockedForUser(String method, String uri) {
        if (uri == null) {
            return false;
        }
        String m = method == null ? "" : method.toUpperCase();

        // 平台用户管理 / 工具 / 系统监控(本机):整段前缀独占,直接拦。
        if (uri.startsWith("/api/app-users")) {
            return true;
        }
        if (uri.startsWith("/api/tools/")) {
            return true;
        }
        if (uri.startsWith("/api/system/")) {
            return true;
        }
        // 全局设置:写(PUT)拦,读(GET)放行。
        if ("/api/settings".equals(uri)) {
            return "PUT".equals(m);
        }
        // 连接相关:大部分写/管理操作拦,但 databases/** 与 query 等共享读写放行。
        if ("/api/connections".equals(uri)) {
            // 集合根:列表(GET)放行,新建(POST)拦。
            return "POST".equals(m);
        }
        if ("/api/connections/status".equals(uri)) {
            return false; // 连接状态:放行(放行页的连接下拉/首页要用)
        }
        if ("/api/connections/test".equals(uri)) {
            return true; // 测试一份连接配置:属连接管理
        }
        if (uri.startsWith("/api/connections/")) {
            // 形如 /api/connections/{id}/{seg}/...
            String rest = uri.substring("/api/connections/".length());
            int slash = rest.indexOf('/');
            String seg = slash < 0 ? "" : rest.substring(slash + 1);
            if (seg.isEmpty()) {
                // /api/connections/{id}:编辑(PUT)/删除(DELETE)拦;其余放行。
                return "PUT".equals(m) || "DELETE".equals(m);
            }
            // 取 {id} 之后的第一个路径段
            int seg2Slash = seg.indexOf('/');
            String first = seg2Slash < 0 ? seg : seg.substring(0, seg2Slash);
            switch (first) {
                case "test":         // {id}/test
                case "test-config":  // {id}/test-config
                case "default":      // {id}/default 设默认
                case "settings":     // {id}/settings 同时更新连接与全局配置
                case "server-stats": // {id}/server-stats 系统监控
                case "users":        // {id}/users/** ClickHouse 用户管理
                    return true;
                default:
                    // databases/** query 等共享接口 → 放行
                    return false;
            }
        }
        return false;
    }
}
