/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbPlan，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次工作流的全部 SQL(预览接口的返回),对应 Go {@code sync.ArbPlan}。
 *
 * <p>⚠️ 预览与执行共用同一份生成器产物,所以界面看到的就是真正会跑的 ——
 * 「预览里出现的每一条 SQL,执行时就是按这个顺序跑的」。
 *
 * <p>生成五样东西:聚合层 DDL、每源 MV、每源回填、服务层 DDL/视图、以及每列的读法提示。
 */
@Data
public class ArbPlan {
    /** 聚合表全名({@code 库.表},用于界面显示)。 */
    private String aggTable;
    /** 聚合层建表语句。 */
    private String aggDdl;
    /** 每源一条物化视图 DDL。 */
    private List<ArbStmt> mvs = new ArrayList<>();
    /** 每源一条历史回填语句(建 MV 之后必须补历史,否则聚合表是空的)。 */
    private List<ArbStmt> backfills = new ArrayList<>();
    /** 每个目标列的读法提示。 */
    private List<ArbPlanColumn> columns = new ArrayList<>();
    /** 配得出、但结果可能不是用户想要的组合(不拦,但必须说出来)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> warnings = new ArrayList<>();

    /** 服务表全名({@code 库.表})。 */
    private String svcTable;
    /**
     * 服务层建表语句:含 sum 列时是物理表({@code ENGINE = MergeTree}),
     * 否则是视图({@code CREATE VIEW},零存储、自动同步、不需要摊平)。
     */
    private String svcDdl;
    /** 摊平查询:把聚合层的状态拆成普通值。服务表/视图都用它取数。 */
    private String svcSelect;
}
