/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbField，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.el2ck.entity.query.QueryPipelineStep;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对接层的一行:某个源的一列 → 某个目标列,对应 Go {@code model.ArbField}(工作流的「③字段」一块)。
 */
@Data
public class ArbField {
    /** 所属源的 {@link ArbSource#getId()}(前端生成的 s0/s1…)。 */
    private String sourceId;
    /** 源列名。 */
    private String col;
    /**
     * 目标列名。默认 = {@link #col};多源列名不一致时(operator.name ↔ ecom.nick)靠它把两边挂到同一个目标列。
     */
    private String outName;
    /**
     * 源列类型(前端从表结构带过来)。生成器按白名单校验后用于推导聚合列的类型。
     *
     * <p>仲裁列不需要它(一律 String);sum/max/min 列必须知道类型。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String colType;
    /**
     * 这一列的业务说明,会作为 COMMENT 落到服务表上(业务查 DESCRIBE 时看得到)。
     *
     * <p>取值顺序:用户手填 &gt; 源列注释(前端在建映射时带过来)。同一个目标列有多条映射时,
     * 取第一条非空的 —— 说明是【目标列】的属性,不是源列的。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String comment;
    /** 该列的处理链(trim、hex(MD5(x))…)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<QueryPipelineStep> steps = new ArrayList<>();
}
