/**
 * 【文件职责】一次查询构建结果，将可执行 SQL 与程序算子后处理计划绑定为不可分离的数据结构。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.workflow;

import com.el2ck.entity.query.ProgColumn;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 SQL 生成的产物:SQL 文本 + 程序算子后处理计划(对应 Go {@code BuildQueryPlan} 的两个返回值)。
 *
 * <p>为什么两者必须【一起】产出、一起传递:程序算子(SM3/3DES 等)不生成任何 SQL,
 * 计划漏传不会报错,只会让该列以明文出现在结果与导出里。绑在同一个对象上,
 * 结构上就不存在「拿了 SQL 忘了拿计划」的路径。
 */
public class QueryPlan {
    /** 生成的可执行 SELECT 语句。 */
    private final String sql;
    /** 程序算子计划;按结果列下标登记,无程序算子时为空列表。 */
    private final List<ProgColumn> progCols;

    /** 构造器:SQL 与计划成对给出。 */
    public QueryPlan(String sql, List<ProgColumn> progCols) {
        this.sql = sql;
        // 判空兜底:调用方(服务层)会按「可空」使用,给出空列表省掉一路判空
        this.progCols = progCols == null ? new ArrayList<>() : progCols;
    }

    /** 生成的 SQL。 */
    public String getSql() {
        return sql;
    }

    /** 程序算子后处理计划(可能为空列表,不会为 null)。 */
    public List<ProgColumn> getProgCols() {
        return progCols;
    }
}
