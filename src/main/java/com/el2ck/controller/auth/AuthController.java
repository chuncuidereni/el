/**
 * 【文件职责】本文件定义认证与资源授权模块的HTTP 控制器 AuthController。接收 Web 请求并完成路由与参数适配，统一封装响应；核心校验、权限和持久化逻辑委托给服务层。
 */

package com.el2ck.controller.auth;

import com.el2ck.controller.common.ApiResponse;

import com.el2ck.config.AuthContext;
import com.el2ck.entity.auth.AppUser;
import com.el2ck.exception.ApiException;
import com.el2ck.service.auth.AppUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import com.el2ck.config.SessionIdentity;
import com.el2ck.service.auth.AccountSecurityService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.Collections;

/**
 * 登录校验与当前身份接口 —— 供独立登录页恢复登录态使用。
 *
 * <p>只负责「验证凭据」与「回报身份」两件事;凭据的逐请求校验由
 * {@link com.el2ck.config.BasicAuthFilter} 承担,本类不参与会话管理(无 token、无 session)。
 *
 * <p>路由豁免见 BasicAuthFilter:{@code /api/login} 与 {@code /api/health} 不要求 Basic 凭据
 * —— 前者是获取凭据的入口,后者用于探活,否则会形成「要登录先得带上凭据」的死循环。
 */
@RestController
@RequestMapping("/api")
public class AuthController {
    private final AppUserService users;
    private final AccountSecurityService accounts;
    private final Map<String, long[]> attempts = new java.util.LinkedHashMap<>();
    /**
     * 创建认证与当前会话 HTTP 控制器并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param users 平台用户服务，负责账号持久化、角色和密码校验
     * @param accounts 账号安全服务，用于校验启用状态、首次改密和会话版本
     */
    public AuthController(AppUserService users, AccountSecurityService accounts) {
        this.users = users;
        this.accounts = accounts;
    }

    /**
     * 校验用户名口令,成功则返回身份(用户名 + 角色)。
     *
     * <p>唯一的公开接口。失败一律返回 401「用户名或密码错误」,**不区分「用户不存在」与
     * 「密码错误」** —— 否则接口会被用来枚举平台上有哪些账号。
     */
    @PostMapping("/login")
    public ApiResponse login(@RequestBody(required = false) LoginInput input, HttpServletRequest request, HttpServletResponse response) {
        throttle(request.getRemoteAddr());
        String username = input == null ? null : input.getUsername();
        String password = input == null ? null : input.getPassword();
        AppUser user = users.verify(username, password)
                .orElseThrow(() -> new ApiException(401, "用户名或密码错误"));
        if (!accounts.enabled(user.getId())) throw new ApiException(401, "用户名或密码错误");
        HttpSession previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        request.getSession(true).setMaxInactiveInterval(30 * 60);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new SessionIdentity(user.getId(), accounts.version(user.getId())), null, Collections.emptyList()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        AuthContext.set(user.getId(),user.getUsername(),user.getRole());
        accounts.loggedIn(user.getId());
        return ApiResponse.ok(accounts.identity(user));
    }

