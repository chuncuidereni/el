package com.el2ck.arb;

/**
 * 【文件职责】把表映射、字段规则和 ARB 选项转换为可审计的 SQL/DDL 计划，并在执行前完成标识符、类型、区间和权限边界校验。
 */

import com.el2ck.entity.arb.ArbColumn;
import com.el2ck.entity.arb.ArbField;
import com.el2ck.entity.arb.ArbPlan;
import com.el2ck.entity.arb.ArbPlanColumn;
import com.el2ck.entity.arb.ArbSource;
import com.el2ck.entity.arb.ArbStmt;
import com.el2ck.entity.arb.ArbWorkflow;
import com.el2ck.exception.ApiException;
import com.el2ck.util.GoFmt;
import com.el2ck.util.Sql;
import com.el2ck.workflow.QueryPlanBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 多源仲裁工作流的 SQL 生成(全是纯函数:不连库、可单测),
 * 对应 Go {@code internal/sync/gen_arb.go}。
 *
 * <p>界面上的「预览 SQL」和执行时真正跑的是同一份产物,所以预览所见即所得。
 *
 * <p>生成五样东西(见 {@link #buildPlan}):
 * <pre>
 * 聚合层 DDL      CREATE TABLE &lt;目标&gt;_agg (mdn + 各目标列的状态类型) ENGINE=AggregatingMergeTree
 * 每源 MV DDL     CREATE MATERIALIZED VIEW mv_&lt;目标&gt;_&lt;源ID&gt; TO &lt;目标&gt;_agg AS SELECT ...
 * 每源回填 SQL    INSERT INTO &lt;目标&gt;_agg SELECT ...   (建 MV 之后必须补历史)
 * 服务层 DDL      有 sum 列:CREATE TABLE &lt;目标&gt;_svc ENGINE=MergeTree;无 sum 列:CREATE VIEW
 * 读法提示        仲裁列 max(x).4 / 聚合列 sum(x) —— 聚合层不能直接 SELECT *
 * </pre>
 *
 * <p>⚠️ 本类里所有返回给前端的报错【不写物理库名】—— 报错会显示在界面(工作流向导/最近执行)上,
 * 而前台只暴露层级名。源表统一只写表名。
 */
public final class ArbPlanBuilder {

    /** 工具类私有构造器:禁止实例化。 */
    private ArbPlanBuilder() {
    }

    /**
     * 允许出现在聚合层里的【基础类型】白名单。
     *
     * <p>为什么必须白名单:类型字符串会原样拼进 DDL。来源是前端的表结构接口,虽然可信度不低,
     * 但它是字符串,拼错一个字就是语法错误或更糟。
     */
    private static final Pattern BASE_TYPE_RE = Pattern.compile("^(?:"
            + "U?Int(?:8|16|32|64)|Float(?:32|64)|Bool|"
            + "Decimal\\(\\d+,\\s*\\d+\\)|"
            + "String|FixedString\\(\\d+\\)|UUID|"
            + "Date(?:32)?|DateTime|DateTime64\\(\\d+(?:,\\s*\\d+)?\\)|DateTime\\('[A-Za-z0-9_+\\-/]+'\\)"
            + ")$");

    /** 单源 SELECT 里的表别名(不是可选项,理由见 {@link #q(String)})。 */
    private static final String SRC_ALIAS = "src";

    /** 号码列的固定列注释。 */
    private static final String MDN_COMMENT = "手机号";

    /** 聚合表的表注释后缀(标注它是中间层)。全角括号,与 Go 逐字一致。 */
    private static final String AGG_COMMENT_SUFFIX = "（中间层）";

    // =====================================================================
    // 类型处理
    // =====================================================================

    /**
     * 去掉 Nullable() / LowCardinality() 包装,拿到基础类型。
     *
     * <p>为什么要拆:
     * <ul>
     *   <li>LowCardinality 嵌在聚合状态里收益很小,而且【同一列在不同源里一个是 String
     *       一个是 LowCardinality(String)】会让「省略列的 INSERT」报 Code 43,
     *       且报错列名不是你写错的那列;</li>
     *   <li>Nullable 在聚合列里要还原成非空(CK 聚合状态对 Nullable 支持不一致),值表达式会补零值。</li>
     * </ul>
     *
     * <p>两者可以嵌套(LowCardinality(Nullable(String))),所以循环剥。
     */
    public static String unwrapType(String type) {
        String t = type == null ? "" : type.trim();
        for (; ; ) {
            String inner = stripTypeWrapper(t, "Nullable");
            if (inner == null) {
                inner = stripTypeWrapper(t, "LowCardinality");
            }
            if (inner == null) {
                return t;
            }
            t = inner.trim();
        }
    }

    /**
     * 若 t 形如 {@code name(...)} 则返回括号内内容,否则返回 null。
     *
     * <p>括号必须配平,否则可能是 {@code FixedString(10)} 这种被误判(名字对不上自然返回 null)。
     */
    private static String stripTypeWrapper(String t, String name) {
        String prefix = name + "(";
        if (t == null || !t.startsWith(prefix) || !t.endsWith(")")) {
            return null;
        }
        String inner = t.substring(prefix.length(), t.length() - 1);
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth < 0) {
                    return null;
                }
            }
        }
        return depth == 0 ? inner : null;
    }

    /** 校验并返回基础类型。 */
    private static String checkedBaseType(String col, String type) {
        String base = unwrapType(type);
        if (base.isEmpty()) {
            throw ApiException.validation(String.format(
                    "字段 %s 缺少类型信息(界面需要先把表结构加载出来)", col));
        }
        if (!BASE_TYPE_RE.matcher(base).matches()) {
            throw ApiException.validation(String.format(
                    "字段 %s 的类型 %s 暂不支持(聚合层只支持数值/字符串/时间/Decimal)", col, base));
        }
        return base;
    }

    /**
     * sum 的目标类型:必须比输入更宽,否则会溢出或报错。
     * <pre>
     * UInt8/16/32/64 → UInt64        Int8/16/32/64 → Int64
     * Float32/64     → Float64       Decimal(p,s)  → Decimal(38,s)
     * </pre>
     *
     * <p>Decimal 的规则来自实测:sum(Decimal(18,2)) 返回 Decimal(38,2),
     * 列类型必须写成返回类型,否则写入时类型不匹配。
     */
    private static String sumType(String base) {
        if (base.startsWith("UInt")) {
            return "UInt64";
        }
        if (base.startsWith("Int")) {
            return "Int64";
        }
        if (base.startsWith("Float")) {
            return "Float64";
        }
        if (base.startsWith("Decimal(")) {
            String inner = base.substring("Decimal(".length(), base.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length != 2) {
                throw ApiException.validation("Decimal 类型 " + base + " 解析失败");
            }
            return "Decimal(38," + parts[1].trim() + ")";
        }
        throw ApiException.validation("类型 " + base + " 不能求和(只支持数值/Decimal)");
    }

    /**
     * 补空值用的字面量:mode=max 补该类型的【最小值】,mode=min 补【最大值】。
     *
     * <p>⚠️ 为什么不能一律补 0(真机上静默错值的一类,已修):
     * <pre>
     * min(ifNull(x, 0)) —— 只要有一行是 NULL,结果就恒为 0(比任何正数都小);
     * max(ifNull(x, 0)) —— 负数列的最大值会被 NULL 顶成 0。
     * </pre>
     * 两种情况都不报错,只是结果错。所以补的必须是「不可能超过真实值」的那一端。
     * 少数类型给不出可信的极值(字符串没有可造的最大值、Decimal 要按精度拼),
     * 宁可直接报错让人改配置,也不静默算错。
     *
     * <p>包级可见是为了让同包的 {@code ArbPlanBuilderTest} 直接钉住这张取值表 ——
     * 与 Go 的 {@code TestArbExtremeLiteral} 直接调用 {@code arbExtremeLiteral} 同一种做法。
     * 从 SQL 里反解字面量只能测到「拼进去之后的样子」,测不准这张表本身。
     */
    static String extremeLiteral(String base, String mode) {
        // lo = true 表示要补的是最小值(mode 是 max 时,空值要输给任何真实值)
        boolean lo = ArbColumn.MODE_MAX.equals(mode);
        switch (base) {
            case "Bool":
                return lo ? "false" : "true";
            case "UInt8":
                return lo ? "0" : "255";
            case "UInt16":
                return lo ? "0" : "65535";
            case "UInt32":
                return lo ? "0" : "4294967295";
            case "UInt64":
                return lo ? "0" : "toUInt64('18446744073709551615')";
            case "Int8":
                return lo ? "-128" : "127";
            case "Int16":
                return lo ? "-32768" : "32767";
            case "Int32":
                return lo ? "-2147483648" : "2147483647";
            case "Int64":
                return lo ? "toInt64('-9223372036854775808')" : "toInt64('9223372036854775807')";
            case "Float32":
                return lo ? "toFloat32(-inf)" : "toFloat32(inf)";
            case "Float64":
                return lo ? "toFloat64(-inf)" : "toFloat64(inf)";
            case "String":
                if (lo) {
                    return "''"; // 空串就是字符串里最小的
                }
                // 造不出「比所有真实值都大」的字符串:不能拿空串兼当最大值
                throw ApiException.validation("字符串列不支持取最小(空值没法补成不可超过的值):改用仲裁,或先把它转成数值列");
            case "UUID":
                return lo ? "toUUID('00000000-0000-0000-0000-000000000000')"
                        : "toUUID('ffffffff-ffff-ffff-ffff-ffffffffffff')";
            case "Date":
                return lo ? "toDate(0)" : "toDate('2149-06-06')";
            case "Date32":
                return lo ? "toDate32('1900-01-01')" : "toDate32('2299-12-31')";
            case "DateTime":
                return lo ? "toDateTime(0)" : "toDateTime(4294967295)";
            default:
                break;
        }
        if (base.startsWith("FixedString")) {
            if (lo) {
                return "''"; // FixedString 补空串会被填成全 0 字节,就是它的最小值
            }
            throw ApiException.validation("FixedString 列不支持取最小(理由同字符串列)");
        }
        if (base.startsWith("DateTime64(")) {
            String inner = base.substring("DateTime64(".length(), base.length() - 1);
            return lo ? "toDateTime64(0, " + inner + ")"
                    : "toDateTime64('2299-12-31 23:59:59', " + inner + ")";
        }
        if (base.startsWith("Decimal(")) {
            // 极值要按精度拼,容易拼错 —— 宁可报错让人改配置,也不静默算错
            throw ApiException.validation("Decimal 列不支持取最大/最小(极值要按精度拼,容易拼错):改用仲裁或 sum");
        }
        throw ApiException.validation("类型 " + base + " 不支持取最大/最小");
    }

    // =====================================================================
    // 校验
    // =====================================================================

    /** 目标库/表/列名是否可作为 CK 标识符安全拼进 DDL。 */
    public static boolean validIdent(String s) {
        return Sql.validIdent(s);
    }

    /**
     * 生成前校验。报错都要能直接告诉用户「哪一项配错了」。
     *
     * <p>⚠️ 本方法会【就地写回】归一化后的取值方式(空 mode → arbitrate),下游的
     * {@link #stateType}、{@link #readExpr}、{@link #warnings} 都按归一化后的值判断。
     * 调用方传进来的工作流会被改动 —— 与 Go 侧一致,不是副作用泄漏。
     */
    public static void validate(ArbWorkflow w) {
        if (w == null) {
            throw ApiException.validation("工作流为空");
        }
        if (trim(w.getName()).isEmpty()) {
            throw ApiException.validation("工作流名称不能为空");
        }
        if (trim(w.getTargetDb()).isEmpty()) {
            throw ApiException.validation("目标库不能为空");
        }
        if (!validIdent(w.getTargetDb())) {
            throw ApiException.validation(String.format(
                    "目标库名 %s 非法(只允许字母/下划线开头,后跟字母数字下划线)", GoFmt.q(w.getTargetDb())));
        }
        if (trim(w.getTargetTable()).isEmpty()) {
            throw ApiException.validation("目标表名不能为空");
        }
        if (!validIdent(w.getTargetTable())) {
            throw ApiException.validation(String.format(
                    "目标表名 %s 非法(只允许字母/下划线开头,后跟字母数字下划线)", GoFmt.q(w.getTargetTable())));
        }
        if (w.getTargetTable().length() + ArbWorkflow.AGG_SUFFIX.length() > ArbWorkflow.MAX_TABLE_LEN) {
            throw ApiException.validation("目标表名过长(加 _agg 后缀后不能超过 110 字符)");
        }

        // ── 源 ──
        if (w.getSources() == null || w.getSources().isEmpty()) {
            throw ApiException.validation("至少要选一张源表");
        }
        Map<String, ArbSource> srcById = new LinkedHashMap<>();
        for (int i = 0; i < w.getSources().size(); i++) {
            ArbSource s = w.getSources().get(i);
            if (trim(s.getId()).isEmpty()) {
                throw ApiException.validation(String.format("第 %d 个源缺少 id", i + 1));
            }
            if (srcById.containsKey(s.getId())) {
                throw ApiException.validation(String.format("源 id %s 重复", GoFmt.q(s.getId())));
            }
            if (isBlank(s.getDatabase()) || isBlank(s.getTable())) {
                throw ApiException.validation(String.format("源 %s 未选择表", s.getId()));
            }
            if (!validIdent(s.getDatabase()) || !validIdent(s.getTable())) {
                throw ApiException.validation(String.format("源表 %s 的名字非法", s.getTable()));
            }
            if (trim(s.getMdnCol()).isEmpty()) {
                throw ApiException.validation(String.format("源 %s 未选择号码列", s.getTable()));
            }
            if (!validIdent(s.getMdnCol())) {
                throw ApiException.validation(String.format(
                        "源 %s 的号码列 %s 名字非法", s.getTable(), GoFmt.q(s.getMdnCol())));
            }
            if (!isBlank(s.getTimeCol()) && !validIdent(s.getTimeCol())) {
                throw ApiException.validation(String.format(
                        "源 %s 的时间列 %s 名字非法", s.getTable(), GoFmt.q(s.getTimeCol())));
            }
            try {
                checkWhere(s.getWhere());
            } catch (ApiException e) {
                throw ApiException.validation(String.format(
                        "源 %s 的过滤条件有问题: %s", s.getTable(), e.getMessage()));
            }
            srcById.put(s.getId(), s);
        }

        // ── 目标列 ──
        if (w.getColumns() == null || w.getColumns().isEmpty()) {
            throw ApiException.validation("至少要配一个目标列");
        }
        Map<String, ArbColumn> colByName = new LinkedHashMap<>();
        for (ArbColumn c : w.getColumns()) {
            if (!validIdent(c.getName())) {
                throw ApiException.validation(String.format(
                        "目标列名 %s 非法(只允许字母/下划线开头,后跟字母数字下划线)", GoFmt.q(c.getName())));
            }
            if (ArbWorkflow.MDN_COLUMN.equals(c.getName())) {
                throw ApiException.validation(String.format(
                        "目标列不能叫 %s(这是号码列保留名)", GoFmt.q(ArbWorkflow.MDN_COLUMN)));
            }
            if (colByName.containsKey(c.getName())) {
                throw ApiException.validation(String.format("目标列 %s 重复", GoFmt.q(c.getName())));
            }
            ruleChecked(c); // 先把非法规则报出来
            // 空值补默认;下游 stateType/readExpr 直接读归一化后的 mode
            c.setMode(modeChecked(c));
            colByName.put(c.getName(), c);
        }

        // ── 对接层(源列 → 目标列) ──
        if (w.getFields() == null || w.getFields().isEmpty()) {
            throw ApiException.validation("至少要把一个源字段挂到目标列上");
        }
        // sourceID → outName → col(校验「一个源对同一列只能挂一次」)
        Map<String, Map<String, String>> perSrcOut = new HashMap<>();
        // outName → 已见过的基础类型(校验同一目标列在各源里类型一致)
        Map<String, String> typeByOut = new HashMap<>();
        for (ArbField f : w.getFields()) {
            ArbSource s = srcById.get(f.getSourceId());
            if (s == null) {
                throw ApiException.validation(String.format(
                        "字段 %s.%s 挂到了不存在的源 %s", f.getCol(), f.getOutName(), GoFmt.q(f.getSourceId())));
            }
            if (trim(f.getCol()).isEmpty()) {
                throw ApiException.validation(String.format("源 %s 有一个字段没选列", s.getTable()));
            }
            if (!validIdent(f.getCol())) {
                throw ApiException.validation(String.format(
                        "源 %s 的字段列名 %s 非法", s.getTable(), GoFmt.q(f.getCol())));
            }
            String out = trim(f.getOutName());
            if (out.isEmpty()) {
                out = f.getCol();
            }
            if (!validIdent(out)) {
                throw ApiException.validation(String.format("目标列名 %s 非法", GoFmt.q(out)));
            }
            ArbColumn c = colByName.get(out);
            if (c == null) {
                throw ApiException.validation(String.format(
                        "源 %s 的字段挂到目标列 %s,但该列没有配取值方式", s.getTable(), GoFmt.q(out)));
            }
            try {
                QueryPlanBuilder.buildPipelineExprFrom(quoteCol(f.getCol()), f.getSteps());
            } catch (ApiException e) {
                throw ApiException.validation(String.format(
                        "源 %s 的处理链有问题: %s", s.getTable(), e.getMessage()));
            }
            if (!ArbColumn.MODE_ARBITRATE.equals(c.getMode())) {
                // 仲裁列不需要类型(一律 String);聚合列必须知道类型。
                // 同一目标列在所有源里的基础类型必须一致 —— 物理列只能有一种类型,
                // 不一致时聚合层的列类型会由「字段出现的先后顺序」决定,一直到回填才报一个
                // 指向该列的裸类型错(说不清是哪个源配错了)。
                String base;
                try {
                    base = checkedBaseType(f.getCol(), f.getColType());
                } catch (ApiException e) {
                    throw ApiException.validation(String.format(
                            "源 %s 的字段 %s: %s", s.getTable(), f.getCol(), e.getMessage()));
                }
                String prev = typeByOut.get(out);
                if (prev != null && !prev.equals(base)) {
                    throw ApiException.validation(String.format(
                            "目标列 %s 的类型在两个源里不一致:一处是 %s,另一处是 %s —— 同一列只能有一种类型,"
                                    + "请统一类型,或拆成两个目标列", GoFmt.q(out), prev, base));
                }
                typeByOut.put(out, base);
            }
            Map<String, String> seen = perSrcOut.computeIfAbsent(f.getSourceId(), k -> new HashMap<>());
            String dup = seen.get(out);
            if (dup != null) {
                throw ApiException.validation(String.format(
                        "源 %s 把 %s 和 %s 同时挂到了目标列 %s(一个源对同一列只能挂一次,否则该列会被算两遍)",
                        s.getTable(), dup, f.getCol(), GoFmt.q(out)));
            }
            seen.put(out, f.getCol());
        }

        // 每个目标列都要有来源,否则它永远是空的
        for (ArbColumn c : w.getColumns()) {
            boolean has = false;
            for (ArbField f : w.getFields()) {
                if (outNameOf(f).equals(c.getName())) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                throw ApiException.validation(String.format(
                        "目标列 %s 没有任何来源字段(要么给它挂源列,要么删掉它)", GoFmt.q(c.getName())));
            }
        }

        // 每个启用的源都要有字段,否则它的 MV 只会往聚合表塞一行空号码
        for (ArbSource s : w.getSources()) {
            if (!s.isEnabled()) {
                continue;
            }
            if (perSrcOut.getOrDefault(s.getId(), new HashMap<>()).isEmpty()) {
                throw ApiException.validation(String.format(
                        "源 %s 没有任何字段映射(要么配字段,要么把它停用/删掉)", s.getTable()));
            }
        }
    }

    /**
     * 源内过滤条件的基础校验。
     *
     * <p>执行 SQL 使用工作流管理身份，条件只能通过受限表达式语法；数据探索的权限不构成这里的授权。
     */
    public static void checkWhere(String where) {
        ArbWhereExpression.validate(where);
    }

    /** 归一化仲裁规则(空 = 默认)。 */
    private static String ruleOf(ArbColumn c) {
        String r = trim(c.getRule());
        return r.isEmpty() ? ArbColumn.RULE_PRI_TIME : r;
    }

    /** 校验仲裁规则取值。 */
    private static String ruleChecked(ArbColumn c) {
        String r = ruleOf(c);
        if (ArbColumn.RULE_PRI_TIME.equals(r) || ArbColumn.RULE_PRI.equals(r) || ArbColumn.RULE_TIME.equals(r)) {
            return r;
        }
        throw ApiException.validation(String.format(
                "目标列 %s 的仲裁规则 %s 不认识(可选 %s / %s / %s)",
                GoFmt.q(c.getName()), GoFmt.q(r),
                ArbColumn.RULE_PRI_TIME, ArbColumn.RULE_PRI, ArbColumn.RULE_TIME));
    }

    /**
     * 校验目标列的取值方式(空 = 默认 arbitrate)。
     *
     * <p>必须在写入边界就拒:否则未知 mode 能一路存进库,直到 flatten/建表才由
     * {@link #stateType} 报错,而那时用户看到的是「生成失败」,不是「你配的这列取值方式写错了」。
     */
    private static String modeChecked(ArbColumn c) {
        String m = trim(c.getMode());
        if (m.isEmpty()) {
            m = ArbColumn.MODE_ARBITRATE;
        }
        if (ArbColumn.MODE_ARBITRATE.equals(m) || ArbColumn.MODE_SUM.equals(m)
                || ArbColumn.MODE_MAX.equals(m) || ArbColumn.MODE_MIN.equals(m)) {
            return m;
        }
        throw ApiException.validation(String.format(
                "目标列 %s 的取值方式 %s 不认识(可选 %s / %s / %s / %s)",
                GoFmt.q(c.getName()), GoFmt.q(m), ArbColumn.MODE_ARBITRATE, ArbColumn.MODE_SUM,
                ArbColumn.MODE_MAX, ArbColumn.MODE_MIN));
    }

    // =====================================================================
    // 列类型与读法
    // =====================================================================

    /** 目标列在聚合层里的列类型。 */
    public static String stateType(ArbColumn c, String srcType) {
        String mode = trim(c.getMode());
        if (ArbColumn.MODE_ARBITRATE.equals(mode)) {
            // ⚠️ 三种仲裁规则共用同一种类型:靠把不参与比较的那一位填 0 表达语义。
            //    这样全表仲裁列类型完全一致 —— 混用状态类型会让「省略列的 INSERT」
            //    报 Code 43,而且报错列名具有误导性。
            //    值一律 String:仲裁只比大小,不比数值。
            return "SimpleAggregateFunction(max, Tuple(UInt8,UInt8,DateTime,String))";
        }
        if (ArbColumn.MODE_SUM.equals(mode)) {
            String base = checkedBaseType(c.getName(), srcType);
            try {
                return "SimpleAggregateFunction(sum, " + sumType(base) + ")";
            } catch (ApiException e) {
                throw ApiException.validation(String.format("目标列 %s: %s", GoFmt.q(c.getName()), e.getMessage()));
            }
        }
        if (ArbColumn.MODE_MAX.equals(mode) || ArbColumn.MODE_MIN.equals(mode)) {
            String base = checkedBaseType(c.getName(), srcType);
            return "SimpleAggregateFunction(" + mode + ", " + base + ")";
        }
        throw ApiException.validation(String.format(
                "目标列 %s 的取值方式 %s 不认识(可选 %s / %s / %s / %s)",
                GoFmt.q(c.getName()), GoFmt.q(c.getMode()), ArbColumn.MODE_ARBITRATE, ArbColumn.MODE_SUM,
                ArbColumn.MODE_MAX, ArbColumn.MODE_MIN));
    }

    /**
     * 目标列的读法(聚合层不能直接 SELECT *,必须按这个写)。
     *
     * <p>这里是【不带表别名】的版本,给计划展示用(用户看到的就是「这一列怎么读」)。
     * 真正拼进 SQL 的那条见 {@link #svcReadExpr},列引用必须带 {@code src.} 限定。
     */
    public static String readExpr(ArbColumn c) {
        return readExpr(quoteCol(c.getName()), c);
    }

    /**
     * 摊平查询里的读法:与 {@link #readExpr} 同一套规则,但列引用带 {@code src.} 限定。
     *
     * <p>⚠️ {@code src.} 不能省:裸列名会被 ClickHouse 优先解析成 SELECT 列表里的【别名】,
     * 于是「列名与别名同名」时指错对象(与 {@link #q} 是同一个坑)。
     * 与 Go {@code BuildArbSvcSelect} 的拼法逐字一致。
     */
    private static String svcReadExpr(ArbColumn c) {
        return readExpr(q(c.getName()), c);
    }

    private static String readExpr(String colRef, ArbColumn c) {
        String mode = trim(c.getMode());
        if (ArbColumn.MODE_ARBITRATE.equals(mode)) {
            return "max(" + colRef + ").4";
        }
        if (ArbColumn.MODE_SUM.equals(mode)) {
            return "sum(" + colRef + ")";
        }
        if (ArbColumn.MODE_MAX.equals(mode)) {
            return "max(" + colRef + ")";
        }
        if (ArbColumn.MODE_MIN.equals(mode)) {
            return "min(" + colRef + ")";
        }
        return colRef;
    }

    /**
     * 取某个目标列的基类型(用任意一条挂到它的字段映射)。
     *
     * <p>什么时候用:某个源【没有】这个目标列,但该列是 min/max —— 得知道类型才能补极值。
     * 多源类型不一致时由各自的 checkedBaseType 报错,这里只负责「随便挑一条能用的」。
     */
    private static String anyFieldBaseType(ArbWorkflow w, String outName) {
        for (ArbField f : w.getFields()) {
            if (outNameOf(f).equals(outName)) {
                return checkedBaseType(f.getCol(), f.getColType());
            }
        }
        throw ApiException.validation(String.format("目标列 %s 没有任何来源字段,无法确定类型", GoFmt.q(outName)));
    }

    /**
     * 目标列在服务表(平列)里的类型。
     *
     * <p>注意仲裁列是 String:聚合层里仲裁的值统一 toString 存(这样才能全表状态类型一致),
     * 摊平后依然是字符串。需要按数值/日期范围筛选的列就不该用仲裁,而该用 sum/max/min。
     */
    public static String serviceType(ArbColumn c, String srcType) {
        String mode = trim(c.getMode());
        if (ArbColumn.MODE_ARBITRATE.equals(mode)) {
            return "String";
        }
        if (ArbColumn.MODE_SUM.equals(mode)) {
            return sumType(checkedBaseType(c.getName(), srcType));
        }
        if (ArbColumn.MODE_MAX.equals(mode) || ArbColumn.MODE_MIN.equals(mode)) {
            return checkedBaseType(c.getName(), srcType);
        }
        throw ApiException.validation(String.format(
                "目标列 %s 的取值方式 %s 不认识", GoFmt.q(c.getName()), GoFmt.q(c.getMode())));
    }

    // =====================================================================
    // 注释
    // =====================================================================

    /**
     * 某个目标列的业务说明。
     *
     * <p>说明是【目标列】的属性,不是源列的:同一个目标列可能有多条映射(多源共用一个列名),
     * 取第一条非空的即可 —— 前端在建映射时会把源列注释作为默认值带过来,用户可改。
     */
    private static String columnComment(ArbWorkflow w, String col) {
        for (ArbField f : w.getFields()) {
            if ((col.equals(f.getOutName()) || (isBlank(f.getOutName()) && col.equals(f.getCol())))
                    && !trim(f.getComment()).isEmpty()) {
                return trim(f.getComment());
            }
        }
        return "";
    }

    /**
     * 把自由文本转义成合法的 CK 字符串字面量内容(反斜杠与单引号都转)。
     *
     * <p>⚠️ 转义顺序不能反:先把 {@code \} 变成 {@code \\},再把 {@code '} 变成 {@code \'}。
     * 反过来会把刚写进去的反斜杠又转一遍。
     *
     * <p>⚠️ 也【不能】换成 {@link Sql#quoteString}:那个走的是引号双写({@code ''}),
     * 而这里必须用反斜杠形式 —— 生成的 SQL 要逐字节与 Go 版一致。
     */
    private static String escapeComment(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * 生成 {@code  COMMENT '...'}(没有就返回空串)。
     *
     * <p>⚠️ 注释是用户随手填的自由文本,必须转义成合法的 CK 字符串字面量 ——
     * 否则一句「张三's 手机号」就能把建表语句拆坏。
     */
    private static String commentClause(ArbWorkflow w, String col) {
        String c = columnComment(w, col);
        if (c.isEmpty()) {
            return "";
        }
        return " COMMENT '" + escapeComment(c) + "'";
    }

    /**
     * 表级注释优先使用工作流说明；未填写说明时保留工作流名称作为回退。
     * 聚合中间层仍追加层级后缀，服务表与服务视图使用同一份业务说明。
     */
    private static String tableCommentClause(ArbWorkflow w, String suffix) {
        String comment = trim(w.getDescription());
        if (comment.isEmpty()) comment = trim(w.getName());
        if (comment.isEmpty()) {
            return "";
        }
        return " COMMENT '" + escapeComment(comment + suffix) + "'";
    }

    /** 聚合表的表注释子句(标注它是中间层)。 */
    public static String aggTableCommentClause(ArbWorkflow w) {
        return tableCommentClause(w, AGG_COMMENT_SUFFIX);
    }

    /** 聚合表的表注释语句(换名之后调用)。说明及名称均为空时返回空串。 */
    public static String aggTableCommentSql(ArbWorkflow w) {
        String clause = aggTableCommentClause(w);
        if (clause.isEmpty()) {
            return "";
        }
        return "ALTER TABLE " + quoteCol(w.getTargetDb()) + "." + quoteCol(w.aggTableName()) + " MODIFY" + clause;
    }

    // =====================================================================
    // 服务层(摊平)
    // =====================================================================

    /**
     * 工作流是否含 sum 列(决定是否必须用物理服务表)。
     *
     * <p>sum 需要跨源累加,只有 AggregatingMergeTree 的 SimpleAggregateFunction(sum, ...)
     * 能在 MV 写入时自动累加;VIEW 做不到(每次查询都重新算,但 MV 写入的是增量,不是全量,
     * 所以 sum 会少算)。没有 sum 时,聚合表的状态可以直接读出来,用 VIEW 替代物理服务表 ——
     * 零存储、自动同步、不需要摊平。
     */
    public static boolean workflowHasSum(ArbWorkflow w) {
        for (ArbColumn c : w.getColumns()) {
            if (ArbColumn.MODE_SUM.equals(trim(c.getMode()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 服务视图建表语句(没有 sum 列时替代物理服务表)。
     *
     * <p>VIEW 直接读聚合表 + GROUP BY,把状态列展开成业务值。不存数据,每次查询实时算 ——
     * 所以永远和聚合表同步,不需要手动摊平。
     *
     * <p>限制:含 sum 列时不能用 VIEW(sum 需要跨源累加,VIEW 每次查询重新聚合,
     * 但 MV 写入的是增量而非全量,结果会少算)。调用方须先用 {@link #workflowHasSum} 判断。
     *
     * <p>⚠️ VIEW 的列清单必须写「名字 + 类型」,只写名字带 COMMENT 会被 CK 拒掉
     * (Syntax error: Expected type name)—— 类型与物理服务表用同一套 {@link #serviceType},
     * 保证两条路径的服务层 schema 一致。表级 COMMENT 必须位于 AS SELECT 查询之后，兼容 ClickHouse 25.3。
     */
    public static String buildSvcViewDdl(ArbWorkflow w) {
        return buildSvcViewDdl(w, w == null ? null : w.svcTableName());
    }

    /**
     * 生成指向指定物理名称的服务视图 DDL。
     *
     * <p>发布服务视图时需要先在临时名称下创建并验证，成功后再原子换名。因此 DDL 的目标名称
     * 不能总是固定为工作流最终的服务表名。这里把名称作为显式参数，避免调用方通过字符串
     * {@code replace} 改写 DDL；后者一旦语句格式变化就可能静默改错或改到查询体里的同名字符串。
     *
     * @param w 工作流配置
     * @param targetName 本次 DDL 要创建的 ClickHouse 对象名（最终名或临时名）
     * @return 完整的 CREATE VIEW 语句
     */
    static String buildSvcViewDdl(ArbWorkflow w, String targetName) {
        validate(w);
        if (workflowHasSum(w)) {
            throw ApiException.validation("工作流含 sum 列,不能用 VIEW 做服务表(必须用物理表存储累加结果)");
        }
        StringBuilder b = new StringBuilder();
        b.append("CREATE VIEW ").append(quoteCol(w.getTargetDb())).append(".").append(quoteCol(targetName))
                .append(" (\n");
        b.append("  ").append(quoteCol(ArbWorkflow.MDN_COLUMN)).append(" String COMMENT '").append(MDN_COMMENT).append("'");
        for (ArbColumn c : w.getColumns()) {
            b.append(",\n  ").append(quoteCol(c.getName())).append(" ")
                    .append(serviceType(c, srcTypeForColumn(w, c.getName())))
                    .append(commentClause(w, c.getName()));
        }
        b.append("\n)");
        b.append(" AS\n");
        b.append(buildSvcSelect(w));
        b.append(tableCommentClause(w, ""));
        return b.toString();
    }

    /**
     * 服务表建表语句。
     *
     * <p>普通 MergeTree + ORDER BY mdn:摊平后它是给圈选/点查用的,不是状态表。
     */
    public static String buildSvcDdl(ArbWorkflow w) {
        validate(w);
        StringBuilder b = new StringBuilder();
        b.append("CREATE TABLE IF NOT EXISTS ").append(quoteCol(w.getTargetDb())).append(".")
                .append(quoteCol(w.svcTableName())).append(" (\n");
        b.append("  ").append(quoteCol(ArbWorkflow.MDN_COLUMN)).append(" String COMMENT '").append(MDN_COMMENT).append("'");
        for (ArbColumn c : w.getColumns()) {
            b.append(",\n  ").append(quoteCol(c.getName())).append(" ")
                    .append(serviceType(c, srcTypeForColumn(w, c.getName())))
                    .append(commentClause(w, c.getName()));
        }
        b.append("\n) ENGINE = MergeTree\nORDER BY ").append(quoteCol(ArbWorkflow.MDN_COLUMN));
        b.append(tableCommentClause(w, ""));
        return b.toString();
    }

    /**
     * 把若干个 ALTER 动作合并成【一条】语句。
     *
     * <p>每条 ALTER 都是一次网络来回(本机 ~8ms):40 个目标列就变成 40 个来回,在「10 万行」
     * 这个量级上光来回就顶掉三分之一的时间(而 ALTER 本身是纯元数据,0 计算)。实测 ClickHouse
     * 允许一条 ALTER 里并列多个 ADD COLUMN IF NOT EXISTS / MODIFY COLUMN,表级的 MODIFY COMMENT
     * 也能与列级动作混在同一条里;动作按数组顺序执行,语义与逐条一致。
     *
     * <p>空动作清单返回空列表(不是含一条空语句的列表)。
     */
    private static List<String> oneAlter(String db, String table, List<String> actions) {
        List<String> out = new ArrayList<>();
        if (actions.isEmpty()) {
            return out;
        }
        out.add("ALTER TABLE " + quoteCol(db) + "." + quoteCol(table) + " " + String.join(", ", actions));
        return out;
    }

    /**
     * 给聚合表补上缺的列。
     *
     * <p>为什么必须有:重建走的路径是「建临时表(结构复制自当前聚合表)→ 回填 → EXCHANGE」,
     * 复制出来的表【不会】带上后来新增的目标列 —— 于是「给已有工作流加一列」(最典型的场景
     * 就是并入一张新源表,它带来了新字段)会在回填时报「No such column xxx」,整次执行失败。实测踩过。
     */
    public static List<String> buildAggAlterSql(ArbWorkflow w, String table) {
        List<String> actions = new ArrayList<>();
        for (ArbColumn c : w.getColumns()) {
            actions.add("ADD COLUMN IF NOT EXISTS " + quoteCol(c.getName()) + " "
                    + stateType(c, srcTypeForColumn(w, c.getName())));
        }
        return oneAlter(w.getTargetDb(), table, actions);
    }

    /**
     * 给服务表补上缺的列。
     *
     * <p>因为服务表是用 IF NOT EXISTS 建的,后来加的目标列不会自动长出来 ——
     * 用 ADD COLUMN IF NOT EXISTS 逐列补(纯元数据,秒级)。
     */
    public static List<String> buildSvcAlterSql(ArbWorkflow w) {
        List<String> actions = new ArrayList<>();
        for (ArbColumn c : w.getColumns()) {
            actions.add("ADD COLUMN IF NOT EXISTS " + quoteCol(c.getName()) + " "
                    + serviceType(c, srcTypeForColumn(w, c.getName())) + commentClause(w, c.getName()));
        }
        return oneAlter(w.getTargetDb(), w.svcTableName(), actions);
    }

    /**
     * 给【已经存在】的服务表列补上/更新业务说明。
     *
     * <p>为什么单列这一组:建表(IF NOT EXISTS)与 ADD COLUMN IF NOT EXISTS 都只对【新列】生效 ——
     * 给一个早就存在的列配上说明时它们什么也不做,注释永远落不下去。
     *
     * <p>⚠️ MODIFY COLUMN 只改注释时是【纯元数据】(实测 20 万行表 0.01s、system.mutations 不增),
     * 不会重写数据,所以每次摊平都跑一遍是安全的(幂等)。
     * ⚠️ 只对本项目自己生成的列用:这些列都是「裸类型」,没有 DEFAULT/CODEC,
     * 所以 MODIFY 时只写类型+注释不会把别的东西抹掉。
     *
     * <p>动作顺序是有意的:表注释在第一条(老表的注释要能跟上,换名字后也能更新),
     * 然后固定是 MDN 列,再按目标列顺序。
     */
    public static List<String> buildSvcCommentSql(ArbWorkflow w) {
        List<String> actions = new ArrayList<>();
        String tableClause = tableCommentClause(w, "");
        if (!tableClause.isEmpty()) {
            actions.add("MODIFY" + tableClause);
        }
        // MDN 列是固定常量,不在 w.Columns 里,单独补上注释
        actions.add("MODIFY COLUMN " + quoteCol(ArbWorkflow.MDN_COLUMN) + " String COMMENT '" + MDN_COMMENT + "'");
        for (ArbColumn c : w.getColumns()) {
            String clause = commentClause(w, c.getName());
            if (clause.isEmpty()) {
                continue; // 没说明的列不动
            }
            actions.add("MODIFY COLUMN " + quoteCol(c.getName()) + " "
                    + serviceType(c, srcTypeForColumn(w, c.getName())) + clause);
        }
        return oneAlter(w.getTargetDb(), w.svcTableName(), actions);
    }

    /**
     * 摊平查询:把聚合层的状态拆成普通值。
     * <pre>
     * SELECT `src`.`mdn` AS `mdn`,
     *        max(`src`.`name`).4 AS `name`,
     *        sum(`src`.`money`)  AS `money`
     * FROM db.agg AS src GROUP BY `mdn`
     * </pre>
     *
     * <p>⚠️ 必须 GROUP BY:聚合层未合并时一个号码会有多行,不聚合会把中间结果当成最终值。
     * 列引用都带 src. 限定,避开「裸标识符被当成 SELECT 别名解析」那个坑。
     */
    public static String buildSvcSelect(ArbWorkflow w) {
        return buildSvcSelect(w, w.aggTableName(), "");
    }

    static String buildSvcSelect(ArbWorkflow w, String table, String predicate) {
        validate(w);
        List<String> exprs = new ArrayList<>();
        exprs.add(q(ArbWorkflow.MDN_COLUMN) + " AS " + quoteCol(ArbWorkflow.MDN_COLUMN));
        for (ArbColumn c : w.getColumns()) {
            String expr = svcReadExpr(c);
            // min/max may retain the SimpleAggregateFunction wrapper in a view result.
            if (ArbColumn.MODE_MAX.equals(trim(c.getMode())) || ArbColumn.MODE_MIN.equals(trim(c.getMode()))) {
                expr = "CAST(" + expr + " AS " + serviceType(c, srcTypeForColumn(w, c.getName())) + ")";
            }
            exprs.add(expr + " AS " + quoteCol(c.getName()));
        }
        return "SELECT\n  " + String.join(",\n  ", exprs)
                + "\nFROM " + quoteCol(w.getTargetDb()) + "." + quoteCol(table)
                + " AS " + quoteCol(SRC_ALIAS)
                + (predicate.isEmpty() ? "" : "\nWHERE " + predicate)
                + "\nGROUP BY " + quoteCol(ArbWorkflow.MDN_COLUMN);
    }

    /** 服务表要写的列名(与 {@link #buildSvcSelect} 的输出一一对应)。 */
    public static List<String> buildSvcColumns(ArbWorkflow w) {
        List<String> out = new ArrayList<>();
        out.add(ArbWorkflow.MDN_COLUMN);
        for (ArbColumn c : w.getColumns()) {
            out.add(c.getName());
        }
        return out;
    }

    // =====================================================================
    // 聚合层与物化视图
    // =====================================================================

    /** 聚合层建表语句。 */
    public static String buildAggDdl(ArbWorkflow w) {
        return buildAggDdl(w, w.aggTableName());
    }

    /** 同上,但表名可换(建临时表用)。 */
    public static String buildAggDdl(ArbWorkflow w, String table) {
        return buildAggDdl(w, table, false);
    }

    static final String BATCH_COLUMN = "__el_arb_batch";

    static String buildAggDdl(ArbWorkflow w, String table, boolean resumable) {
        validate(w);
        StringBuilder b = new StringBuilder();
        b.append("CREATE TABLE IF NOT EXISTS ").append(quoteCol(w.getTargetDb())).append(".")
                .append(quoteCol(table)).append(" (\n");
        b.append("  ").append(quoteCol(ArbWorkflow.MDN_COLUMN)).append(" String COMMENT '").append(MDN_COMMENT).append("'");
        for (ArbColumn c : w.getColumns()) {
            b.append(",\n  ").append(quoteCol(c.getName())).append(" ")
                    .append(stateType(c, srcTypeForColumn(w, c.getName())));
        }
        if (resumable) {
            for (ArbColumn c : w.getColumns()) {
                if (BATCH_COLUMN.equalsIgnoreCase(c.getName())) throw ApiException.validation("目标列使用了工作流保留列名");
            }
            b.append(",\n  ").append(quoteCol(BATCH_COLUMN)).append(" String DEFAULT ''");
        }
        b.append("\n) ENGINE = AggregatingMergeTree\n");
        if (resumable) b.append("PARTITION BY ").append(quoteCol(BATCH_COLUMN)).append("\n");
        b.append("ORDER BY ").append(quoteCol(ArbWorkflow.MDN_COLUMN));
        b.append(aggTableCommentClause(w));
        return b.toString();
    }

    /**
     * 某个源的物化视图名。
     *
     * <p>一源一个 MV:ClickHouse 的 MV 是 INSERT 触发器,只挂在 FROM 里第一个表上,
     * 一个 MV 里 UNION 多张源表时,其余源的数据会【静默丢失】(建表不报错、写入不报错)。
     */
    public static String mvName(ArbWorkflow w, ArbSource src) {
        return "mv_" + w.getTargetTable() + "_" + src.getId();
    }

    /**
     * 带表别名的列引用。
     *
     * <p>⚠️ {@code src.} 不是可选项:裸列名会被 ClickHouse 优先解析成 SELECT 列表里的【别名】,
     * 于是「时间列同时也是输出列」时,别的列里的 {@code toDateTime(login_at)} 就指到了那个聚合别名上,
     * 报 code 184 聚合套聚合。实测复现过。
     */
    private static String q(String col) {
        return quoteCol(SRC_ALIAS) + "." + quoteCol(col);
    }

    /** 号码列表达式(归一化 + 兜底成 String)。 */
    private static String mdnExpr(ArbSource src) {
        String expr;
        try {
            expr = QueryPlanBuilder.buildPipelineExprFrom(q(src.getMdnCol()), src.getMdnSteps());
        } catch (ApiException e) {
            throw ApiException.validation("号码列处理链有问题: " + e.getMessage());
        }
        return "ifNull(toString(" + expr + "), '')";
    }

    /** {@link #buildSourceSelect} 的产物:SELECT 语句 + 它实际输出的列名(同序)。 */
    private static final class SourceSelect {
        private final String sql;
        private final List<String> cols;

        private SourceSelect(String sql, List<String> cols) {
            this.sql = sql;
            this.cols = cols;
        }
    }

    /**
     * 单源的 SELECT(不含 MV / INSERT 外壳)。
     *
     * <p>返回值里的 cols = 这条 SELECT 实际输出的列名(顺序一致)。【必须】和 SQL 同源 ——
     * 回填要写成 {@code INSERT INTO t (列名…) SELECT …}:漏了列名清单时 ClickHouse 要求
     * SELECT 的列数与目标表【完全相等】,而源通常只提供部分列,实测直接报
     * {@code Number of columns doesn't match (source: 4 and result: 5)}。
     *
     * <p>形状(以两个仲裁列 + 一个 sum 列为例):
     * <pre>
     * SELECT &lt;号码&gt; AS mdn,
     *        max(tuple(&lt;值&gt; != '', toUInt8(3), toDateTime(&lt;时间&gt;), &lt;值&gt;)) AS name,
     *        sum(ifNull(&lt;列&gt;, 0)) AS order_cnt
     * FROM ods_data.ods_x
     * WHERE ...
     * GROUP BY mdn
     * </pre>
     *
     * <p>该源没有的目标列:仲裁列与 sum 列【不写】—— 引擎会给该列填空状态,而空状态在仲裁里
     * 天然输给任何有值的数据(第一个分量 0 最小),sum 的 0 又是加性零元,所以新源/少字段的源
     * 不会顶掉别的源已经写进去的值。
     *
     * <p>⚠️ min/max 列【必须显式补极值】,不能靠「空状态 = 0」:0 对 min 是【最大】元
     * (任何非负值都会被它压成 0),对全负数的 max 也是最大元 —— 都不报错,只是结果错。
     * 这跟「列内取值本身是 NULL」是同一个坑,所以复用同一个 {@link #extremeLiteral}。
     */
    private static SourceSelect buildSourceSelect(ArbWorkflow w, ArbSource src) {
        return buildSourceSelect(w, src, src.getDatabase(), src.getTable(), "");
    }

    private static SourceSelect buildSourceSelect(ArbWorkflow w, ArbSource src, String database, String table, String predicate) {
        String mdn = mdnExpr(src);

        Map<String, ArbColumn> colByName = new LinkedHashMap<>();
        for (ArbColumn c : w.getColumns()) {
            colByName.put(c.getName(), c);
        }

        // 本源的字段按目标列分组(一个目标列在本源最多一个字段,校验已保证)
        Map<String, ArbField> byOut = new LinkedHashMap<>();
        for (ArbField f : w.getFields()) {
            if (!src.getId().equals(f.getSourceId())) {
                continue;
            }
            byOut.put(outNameOf(f), f);
        }

        // 时间表达式:没配时间列 → 恒 0(时间不参与比较)
        //
        // ⚠️ 必须把 NULL 兑掉:Nullable(DateTime) 的直接 toDateTime 结果是 Nullable,
        //    元组会变成 Tuple(UInt8,UInt8,Nullable(DateTime),String),与目标列的
        //    Tuple(UInt8,UInt8,DateTime,String) 对不上 → 写入报类型错。
        //    「映射同步」建的贴源表是可能带 Nullable 的。
        String tExpr = "toDateTime(0)";
        if (!isBlank(src.getTimeCol())) {
            tExpr = "ifNull(toDateTime(" + q(src.getTimeCol()) + "), toDateTime(0))";
        }
        String priExpr = "toUInt8(" + src.getPri() + ")";

        // 按【目标列】的顺序输出,保证各源 SELECT 的列序一致(便于人工核对)
        List<String> exprs = new ArrayList<>();
        List<String> outCols = new ArrayList<>();
        exprs.add(mdn + " AS " + quoteCol(ArbWorkflow.MDN_COLUMN));
        outCols.add(ArbWorkflow.MDN_COLUMN);

        for (ArbColumn c : w.getColumns()) {
            ArbField field = byOut.get(c.getName());
            if (field == null) {
                // 本源没有这个目标列(见方法头说明):min/max 要显式补该类型的极值
                if (ArbColumn.MODE_MAX.equals(trim(c.getMode())) || ArbColumn.MODE_MIN.equals(trim(c.getMode()))) {
                    String base = anyFieldBaseType(w, c.getName());
                    String extreme;
                    try {
                        extreme = extremeLiteral(base, trim(c.getMode()));
                    } catch (ApiException e) {
                        throw ApiException.validation(String.format("目标列 %s: %s", c.getName(), e.getMessage()));
                    }
                    exprs.add(trim(c.getMode()) + "(" + extreme + ") AS " + quoteCol(c.getName()));
                    outCols.add(c.getName());
                }
                continue;
            }
            String val;
            try {
                val = QueryPlanBuilder.buildPipelineExprFrom(q(field.getCol()), field.getSteps());
            } catch (ApiException e) {
                throw ApiException.validation(String.format(
                        "字段 %s.%s 的处理链有问题: %s", src.getTable(), field.getCol(), e.getMessage()));
            }
            String mode = trim(c.getMode());
            if (ArbColumn.MODE_ARBITRATE.equals(mode)) {
                String rule = ruleChecked(c);
                String rulePri = priExpr;
                String ruleTime = tExpr;
                if (ArbColumn.RULE_PRI.equals(rule)) {
                    ruleTime = "toDateTime(0)"; // 时间不参与
                }
                if (ArbColumn.RULE_TIME.equals(rule)) {
                    rulePri = "toUInt8(0)"; // 优先级不参与
                }
                // 值转 String:仲裁列的状态类型全表统一成 String
                String v = "ifNull(toString(" + val + "), '')";
                exprs.add("max(tuple(" + v + " != '', " + rulePri + ", " + ruleTime + ", " + v + ")) AS "
                        + quoteCol(c.getName()));
            } else if (ArbColumn.MODE_SUM.equals(mode)) {
                exprs.add("sum(ifNull(" + val + ", 0)) AS " + quoteCol(c.getName()));
            } else if (ArbColumn.MODE_MAX.equals(mode) || ArbColumn.MODE_MIN.equals(mode)) {
                String base = checkedBaseType(field.getCol(), field.getColType());
                String extreme;
                try {
                    extreme = extremeLiteral(base, mode);
                } catch (ApiException e) {
                    throw ApiException.validation(String.format(
                            "字段 %s.%s: %s", src.getTable(), field.getCol(), e.getMessage()));
                }
                exprs.add(mode + "(ifNull(" + val + ", " + extreme + ")) AS " + quoteCol(c.getName()));
            } else {
                // 未知取值方式:走一遍 stateType,把「不认识」那个错误抛出来
                stateType(c, "");
            }
            outCols.add(c.getName());
        }

        StringBuilder b = new StringBuilder();
        b.append("SELECT\n  ").append(String.join(",\n  ", exprs)).append("\n");
        b.append("FROM ").append(quoteCol(database)).append(".").append(quoteCol(table))
                .append(" AS ").append(quoteCol(SRC_ALIAS));
        String where = trim(src.getWhere());
        if (!predicate.isEmpty()) where = where.isEmpty() ? predicate : "(" + where + ") AND (" + predicate + ")";
        if (!where.isEmpty()) {
            // 提示:源内过滤里引用列时建议写 src.xxx(裸列名会被解析成输出别名)
            b.append("\nWHERE ").append(where);
        }
        b.append("\nGROUP BY ").append(quoteCol(ArbWorkflow.MDN_COLUMN));
        return new SourceSelect(b.toString(), outCols);
    }

    /**
     * 某源的物化视图语句。
     *
     * <p>⚠️ 每个表达式必须起别名(否则 CK 报 "SELECT query outputs column with name ...
     * which is not found in the target table");我们本来就都写 AS,这里指出来免得以后有人改简写。
     */
    public static String buildMvSql(ArbWorkflow w, ArbSource src) {
        return "CREATE MATERIALIZED VIEW " + quoteCol(w.getTargetDb()) + "." + quoteCol(mvName(w, src))
                + " TO " + quoteCol(w.getTargetDb()) + "." + quoteCol(w.aggTableName())
                + " AS\n" + buildSourceSelect(w, src).sql;
    }

    /**
     * 某源的历史回填语句。
     *
     * <p>⚠️ 建完 MV 必须跑一次:MV 只接住建好【之后】的 INSERT,建视图前已经写进源表的数据
     * 不会自己流进来 —— 不跑这一步,聚合表是空的。
     *
     * <p>⚠️ 必须写【列名清单】:源通常只提供部分列,而 {@code INSERT INTO t SELECT …} 要求
     * 列数与目标表完全相等。清单直接取 SELECT 的产物列,两者不可能漂移。
     */
    public static String buildBackfillSql(ArbWorkflow w, ArbSource src) {
        SourceSelect sel = buildSourceSelect(w, src);
        List<String> quoted = new ArrayList<>();
        for (String c : sel.cols) {
            quoted.add(quoteCol(c));
        }
        return "INSERT INTO " + quoteCol(w.getTargetDb()) + "." + quoteCol(w.aggTableName())
                + " (" + String.join(", ", quoted) + ")\n" + sel.sql;
    }

    /** 快照内按转换后的号码分组分段；相同号码的所有明细始终落在同一段。 */
    static String sourceBoundary(ArbSource src, String database, String snapshot, String last, int size) {
        String key = mdnExpr(src);
        String where = trim(src.getWhere());
        String range = last == null ? "1" : key + " > " + Sql.quoteString(last);
        if (!where.isEmpty()) range = "(" + where + ") AND (" + range + ")";
        return "SELECT uniqExact(k), max(k) FROM (SELECT " + key + " AS k FROM "
                + quoteCol(database) + "." + quoteCol(snapshot) + " AS " + quoteCol(SRC_ALIAS)
                + " WHERE " + range + " ORDER BY k LIMIT " + size + ")";
    }

    static String keyRange(String expression, String last, String upper) {
        return (last == null ? "" : expression + " > " + Sql.quoteString(last) + " AND ")
                + expression + " <= " + Sql.quoteString(upper);
    }

    static String sourceBatch(ArbWorkflow w, ArbSource src, String snapshot, String destination,
                              String last, String upper, String batch) {
        SourceSelect sel = buildSourceSelect(w, src, w.getTargetDb(), snapshot, keyRange(mdnExpr(src), last, upper));
        List<String> cols = new ArrayList<>();
        for (String col : sel.cols) cols.add(quoteCol(col));
        cols.add(quoteCol(BATCH_COLUMN));
        return "INSERT INTO " + quoteCol(w.getTargetDb()) + "." + quoteCol(destination)
                + " (" + String.join(", ", cols) + ") SELECT *, " + Sql.quoteString(batch) + " FROM (" + sel.sql + ")";
    }

    /** 删除某源的物化视图(重建 MV 前必须先删:同名不能重复创建)。 */
    public static String buildDropMvSql(ArbWorkflow w, ArbSource src) {
        return "DROP VIEW IF EXISTS " + quoteCol(w.getTargetDb()) + "." + quoteCol(mvName(w, src));
    }

    /**
     * 删掉整套东西(视图 + 聚合表 + 服务表)。
     *
     * <p>范围是【精确名】:配置里每个源的 mv_&lt;目标表&gt;_&lt;源id&gt;,加本工作流的聚合表与服务表。
     * 这里【绝不能】改成按名字前缀删 —— 目标表 dwd_a 的 "mv_dwd_a_" 同时是目标表 dwd_a_2
     * 那套视图的前缀,按前缀删会把别人正在用的视图删掉。
     *
     * <p>注意也包括【停用源】的视图:停用的源删掉配置后视图还留在库里继续灌数据,一样要清。
     */
    public static List<String> buildDropAllSql(ArbWorkflow w) {
        List<String> out = new ArrayList<>();
        for (ArbSource s : w.getSources()) {
            out.add(buildDropMvSql(w, s));
        }
        out.add("DROP TABLE IF EXISTS " + quoteCol(w.getTargetDb()) + "." + quoteCol(w.aggTableName()));
        // 服务表也是产物的一部分,一起删(否则留一张不再更新的死表)
        out.add("DROP TABLE IF EXISTS " + quoteCol(w.getTargetDb()) + "." + quoteCol(w.svcTableName()));
        return out;
    }

    /**
     * 一次工作流的全部 SQL(预览与执行共用同一份产物)。
     *
     * <p>MV 与回填只对【启用】的源生成,顺序与配置里的源顺序一致。
     */
    public static ArbPlan buildPlan(ArbWorkflow w) {
        validate(w);
        ArbPlan plan = new ArbPlan();
        plan.setAggDdl(buildAggDdl(w));
        plan.setAggTable(w.getTargetDb() + "." + w.aggTableName());
        plan.setSvcDdl(workflowHasSum(w) ? buildSvcDdl(w) : buildSvcViewDdl(w));
        plan.setSvcSelect(buildSvcSelect(w));
        plan.setSvcTable(w.getTargetDb() + "." + w.svcTableName());

        for (ArbColumn c : w.getColumns()) {
            ArbPlanColumn pc = new ArbPlanColumn();
            pc.setName(c.getName());
            pc.setMode(trim(c.getMode()));
            pc.setStateType(stateType(c, srcTypeForColumn(w, c.getName())));
            pc.setRead(readExpr(c));
            plan.getColumns().add(pc);
        }
        for (ArbSource s : w.getSources()) {
            if (!s.isEnabled()) {
                continue;
            }
            String label = s.getDatabase() + "." + s.getTable() + " (pri=" + s.getPri() + ")";
            plan.getMvs().add(new ArbStmt(label, buildMvSql(w, s)));
            plan.getBackfills().add(new ArbStmt(label, buildBackfillSql(w, s)));
        }
        plan.setWarnings(warnings(w));
        return plan;
    }

    // =====================================================================
    // 风险提示
    // =====================================================================

    /**
     * 配得出、但结果可能不是用户想要的组合。
     *
     * <p>这些不拦(用户可能就是要这样),但必须说出来 —— 它们都是「不报错但结果不对」那一类,
     * 事后发现成本很高。
     *
     * <p>输出顺序是固定的(同一条配置每次生成同样的提示),前端把 warnings 直接渲染成一列,
     * 顺序抖动会让用户以为配置变了。
     */
    public static List<String> warnings(ArbWorkflow w) {
        List<String> out = new ArrayList<>();

        // 1. 数值列做仲裁:值会被转成字符串,后续圈选时字符串比较会出错('9' > '100')
        for (ArbColumn c : w.getColumns()) {
            if (!ArbColumn.MODE_ARBITRATE.equals(trim(c.getMode()))) {
                continue;
            }
            String base = unwrapType(srcTypeForColumn(w, c.getName()));
            if (!base.isEmpty() && !base.startsWith("String") && !base.startsWith("FixedString")
                    && !"Date".equals(base) && !"Date32".equals(base) && !base.startsWith("DateTime")) {
                out.add(String.format("目标列 %s 是数值/其他类型(%s)却按仲裁取值:仲裁列一律按字符串存,"
                                + "后续按范围筛选时字符串比较会出错(例如 '9' > '100' 成立)。数值列建议改成 sum/max/min。",
                        c.getName(), base));
            }
        }

        // 2. sum 列:源表若是「全量重灌」型同步,每灌一次就翻一倍
        if (workflowHasSum(w)) {
            out.add("含 sum 列:若某张源表的同步策略是【全量】重灌(TRUNCATE 后重写),"
                    + "每次同步都会让 sum 再加一遍,数据会翻倍。发现后执行「重建聚合层」可修正。");
        }

        // 3. 没有时间列 → 仲裁只看非空和优先级
        for (ArbSource s : w.getSources()) {
            if (s.isEnabled() && isBlank(s.getTimeCol())) {
                out.add(String.format("源 %s 没有配时间列:该源的仲裁不比较更新时间,只比非空和优先级。",
                        s.getTable()));
            }
        }

        // 4. 同优先级的源之间会「并列」:先比时间,时间也一样就按值的字节序取最大。
        //
        // 并列不是错误(每次重建结果都一样,不会抖),但胜者业务上无意义 ——
        // 用户看到的是「两个源都有值、优先级也一样,凭什么选了他」。这种事必须提前说清,
        // 因为事后查不出来(库里只有结果,没有"为什么是它")。
        //
        // 注意:没有仲裁列时优先级根本不参与,不提示(免得给 sum/max 的工作流白报一堆)。
        int arbCols = 0;
        int priRuleCols = 0;
        for (ArbColumn c : w.getColumns()) {
            if (!ArbColumn.MODE_ARBITRATE.equals(trim(c.getMode()))) {
                continue;
            }
            arbCols++;
            // 取值方式=「只看优先级」的列不理时间列,pri 一样就必然并列
            if (ArbColumn.RULE_PRI.equals(ruleChecked(c))) {
                priRuleCols++;
            }
        }
        List<List<ArbSource>> groups = equalPriGroups(w);
        if (arbCols > 0) {
            for (List<ArbSource> grp : groups) {
                List<String> names = new ArrayList<>();
                int noTime = 0;
                for (ArbSource s : grp) {
                    names.add(s.getTable());
                    if (isBlank(s.getTimeCol())) {
                        noTime++;
                    }
                }
                String msg = String.format("源 %s 的优先级相同(pri=%d):它们之间只比时间列的新旧;"
                                + "时间也一样时按【值的字节序】取最大(例如 '王五' > '张三'、'9' > '10'),"
                                + "业务上等于在这几行里任取一个。想让每个字段的来源确定,就把各来源的优先级配成不一样的。",
                        String.join("、", names), grp.get(0).getPri());
                if (noTime == grp.size()) {
                    msg += " 这几个源都没配时间列,串到同一个号码上就【必然】并列。";
                }
                out.add(msg);
            }
        }
        if (arbCols > 0 && priRuleCols > 0 && !groups.isEmpty()) {
            out.add(String.format("有 %d 个目标列的取值方式选了「只看优先级」:这类列不理时间列,"
                    + "只要来源优先级相同就必然并列(按值的字节序取最大)。", priRuleCols));
        }
        return out;
    }

    /**
     * 优先级相同的启用源分组(每组的源数 &gt;= 2,单个源谈不上并列)。
     *
     * <p>按「首次出现的 pri」顺序输出、组内保持源的原顺序 —— 同一份配置每次生成的提示顺序一致,
     * 免得界面上告警忽上忽下。用 LinkedHashMap 归组,不排序。
     */
    static List<List<ArbSource>> equalPriGroups(ArbWorkflow w) {
        Map<Integer, List<ArbSource>> byPri = new LinkedHashMap<>();
        for (ArbSource s : w.getSources()) {
            if (!s.isEnabled()) {
                continue;
            }
            byPri.computeIfAbsent(s.getPri(), k -> new ArrayList<>()).add(s);
        }
        List<List<ArbSource>> out = new ArrayList<>();
        for (List<ArbSource> g : byPri.values()) {
            if (g.size() > 1) {
                out.add(g);
            }
        }
        return out;
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    /** 取字段的目标列名(空 = 源列名)。 */
    static String outNameOf(ArbField f) {
        String out = trim(f.getOutName());
        return out.isEmpty() ? f.getCol() : out;
    }

    /**
     * 目标列类型需要一个「代表源列类型」:多源同列时取第一个能用的。
     *
     * <p>⚠️ 顺序敏感:同一目标列有多条映射且来源类型不一致时,最终列类型由这里选的
     * 「第一条非空类型」决定 —— 校验里的类型一致性检查会先把不一致的组合拦下来,
     * 所以走到生成这一步时它们是相等的。
     */
    static String srcTypeForColumn(ArbWorkflow w, String col) {
        for (ArbField f : w.getFields()) {
            if (outNameOf(f).equals(col) && !isBlank(f.getColType())) {
                return f.getColType();
            }
        }
        return "";
    }

    /** 反引号引用标识符(内部反引号翻倍转义)。 */
    private static String quoteCol(String s) {
        return Sql.quoteCol(s);
    }

    /** trim,null 安全。 */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 去空白后是否为空。 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
