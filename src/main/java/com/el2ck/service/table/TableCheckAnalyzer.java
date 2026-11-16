/**
 * 【文件职责】本文件定义表管理与数据编辑模块的分析器 TableCheckAnalyzer。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.table;

import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.table.DDLCheckColumn;
import com.el2ck.entity.table.DDLCheckFinding;
import com.el2ck.entity.table.TableCheckResult;
import com.el2ck.util.DdlGuard;
import com.el2ck.util.PartitionRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表体检的纯逻辑部分,对应 Go internal/service/table_check.go 的 AnalyzeTable。
 *
 * <p>目的:把已经写在 {@link DdlGuard} 与 {@link PartitionRule} 里的护栏,搬到一个「随时能问」的地方。
 * 今天这些规则只在动手时才说话 —— 建表/改列被拦、同步任务保存时被拦,或者更糟:
 * 写入被 ClickHouse 拒掉(code 252)之后才翻译成人话,而那时表已经建出来了,
 * MergeTree 的分区键还改不了,只能删表重建。
 *
 * <p>两条刻意的设计:
 * <ol>
 *   <li><b>只报告,不阻断</b>。面对的多半是已经存在的表,拿它当门没有意义 ——
 *       门仍然留在 Create / Alter / 同步保存那里。本类不做任何写操作。</li>
 *   <li><b>只报本项目口径下确定有问题的项</b>。通用 linter 会说「高基数列别做排序键」,
 *       但本项目 ORDER BY 号码列是刻意的,报它等于制造假问题。故只跑 {@link #RULES} 里列出的那几条。</li>
 * </ol>
 */
public final class TableCheckAnalyzer {

    /** 工具类私有构造器:禁止实例化。 */
    private TableCheckAnalyzer() {
    }

    /**
     * 明确列出「只查了」这几条。回给前端是为了让「0 条结论」不被误读成「这张表没问题」——
     * 覆盖面必须写在脸上。文案与 Go tableCheckRules 逐字一致。
     */
    public static final List<String> RULES = Collections.unmodifiableList(Arrays.asList(
            "列类型片段能不能通过平台校验(通不过 = 这一列在平台上改不了)",
            "列 DEFAULT 表达式能不能通过平台校验",
            "分区键:不能引用号码列、必须含日期/时间列"));

