package com.el2ck.mapper.workflow;

/**
 * 【文件职责】定义 SQL 工作流运行记录的 MyBatis 持久化操作。
 */

import com.el2ck.entity.workflow.WfRun;
import com.el2ck.entity.workflow.WfRunLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流运行记录(wf_runs)与节点日志(wf_run_logs)的 MyBatis Mapper。
 */
@Mapper
public interface WfRunMapper {
    // SQL: src/main/resources/com/el2ck/mapper/workflow/WfRunMapper.xml

    /** 创建运行记录(初始状态 running),自增主键回填到 r.id。 */
    // INSERT 仅写 workflow_id、status(running);started_at 由列默认 datetime('now') 填充,主键回填 r.id
    int insert(WfRun r);

    /** 结束运行:写状态/结束时间/错误(成功时为空串,error 列为 NOT NULL)。 */
    // UPDATE 结束态:写 status / finished_at / error;null 错误归一为空串
    int finish(WfRun r);

    /** 某工作流最近 limit 条运行记录(倒序)。 */
    // SELECT wf_runs 按 workflow_id 过滤,id 倒序取最近 limit 条
    List<WfRun> listByWorkflow(@Param("workflowId") long workflowId, @Param("limit") int limit);

    /** 工作流列表使用的最近一次运行摘要。 */
    // SELECT 该工作流最近一条运行记录(id DESC LIMIT 1)
    WfRun latestByWorkflow(@Param("workflowId") long workflowId);

    /** 某工作流是否有正在运行的记录(并发防重入)。 */
    // SELECT 计数:按 workflow_id 且 status='running' 统计
    long countRunning(@Param("workflowId") long workflowId);

    /** 创建节点日志(初始状态 running),自增主键回填到 l.id。 */
    // INSERT 节点日志:写 run_id/node_id/node_type,status 固定 running
    int insertLog(WfRunLog l);

    /** 结束节点日志:写状态/读写行数/耗时/错误。 */
    // UPDATE 结束态:写 status/rows_in/rows_out/duration_ms/error,null 各数值归一为 0
    int finishLog(WfRunLog l);

    /** 某次运行的节点日志(按执行顺序)。 */
    // SELECT wf_run_logs 按 run_id 过滤,id 升序取全部节点日志
    List<WfRunLog> listLogs(@Param("runId") long runId);

    /** 删除某工作流下全部运行的节点日志(删除工作流时先清子表)。 */
    // DELETE 通过子查询圈定该工作流各运行(run_id)对应的日志行
    int deleteLogsByWorkflow(@Param("workflowId") long workflowId);

    /** 删除某工作流的全部运行记录(删除工作流时级联清理)。 */
    // DELETE 按 workflow_id 清空该工作流的运行记录
    int deleteByWorkflow(@Param("workflowId") long workflowId);
}