    /**
     * 回报当前请求的身份。
     *
     * <p>身份不来自请求体,而取自 {@link AuthContext} —— 它由 BasicAuthFilter 在验证凭据后写入、
     * 请求结束时清理。因此这里的 401 意味着「本请求没带或带了无效凭据」,与 {@link #login} 的
     * 401 含义不同。
     */
    @GetMapping("/me")
    public ApiResponse me() {
        AuthContext.Principal principal = AuthContext.get();
        if (principal == null) {
            throw new ApiException(401, "未授权");
        }
        return ApiResponse.ok(accounts.identity(users.get(principal.getUserId())));
    }
    /**
     * 处理 {@code GET /api/csrf} 请求：返回当前会话的 CSRF 令牌。
     *
     * 响应供前端在后续写请求中回传，令牌值来自 Spring Security 的当前请求上下文。
     *
     * @param token token 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @GetMapping("/csrf")
    public ApiResponse csrf(CsrfToken token) {
        Map<String,String> result = new LinkedHashMap<>();
        result.put("token", token.getToken()); result.put("headerName", token.getHeaderName());
        return ApiResponse.ok(result);
    }
    /**
     * 处理 {@code POST /api/logout} 请求：注销当前登录会话。
     *
     * 清理当前会话并返回统一成功响应；其他设备上的会话不受影响。
     *
     * @param request request 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/logout")
    public ApiResponse logout(HttpServletRequest request) {
        accounts.audit("logout", "user", AuthContext.userId(), AuthContext.userId());
        invalidate(request);
        return ApiResponse.ok(Collections.singletonMap("ok", true));
    }
    /**
     * 处理 {@code POST /api/me/logout-all} 请求：注销当前账号的全部登录会话。
     *
     * 账号认证版本递增后，已有会话在后续鉴权时失效。
     *
     * @param request request 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PostMapping("/me/logout-all")
    public ApiResponse logoutAll(HttpServletRequest request) {
        accounts.revoke(AuthContext.userId());
        invalidate(request);
        return ApiResponse.ok(Collections.singletonMap("ok", true));
    }
    /**
     * 处理 {@code PUT /api/me/password} 请求：修改当前登录用户的密码。
     *
     * 服务层校验旧密码，更新密码摘要、递增认证版本并记录审计；校验失败不会修改密码。
     *
     * @param input input 参数；其具体约束由该端点调用链校验
     * @param request request 参数；其具体约束由该端点调用链校验
     * @return 统一 {@link com.el2ck.controller.common.ApiResponse}；成功时封装业务结果，失败由全局异常处理器转换
     */
    @PutMapping("/me/password")
    public ApiResponse changePassword(@RequestBody Map<String,String> input, HttpServletRequest request) {
        accounts.changePassword(input.get("oldPassword"), input.get("newPassword"));
        invalidate(request);
        return ApiResponse.ok(Collections.singletonMap("ok", true));
    }

    /**
     * 注销当前 HTTP 会话并清理线程绑定的 Spring Security 上下文。
     *
     * <p>会话不存在时仍会清理安全上下文，因此重复调用是安全的；本方法不负责
     * 向客户端写 Cookie，响应头由上层注销端点处理。
     *
     * @param request 当前请求
     */
    private void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    /**
     * 对登录来源地址执行进程内滑动窗口限流。
     *
     * <p>每次调用先清理一分钟前的记录；单个地址一分钟内第 16 次尝试、或全局
     * 地址表超过 10,000 项时抛出 429。同步块保证当前 JVM 内计数原子性，
     * 但计数不跨实例共享，重启后也会清空。
     *
     * @param address 客户端来源地址
     */
    private synchronized void throttle(String address) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        if (!attempts.containsKey(address) && attempts.size() >= 10000) throw new ApiException(429, "登录请求过于频繁，请稍后重试");
        long[] entry = attempts.computeIfAbsent(address, key -> new long[]{now, 0});
        if (++entry[1] > 15) throw new ApiException(429, "登录请求过于频繁，请稍后重试");
    }

    /**
     * 构造登录响应中的身份信息，并保持 username、role 的稳定输出顺序。
     *
     * @param username 用户名
     * @param role 角色代码
     * @return 只含用户名和角色的有序映射
     */
    private static Map<String, String> identity(String username, String role) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("username", username);
        result.put("role", role);
        return result;
    }

    public static class LoginInput {
        private String username;
        private String password;
        /**
         * 读取 Username 字段。
         */
        public String getUsername() { return username; }
        /**
         * 设置 Username 字段。
         *
         * @param username 用户名
         */
        public void setUsername(String username) { this.username = username; }
        /**
         * 读取 Password 字段。
         */
        public String getPassword() { return password; }
        /**
         * 设置 Password 字段。
         *
         * @param password 密码
         */
        public void setPassword(String password) { this.password = password; }
    }
}
