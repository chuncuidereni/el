/**
 * 【文件职责】本文件定义用户与授权规则模块的领域结构模型 GrantRule，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.user;

import lombok.Data;

/** ClickHouse 授权规则。access ∈ select | readwrite | all。 */
@Data
public class GrantRule {
    /** 授权目标库(* 表示全部库)。 */
    private String database;
    /** 授权目标表(* 表示库内全部表)。 */
    private String table;
    /** 授权级别:select(只读)/ readwrite(读写)/ all(全部)。 */
    private String access;
}
