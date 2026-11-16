package com.el2ck.config;

/**
 * 【文件职责】保存一次 HTTP 请求线程内的已认证用户身份，供服务层做权限判断并在请求结束时清理。
 */

/**
 * 请求级登录身份上下文(ThreadLocal)。
 *
 * <p>{@link BasicAuthFilter} 校验通过后写入当前请求的 {username, role},
 * {@link RoleAuthFilter} 与 {@code AuthController#me} 读取它;请求处理完毕后由
 * BasicAuthFilter 在 {@code finally} 里 {@link #clear()},避免线程复用时串号。
 */
public final class AuthContext {

    /** 当前线程的登录身份;未登录(或已清理)为 null。 */
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 一次登录身份:用户名 + 角色(admin / user)。 */
    public static final class Principal {
        private final long userId;
        private final String username;
        private final String role;

        public Principal(String username, String role) {
            this(0, username, role);
        }

        public Principal(long userId, String username, String role) {
            this.userId = userId;
            this.username = username;
            this.role = role;
        }

/** 返回当前登录身份的用户名。 */
        public String getUsername() {
            return username;
        }

/** 返回当前登录身份的用户 ID。 */
        public long getUserId() { return userId; }

/** 返回当前登录身份的角色。 */
        public String getRole() {
            return role;
        }
    }

    /** 写入当前请求的登录身份。 */
    public static void set(String username, String role) {
        CURRENT.set(new Principal(username, role));
    }

/** 把用户 ID、用户名和角色写入当前请求线程的身份上下文。 */
    public static void set(long userId, String username, String role) {
        CURRENT.set(new Principal(userId, username, role));
    }


    public static long userId() {
        Principal p = get();
        if (p == null || p.userId <= 0) throw new com.el2ck.exception.ApiException(401, "请先登录");
        return p.userId;
    }

    /** 取当前请求的登录身份;未登录返回 null。 */
    public static Principal get() {
        return CURRENT.get();
    }

    /** 当前是否为普通用户(user)角色。 */
    public static boolean isUser() {
        Principal p = CURRENT.get();
        return p != null && "user".equals(p.getRole());
    }

    /** 内置平台管理员使用配置的 CK 业务账号;其他账号仍按各自身份隔离。 */
    public static boolean isBuiltinAdmin() {
        Principal p = CURRENT.get();
        return p != null && "admin".equals(p.getRole()) && "admin".equals(p.getUsername());
    }

    /** 清理线程本地存储(请求收尾必调)。 */
    public static void clear() {
        CURRENT.remove();
    }
}
