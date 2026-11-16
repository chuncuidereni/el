/**
 * 【文件职责】本文件定义ARB 查询编排模块的请求/写入参数模型 ArbWorkflowInput，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 新建 / 更新工作流的入参(HTTP 请求体),对应 Go {@code service.ArbWorkflowInput}。
 *
 * <p>⚠️ {@link #enabled} 与 {@link #wide} 刻意是包装类型而不是 boolean:
 * 「没传」与「明确传 false」是两种不同的语义,用值类型时前者会被当成 false,
 * 于是一次局部更新(比如只改描述)会把标记或启用状态悄悄抹掉 —— 实测踩过两次:
 * <ul>
 *   <li>{@code isWide}:在列表里点一条普通工作流的启用开关(前端会带上 isWide:false),
 *       真正的宽表标记就被抹掉,宽表页签再也找不到那张表;</li>
 *   <li>{@code enabled}:用户停用一条工作流后随便改个字段,它又被跑起来了
 *       (sum 类工作流会因此重复累加)。</li>
 * </ul>
 * null = 保持原值,false = 明确取消。
 */
@Data
public class ArbWorkflowInput {
    /** 工作流名称。必填。 */
    private String name;
    /** 描述,可为空。 */
    private String description;
    /** 是否启用。null = 保持原值;新建时不传按启用处理。 */
    private Boolean enabled;
    /**
     * 源表与目标表所在的连接。
     *
     * <p>界面上不再让用户选(用户原话:基本信息中也不要连接了),传空(0/不传)就用默认连接。
     * 显式传了合法 id 仍尊重,便于接口直调与测试。
     */
    private long connectionId;
    /** 标记为「宽表」(同一时刻只有一个)。null = 保持原值。 */
    @JsonProperty("isWide")
    private Boolean wide;
    /**
     * 目标库。
     *
     * <p>⚠️ 收下但不认:界面上【不暴露 CK 的库概念】,服务层统一落到 {@link ArbWorkflow#DEFAULT_DB}。
     * 留着这个字段是为了让前端的请求体多传它时不会 400(反序列化失败),而不是因为它有用。
     */
    private String targetDb;
    /** 聚合表的基础名。用户只填中间那段,前后缀由服务层补。 */
    private String targetTable;
    /** 「②源」。 */
    private List<ArbSource> sources = new ArrayList<>();
    /** 「③字段」。 */
    private List<ArbField> fields = new ArrayList<>();
    /** 「④仲裁方案」。 */
    private List<ArbColumn> columns = new ArrayList<>();
}
