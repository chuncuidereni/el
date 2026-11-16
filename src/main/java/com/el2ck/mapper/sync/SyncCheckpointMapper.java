package com.el2ck.mapper.sync;

/**
 * 【文件职责】定义同步断点记录的 MyBatis 读写操作。
 */

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 同步断点表(el_sync_checkpoints)的 MyBatis Mapper。
 *
 * <p>断点的读写本身由 {@link com.el2ck.sync.SyncCheckpointStore} 用原生 JDBC 完成
 * (它需要一条连接同时持有 MySQL 命名锁并持续更新断点);这里只补仓储层需要的生命周期操作,
 * 让删除任务和已完成全量转增量的配置变更与断点保持同一事务。
 */
@Mapper
public interface SyncCheckpointMapper {
    // SQL: src/main/resources/com/el2ck/mapper/sync/SyncCheckpointMapper.xml

    /** 删除某任务的断点(任务删除时级联清理)。 */
    int deleteByTask(@Param("taskId") long taskId);

    /** 与任务更新同事务，仅更新已核验完成断点的配置指纹，逐表水位保持原样。 */
    int rebindCompleted(@Param("taskId") long taskId,
                        @Param("previousFingerprint") String previousFingerprint,
                        @Param("previousToken") String previousToken,
                        @Param("nextFingerprint") String nextFingerprint);
}
