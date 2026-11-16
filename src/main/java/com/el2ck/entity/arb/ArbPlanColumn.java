/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbPlanColumn，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 目标列在聚合层里的形态,对应 Go {@code sync.ArbPlanColumn}(界面用它说明「怎么读」)。
 */
@Data
public class ArbPlanColumn {
    /** 目标列名。 */
    private String name;
    /** 取值方式:arbitrate / sum / max / min。 */
    private String mode;
    /** 该列在聚合层里的列类型(SimpleAggregateFunction(...))。 */
    private String stateType;
    /**
     * 读法表达式。聚合层不能直接查(存的是中间状态),必须按这个写 ——
     * 例如仲裁列是 {@code max(`name`).4},聚合列是 {@code sum(`order_amount`)}。
     */
    private String read;
}
