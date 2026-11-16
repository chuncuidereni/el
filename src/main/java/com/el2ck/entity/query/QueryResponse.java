/**
 * 【文件职责】本文件定义查询执行模块的查询或操作结果模型 QueryResponse，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.el2ck.ckpool.QueryResult;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行响应:多语句时按顺序产出多个结果(只读)与多条消息(写),truncated 表示结果被截断到 1000 行。
 */
@Data
public class QueryResponse {
    /** 本次执行是否只包含读操作。 */
    private Boolean readOnly;
    /** 各条读语句的执行结果(多语句时按顺序对应)。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<QueryResult> results;
    /** 各条写语句的返回消息。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> messages;
    /** 结果是否被截断到 1000 行。 */
    private Boolean truncated;

    /**
     * 与 {@link #results} 一一对应的原始语句文本。
     *
     * <p>用途:结果抽屉里对单个结果集导出时,要能拿到它对应的那条语句(多语句时尤其)。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> resultStatements;

    /**
     * 结果列名 → 该列在 ClickHouse 里的表字段注释(只含「有注释」的列)。
     *
     * <p>用途:界面在列名旁展示注释 —— 贴源表里 mdn / wls_16 这类缩写光看名字猜不出来,
     * 注释才是唯一说得清含义的东西。
     *
     * <p>⚠️ 取注释失败【不当错误】:它只是展示信息,不该因为取不到注释就让整个查询失败。
     * 同名不同注释时取到哪一个存在不确定性,因此它只用于展示,不作为数据依据。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> columnComments;
}
