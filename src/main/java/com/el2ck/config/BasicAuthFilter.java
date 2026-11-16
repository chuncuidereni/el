package com.el2ck.config;

/**
 * 【文件职责】处理基础认证、登录失败响应和认证上下文建立，是安全过滤链的一部分。
 */

import com.el2ck.entity.auth.AppUser;
import com.el2ck.service.auth.AppUserService;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * HTTP Basic 鉴权过滤器 —— 校验对象从「单一配置账号」升级为 {@code app_users} 表(加盐 SHA-256)。
 *
 * 规则:
 *  - /api/health、/api/login 豁免(探活与登录本身);
 *  - 其余 /api/** 均需携带 Authorization: Basic base64(user:pass),按 UTF-8 解码;
 *  - 凭据比对交给 {@link AppUserService#verify}(盐+摘要,常量时间比对);
 *  - 校验通过后把 {username, role} 写入请求属性与 {@link AuthContext}(供 {@link RoleAuthFilter} 与 /api/me 使用),
 *    并在 {@code finally} 里清理 ThreadLocal;
 *  - 失败返回 JSON 401，交给前端登录页处理。
 *
 * <p>与旧版差异:不再有「未配置 user 则整体不鉴权」的开发旁路 —— 平台用户表启动即播种
 * admin/user 两个账号(见 StartupConfiguration),登录成为硬门槛。
 * 顺序 @Order 较高 → 在 CORS(OPTIONS 已 204 拦截)之后、{@link RoleAuthFilter} 之前执行。
 */
public class BasicAuthFilter implements Filter {

    /** 平台用户校验服务(查 app_users + 盐+摘要比对)。 */
    private final AppUserService appUsers;


    public BasicAuthFilter(AppUserService appUsers) {
        this.appUsers = appUsers;
    }

    /**
     * 鉴权入口:非 {@code /api/**} 以及探活({@code /api/health})、登录({@code /api/login})直接放行;
     * 其余请求校验 Basic 凭据,通过后把身份写入请求属性与 {@link AuthContext}
     * (后者在 {@code finally} 里清理,避免线程复用时串号)。
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        String uri = request.getRequestURI();
        // 仅保护 /api/**,并豁免探活与登录端点。
        boolean isApi = uri.startsWith("/api/") || "/api".equals(uri);
        if (!isApi || "/api/health".equals(uri) || uri.startsWith("/api/health/") || "/api/login".equals(uri)) {
            chain.doFilter(req, resp);
            return;
        }

        // 校验凭据 → 命中则取到该用户
        Optional<AppUser> user = authenticate(request);
        if (!user.isPresent()) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未授权");
            return;
        }

        // 登录身份下放:请求属性(controller 可读) + ThreadLocal(RoleAuthFilter / AuthController 读)
        AppUser u = user.get();
        request.setAttribute("el2ck.username", u.getUsername());
        request.setAttribute("el2ck.role", u.getRole());
        AuthContext.set(u.getUsername(), u.getRole());
        try {
            chain.doFilter(req, resp);
        } finally {
            // 线程会被复用,必须清理,否则串到下一个请求
            AuthContext.clear();
        }
    }

    /** 解析 Authorization: Basic base64(user:pass) 并交给 AppUserService 校验;失败返回空。 */
    private Optional<AppUser> authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return Optional.empty();
        }
        // 切出 scheme 与 base64 载荷(容忍多空格)
        String[] parts = header.trim().split("\\s+", 2);
        if (parts.length != 2 || !"Basic".equalsIgnoreCase(parts[0])) {
            return Optional.empty();
        }
        byte[] decoded = decodeBase64(parts[1].trim());
        if (decoded == null) {
            return Optional.empty();
        }
        // 还原 user:pass —— 只按第一个冒号切分(密码里可能含冒号)
        String creds = new String(decoded, StandardCharsets.UTF_8);
        int colon = creds.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String username = creds.substring(0, colon);
        String password = creds.substring(colon + 1);
        return appUsers.verify(username, password);
    }

    /** 标准 base64 解码;失败退回 MIME 宽松解码(容忍缺失 padding)。 */
    private static byte[] decodeBase64(String s) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getMimeDecoder().decode(s);
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    /** 输出 JSON 错误体:{code,message},无 data 字段(与 ApiResponse 失败信封一致)。 */
    static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = ("{\"code\":" + status + ",\"message\":\"" + escape(message) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        OutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }

    /** 简单 JSON 字符串转义。 */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
