/**
 * 【文件职责】本文件定义数据同步模块的查询或操作结果模型 SyncRunPage，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.sync;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 执行日志分页结果(前端执行日志页:表格 + 总数 + 页码)。 */
@Data
public class SyncRunPage {
    /** 当前页运行记录,按运行号倒序。 */
    private List<SyncRun> rows = new ArrayList<>();
    /** 满足过滤条件的总条数。 */
    private long total;
    /** 当前页码(从 1 开始)。 */
    private int page;
    /** 每页条数。 */
    private int size;
}
