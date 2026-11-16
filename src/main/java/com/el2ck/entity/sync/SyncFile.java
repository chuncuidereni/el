/**
 * 【文件职责】本文件定义数据同步模块的领域数据模型 SyncFile，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

/**
 * sync_files 表一行:SFTP 目录同步的文件级去重记录。
 *
 * <p>按 (task_id, remote_path) 唯一;记录已同步文件的 size / mtime,
 * 下次同步时据此判断某文件是新出现、未变化(跳过)还是已变化。对应 Go store 的同名记录。
 */
public class SyncFile {
    // 自增主键
    private long id;
    // 所属同步任务
    private long taskId;
    // 远端文件完整路径(去重键)
    private String remotePath;
    // 上次同步时的文件字节数
    private long size;
    // 上次同步时的文件修改时间(SFTP mtime 文本;仅用于等值比对,不解析)
    private String mtime = "";
    /**
     * 读取 Id 字段。
     */
    public long getId() { return id; }
    /**
     * 设置 Id 字段。
     *
     * @param id 资源或记录标识
     */
    public void setId(long id) { this.id = id; }
    /**
     * 读取 TaskId 字段。
     */
    public long getTaskId() { return taskId; }
    /**
     * 设置 TaskId 字段。
     *
     * @param taskId 任务 ID
     */
    public void setTaskId(long taskId) { this.taskId = taskId; }
    /**
     * 读取 RemotePath 字段。
     */
    public String getRemotePath() { return remotePath; }
    /**
     * 设置同步文件在远端存储中的相对路径。
     *
     * @param remotePath 远端文件路径
     */
    public void setRemotePath(String remotePath) { this.remotePath = remotePath; }
    /**
     * 读取 Size 字段。
     */
    public long getSize() { return size; }
    /**
     * 设置 Size 字段。
     *
     * @param size 每页数量
     */
    public void setSize(long size) { this.size = size; }
    /**
     * 读取 Mtime 字段。
     */
    public String getMtime() { return mtime; }
    /**
     * 设置远端文件最后修改时间。
     *
     * @param mtime 远端文件修改时间文本
     */
    public void setMtime(String mtime) { this.mtime = mtime; }
}
