/**
 * 【文件职责】本文件定义表管理与数据编辑模块的查询或操作结果模型 MaterializeResult，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.table;

import lombok.Data;

/**
 * 圈选落表结果。对应 Go service.MaterializeResult。
 * <p>{@code table} <b>只回表名</b>:它是给界面提示用的,带库名等于把物理库名露到前台
 * (见 web/src/utils/layer.ts 的约定)。
 */
@Data
public class MaterializeResult {
    /** 结果表名(只有表名)。 */
    private String table = "";
    /** 写入了多少行;dryRun 或计数失败时为 0。 */
    private long rows;
    /** 实际执行的建表语句。 */
    private String sql = "";
    /** 同名表是否已存在(dryRun 时也能拿到,界面可以提前提醒)。 */
    private boolean exists;
}
