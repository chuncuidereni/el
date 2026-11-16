/**
 * 【文件职责】本文件定义认证与资源授权模块的业务服务 AppUserService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.auth;

import com.el2ck.entity.auth.AppUser;
import com.el2ck.exception.ApiException;
import com.el2ck.mapper.auth.AppUserMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

/**
 * 平台登录用户服务:登录校验、增删改、改密。
 *
 * <p>密码存储 = 每账号随机盐 + {@code hex(sha256(salt||password))}(大写十六进制),绝不落明文。
 * 校验用 {@link MessageDigest#isEqual} 常量时间比对摘要字节,避免时序侧信道。
 * 角色仅 admin / user 两种。
 */
@Service
public class AppUserService {

    /** 合法角色。 */
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    private final AppUserMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwords =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    /**
     * 创建平台用户持久化服务并装配后续调用所需的依赖。
     *
     * 依赖由 Spring 容器统一管理；本构造器只保存引用或初始化轻量适配对象，不执行远程业务操作。
     *
     * @param mapper MyBatis 持久化映射器
     */
    public AppUserService(AppUserMapper mapper) {
        this.mapper = mapper;
    }

    // =====================================================================
    // 登录校验
    // =====================================================================

    /**
     * 校验用户名/密码。成功返回该用户,失败返回空 —— 调用方据此决定 401(不区分「用户不存在」与「密码错」)。
     */
    public Optional<AppUser> verify(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        AppUser u = mapper.findByUsername(username.trim());
        if (u == null) {
            return Optional.empty();
        }
        if (u.getPasswordHash().startsWith("$2")) {
            return passwords.matches(password, u.getPasswordHash()) ? Optional.of(u) : Optional.empty();
        }
        byte[] expected = hexToBytes(u.getPasswordHash());
        byte[] actual = hexToBytes(hashPassword(password, u.getSalt()));
        // 常量时间比对摘要字节(长度不同 isEqual 直接返回 false)
        if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();
        // Login must not rewrite a shared account: older deployments only understand the legacy digest.
        // Password format migration requires an explicit rollout after all consumers are compatible.
        return Optional.of(u);
    }

    // =====================================================================
    // 查询 / 增删改
    // =====================================================================

    /** 全部平台账号(按 id 倒序,最新创建在前)。 */
    public List<AppUser> list() {
        return mapper.listAll();
    }

    /** 按主键取账号;不存在抛 404。各写操作前用它做存在性校验。 */
    public AppUser get(long id) {
        AppUser u = mapper.findById(id);
        if (u == null) {
            throw ApiException.notFound("用户不存在");
        }
        return u;
    }

    /** 建用户(用户名唯一、角色合法、密码非空)。返回落库后的用户。 */
    public AppUser create(String username, String password, String role) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw ApiException.validation("用户名不能为空");
        }
        if (password == null || password.isEmpty()) {
            throw ApiException.validation("密码不能为空");
        }
        String r = normalizeRole(role);
        if (mapper.findByUsername(name) != null) {
            throw ApiException.validation("用户名已存在: " + name);
        }
        AppUser u = new AppUser();
        u.setUsername(name);
        String salt = newSalt();
        u.setSalt(salt);
        validatePassword(password);
        u.setPasswordHash(passwords.encode(password));
        u.setRole(r);
        mapper.insert(u);
        return u;
    }

    /**
     * 幂等播种一个账号:已存在同名则不动(返回 false),不存在则创建(返回 true)。启动播种用。
     */
    public boolean seedIfAbsent(String username, String password, String role) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || mapper.findByUsername(name) != null) {
            return false;
        }
        create(name, password, role);
        return true;
    }

    /** 修改账号角色;角色取值会先归一到 admin / user。 */
    public void updateRole(long id, String role) {
        get(id); // 存在性校验
        mapper.updateRole(id, normalizeRole(role));
    }

    /** 删除账号。只做存在性校验,**不阻止删掉最后一个管理员**(删空后平台将无法登录)。 */
    public void delete(long id) {
        get(id);
        mapper.delete(id);
    }

    /** 管理员重置某用户密码(不校验旧密码)。 */
    public void resetPassword(long id, String newPassword) {
        get(id);
        if (newPassword == null || newPassword.isEmpty()) {
            throw ApiException.validation("新密码不能为空");
        }
        String salt = newSalt();
        validatePassword(newPassword);
        mapper.updatePassword(id, passwords.encode(newPassword), "");
    }

    /** 用户改自己的密码:校验旧密码。 */
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.isEmpty()) {
            throw ApiException.validation("新密码不能为空");
        }
        AppUser u = verify(username, oldPassword)
                .orElseThrow(() -> ApiException.validation("原密码不正确"));
        String salt = newSalt();
        validatePassword(newPassword);
        mapper.updatePassword(u.getId(), passwords.encode(newPassword), "");
    }

    /**
     * 校验平台密码满足长度、大小写、数字和特殊字符要求。
     *
     * @param password 明文密码
     * @throws com.el2ck.common.ApiException 不符合策略时抛出 400
     */
    private static void validatePassword(String password) {
        // 不设最小长度;仅保留 BCrypt 的 72 字节上限,避免超长密码被静默截断。
        if (password == null || password.isEmpty()) {
            throw ApiException.validation("密码不能为空");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.validation("密码 UTF-8 编码不能超过 72 字节");
        }
    }

    // =====================================================================
    // 内部:哈希与盐
    // =====================================================================

    /**
     * 将角色输入规范化并校验为受支持的角色代码。
     *
     * @param role 原始角色
     * @return 规范化角色
     * @throws com.el2ck.common.ApiException 角色非法时抛出 400
     */
    private String normalizeRole(String role) {
        String r = role == null ? "" : role.trim().toLowerCase();
        if (!ROLE_ADMIN.equals(r) && !ROLE_USER.equals(r)) {
            throw ApiException.validation("角色只能是 admin 或 user");
        }
        return r;
    }

    /** 生成 16 字节随机盐的十六进制串。 */
    private String newSalt() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return toHex(b);
    }

    /** hex(sha256(salt||password)),大写。salt 为盐的十六进制字符串。 */
    static String hashPassword(String password, String saltHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(hexToBytes(saltHex));
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法,理论上不会走到这里
            throw ApiException.server("哈希算法不可用: " + e.getMessage());
        }
    }

    /**
     * 将字节数组编码为大写十六进制文本。
     *
     * @param data 待编码字节
     * @return 每字节两位的大写十六进制字符串
     */
    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase();
    }

    /**
     * 解析十六进制文本；null、奇数长度或含非十六进制字符时返回空数组。
     *
     * <p>空数组用于让调用方按“未配置”处理，而不是把格式错误静默解释成部分数据。
     *
     * @param hex 十六进制文本，可含首尾空白
     * @return 解码后的字节；输入非法时为空数组
     */
    private static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return new byte[0];
        }
        String s = hex.trim();
        int len = s.length();
        if (len % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return new byte[0];
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
