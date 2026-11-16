/**
 * 【文件职责】本文件定义ARB 查询编排模块的运行状态或日志模型 ArbWorkflowRun，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.arb;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 一次执行记录，对应 Go {@code model.ArbWorkflowRun}(MySQL 表 arb_workflow_runs)。
 *
 * <p>setup = 建表建视图并回填;rebuild = 只重建聚合层(修 sum 翻倍/幽灵行);
 * flatten = 只摊平服务表。三者共用同一套执行路径,action 只影响记录里的标签。
 */
@Data
public class ArbWorkflowRun {
    /** 当前断点进度；历史记录使用执行结束时保存的快照。 */
    private String progress;

    /** 建表 + 建物化视图 + 回填历史(首次执行或改配置后执行)。 */
    public static final String ACTION_SETUP = "setup";
    /** 重建聚合层(清空重算)。与 setup 是同一个操作,只是记录里叫法不同。 */
    public static final String ACTION_REBUILD = "rebuild";
    /** 只摊平服务表(聚合层已被物化视图实时更新,服务表要单独刷)。 */
    public static final String ACTION_FLATTEN = "flatten";

    /** 执行中。 */
    public static final String STATUS_RUNNING = "running";
    /** 执行成功。 */
    public static final String STATUS_SUCCESS = "success";
    /** 执行失败。 */
    public static final String STATUS_FAILED = "failed";

    /** 主键。 */
    private long id;
    /** 所属工作流 ID。 */
    private long workflowId;
    /** 动作:setup / rebuild / flatten。 */
    private String action;
    /** 状态:running / success / failed。 */
    private String status;
    /** 本次产出的行数(成功时是服务表行数,即收敛后的号码数)。 */
    private long rows;
    /** 开始时间。 */
    private String startedAt;
    /** 结束时间(进行中时为空)。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String finishedAt;
    /** 失败原因。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String error;
    /**
     * 本次实际执行的语句(多条用 ; 分隔),失败时是唯一的排查线索。
     *
     * <p>失败的【那一条】也在里面:引擎是先记录再执行,就是为了让失败现场留下来。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String sql;
}
