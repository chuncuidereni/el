/**
 * 【文件职责】本文件定义表管理与数据编辑模块的构建器 CrowdTableBuilder。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.table;

import com.el2ck.entity.table.FilterNode;
import com.el2ck.util.CkColumns;
import com.el2ck.util.Sql;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 圈选落表(「数据包」)的注释与建表语句生成,对应 Go internal/service/table_service.go 里的
 * CrowdTableCommentPrefix / HumanFilter / buildCrowdComment / BuildMaterializeSQL 四个纯逻辑。
 *
 * <p>数据包<b>不另建元数据表</b> —— 直接写在 ClickHouse 的【表注释】里:
 * <ul>
 *   <li>零存储、零迁移:表本身就是记录,建表语句里一行 COMMENT 搞定;</li>
 *   <li>表注释本来就会随表清单接口({@code TableInfo.remark})回到界面,
 *       DWD 层「数据包」页按这个前缀筛出来即可;</li>
 *   <li>库被整个删掉时也不会留下孤儿元数据。</li>
 * </ul>
 *
 * <p>⚠️ 前端 {@code web/src/utils/arbFields.ts} 里有同名前缀与同一套解析规则
 * ({@code 数据包｜来源 <源表>｜<时间>｜条件 <条件>}),改这里要一起改。
 * 前端还兼容更早的「人群包」前缀,所以改名不会让老数据包从列表里消失。
 */
public final class CrowdTableBuilder {

    /** 工具类私有构造器:禁止实例化。 */
    private CrowdTableBuilder() {
    }

    /** 数据包的表注释前缀。必须与前端 {@code ARB_CROWD_MARK} 一致。 */
    public static final String CROWD_TABLE_COMMENT_PREFIX = "数据包";

    /** 注释里「来源」段的前缀(前端按 ｜ 切段解析)。 */
    public static final String CROWD_COMMENT_SOURCE_PREFIX = "来源 ";

    /** 表注释的时间格式,与前端 {@code ^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$} 的判据一致。 */
    private static final DateTimeFormatter COMMENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 注释里条件段的截断长度(按码点计,与 Go 的 []rune 一致)。 */
    private static final int COND_MAX_CODE_POINTS = 300;

