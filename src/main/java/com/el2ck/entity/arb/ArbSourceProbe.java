/**
 * 【文件职责】本文件定义ARB 查询编排模块的领域结构模型 ArbSourceProbe，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 单个源的运行状态,对应 Go {@code sync.ArbSourceProbe}(拓扑图上每张源卡片要显示的东西)。
 */
@Data
public class ArbSourceProbe {
    /** 源标识(s0/s1…)。 */
    private String sourceId;
    /** 源表全名(库.表)。 */
    private String table;
    /** 源表总行数(走元数据,极快)。 */
    private long rows;
    /** 源表去重号码数。要真算(uniqExact),大表会慢 —— 但这是用户主动点的「看现状」。 */
    private long mdns;
    /** 该源的物化视图名。 */
    private String mvName;
    /** 该源的物化视图在不在(不在 = 增量断流)。 */
    private boolean mvExists;
    /**
     * 该源探测失败的原因(表被删 / 号码列改名……)。
     *
     * <p>单个源失败不能让整张图拿不到 —— 那正是最需要看图的时候。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String error;
}
