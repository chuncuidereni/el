package com.el2ck.arb;

/**
 * 【文件职责】承载 ARB 执行期间的计数统计，便于引擎和上层服务统一汇总处理结果。
 */

import lombok.Data;

/**
 * 一次仲裁执行的统计,对应 Go {@code sync.ArbStats}。
 *
 * <p>不对外序列化:HTTP 返回的是 {@link com.el2ck.entity.arb.ArbWorkflowRun}(从这里面取行数与 SQL)。
 */
@Data
public class ArbStats {
    /** 产出多少行(= 服务表行数,即收敛后的号码数)。 */
    private long rows;
    /** 耗时(毫秒)。 */
    private long elapsedMs;
    /**
     * 本次实际执行过的语句(失败时也返回已执行的部分,这是唯一的排查线索)。
     *
     * <p>末尾可能追加 {@code -- 备注} 行(比如「N 个源的物化视图未能恢复」)——
     * 备注必须【粘住】:以前直接用 stats.SQL 重写,后面任何一条 run 都会把它整段覆盖掉,等于没留痕。
     */
    private String sql = "";
    /** 断点阶段摘要单独存储，不再混入可执行 SQL 和审计文本。 */
    private String progress = "";
}
