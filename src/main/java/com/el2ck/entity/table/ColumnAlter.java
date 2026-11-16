/**
 * 【文件职责】本文件定义表管理与数据编辑模块的领域结构模型 ColumnAlter，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/** 表结构变更单列操作。 */
@Data
public class ColumnAlter {
    /** rename / modify / add / drop */
    private String action;
    /** 变更前的列名(rename/modify 时需要)。 */
    private String oldName;
    /** 变更后的新列名(add/rename 时需要)。 */
    private String newName;
    /** 目标列类型(add/modify 时需要)。 */
    private String type;
    /** 是否允许为空。 */
    private Boolean nullable;
    /** 列的默认值(可选)。 */
    private String defaultValue;
    /** 列的注释(可选)。 */
    private String comment;
}
