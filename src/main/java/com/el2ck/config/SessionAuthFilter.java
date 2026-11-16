package com.el2ck.config;
/**
 * 【文件职责】校验服务端会话身份、账号启用状态、密码版本和会话有效期，并处理强制修改初始密码的访问限制。
 */

import com.el2ck.entity.auth.AppUser;
import com.el2ck.service.auth.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;

/**
 * SessionAuthFilter：校验服务端会话身份、账号启用状态、密码版本和会话有效期，并处理强制修改初始密码的访问限制。
 */
public class SessionAuthFilter extends OncePerRequestFilter {
    private final AppUserService users;
    private final AccountSecurityService accounts;
/**
 * 注入用户读取和账号安全服务；过滤器不缓存用户对象，以便每次请求都能识别禁用和密码版本变化。
 *
 * @param users 用户查询服务
 * @param accounts 账号启用状态与密码版本服务
 */
    public SessionAuthFilter(AppUserService users,AccountSecurityService accounts) {this.users=users;this.accounts=accounts;}
/**
 * 在每个请求上重新校验会话用户、启用状态、密码版本和 12 小时有效期；认证有效时建立
 * {@link AuthContext}，强制改密期间仅放行必要接口，最后无论成功或失败都清理线程上下文。
 *
 * @param req 当前 HTTP 请求
 * @param res 当前 HTTP 响应
 * @param chain 后续 Servlet 过滤链
 * @throws ServletException 下游过滤器失败时传播
 * @throws IOException 写响应或下游处理发生 I/O 错误时传播
 */
    @Override
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        try {
            if(auth!=null && auth.getPrincipal() instanceof SessionIdentity) {
                SessionIdentity identity=(SessionIdentity)auth.getPrincipal();
                AppUser user;
                try { user=users.get(identity.userId); }
                catch(com.el2ck.exception.ApiException e) { invalidate(req); BasicAuthFilter.writeJson(res,401,"登录已失效，请重新登录");return; }
                if(!accounts.enabled(user.getId()) || accounts.version(user.getId())!=identity.version
                    || System.currentTimeMillis()-identity.createdAt>12*60*60*1000L) {
                    invalidate(req);BasicAuthFilter.writeJson(res,401,"登录已失效，请重新登录");return;
                }
                AuthContext.set(user.getId(),user.getUsername(),user.getRole());
                String path=req.getServletPath();
                if(accounts.mustChange(user.getId()) && path.startsWith("/api/") && !path.equals("/api/me")
                    && !path.equals("/api/me/password") && !path.equals("/api/logout")
                    && !path.equals("/api/csrf") && !path.equals("/api/login")) {
                    BasicAuthFilter.writeJson(res,403,"请先修改初始密码");return;
                }
            }
            chain.doFilter(req,res);
        } finally { AuthContext.clear(); }
    }
/**
 * 使当前 HTTP 会话和 Spring Security 上下文同时失效，避免后续过滤器复用旧身份。
 *
 * @param req 本次操作使用的输入参数
 */
    private void invalidate(HttpServletRequest req) {
        HttpSession s=req.getSession(false);if(s!=null)s.invalidate();SecurityContextHolder.clearContext();
    }
}
