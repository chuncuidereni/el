/**
 * 【文件职责】本文件定义用户与授权规则模块的展示或状态视图模型 UserInfo，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/** ClickHouse 用户信息。authType 来自 SHOW USERS/系统表。 */
@Data
public class UserInfo {
    /** 用户名。 */
    private String name;
    /** 认证类型(如 sha256_password/plaintext_password,来自 SHOW USERS/系统表)。 */
    private String authType;
    /** 权限角色:只读 / 读写 / 管理员。 */
    private String role;
    /** 允许登录的主机列表。 */
    private List<String> hosts;
    /** 备注;为空时省略。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String remark;
}
