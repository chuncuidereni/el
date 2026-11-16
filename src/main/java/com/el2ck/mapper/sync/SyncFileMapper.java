package com.el2ck.mapper.sync;

/**
 * 【文件职责】定义同步文件元数据的 MyBatis 持久化操作。
 */

import com.el2ck.entity.sync.SyncFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SFTP 目录同步的文件级去重表(el_sync_files)Mapper。
 *
 * <p>记录每个任务已同步的远端文件(size/mtime),用于「只处理新出现 / 已变化 / 强制重建」的判定。
 * 唯一约束 (task_id, remote_path);表结构见手工 SQL 归档 {@code docs/mysql/archive/V1__metadata.sql},
 * 表与字段的中文注释在 {@code V2__comments.sql}、{@code V3__comments_utf8.sql}。
 * 对应 Go store 的 ListSyncedFiles / UpsertSyncedFile / ClearSyncedFiles。
 */
@Mapper
public interface SyncFileMapper {
    // SQL: src/main/resources/com/el2ck/mapper/sync/SyncFileMapper.xml

    /** 某任务已同步的全部文件记录。 */
    List<SyncFile> listByTask(@Param("taskId") long taskId);

    /**
     * 记录/更新一个已同步文件:按 (task_id, remote_path) upsert,冲突时刷新 size/mtime/synced_at。
     * 对应 Go UpsertSyncedFile。
     */
    int upsert(@Param("taskId") long taskId, @Param("remotePath") String remotePath,
               @Param("size") long size, @Param("mtime") String mtime);

    /** 清空某任务的全部去重记录(强制全量重建时用)。对应 Go ClearSyncedFiles。 */
    int clearByTask(@Param("taskId") long taskId);
}
