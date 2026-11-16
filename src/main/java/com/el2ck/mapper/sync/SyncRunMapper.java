package com.el2ck.mapper.sync;

/**
 * 【文件职责】定义同步运行记录、进度、取消和中断恢复相关的 MyBatis 操作。
 */

import com.el2ck.repository.metadata.MetadataRepository;

import com.el2ck.entity.sync.SyncRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 同步运行历史表(sync_runs)的 MyBatis Mapper。
 * 记录每次手动/计划同步:任务ID、状态(running/success/failed)、时间与读写行数、错误。
 */
@Mapper
public interface SyncRunMapper {
    /** 只读取当前所有者的任务最近一次运行；不扫描其他用户的历史摘要。 */
    List<SyncRun> listLatestOwned(@Param("ownerId") long ownerId, @Param("taskIds") List<Long> taskIds);
    // SQL: src/main/resources/com/el2ck/mapper/sync/SyncRunMapper.xml

    /** 创建运行记录(初始状态 running),自增主键回填到 r.id。 */
    // INSERT 仅写 task_id、status(running);started_at 由列默认 datetime('now') 填充,主键回填 r.id
    int insert(SyncRun r);

    /** 仅将 running 收尾一次，保留已结束记录的状态、时间和行数。 */
    // UPDATE 结束态:写 status/finished_at/rows_read/rows_written/error,null 错误归一为空串
    int finish(SyncRun r);

    /** 运行结果已知时重试收尾，保留原状态和行数，避免瞬时写库失败留下 running。 */
    int finishIfRunning(SyncRun r);
    int progress(SyncRun r);

    /** 某任务最近 limit 条运行历史(倒序);limit 非法的默认处理见门面 MetadataRepository。 */
    // SELECT 按 task_id 过滤,id 倒序取最近 limit 条运行历史
    List<SyncRun> listByTask(@Param("taskId") long taskId, @Param("limit") int limit);

    /**
     * 执行日志分页查询:按状态/是否续传/最近天数/关键字过滤,id 倒序。
     * 条件为 null 或空时不过滤;关键字匹配运行号、说明、错误、断点起始键。
     */
    List<SyncRun> queryPage(@Param("taskId") long taskId,
                            @Param("status") String status,
                            @Param("resumed") Integer resumed,
                            @Param("days") int days,
                            @Param("keyword") String keyword,
                            @Param("limit") int limit,
                            @Param("offset") long offset);

    /** 与 {@link #queryPage} 同条件的总数。 */
    long countPage(@Param("taskId") long taskId,
                   @Param("status") String status,
                   @Param("resumed") Integer resumed,
                   @Param("days") int days,
                   @Param("keyword") String keyword);

    /** 按任务和运行号查询，不受最近历史条数限制。 */
    SyncRun getByTaskAndId(@Param("taskId") long taskId, @Param("runId") long runId);

    /** 一次查出每个任务的最新运行，避免任务列表逐条查询运行历史。 */
    // 子查询按 task_id 取 MAX(id) 定位最新一条(id 自增,故最大 id 即最近一次),再 JOIN 回原表取整行;
    // 用 INNER JOIN:从未运行过的任务不会出现在结果里,调用方需自行按 task_id 缺失处理
    List<SyncRun> listLatest();

    /** 某任务是否有正在运行的记录(并发防重入)。 */
    // SELECT 计数:按 task_id 且 status='running' 统计
    long countRunning(@Param("taskId") long taskId);

    /** 删除前锁定任务内的日志，与运行结束操作串行检查状态。 */
    SyncRun lockRunLog(@Param("taskId") long taskId, @Param("runId") long runId);

    /** 仅标记成功/失败日志，不删除记录、不修改 status 或检查点。 */
    int softDeleteRunLog(@Param("taskId") long taskId, @Param("runId") long runId);

    /** 删除任务时一并清理其运行历史。 */
    // DELETE 按 task_id 清空该任务的运行历史
    int deleteByTask(@Param("taskId") long taskId);
}