    /**
     * 对一张表做体检的纯逻辑部分(不碰数据库,便于单测)。
     *
     * @param database        库名
     * @param table           表名
     * @param engine          引擎(system.tables.engine)
     * @param partitionKeyExpr 分区键表达式
     * @param sortingKeyExpr  排序键表达式
     * @param primaryKeyExpr  主键表达式
     * @param desc            DESCRIBE TABLE 的结果
     * @return 体检报告
     */
    public static TableCheckResult analyze(String database, String table, String engine,
                                           String partitionKeyExpr, String sortingKeyExpr,
                                           String primaryKeyExpr, QueryResult desc) {
        TableCheckResult out = new TableCheckResult();
        out.setDatabase(def(database));
        out.setTable(def(table));
        out.setEngine(def(engine));
        out.setPartitionKeyExpr(def(partitionKeyExpr));
        out.setSortingKeyExpr(def(sortingKeyExpr));
        out.setPrimaryKeyExpr(def(primaryKeyExpr));
        out.setRules(new ArrayList<>(RULES));
        // columns / findings 已在实体里初始化为空列表 —— 必须是非 null 的 []:
        // Go 的 nil 切片序列化成 null,前端读 result.findings.length 会直接报错。

        // 按【列名】取 DESCRIBE 的字段:不同 ClickHouse 版本的列数/顺序并不一致,按位置取会静默取错。
        Map<String, Integer> idx = new LinkedHashMap<>();
        if (desc != null && desc.getColumns() != null) {
            List<String> names = desc.getColumns();
            for (int i = 0; i < names.size(); i++) {
                idx.put(names.get(i).toLowerCase(), i);
            }
        }
        // 列名 → 类型(供分区键规则判断日期列/号码列)。
        Map<String, String> types = new LinkedHashMap<>();
        if (desc != null && desc.getRows() != null) {
            for (List<Object> row : desc.getRows()) {
                DDLCheckColumn c = new DDLCheckColumn();
                c.setName(cellAt(row, idx.get("name")));
                c.setType(cellAt(row, idx.get("type")));
                c.setDefaultType(cellAt(row, idx.get("default_type")));
                c.setDefaultExpr(cellAt(row, idx.get("default_expression")));
                c.setComment(cellAt(row, idx.get("comment")));
                c.setCodec(cellAt(row, idx.get("codec_expression")));
                c.setTtl(cellAt(row, idx.get("ttl_expression")));
                if (c.getName().isEmpty()) {
                    continue;
                }
                types.put(c.getName(), c.getType());
                out.getColumns().add(c);
            }
        }

        // ① 列类型能否通过平台校验。
        for (DDLCheckColumn c : out.getColumns()) {
            String err = DdlGuard.validateColumnType(c.getType());
            if (err != null) {
                DDLCheckFinding f = new DDLCheckFinding();
                f.setLevel("error");
                f.setColumn(c.getName());
                f.setTitle("这一列在当前平台上无法编辑");
                f.setDetail("类型片段 " + goQuote(c.getType()) + " 通不过平台的列类型校验:" + err + "。"
                        + "平台会拒绝「顶层出现逗号」的片段(防的是一条语句里夹带第二个动作),"
                        + "所以改这一列时(哪怕只改注释)会被整体拒绝。");
                f.setFix("若这是平台校验器漏掉的正常类型,该修校验器;若类型里确实混进了别的语句,改正它。");
                out.getFindings().add(f);
            }
            // ② 只校验真正的 DEFAULT。
            // ⚠️ MATERIALIZED / ALIAS 的表达式是另一套语义,拿 DEFAULT 的白名单去套会误报 ——
            //    Go 侧已经因为「校验写太严 = 会让已存在的列不可编辑」回归过三次。
            String defType = c.getDefaultType() == null ? "" : c.getDefaultType().trim();
            String defExpr = c.getDefaultExpr() == null ? "" : c.getDefaultExpr().trim();
            if ("DEFAULT".equalsIgnoreCase(defType) && !defExpr.isEmpty()) {
                String derr = DdlGuard.validateDefaultExpr(defExpr);
                if (derr != null) {
                    DDLCheckFinding f = new DDLCheckFinding();
                    f.setLevel("error");
                    f.setColumn(c.getName());
                    f.setTitle("这一列的默认值在当前平台上无法编辑");
                    f.setDetail("默认值 " + goQuote(defExpr) + " 通不过平台校验:" + derr
                            + " —— 改这一列(哪怕只改注释)会被整体拒绝。");
                    out.getFindings().add(f);
                }
            }
        }

        // ③ 分区键:空串与 tuple() 都表示不分区,合法。
        String part = out.getPartitionKeyExpr().trim();
        if (!part.isEmpty() && !"tuple()".equals(part)) {
            // 体检面对的是已存在的、可能非本平台建的表,其 PARTITION BY 是 ClickHouse 原文,
            // 合法形态远多于本平台写路径支持的写法 —— 只做语义检查(不含号码列、须含日期列),
            // 不套写路径的字符/形状严格度,否则会长期误报(与 Go AnalyzeTable 用 ValidatePartitionSemantics 一致)。
            String perr = PartitionRule.validatePartitionSemantics(part, phoneColumnOf(types), types);
            if (perr != null) {
                DDLCheckFinding f = new DDLCheckFinding();
                f.setLevel("warn");
                f.setTitle("分区键不符合平台规则");
                f.setDetail(perr + "\n若这张表不由本平台写入,这一条可以忽略。");
                f.setFix("按日期列分区,例如 PARTITION BY toYYYYMM(某日期列)。⚠️ 分区键建完就改不了,只能删表重建。");
                out.getFindings().add(f);
            }
        }

        return out;
    }

    /**
     * 取一行里的第 idx 格(idx 为 null 或越界返回空串)。
     * DESCRIBE / system.tables 的列数随版本变化,直接下标会取错甚至越界。
     */
    static String cellAt(List<Object> row, Integer idx) {
        if (row == null || idx == null || idx < 0 || idx >= row.size() || row.get(idx) == null) {
            return "";
        }
        return String.valueOf(row.get(idx)).trim();
    }

    /**
     * 从列类型表里挑出「号码列」。
     * 与圈选落表(Materialize)默认排序键用的是同一份候选名单 —— 别处再加名单就会漂移。
     * 对应 Go phoneColumnOf。
     */
    static String phoneColumnOf(Map<String, String> types) {
        for (String cand : new String[]{"mdn", "phone", "mobile"}) {
            if (types.containsKey(cand)) {
                return cand;
            }
        }
        return "";
    }

    /** null 兜底空串。 */
    private static String def(String s) {
        return s == null ? "" : s;
    }

    /**
     * 把字符串渲染成 Go {@code %q} 的写法。体检结论里的类型/默认值原文要与 Go 输出一致:
     * {@code %q} 会把双引号写成 {@code \"}、反斜杠写成 {@code \\}、换行写成 {@code \n},
     * 直接拼一对引号在这几种字符上会不一样。
     */
    static String goQuote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
