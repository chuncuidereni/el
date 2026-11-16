/**
 * 【文件职责】本文件定义认证与资源授权模块的领域数据模型 AppUser，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.auth;

/**
 * app_users 表一行:平台登录用户(区别于 ClickHouse 用户,后者由 UserController 管理)。
 *
 * <p>密码以「每账号随机盐 + hex(sha256(盐||明文))」存储:{@code salt} 存盐,
 * {@code passwordHash} 存十六进制摘要;绝不落明文。角色 {@code role} 取值 admin / user。
 */
public class AppUser {
    // 自增主键
    private long id;
    // 登录用户名(唯一)
    private String username;
    // 密码摘要:hex(sha256(salt||password)),大写十六进制
    private String passwordHash;
    // 每账号随机盐(hex)
    private String salt;
    // 角色:admin(管理员,全权) / user(普通用户,仅首页+数据源组+工作台组)
    private String role;
    // 创建时间
    private String createdAt;
    // 更新时间
    private String updatedAt;
    /**
     * 读取 Id 字段。
     */
    public long getId() { return id; }
    /**
     * 设置 Id 字段。
     *
     * @param id 资源或记录标识
     */
    public void setId(long id) { this.id = id; }
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
     * 读取 PasswordHash 字段。
     */
    public String getPasswordHash() { return passwordHash; }
    /**
     * 设置密码摘要。
     *
     * @param passwordHash 经过不可逆算法和盐值处理后的密码摘要
     */
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    /**
     * 读取 Salt 字段。
     */
    public String getSalt() { return salt; }
    /**
     * 设置该账号密码使用的盐值。
     *
     * @param salt 与密码摘要配套保存的随机盐值
     */
    public void setSalt(String salt) { this.salt = salt; }
    /**
     * 读取 Role 字段。
     */
    public String getRole() { return role; }
    /**
     * 设置账号角色。
     *
     * @param role 角色标识；持久化前由服务层归一化并校验
     */
    public void setRole(String role) { this.role = role; }
    /**
     * 读取 CreatedAt 字段。
     */
    public String getCreatedAt() { return createdAt; }
    /**
     * 设置账号创建时间。
     *
     * @param createdAt 数据库或调用方提供的创建时间文本
     */
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    /**
     * 读取 UpdatedAt 字段。
     */
    public String getUpdatedAt() { return updatedAt; }
    /**
     * 设置账号最后更新时间。
     *
     * @param updatedAt 数据库或调用方提供的更新时间文本
     */
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
