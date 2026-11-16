/**
 * 【文件职责】本文件定义数据库连接管理模块的领域数据模型 Connection，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.connection;

import com.el2ck.ckpool.CkConfig;
import lombok.Data;

/**
 * 一条 ClickHouse 目标源连接配置(存储模型)。密码以密文存放,永不通过 API 回传。
 * 高级配置字段为空表示继承全局默认。
 */
@Data
public class Connection {
    /** 连接主键。 */
    private long id;
    /** 连接名称。 */
    private String name;
    /** 主机地址。 */
    private String host;
    /** 端口。 */
    private int port;
    /** 数据库名。 */
    private String database;
    /** 用户名。 */
    private String username;
    /** 密码密文(仅存储/读取用,永不回传前端)。 */
    private String passwordEnc;
    /**
     * 连接协议:{@link CkConfig#PROTOCOL_NATIVE}(默认)或 {@link CkConfig#PROTOCOL_HTTP}。
     *
     * <p>字段默认值给 {@code native} 而非 null,是为了让「未显式设置协议的旧代码路径」
     * 与数据库列默认值({@code NOT NULL DEFAULT 'native'})行为一致,避免写入 NULL 撞约束。
     */
    private String protocol = CkConfig.PROTOCOL_NATIVE;
    /** 是否启用 TLS 安全连接。 */
    private boolean secure;
    /** 描述/备注。 */
    private String description;
    /** 是否为默认连接。 */
    private boolean isDefault;
    /** 创建时间。 */
    private String createdAt;
    /** 更新时间。 */
    private String updatedAt;

    // ---- 高级配置(可空,继承全局) ----
    /** 拨号超时覆盖(毫秒);为 null 时继承全局默认。 */
    private Long dialTimeoutMs;
    /** 查询超时覆盖(毫秒);为 null 时继承全局默认。 */
    private Long queryTimeoutMs;
    /** 连接最长存活覆盖(分钟);为 null 时继承全局默认。 */
    private Long connMaxLifetimeMin;
    /** 只读覆盖;为 null 时继承全局默认。 */
    private Boolean readOnly;
    /** 自定义连接参数(JSON);为 null 时继承全局默认。 */
    private String paramsJson;
}
