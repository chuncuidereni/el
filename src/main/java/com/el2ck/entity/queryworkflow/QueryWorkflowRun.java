/**
 * 【文件职责】本文件定义查询工作流模块的运行状态或日志模型 QueryWorkflowRun，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.queryworkflow;

import lombok.Data;

/**
 * 查询工作流的一次运行记录，对应 Go {@code model.QueryWorkflowRun}(MySQL 表 query_workflow_runs)。
 *
 * <p>{@code status='running'} 的行受部分唯一索引 {@code idx_qw_runs_one_running} 约束:
 * 同一个查询同一时刻至多一条 running —— 并发去重靠数据库约束而不是「先查再插」,后者在
 * 并发点两次时是 check-then-act。因此【收尾必须无条件执行】,漏掉会让该查询再也跑不起来
 * (插入会撞唯一约束),只能等进程重启时的遗留重置。
 */
@Data
public class QueryWorkflowRun {
    /** 主键。 */
    private long id;
    /** 所属查询工作流 ID。 */
    private long workflowId;
    /** running / success / failed。 */
    private String status;
    /** 开始时间。 */
    private String startedAt;
    /** 结束时间;未结束时为空串。 */
    private String finishedAt;
    /** 本次读到的行数(第一条结果集的行数)。 */
    private long rowsRead;
    /** 本次实际执行的 SQL(带别名与限制的那条),供复现与排查。 */
    private String generatedSql;
    /** 失败原因;成功时为空串。 */
    private String error;
}