    /**
     * 过滤操作符的中文说法。
     *
     * <p>与前端圈选界面的标签保持一致 —— 同一套条件在圈选界面和数据包注释里应该读起来是同一句话
     * (别一边「包含于」一边「in」)。表里没有的操作符(如 starts/ends)会退回原样输出。
     */
    private static final Map<String, String> FILTER_OP_LABELS;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("eq", "等于");
        m.put("ne", "不等于");
        m.put("in", "包含于");
        m.put("notin", "不包含于");
        m.put("between", "区间");
        m.put("gt", "大于");
        m.put("gte", "大于等于");
        m.put("lt", "小于");
        m.put("lte", "小于等于");
        m.put("like", "包含文本");
        m.put("isnull", "为空");
        m.put("isnotnull", "不为空");
        m.put("distinct", "字段去重");
        FILTER_OP_LABELS = Collections.unmodifiableMap(m);
    }

    /**
     * 把圈选条件说成人话 —— 写进数据包注释、显示在界面上。
     *
     * <pre>
     *   `sex` IN ('男') AND toFloat64OrZero(`age`) &gt;= 30
     *     →  性别 包含于 男;年龄 大于等于 30
     * </pre>
     *
     * <p>为什么要多这一层:数据包的「圈选条件」列是给业务同学看的,SQL 读不懂;
     * 列名用【列注释】(工作流里配的业务名)代替,读起来就是一句中文。
     * 嵌套的 or 用括号 + 「或」表达,顶层多条用「;」分隔。
     *
     * @param node     过滤条件树
     * @param comments 列名 → 列注释(取不到就用列名兜底)
     */
    public static String humanFilter(FilterNode node, Map<String, String> comments) {
        if (node == null) {
            return "";
        }
        List<FilterNode> children = node.getChildren();
        if (children != null && !children.isEmpty()) {
            String sep = "or".equalsIgnoreCase(node.getLogic()) ? " 或 " : ";";
            List<String> parts = new ArrayList<>(children.size());
            for (FilterNode c : children) {
                String p = humanCond(c, comments);
                if (!p.isEmpty()) {
                    parts.add(p);
                }
            }
            return String.join(sep, parts);
        }
        return humanCond(node, comments);
    }

    /** 单个条件(带子条件时套括号)。 */
    private static String humanCond(FilterNode n, Map<String, String> comments) {
        if (n == null) {
            return "";
        }
        List<FilterNode> children = n.getChildren();
        if (children != null && !children.isEmpty()) {
            String inner = humanFilter(n, comments);
            if (inner.isEmpty()) {
                return "";
            }
            // 嵌套组里的「;」(且)在括号内读着不顺,换成「且」
            return "(" + inner.replace(";", " 且 ") + ")";
        }
        String column = n.getColumn() == null ? "" : n.getColumn();
        if (column.isEmpty()) {
            return "";
        }
        // 没配列注释就退回字段名,总比不显示强
        String comment = comments == null ? null : comments.get(column);
        String name = comment == null ? "" : comment.trim();
        if (name.isEmpty()) {
            name = column;
        }
        String op = n.getOp() == null ? "" : n.getOp();
        String label = FILTER_OP_LABELS.get(op);
        if (label == null) {
            label = op;
        }
        if ("isnull".equals(op) || "isnotnull".equals(op) || "distinct".equals(op)) {
            return name + " " + label;
        }
        String val = valueText(n.getValue());
        if (n.getValue() == null || val.isEmpty()) {
            return name + " " + label;
        }
        if ("in".equals(op) || "notin".equals(op)) {
            // 多值用顿号连:逗号在中文里读起来断不开(前端就是个逗号分隔的输入框)
            return name + " " + label + " " + String.join("、", val.split(",", -1));
        }
        if ("between".equals(op)) {
            int i = val.indexOf(',');
            if (i >= 0) {
                return name + " " + val.substring(0, i).trim() + " ~ " + val.substring(i + 1).trim();
            }
        }
        return name + " " + label + " " + val;
    }

    /**
     * 条件值的文本形式。整数值去掉 {@code .0} —— JSON 里的数字到 Java 可能是 Double,
     * 直接 String.valueOf(30.0) 会写成 "30.0",而注释是给人看的。
     */
    private static String valueText(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(v).trim();
    }

    /**
     * 拼数据包的表注释:{@code 数据包｜来源 <源表>｜<时间>｜条件 <条件>}
     *
     * <p>为什么把条件也写进去:过一周回来只看到 crowd_20260917_1625 这种名字,
     * 根本想不起来这批人是怎么筛的 —— 注释是唯一自然能记住这件事的地方。
     *
     * @param src   来源【表名】(不写库名:前台不显示物理库名)
     * @param now   建包时间
     * @param where 已说成人话的条件(空串表示没有条件)
     */
    public static String buildCrowdComment(String src, LocalDateTime now, String where) {
        String cond = (where == null || where.isEmpty()) ? "无" : where;
        // 按【码点】截断:表注释是给人看的,不能塞进整条 WHERE;按 char 截会劈开代理对
        int points = cond.codePointCount(0, cond.length());
        if (points > COND_MAX_CODE_POINTS) {
            cond = cond.substring(0, cond.offsetByCodePoints(0, COND_MAX_CODE_POINTS)) + "…";
        }
        return CROWD_TABLE_COMMENT_PREFIX + "｜" + CROWD_COMMENT_SOURCE_PREFIX + src + "｜"
                + now.format(COMMENT_TIME_FORMAT) + "｜条件 " + cond;
    }

    /**
     * 生成「数据包」建表语句(纯函数,便于单测与界面预览)。
     *
     * <pre>
     *   CREATE TABLE db.crowd_x ENGINE = MergeTree ORDER BY mdn
     *   AS SELECT mdn, city FROM db.src WHERE `sex` = '男'
     * </pre>
     *
     * <p>为什么用 {@code CREATE TABLE ... AS SELECT} 而不是先建空表再插:一条语句,不用管中间态。
     * 排序键默认取号码列(圈选的产物通常按号码用);没有号码列就 {@code tuple()}(不设键)。
     *
     * @param db          目标库名(已过 quoteIdent 校验,此处不再加引号)
     * @param table       目标表名(同上)
     * @param srcQualified 源表的限定名(已按库.表拼好、带反引号)
     * @param cols        要落的列;空 = 全部(按 srcTypes 的顺序)
     * @param srcTypes    源表的 列名 → 类型(有序);用于按类型改写投影,别名或空表结构可为 null
     * @param orderBy     排序键列;空 = 不设键
     * @param where       过滤条件(已渲染成 SQL);空 = 全表
     * @param comment     表注释;空 = 不写
     */
    public static String buildMaterializeSql(String db, String table, String srcQualified,
                                             List<String> cols, Map<String, String> srcTypes, String orderBy,
                                             String where, String comment) {
        // 未指定列 = 全部。必须按表结构顺序取(不能是哈希序,否则每次落表的列序都可能不同)。
        List<String> use = cols == null || cols.isEmpty()
                ? (srcTypes == null ? new ArrayList<String>() : new ArrayList<String>(srcTypes.keySet()))
                : cols;
        // 列投影按类型改写(见 CkColumns):源表若是多源仲裁工作流的聚合表(业务列是
        // SimpleAggregateFunction),原样选出来会让新表【继承】这个类型 —— 于是这张刚建好的
        // 数据包从此也读不了,等于修一个坑又挖一个。
        String sel = use.isEmpty() ? "*" : CkColumns.projection(use, srcTypes);
        String key = "tuple()";
        if (orderBy != null && !orderBy.isEmpty()) {
            key = Sql.quoteCol(orderBy);
        }
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(db).append('.').append(table)
                .append(" ENGINE = MergeTree ORDER BY ").append(key);
        // 显式别名避免无 WHERE 时 COMMENT 被旧版本解析为源表的隐式别名。
        sql.append("\nAS SELECT ").append(sel).append(" FROM ").append(srcQualified).append(" AS crowd_source");
        if (where != null && !where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        // CREATE ... AS SELECT 的表级注释放在查询末尾，兼容 ClickHouse 25.3。
        if (comment != null && !comment.isEmpty()) {
            sql.append(" COMMENT ").append(Sql.quoteString(comment));
        }
        return sql.toString();
    }
}
