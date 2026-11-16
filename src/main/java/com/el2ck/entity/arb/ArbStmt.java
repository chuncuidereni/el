/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbStmt，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import lombok.Data;

/**
 * 一条待执行的语句,对应 Go {@code sync.ArbStmt}。
 *
 * <p>{@link #source} 只用于界面分组显示(如 {@code ods_data.ods_operator (pri=3)}),
 * 不参与 SQL。
 */
@Data
public class ArbStmt {
    /** 该语句属于哪个源(界面用的标签)。 */
    private String source;
    /** 语句正文。 */
    private String sql;

    /** 空构造器(Jackson 反序列化与手工装配都要用)。 */
    public ArbStmt() {
    }

    /** 直接给出标签与语句。 */
    public ArbStmt(String source, String sql) {
        this.source = source;
        this.sql = sql;
    }
}
