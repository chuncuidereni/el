/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbSource，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.el2ck.entity.query.QueryPipelineStep;
import com.el2ck.entity.queryworkflow.QueryWorkflowSource;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张贴源表,对应 Go {@code model.ArbSource}(多源仲裁工作流的「②源」一块)。
 *
 * <p>与 {@link QueryWorkflowSource}(数据探索的 JOIN 型)是两套东西:那个是同名字段静态挑一侧,
 * 这里是 UNION ALL 之后由 (非空, 优先级, 时间) 仲裁。
 */
@Data
public class ArbSource {
    /**
     * 前端生成的稳定标识(s0/s1…),字段靠它挂在源上。
     *
     * <p>⚠️ 不用下标:中途删一张源表会让所有下标错位,原本属于 A 的字段会挂到 B 上。
     */
    private String id;
    /** 源表所在库。界面不暴露,服务层补默认值 ods_data。 */
    private String database;
    /** 源表名。 */
    private String table;
    /** 号码列(各源可以不同名,归一化后统一落到聚合表的 mdn)。 */
    private String mdnCol;
    /** 号码列的处理链(取前 7 位、转 MD5…)。多源号码格式不一致时靠它对齐到同一个 mdn。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryPipelineStep> mdnSteps = new ArrayList<>();
    /**
     * 更新时间列。空 = 该源无时间,仲裁时时间位恒为 0(等价于「只比非空和优先级」)。
     *
     * <p>omitempty:空串不序列化(对齐 Go 的 {@code json:"timeCol,omitempty"})。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String timeCol;
    /**
     * 来源优先级,越大越权威。挂在【源】上而不是字段上 ——「谁更权威」是源的属性,与字段无关。
     *
     * <p>用 int 而非 byte 对齐 JSON 数字语义;Go 侧是 uint8,取值域一致
     * (服务层补默认值时按源数量倒序赋值,源数量不会超过 255)。
     */
    private int pri;
    /** 源内行过滤(可选)。原样拼进该源的 SELECT,只做反注入的基础校验。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String where;
    /**
     * 是否参与。
     *
     * <p>生成器与校验都尊重它(停用的源不建 MV、不参与回填、也不要求有字段映射),
     * 但【界面上只能启用】—— 服务层 normalize 统一置 true。想临时摘掉一个源,现在的做法是在界面上删掉它。
     */
    private boolean enabled;
}
