package com.el2ck.util;

/**
 * 【文件职责】解析和规范化 ClickHouse 列定义，辅助类型兼容、映射和 DDL 生成。
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse 列读取改写:把<b>驱动读不了的结果列类型</b>在 SELECT 表达式层面降级成它能读的类型。
 *
 * <p>为什么需要它 —— 内置的 housepower 原生驱动
 * ({@code com.github.housepower:clickhouse-native-jdbc:2.6.4})的 {@code DataTypeFactory} 只认固定那几种类型,
 * 遇到 {@code SimpleAggregateFunction(...)} 会直接抛 {@code Unknown data type:
 * SimpleAggregateFunction}。触发点在 {@code CkClient.query} 逐列取
 * {@code ResultSetMetaData.getColumnTypeName(i)} 时 —— 也就是说<b>只要结果集里有这么一列就炸</b>,
 * 跟读不读它的值无关。而多源仲裁工作流的聚合表(AggregatingMergeTree)业务列<b>全是</b>这个类型,
 * 于是「点开表看数据」这类最普通的操作都会失败。
 *
 * <p>改写方式的选择(真机实测,表 dwd_data.dwd_mdn_test_agg):
 * <ul>
 *   <li>{@code SELECT 该列} → 抛 Unknown data type;</li>
 *   <li>{@code toString(该列) AS 该列} → <b>成功</b>,拿到聚合状态文本
 *       {@code (1,1,'1970-01-01 00:00:00','255')};</li>
 *   <li>{@code CAST(该列 AS 内层类型)} → 也能成功(驱动读成 ClickHouseStruct),但网格/CSV 会拿到
 *       嵌套结构,渲染与导出都要额外适配 —— 故不采用;</li>
 *   <li>{@code max(该列)} → 结果类型<b>仍是</b> SimpleAggregateFunction,所以不能指望「套个聚合函数」
 *       顺手把它化解掉。</li>
 * </ul>
 * 因此统一用 {@code toString(...)}:信息量与 ClickHouse 自己的文本输出一致,且 String 是所有下游
 * (网格 / CSV / JSON)都天然处理的类型。
 *
 * <p>⚠️ 这属于「在应用边界绕开驱动缺陷」,与 {@code CkClient} 里绕开
 * {@code Unknown data type: Bool} 那处(改用 SQL 字面量写入)是同一类做法:换驱动版本时要重新评估
 * 还能不能拆掉。
 */
public final class CkColumns {

    /** 工具类私有构造器:禁止实例化。 */
    private CkColumns() {
    }

    /** 驱动不认识的聚合状态类型名。 */
    private static final String SAF = "SimpleAggregateFunction";

    /**
     * 剥掉外层 {@code Nullable(...)} / {@code LowCardinality(...)} 外壳(可嵌套)后的基础类型串。
     * 仅用于类型判定;调用方拼进 SQL 的始终是原始列引用,不是它。
     */
    static String stripShells(String type) {
        String t = type == null ? "" : type.trim();
        // 循环剥壳:Nullable(LowCardinality(String)) 这种可以叠两层
        boolean changed = true;
        while (changed && t.endsWith(")")) {
            changed = false;
            for (String shell : new String[]{"Nullable(", "LowCardinality("}) {
                if (t.regionMatches(true, 0, shell, 0, shell.length())) {
                    t = t.substring(shell.length(), t.length() - 1).trim();
                    changed = true;
                }
            }
        }
        return t;
    }

    /** 该列类型是否为驱动读不了的 {@code SimpleAggregateFunction(...)}(剥壳后判定,大小写不敏感)。 */
    public static boolean isSimpleAggregateFunction(String type) {
        String t = stripShells(type);
        return t.regionMatches(true, 0, SAF + "(", 0, SAF.length() + 1);
    }

    /**
     * SELECT 投影里的列表达式(带原位别名,列名保持不变)。
     *
     * <p>两种按类型改写:聚合状态列 → {@code toString(列) AS 列};Bool → {@code toUInt8(列) AS 列}
     * (Bool 那支是为了让网格/导出的数值口径一致,原在 TableService/DbSource 各写了一份)。
     * 其余类型原样返回调用方给来的引用串。
     *
     * @param quotedCol 已引用好的列名(反引号形式),由调用方按其上下文生成
     */
    public static String selectExpr(String quotedCol, String type) {
        if (isSimpleAggregateFunction(type)) {
            return "toString(" + quotedCol + ") AS " + quotedCol;
        }
        String base = stripShells(type);
        if ("Bool".equalsIgnoreCase(base) || "Boolean".equalsIgnoreCase(base)) {
            return "toUInt8(" + quotedCol + ") AS " + quotedCol;
        }
        return quotedCol;
    }

    /**
     * 只读引用(不带别名),供 WHERE / ORDER BY 等不能用别名的地方。
     *
     * <p>聚合状态列在语句里是<b>裸比不了的</b>:{@code WHERE 该列 = '255'} 会在 ClickHouse 侧报
     * {@code Cannot parse input: expected '(' … converting '255' to SimpleAggregateFunction(...)}
     * (真机实测)。这里同样按 {@code toString(列)} 引用,好处是「能过滤到的」与「网格里看到的」
     * 是同一个文本。Bool 不在此改写:它在 WHERE 里比 {@code true}/{@code false} 本来就正常。
     */
    public static String refExpr(String quotedCol, String type) {
        return isSimpleAggregateFunction(type)
                ? "toString(" + quotedCol + ")" : quotedCol;
    }

    /**
     * 按给定列顺序生成整列投影(逗号拼接),列名走 {@link Sql#quoteCol}(来自表结构,已过白名单)。
     *
     * <p>类型按列名查;查不到(表结构变了、调用方没给)就按普通列处理 —— 与列不存在时的处理
     * 一致:让它去执行时报「未知字段」,而不是在这里拼出一个坏 SQL。
     *
     * @param names       列名,顺序即投影顺序
     * @param typeByName  列名 → 类型;可为 null
     */
    public static String projection(List<String> names, Map<String, String> typeByName) {
        List<String> parts = new ArrayList<>(names.size());
        for (String name : names) {
            String type = typeByName == null ? null : typeByName.get(name);
            parts.add(selectExpr(Sql.quoteCol(name), type));
        }
        return String.join(", ", parts);
    }
}
