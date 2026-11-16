/**
 * 【文件职责】本文件定义查询工作流模块的领域结构模型 QueryWorkflowSource，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一张源表(向导步骤①),对应 Go {@code model.QueryWorkflowSource}。 */
@Data
public class QueryWorkflowSource {
    /** 用于读取的连接 ID。多表关联要求所有源表属于同一连接。 */
    private long connectionId;
    /** 库名,如 ods_data。 */
    private String database;
    /** 库下的表名。 */
    private String table;
    /** 勾选字段(空 = 全部,生成 {@code *})。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> columns = new ArrayList<>();
    /** 列重命名:源列名 → 新列名。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> rename = new LinkedHashMap<>();
    /**
     * 行级 WHERE(对源表)。
     *
     * <p>⚠️ 已废弃且【显式拒绝】:这个字段会被原样拼进 CTE,是一整条 SQL 注入面
     * (实测 {@code "1=1 UNION ALL SELECT credit_card FROM ods_data.secret"} 可原样进入)。
     * 前端已无任何代码会写它,故传非空一律报错,而不是静默忽略 ——
     * 静默忽略会让存量配置的结果悄悄变化。请改用步骤④的过滤条件。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String whereFilter;
}
