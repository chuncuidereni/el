/**
 * 【文件职责】本文件定义数据源管理模块的领域数据模型 SftpEntry，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** SFTP 目录的单层子项，与 Go sftpcli.Entry 的 JSON 字段一致。 */
@Data
public class SftpEntry {
    /** 文件名(不含目录)。以 . 开头者同时会把 hidden 置 true。 */
    private String name;
    /** 完整远程路径 = 父目录去尾斜杠后拼 '/' + name；前端点击目录时直接把它回传作为下一层的 path。 */
    private String path;
    /** 是否目录。Java 字段名 dir 与 JSON/前端的 isDir 不一致，故显式指定序列化名。 */
    @JsonProperty("isDir")
    private boolean dir;
    /** 文件字节数；目录的该值无意义(部分服务端返回 0 或块大小)，前端按 dir 忽略它。 */
    private long size;
    /** 修改时间，已由服务端把 epoch 秒按本机时区格式化为 yyyy-MM-dd HH:mm:ss。 */
    private String mtime;
    /** 是否隐藏项(名字以 . 开头)。浏览接口会把这类项一并返回，由前端决定是否显示。 */
    @JsonProperty("isHidden")
    private boolean hidden;
}
