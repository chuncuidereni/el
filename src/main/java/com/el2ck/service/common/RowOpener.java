/**
 * 【文件职责】本文件定义HTTP 公共基础设施模块的连接/数据源适配组件 RowOpener。封装特定数据源或结果集的连接、读取和异常转换语义。
 */

package com.el2ck.service.common;

import com.el2ck.ckpool.CkClient;

import java.io.IOException;

/**
 * 按 SQL 开一条导出行流。对应 Go service.exportStreamOpener。
 *
 * <p>生产实现包一层 {@link CkClient#queryStream};单测里换成内存假流 ——
 * 表头开关、分隔符、转义、行数统计这些是纯逻辑,不该为了测它们去连一个真的 ClickHouse。
 */
public interface RowOpener {
    /** 打开行流,把列名与逐行数据交给 handler。 */
    void open(String sql, CkClient.RowHandler handler) throws IOException;

    /** 生产实现:直接用连接上的流式查询。 */
    static RowOpener of(CkClient client) {
        // 方法引用即可,无需额外包装类。
        return client::queryStream;
    }
}
