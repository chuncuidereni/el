/**
 * 【文件职责】本文件定义用户与授权规则模块的请求/写入参数模型 UserInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.user;

import lombok.Data;

import java.util.List;

/** 创建/更新 ClickHouse 用户的请求体。 */
@Data
public class UserInput {
    /** 用户名。 */
    private String name;
    /** 密码(仅创建/重置时下发)。 */
    private String password;
    /** 允许登录的主机列表(为空表示不限制)。 */
    private List<String> hosts;
    /** 授权规则列表。 */
    private List<GrantRule> grants;
    /** true=清空该用户全部授权后再按 grants 重建 */
    private Boolean clearGrants;
    /** 备注。 */
    private String remark;
}
