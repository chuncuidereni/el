/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域结构模型 DDLCheckColumn，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 表体检报告里的一列 —— 只陈述事实,不做判断。对应 Go service.DDLCheckColumn。
 * <p>这些字段(编码/注释/TTL)是用户判断「这一列为什么长这样」的依据,体检只负责原样带出来。
 */
@Data
public class DDLCheckColumn {
    /** 列名。 */
    private String name;
    /** 类型原文(如 {@code DateTime64(3, 'Asia/Shanghai')})。 */
    private String type;
    /** DEFAULT / MATERIALIZED / ALIAS / 空 —— 决定 defaultExpr 是不是「默认值」语义。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String defaultType;
    /**
     * 默认值/表达式原文。JSON 键是 {@code default}(与 Go 对齐);
     * Java 里 {@code default} 是保留字,故字段名另取,由 @JsonProperty 改回。
     */
    @JsonProperty("default")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String defaultExpr;
    /** 列备注。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String comment;
    /** 编码表达式(如 ZSTD(1))。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String codec;
    /** TTL 表达式。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String ttl;
}
