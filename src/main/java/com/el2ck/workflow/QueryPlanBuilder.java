/**
 * 【文件职责】把工作流查询/映射配置编译为 SQL，并记录必须在应用侧执行的程序算子计划。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.workflow;

import com.el2ck.entity.query.ProgColumn;
import com.el2ck.entity.query.QueryPipelineArg;
import com.el2ck.entity.query.QueryPipelineStep;
import com.el2ck.entity.queryworkflow.QueryWorkflowConfig;
import com.el2ck.entity.queryworkflow.QueryWorkflowFieldRule;
import com.el2ck.entity.queryworkflow.QueryWorkflowJoinOn;
import com.el2ck.entity.queryworkflow.QueryWorkflowMdnRule;
import com.el2ck.entity.queryworkflow.QueryWorkflowOrder;
import com.el2ck.entity.queryworkflow.QueryWorkflowSource;
import com.el2ck.entity.queryworkflow.QueryWorkflowWhere;
import com.el2ck.exception.ApiException;
import com.el2ck.util.GoFmt;
import com.el2ck.util.Sql;

import java.util.ArrayList;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询工作流 SQL 生成器(纯函数,不连接数据库),移植自 Go
 * {@code internal/sync/engine_query_workflow.go} 的 {@code BuildQueryPlan}。
 *
 * <p>输入:向导 4 步的完整配置;输出:一条可执行的 ClickHouse SELECT + 程序算子后处理计划。
 *
 * <p>设计原则:
 * <ul>
 *   <li>完全无状态:只做字符串拼接,不碰 ClickHouse,不碰元数据库;</li>
 *   <li>配置里的库名 / 表名 / 列名一律先过 {@link Sql#validIdent} 再引用,函数名走白名单,
 *       操作符走白名单,值一律转义 —— config 直接来自 HTTP 请求体,任何直通路径都是注入面;</li>
 *   <li>所有错误文案与 Go 逐字一致(含引号形式),便于两端对照排查。</li>
 * </ul>
 *
 * <p>生成结果形如:
 * <pre>
 * WITH s0 AS (SELECT ..., pipeline(mdn_raw) AS mdn_norm, ... FROM `ods_data`.`A`),
 *      s1 AS (SELECT pipeline(phone) AS mdn_norm, ... FROM `ods_data`.`B`)
 * SELECT a.mdn_norm, a.age, b.sex, b.amount
 * FROM s0 AS a LEFT JOIN s1 AS b ON a.mdn_norm = b.mdn_norm
 * WHERE a.age &gt; 18
 * ORDER BY b.amount DESC
 * LIMIT 100
 * </pre>
 */
public final class QueryPlanBuilder {

    /** 工具类私有构造器:禁止实例化。 */
    private QueryPlanBuilder() {
    }

    /** 归一化后的 mdn 列别名常量(向导步骤②约定使用)。 */
    public static final String MDN_ALIAS = "mdn_norm";

    /**
     * 处理链函数白名单:只允许这些函数名出现在 steps/pipeline,避免 SQL 注入。
     * 与前端 CHFunctionBrowser 的函数列表对齐。
     *
     * <p>值的形式有三种:
     * <ol>
     *   <li>纯函数名 {@code "upper"} → {@code upper(输入)}</li>
     *   <li>单参复合模板 {@code "hex(MD5(%s))"} → {@code hex(MD5(输入))}</li>
     *   <li>带参模板 {@code "substring(%s, %s, %s)"} → {@code substring(输入, 参数1, 参数2)}</li>
     * </ol>
     * 带参模板里 {@code %s} 的个数 - 1 = 该算子需要的额外参数个数。
     */
    private static final Map<String, String> ALLOWED_FUNCTIONS = new LinkedHashMap<String, String>();

    /** 不需要参数的算子 —— 链式里传参会生成 {@code today(col)},非法。 */
    private static final Set<String> ZERO_ARG_FUNCS = new LinkedHashSet<String>(Arrays.asList("today", "now"));

    /**
     * 必须带额外参数、但语义上不适合单输入链式的算子 —— 直接拒绝,给出可读错误
     * 而不是把非法 SQL 丢给 ClickHouse。
     */
    private static final Set<String> ARG_REQUIRED_FUNCS =
            new LinkedHashSet<String>(Arrays.asList("if", "multi_if", "date_diff", "concat_ws"));

    /** 聚合函数白名单 —— 防止 agg 裸拼接造成 SQL 注入。 */
    private static final Map<String, String> ALLOWED_AGGS = new LinkedHashMap<String, String>();

    /** WHERE 操作符白名单(键为前端传入值,小写)—— 防止 op 裸拼接造成 SQL 注入。 */
    private static final Map<String, String> WHERE_OPS = new LinkedHashMap<String, String>();

    /** JOIN 类型白名单:joinType 会原样进 SQL,必须白名单。 */
    private static final Map<String, String> JOIN_TYPES = new LinkedHashMap<String, String>();

    /** 校验 ON 的一侧:{@code 别名.标识符}(别名是 a/b/c…,列名走标识符规则)。 */
    private static final Pattern JOIN_COL_RE = Pattern.compile("^([a-z][0-9]*)\\.([A-Za-z_][A-Za-z0-9_]*)$");

    static {
        // ── 字符串:单参 ──
        ALLOWED_FUNCTIONS.put("length", "length");                // 字节数
        ALLOWED_FUNCTIONS.put("length_utf8", "lengthUTF8");       // 字符数(中文用这个)
        ALLOWED_FUNCTIONS.put("upper", "upper");
        ALLOWED_FUNCTIONS.put("lower", "lower");
        ALLOWED_FUNCTIONS.put("trim", "trim");
        ALLOWED_FUNCTIONS.put("trim_left", "trimLeft");
        ALLOWED_FUNCTIONS.put("trim_right", "trimRight");
        ALLOWED_FUNCTIONS.put("reverse", "reverse");
        // ── 字符串:带参 ──
        ALLOWED_FUNCTIONS.put("substring", "substring(%s, %s, %s)");          // [起始位置(从1), 长度]
        ALLOWED_FUNCTIONS.put("left", "left(%s, %s)");                        // [长度]
        ALLOWED_FUNCTIONS.put("right", "right(%s, %s)");                      // [长度]
        ALLOWED_FUNCTIONS.put("replace", "replace(%s, %s, %s)");              // [被替换, 替换为]
        ALLOWED_FUNCTIONS.put("replace_regexp", "replaceRegexpOne(%s, %s, %s)"); // [正则, 替换为(可 $1 引用)]
        ALLOWED_FUNCTIONS.put("regexp_replace_all", "replaceRegexpAll(%s, %s, %s)"); // [正则, 替换为]
        ALLOWED_FUNCTIONS.put("regexp_extract", "extract(%s, %s)");           // [正则(取第一个捕获组)]
        ALLOWED_FUNCTIONS.put("starts_with", "startsWith(%s, %s)");           // [前缀]
        ALLOWED_FUNCTIONS.put("ends_with", "endsWith(%s, %s)");               // [后缀]
        ALLOWED_FUNCTIONS.put("match", "match(%s, %s)");                      // [正则]
        ALLOWED_FUNCTIONS.put("concat", "concat(%s, %s)");                    // [追加串]
        ALLOWED_FUNCTIONS.put("concat_ws", "concat_ws(%s, %s)");              // [分隔符](实际被 ARG_REQUIRED 拒绝)
        ALLOWED_FUNCTIONS.put("left_pad", "leftPad(%s, %s, %s)");             // [总长度, 填充字符]
        ALLOWED_FUNCTIONS.put("right_pad", "rightPad(%s, %s, %s)");           // [总长度, 填充字符]
        // ── 哈希 ──
        // ⚠️ MD5()/SHA256() 等返回 FixedString 二进制,不能直接展示或比对;
        //    要十六进制必须套 hex() —— 用下面 hex_* 这组。
        ALLOWED_FUNCTIONS.put("md5", "MD5");
        ALLOWED_FUNCTIONS.put("sha256", "SHA256");
        ALLOWED_FUNCTIONS.put("hex_md5", "hex(MD5(%s))");                     // 32 位
        ALLOWED_FUNCTIONS.put("hex_sha1", "hex(SHA1(%s))");                   // 40 位
        ALLOWED_FUNCTIONS.put("hex_sha224", "hex(SHA224(%s))");               // 56 位
        ALLOWED_FUNCTIONS.put("hex_sha256", "hex(SHA256(%s))");               // 64 位
        ALLOWED_FUNCTIONS.put("hex_sha384", "hex(SHA384(%s))");               // 96 位
        ALLOWED_FUNCTIONS.put("hex_sha512", "hex(SHA512(%s))");               // 128 位
        ALLOWED_FUNCTIONS.put("hex_sha512_256", "hex(SHA512_256(%s))");       // 64 位
        ALLOWED_FUNCTIONS.put("hex_md4", "hex(MD4(%s))");                     // 32 位(已不安全,兼容老数据)
        ALLOWED_FUNCTIONS.put("hex_ripemd160", "hex(RIPEMD160(%s))");         // 40 位
        ALLOWED_FUNCTIONS.put("hex_blake3", "hex(BLAKE3(%s))");               // 64 位
        // 注:ClickHouse 无 SM3 / SM4(实测 system.functions 无此函数,encrypt 也不支持该 mode)
        // ── 类型转换 ──
        ALLOWED_FUNCTIONS.put("to_string", "toString");
        ALLOWED_FUNCTIONS.put("to_int8", "toInt8");
        ALLOWED_FUNCTIONS.put("to_int16", "toInt16");
        ALLOWED_FUNCTIONS.put("to_int32", "toInt32");
        ALLOWED_FUNCTIONS.put("to_int64", "toInt64");
        ALLOWED_FUNCTIONS.put("to_uint32", "toUInt32");
        ALLOWED_FUNCTIONS.put("to_uint64", "toUInt64");
        ALLOWED_FUNCTIONS.put("to_float64", "toFloat64");
        ALLOWED_FUNCTIONS.put("cast_nullable", "accurateCastOrNull(%s, %s)"); // [目标类型, 如 Int64]
        // ── 空值 ──
        ALLOWED_FUNCTIONS.put("if_null", "ifNull(%s, %s)");                   // [为空时的默认值]
        ALLOWED_FUNCTIONS.put("coalesce", "coalesce(%s, %s)");                // [第二候选]
        ALLOWED_FUNCTIONS.put("assume_nn", "assumeNotNull");
        // ── 条件(语义上不是单输入链式,保持拒绝) ──
        ALLOWED_FUNCTIONS.put("if", "if");
        ALLOWED_FUNCTIONS.put("multi_if", "multiIf");
        // ── 日期 / 时间 ──
        ALLOWED_FUNCTIONS.put("to_date", "toDate");
        ALLOWED_FUNCTIONS.put("to_datetime", "toDateTime");
        ALLOWED_FUNCTIONS.put("to_year", "toYear");
        ALLOWED_FUNCTIONS.put("to_month", "toMonth");
        ALLOWED_FUNCTIONS.put("to_day", "toDayOfMonth");
        ALLOWED_FUNCTIONS.put("to_hour", "toHour");
        ALLOWED_FUNCTIONS.put("to_start_of_day", "toStartOfDay");
        ALLOWED_FUNCTIONS.put("to_start_of_month", "toStartOfMonth");
        ALLOWED_FUNCTIONS.put("to_start_of_week", "toStartOfWeek");
        ALLOWED_FUNCTIONS.put("to_yyyy_mm", "toYYYYMM");                      // 202401(数值,适合分组)
        ALLOWED_FUNCTIONS.put("to_yyyy_mm_dd", "toYYYYMMDD");
        ALLOWED_FUNCTIONS.put("today", "today");
        ALLOWED_FUNCTIONS.put("now", "now64");
        ALLOWED_FUNCTIONS.put("format_dt", "formatDateTime(%s, %s)");         // [格式, 如 %Y-%m-%d]
        ALLOWED_FUNCTIONS.put("date_diff", "dateDiff");
        // ── 数学:单参 ──
        ALLOWED_FUNCTIONS.put("abs", "abs");
        ALLOWED_FUNCTIONS.put("negate", "negate");                            // 取负
        ALLOWED_FUNCTIONS.put("sqrt", "sqrt");
        ALLOWED_FUNCTIONS.put("cbrt", "cbrt");
        ALLOWED_FUNCTIONS.put("exp", "exp");
        ALLOWED_FUNCTIONS.put("ln", "ln");
        ALLOWED_FUNCTIONS.put("log2", "log2");
        ALLOWED_FUNCTIONS.put("log10", "log10");
        ALLOWED_FUNCTIONS.put("sign", "sign");                                // -1 / 0 / 1
        ALLOWED_FUNCTIONS.put("floor", "floor");
        ALLOWED_FUNCTIONS.put("ceil", "ceil");
        ALLOWED_FUNCTIONS.put("round", "round");
        ALLOWED_FUNCTIONS.put("trunc", "trunc");                              // 截断小数
        // ── 数学:带参 ──
        ALLOWED_FUNCTIONS.put("round_to", "round(%s, %s)");                   // [保留小数位]
        ALLOWED_FUNCTIONS.put("truncate_to", "truncate(%s, %s)");             // [保留小数位,不四舍五入]
        ALLOWED_FUNCTIONS.put("pow", "pow(%s, %s)");                          // [指数](注意 ^ 在 CK 里不是乘方)
        ALLOWED_FUNCTIONS.put("int_div", "intDiv(%s, %s)");                   // [除数]
        ALLOWED_FUNCTIONS.put("modulo_op", "modulo(%s, %s)");                 // [除数] 取余
        ALLOWED_FUNCTIONS.put("greatest", "greatest(%s, %s)");                // [比较值] 取较大
        ALLOWED_FUNCTIONS.put("least", "least(%s, %s)");                      // [比较值] 取较小
        ALLOWED_FUNCTIONS.put("bit_and", "bitAnd(%s, %s)");
        ALLOWED_FUNCTIONS.put("bit_or", "bitOr(%s, %s)");
        ALLOWED_FUNCTIONS.put("bit_xor", "bitXor(%s, %s)");
        ALLOWED_FUNCTIONS.put("bit_shift_left", "bitShiftLeft(%s, %s)");
        ALLOWED_FUNCTIONS.put("bit_shift_right", "bitShiftRight(%s, %s)");
        // ── 编码 / 解码 ──
        ALLOWED_FUNCTIONS.put("base64_encode", "base64Encode(%s)");
        ALLOWED_FUNCTIONS.put("base64_decode", "base64Decode(%s)");           // 严格:非法输入报错
        ALLOWED_FUNCTIONS.put("base64_decode_safe", "tryBase64Decode(%s)");   // 宽松:非法输入返回空串
        ALLOWED_FUNCTIONS.put("base64url_encode", "base64URLEncode(%s)");     // URL 安全字符集(- _ 代替 + /)
        ALLOWED_FUNCTIONS.put("base64url_decode", "tryBase64URLDecode(%s)");
        // 合成演示数据的 AES-256-ECB/Base64 密文可在 JOIN 前解密为号码。
        ALLOWED_FUNCTIONS.put("aes256_dec", "decrypt('aes-256-ecb', base64Decode(%s), %s)");
        // CBC 密文使用单独的 16 字节 IV，密文仍以 Base64 存储。
        ALLOWED_FUNCTIONS.put("aes256_cbc_dec", "decrypt('aes-256-cbc', base64Decode(%s), %s, %s)");
        // ── 数组 ──
        ALLOWED_FUNCTIONS.put("array_length", "length");

        ALLOWED_AGGS.put("count", "count");       // 非空计数 count(x)
        ALLOWED_AGGS.put("uniqExact", "uniqExact");
        ALLOWED_AGGS.put("uniq", "uniq");
        ALLOWED_AGGS.put("sum", "sum");
        ALLOWED_AGGS.put("avg", "avg");
        ALLOWED_AGGS.put("max", "max");
        ALLOWED_AGGS.put("min", "min");
        ALLOWED_AGGS.put("median", "median");
        ALLOWED_AGGS.put("groupArray", "groupArray");
        ALLOWED_AGGS.put("any", "any");
        ALLOWED_AGGS.put("anyLast", "anyLast");

        WHERE_OPS.put("=", "=");
        WHERE_OPS.put("!=", "!=");
        WHERE_OPS.put("<>", "!=");
        WHERE_OPS.put(">", ">");
        WHERE_OPS.put(">=", ">=");
        WHERE_OPS.put("<", "<");
        WHERE_OPS.put("<=", "<=");
        WHERE_OPS.put("like", "LIKE");
        WHERE_OPS.put("not_like", "NOT LIKE");
        WHERE_OPS.put("in", "IN");
        WHERE_OPS.put("not_in", "NOT IN");
        WHERE_OPS.put("not in", "NOT IN");            // 容忍带空格的写法
        WHERE_OPS.put("between", "BETWEEN");
        WHERE_OPS.put("is_null", "IS NULL");
        WHERE_OPS.put("is_not_null", "IS NOT NULL");
        // 容忍带空格的写法:'IS NULL' / 'IS NOT NULL'(老配置 / 直传 API 会这么写)。
        // 不做这个兜底时它会走到 whereCondFilled 的 default 分支被当成「未填值」跳过,
        // 条件静默消失、结果变多。
        WHERE_OPS.put("is null", "IS NULL");
        WHERE_OPS.put("is not null", "IS NOT NULL");

        JOIN_TYPES.put("LEFT", "LEFT");
        JOIN_TYPES.put("INNER", "INNER");
        JOIN_TYPES.put("RIGHT", "RIGHT");
        JOIN_TYPES.put("FULL", "FULL");
        JOIN_TYPES.put("CROSS", "CROSS");
        JOIN_TYPES.put("LEFT OUTER", "LEFT OUTER");
        JOIN_TYPES.put("RIGHT OUTER", "RIGHT OUTER");
        JOIN_TYPES.put("FULL OUTER", "FULL OUTER");
    }

    /**
     * 生成 SQL + 程序算子后处理计划。
     *
     * <p>程序算子(prog)不进入 SQL —— 它们在结果返回后由本程序逐行计算,
     * 因此 ClickHouse 不知道这些列的值,不能用于过滤 / 分组 / 排序(本方法会校验并报错)。
     */
    public static QueryPlan build(QueryWorkflowConfig cfg) {
        if (cfg == null) {
            throw ApiException.validation("配置无效: 配置不能为空");
        }
        if (cfg.getSources() == null || cfg.getSources().isEmpty()) {
            throw ApiException.validation("至少选一张源表");
        }
        if (cfg.getFields() == null || cfg.getFields().isEmpty()) {
            throw ApiException.validation("至少选一个输出字段");
        }
        List<QueryWorkflowSource> sources = cfg.getSources();

        // ⚠️ 所有源表必须属于【同一个连接】。
        //    生成的 SQL 里各表都不带连接限定(连接是执行期按 sources[0] 选的),
        //    跨连接的配置会静默地在第一个连接上解析 ——
        //    要么报「表不存在」,要么**查到同名表返回错数据**。
        //    放在生成器里是因为 validate / preview / execute / export 都会经过这里。
        if (sources.size() > 1) {
            long first = sources.get(0).getConnectionId();
            for (int i = 0; i < sources.size(); i++) {
                if (sources.get(i).getConnectionId() != first) {
                    throw ApiException.validation(String.format(
                            "第 %d 张源表属于连接 %d,与第 1 张(连接 %d)不一致 —— 多表关联要求所有源表在同一连接下",
                            i + 1, sources.get(i).getConnectionId(), first));
                }
            }
        }

        // mdn 归一化改为【可选】:按 sourceIdx 建索引。
        // 单表查询允许不配(不注入 mdn_norm 列);多表 JOIN 仍要求每张表都配。
        Map<Integer, QueryWorkflowMdnRule> mdnRuleBySrc = new HashMap<Integer, QueryWorkflowMdnRule>();
        if (cfg.getMdnRules() != null) {
            for (QueryWorkflowMdnRule rule : cfg.getMdnRules()) {
                // ⚠️ 越界以前被静默忽略(规则石沉大海),重复下标前者被后者覆盖 —— 都要显式报错
                if (rule.getSourceIdx() < 0 || rule.getSourceIdx() >= sources.size()) {
                    throw ApiException.validation(String.format(
                            "mdn 归一化规则的来源表索引 %d 越界(共 %d 张源表)", rule.getSourceIdx(), sources.size()));
                }
                if (mdnRuleBySrc.containsKey(rule.getSourceIdx())) {
                    throw ApiException.validation(String.format(
                            "第 %d 张源表配置了多条 mdn 归一化规则", rule.getSourceIdx() + 1));
                }
                mdnRuleBySrc.put(rule.getSourceIdx(), rule);
            }
        }
        if (sources.size() > 1) {
            for (int i = 0; i < sources.size(); i++) {
                if (!mdnRuleBySrc.containsKey(i)) {
                    throw ApiException.validation(String.format(
                            "多表 JOIN 时每张源表都必须配置 mdn 归一化(%d 张中第 %d 张未配)", sources.size(), i + 1));
                }
            }
        }

        // 1. 为每张源表生成 CTE:清洗 + mdn 归一化 + 字段选择
        List<String> ctes = new ArrayList<String>(sources.size());
        String[] mdnAliases = new String[sources.size()]; // 各源表的 mdn 别名(在 CTE 内统一命名为 mdn_norm)
        for (int i = 0; i < sources.size(); i++) {
            QueryWorkflowSource src = sources.get(i);
            if (isBlank(src.getDatabase()) || isBlank(src.getTable())) {
                throw ApiException.validation(String.format("第 %d 张源表未指定库/表", i + 1));
            }
            if (!Sql.validIdent(src.getDatabase()) || !Sql.validIdent(src.getTable())) {
                throw ApiException.validation(String.format("第 %d 张源表库/表名非法", i + 1));
            }
            // 字段选择(支持重命名)
            List<String> selParts = buildSourceSelectList(src);
            // mdn 归一化列(统一命名为 mdn_norm 供 JOIN 用)。
            // 仅当该源表配了 mdn 规则时才注入 —— 单表查询可不配。
            QueryWorkflowMdnRule mdnRule = mdnRuleBySrc.get(i);
            if (mdnRule != null) {
                if (isBlank(mdnRule.getSourceCol())) {
                    throw ApiException.validation(String.format("第 %d 张源表未指定 mdn 列", i + 1));
                }
                String mdnExpr;
                try {
                    mdnExpr = buildPipelineExpr(mdnRule.getSourceCol(),
                            normalizeSteps(mdnRule.getPipeline(), mdnRule.getSteps()));
                } catch (ApiException e) {
                    throw ApiException.validation(String.format("源表 %d mdn 归一化失败: %s", i + 1, e.getMessage()));
                }
                selParts.add(mdnExpr + " AS " + MDN_ALIAS);
                mdnAliases[i] = MDN_ALIAS;
            }

            // ⚠️ whereFilter 是【裸拼进 SQL】的:它是 HTTP 请求体里的字符串,也能被存库后
            //    反复执行,构成一个完整的 SQL 注入面。实测下列 payload 会原样进入 CTE,
            //    外层 SELECT 即可把任意表的任意列带出来:
            //      whereFilter = "1=1 UNION ALL SELECT credit_card FROM ods_data.secret"
            //      whereFilter = "1=1) UNION ALL SELECT mdn FROM t WHERE (1=1"   ← 逃出 CTE
            //    前端已无任何代码会写这个字段,所以直接拒绝;保留一个"静默忽略"反而
            //    会让存量配置的结果悄变,宁可直接报错。
            if (!isBlank(src.getWhereFilter())) {
                throw ApiException.validation(String.format(
                        "源表 %d 的 whereFilter 已不再支持(它会被原样拼进 SQL,存在注入风险);请改用过滤条件(where)",
                        i + 1));
            }

            String from = quoteCk(src.getDatabase()) + "." + quoteCk(src.getTable());
            ctes.add(String.format("s%d AS (SELECT %s FROM %s)", i, join(selParts, ", "), from));
        }

        // 2. 输出字段:从各 CTE 选择,经变换 + 可选聚合
        List<String> outParts = new ArrayList<String>(cfg.getFields().size());
        // 程序算子后处理计划(按输出列下标)
        List<ProgColumn> progCols = new ArrayList<ProgColumn>();
        // 「只参与查询、不输出」的字段:别名 → 可用于过滤/排序/聚合后过滤的 SQL 表达式。
        //
        // ⚠️ 为什么需要它:外层查询是 `SELECT <别名> FROM s0 AS a`,被隐藏的字段
        //    不在 SELECT 列表里,过滤/排序再引用它的别名在 ClickHouse 里找不到列。
        //    所以改成引用 CTE 侧的带别名表达式(如 `a`.`city`)。
        Map<String, HiddenRef> hiddenRefs = new HashMap<String, HiddenRef>();
        int visibleFields = 0;
        for (QueryWorkflowFieldRule f : cfg.getFields()) {
            if (isBlank(f.getAlias()) || isBlank(f.getSourceCol())) {
                throw ApiException.validation("输出字段必须指定别名和源列");
            }
            if (!Sql.validIdent(f.getAlias())) {
                throw ApiException.validation(String.format("输出字段别名 %s 非法", GoFmt.q(f.getAlias())));
            }
            if (f.getSourceIdx() < 0 || f.getSourceIdx() >= sources.size()) {
                throw ApiException.validation(String.format(
                        "字段 %s 的来源表索引 %d 越界", f.getAlias(), f.getSourceIdx()));
            }
            List<QueryPipelineStep> steps = normalizeSteps(f.getPipeline(), f.getSteps());
            // 特殊:别名=mdn_norm 时直接引用上游的 mdn_norm(每张源表在 CTE 里统一命名为 mdn_norm)
            if (MDN_ALIAS.equals(f.getAlias())) {
                if (mdnAliases[f.getSourceIdx()] == null) {
                    throw ApiException.validation(String.format(
                            "字段 mdn_norm 引用了第 %d 张源表,但该表未配置 mdn 归一化", f.getSourceIdx() + 1));
                }
                // ⚠️ 这条分支直接引用上游 mdn_norm,不会走下面的变换/聚合/程序算子登记。
                //    以前是静默 continue —— 配了 SM3 也不生效(用户看到明文),必须显式报错。
                if (!steps.isEmpty() || !isBlank(f.getProg()) || !isBlank(f.getAgg())) {
                    throw ApiException.validation(
                            "字段 mdn_norm 直接引用上游归一化列,不能再叠加处理链/聚合/程序算子(请改名或去掉变换)");
                }
                String mdnRef = tableAlias(f.getSourceIdx()) + "." + MDN_ALIAS;
                if (f.isHidden()) {
                    hiddenRefs.put(f.getAlias(), new HiddenRef(mdnRef, mdnRef, false));
                    continue;
                }
                visibleFields++;
                outParts.add(mdnRef + " AS " + quoteCk(MDN_ALIAS));
                continue;
            }
            // steps → 表达式(如 toFloat64(`amount`))
            String expr;
            try {
                expr = buildPipelineExpr(f.getSourceCol(), steps);
            } catch (ApiException e) {
                throw ApiException.validation(String.format("字段 %s 变换失败: %s", f.getAlias(), e.getMessage()));
            }
            // 把列引用替换成带表别名:toFloat64(`amount`) → toFloat64(a.`amount`)
            String colRef = tableAlias(f.getSourceIdx()) + "." + quoteCk(f.getSourceCol());
            String base = replaceColRef(expr, f.getSourceCol(), colRef);
            // 聚合与处理链【可组合】:sum(toFloat64(a.`amount`))
            String finalExpr = base;
            String agg = f.getAgg() == null ? "" : f.getAgg();
            if (agg.isEmpty()) {
                // 不聚合,原样输出
            } else if ("countAll".equals(agg)) {
                // ⚠️ count() 不引用列,处理链/程序算子的值根本参与不进来。
                //    以前这里直接丢弃它们 —— 用户以为在统计「(处理后的)非空值」,
                //    实际数的是全部行数,而且一声不响。与 mdn_norm 分支保持同一套做法:报错。
                if (!steps.isEmpty() || !isBlank(f.getProg())) {
                    throw ApiException.validation(String.format(
                            "字段 %s 用了「计数(行数)countAll」,它数的是行数,不能再叠加处理链/程序算子;"
                                    + "想统计处理后的非空值请改用「计数(非空)count」", f.getAlias()));
                }
                finalExpr = "count()"; // 数行数,不引用列
            } else {
                if (!ALLOWED_AGGS.containsKey(agg)) {
                    throw ApiException.validation(String.format("不支持的聚合函数 %s", GoFmt.q(agg)));
                }
                finalExpr = ALLOWED_AGGS.get(agg) + "(" + base + ")";
            }
            // 程序算子:校验后记录下来 —— 值由本程序算,不生成任何 SQL
            if (!isBlank(f.getProg())) {
                // ⚠️ 隐藏字段的结果不进结果集,程序算子算完也无处可放 —— 显式报错,
                //    不能静默丢弃(用户配了脱敏却看不到效果,比报错危险)。
                if (f.isHidden()) {
                    throw ApiException.validation(String.format(
                            "字段 %s 已设为「不输出」,不能再配程序算子(程序算子作用于结果列);请取消隐藏或去掉程序算子",
                            f.getAlias()));
                }
                ProgOps.validate(f.getAlias(), f.getProg());
                ProgColumn progCol = new ProgColumn();
                // 下标 = 该字段在 SELECT 列表里的位置,必须在 append 之前取
                progCol.setIndex(outParts.size());
                progCol.setAlias(f.getAlias());
                progCol.setOps(f.getProg());
                progCols.add(progCol);
            }
            if (f.isHidden()) {
                // base 用于 WHERE/ORDER BY(不能带聚合),final 用于 HAVING/ORDER BY
                hiddenRefs.put(f.getAlias(), new HiddenRef(base, finalExpr, !agg.isEmpty()));
                continue;
            }
            visibleFields++;
            outParts.add(finalExpr + " AS " + quoteCk(f.getAlias()));
        }
        // 全部字段都被隐藏 → 没有任何输出列,SQL 直接报错;提前给清楚的提示。
        if (visibleFields == 0) {
            throw ApiException.validation("至少要保留一个输出字段(当前所有字段都设成了「不输出」)");
        }

        // 3. JOIN 拼装
        String fromClause = buildJoinClause(cfg);

        // 「字段去重」是结果操作，不拼进 WHERE/HAVING。所有入口合并为一组去重键。
        Set<String> conditionDistinctKeys = new LinkedHashSet<String>();
        for (List<QueryWorkflowWhere> conditions : Arrays.asList(cfg.getWhere(), cfg.getHaving())) {
            if (conditions == null) continue;
            for (QueryWorkflowWhere condition : conditions) {
                if (condition != null && "distinct".equalsIgnoreCase(trim(condition.getOp()))
                        && !isBlank(condition.getColumn())) {
                    conditionDistinctKeys.add(condition.getColumn());
                }
            }
        }

        // 4. WHERE(操作符白名单 + 值转义 + 时间预设)
        String whereClause = "";
        if (cfg.getWhere() != null && !cfg.getWhere().isEmpty()) {
            List<String> parts = new ArrayList<String>(cfg.getWhere().size());
            for (QueryWorkflowWhere w : cfg.getWhere()) {
                if (w == null || "distinct".equalsIgnoreCase(trim(w.getOp()))) continue;
                if (isBlank(w.getColumn())) {
                    continue;
                }
                // 用户刚点「添加条件」、还没填值的中间状态 → 跳过。
                // 不跳的话有两类坏事:数值列直接 400(连预览都看不到),
                // 字符串列更隐蔽 —— 会生成 `col = ''`(静默筛成空结果)。
                if (!whereCondFilled(w)) {
                    continue;
                }
                if (!Sql.validIdent(w.getColumn())) {
                    throw ApiException.validation(String.format("过滤字段 %s 非法", GoFmt.q(w.getColumn())));
                }
                String colExpr = quoteCk(w.getColumn());
                // 「不输出」的字段:它不在 SELECT 里,改写为 CTE 侧带别名表达式
                HiddenRef hr = hiddenRefs.get(w.getColumn());
                if (hr != null) {
                    if (hr.agg) {
                        throw ApiException.validation(String.format(
                                "字段 %s 带聚合函数,不能用在过滤条件(WHERE)里;请改用聚合后过滤(HAVING)", GoFmt.q(w.getColumn())));
                    }
                    colExpr = hr.base;
                }
                parts.add(buildWhereCondOn(w, colExpr));
            }
            if (!parts.isEmpty()) {
                whereClause = " WHERE " + join(parts, " AND ");
            }
        }

        // 可用于 GROUP BY / ORDER BY 的列名集合。
        //
        // ⚠️ 不能只校验「输出别名」:外层查询是 `SELECT <别名> FROM s0 AS a`,
        //    而 s0(CTE)还暴露源列与 mdn_norm —— ClickHouse 允许按未选出的列分组。
        //    真正会失败的是引用了【哪里都不存在】的列(如老配置 groupByMdn=true 但没配 mdn 规则)。
        Set<String> availableCols = new LinkedHashSet<String>();
        for (QueryWorkflowFieldRule f : cfg.getFields()) {
            if (!isBlank(f.getAlias())) {
                availableCols.add(f.getAlias());
            }
        }
        for (int i = 0; i < sources.size(); i++) {
            QueryWorkflowSource src = sources.get(i);
            if (src.getColumns() != null) {
                for (String c : src.getColumns()) {
                    String newName = src.getRename() == null ? null : src.getRename().get(c);
                    if (!isBlank(newName)) {
                        availableCols.add(newName);
                    } else {
                        availableCols.add(c);
                    }
                }
            }
            if (mdnAliases[i] != null) {
                availableCols.add(MDN_ALIAS);
            }
        }

        // 5. GROUP BY(按【输出字段别名】分组,如 city / status)
        List<String> groupBy = cfg.getGroupBy() == null ? new ArrayList<String>() : cfg.getGroupBy();
        if (groupBy.isEmpty() && cfg.isGroupByMdn()) {
            groupBy = new ArrayList<String>(Arrays.asList(MDN_ALIAS)); // 向后兼容旧配置
        }
        String groupClause = "";
        if (!groupBy.isEmpty()) {
            List<String> parts = new ArrayList<String>(groupBy.size());
            for (String g : groupBy) {
                if (!Sql.validIdent(g)) {
                    throw ApiException.validation(String.format("分组字段 %s 非法", GoFmt.q(g)));
                }
                if (!availableCols.contains(g)) {
                    throw ApiException.validation(String.format(
                            "分组字段 %s 不在输出字段中(请先在字段列表里添加它)", GoFmt.q(g)));
                }
                parts.add(quoteCk(g));
            }
            groupClause = " GROUP BY " + join(parts, ", ");
        }

        // 5.2 去重键 → ClickHouse 的 LIMIT 1 BY:按 key 各保留一行,其它列原样带出来。
        //
        // 为什么不用 DISTINCT:SELECT DISTINCT 只能"对输出列整体去重",想把 mdn 之外的
        // 列原值带出来就做不到(要么每列都进 DISTINCT 的键,要么用 GROUP BY + any() 逐列配)。
        // LIMIT 1 BY 就是为这个场景设计的:按 key 取前 N 行,其它列原样。
        String limitByClause = "";
        Set<String> distinctKeys = new LinkedHashSet<String>(conditionDistinctKeys);
        if (cfg.getDistinctKeys() != null) distinctKeys.addAll(cfg.getDistinctKeys());
        if (!distinctKeys.isEmpty()) {
            if (!groupBy.isEmpty() && cfg.getDistinctKeys() != null && !cfg.getDistinctKeys().isEmpty()) {
                throw ApiException.validation(
                        "「去重」与「汇总统计(分组)」不能同时开:去重是按 key 保留一行明细,分组是把其它列折叠成聚合值");
            }
            List<String> parts = new ArrayList<String>(distinctKeys.size());
            for (String k : distinctKeys) {
                if (!Sql.validIdent(k)) {
                    throw ApiException.validation(String.format("去重键 %s 非法", GoFmt.q(k)));
                }
                if (!availableCols.contains(k)) {
                    throw ApiException.validation(String.format(
                            "去重键 %s 不在输出字段中(请先在字段列表里把它加进来)", GoFmt.q(k)));
                }
                // 隐藏字段仍可用于去重，使用与 ORDER BY 一致的实际表达式。
                HiddenRef hr = hiddenRefs.get(k);
                parts.add(hr == null ? quoteCk(k) : hr.finalExpr);
            }
            limitByClause = " LIMIT 1 BY " + join(parts, ", ");
        }

        // 5.1 聚合与明细混用却没有分组列 —— 提前拦住,别交给 ClickHouse 抱怨
        //
        // ⚠️ 为什么必须拦:SELECT 里既有聚合(sum/count…)又有裸列(如 mdn),
        //    而没有 GROUP BY 时 ClickHouse 报的是
        //      "Column 'a.mdn' is not under aggregate function and not in GROUP BY keys"
        //    —— 只提一个列名,看不出"配置里漏了分组",排查成本很高(实测踩过一次)。
        //
        //    向导界面会按「粒度」自动推导分组,正常不会出现:
        //    粒度选「保持明细」或没有聚合字段时,groupBy 天然为空 —— 但那时也不会有
        //    "聚合字段" 与 "非聚合字段" 混用。所以只有从接口直接来的配置会中。
        //
        // 只看【要输出】的字段:被标记为「不输出」的字段不在 SELECT 里,
        // 它们只参与 WHERE/排序(在聚合之前生效),不需要进分组。
        //
        // ⚠️ 判定条件是「没进分组」而不是「没有分组」—— 只勾了一半分组列同样是坏 SQL
        //    (CK 报 code 215,而且只点名一个列),这里一次性把漏的都列出来。
        List<String> aggAliases = new ArrayList<String>();
        List<String> plainAliases = new ArrayList<String>();
        for (QueryWorkflowFieldRule f : cfg.getFields()) {
            if (f.isHidden()) {
                continue;
            }
            if (!isBlank(f.getAgg())) {
                aggAliases.add(f.getAlias());
            } else {
                plainAliases.add(f.getAlias());
            }
        }
        if (!conditionDistinctKeys.isEmpty() && (!groupBy.isEmpty() || !aggAliases.isEmpty())) {
            for (String key : conditionDistinctKeys) {
                boolean grouped = groupBy.contains(key);
                for (QueryWorkflowFieldRule field : cfg.getFields()) {
                    if (key.equals(field.getAlias()) && !isBlank(field.getAgg())) grouped = true;
                }
                if (!grouped) throw ApiException.validation("去重字段 " + GoFmt.q(key)
                        + " 不在聚合结果中，请将其加入分组或设置聚合函数");
            }
        }
        // 全字段都带聚合(如只有一个 countAll)时 groupBy 天然为空,这是合法的 ——
        // 所以只在"混用"时报错。
        if (!aggAliases.isEmpty() && !plainAliases.isEmpty()) {
            Set<String> inGroup = new LinkedHashSet<String>(groupBy);
            List<String> missing = new ArrayList<String>();
            for (String p : plainAliases) {
                if (!inGroup.contains(p)) {
                    missing.add(p);
                }
            }
            if (!missing.isEmpty()) {
                throw ApiException.validation(String.format(
                        "字段 %s 用了聚合,但 %s 没有、也没作为分组列 —— 聚合查询里不带聚合函数的字段必须进分组"
                                + "(否则会报 not under aggregate function)。请在分组里勾上:%s",
                        join(aggAliases, "、"), join(missing, "、"), join(missing, "、")));
            }
        }

        // 5.5 HAVING(对聚合结果的过滤)
        //
        // ⚠️ 只校验 availableCols 不够:没有聚合字段时 GROUP BY 为空,HAVING 会挂在
        //    一个「明细查询」上,ClickHouse 直接报 "Column ... is not under aggregate
        //    function"。先要求配置里确实有聚合字段,错误提示比让 CH 报错清楚得多。
        String havingClause = "";
        if (cfg.getHaving() != null && !cfg.getHaving().isEmpty()) {
            boolean hasAgg = false;
            for (QueryWorkflowFieldRule f : cfg.getFields()) {
                if (!isBlank(f.getAgg())) {
                    hasAgg = true;
                    break;
                }
            }
            if (!hasAgg) {
                throw ApiException.validation("聚合后过滤(HAVING)需要先给至少一个字段设置聚合函数");
            }
            List<String> parts = new ArrayList<String>(cfg.getHaving().size());
            for (QueryWorkflowWhere h : cfg.getHaving()) {
                if (h == null || "distinct".equalsIgnoreCase(trim(h.getOp()))) continue;
                if (isBlank(h.getColumn())) {
                    continue;
                }
                // 同 WHERE:没填值的条件直接跳过
                if (!whereCondFilled(h)) {
                    continue;
                }
                if (!Sql.validIdent(h.getColumn())) {
                    throw ApiException.validation(String.format("聚合后过滤字段 %s 非法", GoFmt.q(h.getColumn())));
                }
                if (!availableCols.contains(h.getColumn())) {
                    throw ApiException.validation(String.format(
                            "聚合后过滤字段 %s 不在输出字段中(请先在字段列表里添加它)", GoFmt.q(h.getColumn())));
                }
                // 与 WHERE 共用同一个渲染器:操作符白名单 / 值转义 / 时间预设只有一份实现
                String colExpr = quoteCk(h.getColumn());
                // 「不输出」的字段:HAVING 用它的【最终表达式】(可能带聚合)
                HiddenRef hr = hiddenRefs.get(h.getColumn());
                if (hr != null) {
                    colExpr = hr.finalExpr;
                }
                parts.add(buildWhereCondOn(h, colExpr));
            }
            if (!parts.isEmpty()) {
                havingClause = " HAVING " + join(parts, " AND ");
            }
        }

        // 6. ORDER BY
        String orderClause = "";
        if (cfg.getOrderBy() != null && !cfg.getOrderBy().isEmpty()) {
            List<String> parts = new ArrayList<String>(cfg.getOrderBy().size());
            for (QueryWorkflowOrder o : cfg.getOrderBy()) {
                if (isBlank(o.getColumn())) {
                    continue;
                }
                if (!Sql.validIdent(o.getColumn())) {
                    throw ApiException.validation(String.format("排序字段 %s 非法", GoFmt.q(o.getColumn())));
                }
                if (!availableCols.contains(o.getColumn())) {
                    throw ApiException.validation(String.format(
                            "排序字段 %s 不在输出字段中(请先在字段列表里添加它)", GoFmt.q(o.getColumn())));
                }
                String dir = o.isDesc() ? "DESC" : "ASC";
                // 「不输出」的字段:用 CTE 侧表达式(聚合字段用最终表达式)
                String expr = quoteCk(o.getColumn());
                HiddenRef hr = hiddenRefs.get(o.getColumn());
                if (hr != null) {
                    expr = hr.agg ? hr.finalExpr : hr.base;
                }
                parts.add(expr + " " + dir);
            }
            if (!parts.isEmpty()) {
                orderClause = " ORDER BY " + join(parts, ", ");
            }
        }

        if (!conditionDistinctKeys.isEmpty()) {
            List<String> tieColumns = new ArrayList<String>();
            for (QueryWorkflowFieldRule field : cfg.getFields()) {
                if (!field.isHidden()) tieColumns.add(quoteCk(field.getAlias()));
            }
            String tie = "toString(tuple(" + join(tieColumns, ", ") + ")) ASC";
            orderClause = orderClause.isEmpty() ? " ORDER BY " + tie : orderClause + ", " + tie;
        }

        // 7. LIMIT
        String limitClause = cfg.getLimit() > 0 ? " LIMIT " + cfg.getLimit() : "";

        // 8. 校验:程序算子列不能用于过滤 / 分组 / 排序
        //    (ClickHouse 不知道这些列的值,下推不了)
        if (!progCols.isEmpty()) {
            Set<String> progSet = new LinkedHashSet<String>();
            for (ProgColumn pc : progCols) {
                progSet.add(pc.getAlias());
            }
            if (cfg.getWhere() != null) {
                for (QueryWorkflowWhere w : cfg.getWhere()) {
                    checkProgCol(progSet, progCols, w.getColumn(), "过滤条件");
                }
            }
            // ⚠️ 必须用解析后的 groupBy —— cfg.groupBy 为空但 groupByMdn=true 时,
            //    实际会 GROUP BY mdn_norm,这里漏检会让程序算子列被下推。
            for (String g : groupBy) {
                checkProgCol(progSet, progCols, g, "分组");
            }
            for (String key : distinctKeys) {
                checkProgCol(progSet, progCols, key, "字段去重");
            }
            if (cfg.getOrderBy() != null) {
                for (QueryWorkflowOrder o : cfg.getOrderBy()) {
                    checkProgCol(progSet, progCols, o.getColumn(), "排序");
                }
            }
            // HAVING 同样不能引用程序算子列(值由本程序算,CK 看不到)
            if (cfg.getHaving() != null) {
                for (QueryWorkflowWhere h : cfg.getHaving()) {
                    checkProgCol(progSet, progCols, h.getColumn(), "聚合后过滤");
                }
            }
        }

        // 9. 拼装完整 SQL
        StringBuilder sb = new StringBuilder();
        if (!ctes.isEmpty()) {
            sb.append("WITH ");
            sb.append(join(ctes, ",\n     "));
            sb.append("\n");
        }
        // ⚠️ 顺序固定:LIMIT 1 BY 必须排在最终 LIMIT 之前(ClickHouse 的语法),
        //    而且它在 ORDER BY 之后 —— "按 key 保留哪一行"就是由 ORDER BY 决定的。
        sb.append("SELECT ").append(join(outParts, ", "))
                .append(" FROM ").append(fromClause)
                .append(whereClause)
                .append(groupClause)
                .append(havingClause)
                .append(orderClause)
                .append(limitByClause)
                .append(limitClause);
        return new QueryPlan(sb.toString(), progCols);
    }

    /** 只要 SQL、不要计划时的入口(对应 Go 的 {@code GenerateQuerySQL})。 */
    public static String generateSql(QueryWorkflowConfig cfg) {
        return build(cfg).getSql();
    }

    /** 校验向导配置能否生成有效 SQL(保存前调用,对应 Go 的 {@code ValidateConfig})。 */
    public static void validate(QueryWorkflowConfig cfg) {
        build(cfg);
    }

    /**
     * 程序算子列的下推检查(对应 Go 里 BuildQueryPlan 第 8 步的闭包 {@code check})。
     * 列名为空(被跳过的空条件)时不报错。
     */
    private static void checkProgCol(Set<String> progSet, List<ProgColumn> progCols, String column, String what) {
        if (column == null || !progSet.contains(column)) {
            return;
        }
        throw ApiException.validation(String.format(
                "字段 %s 由程序算子(%s)计算,查询引擎无法下推 —— 不能用于%s",
                GoFmt.q(column), progOpsName(progCols, column), what));
    }

    /** 取某列用的程序算子名(仅用于错误提示)。 */
    private static String progOpsName(List<ProgColumn> cols, String alias) {
        for (ProgColumn c : cols) {
            if (c.getAlias().equals(alias)) {
                List<String> names = new ArrayList<String>();
                if (c.getOps() != null) {
                    for (QueryPipelineStep s : c.getOps()) {
                        names.add(s.getFn());
                    }
                }
                return join(names, "→");
            }
        }
        return "";
    }

    /** 「只参与查询、不输出」的字段在 SQL 里的引用方式。 */
    private static final class HiddenRef {
        /** 不带聚合的表达式(带表别名,如 {@code a`.`city`)→ 用于 WHERE / ORDER BY。 */
        private final String base;
        /** 最终表达式(可能是聚合,如 sum(a.amount))→ 用于 HAVING / ORDER BY。 */
        private final String finalExpr;
        /** 是否带聚合。聚合结果不能出现在 WHERE 里。 */
        private final boolean agg;

        private HiddenRef(String base, String finalExpr, boolean agg) {
            this.base = base;
            this.finalExpr = finalExpr;
            this.agg = agg;
        }
    }

    /** 为源表生成 SELECT 字段列表(支持重命名)。 */
    private static List<String> buildSourceSelectList(QueryWorkflowSource src) {
        List<String> cols = src.getColumns();
        if (cols == null || cols.isEmpty()) {
            // 空 = 全选(用 *)
            return new ArrayList<String>(Arrays.asList("*"));
        }
        List<String> parts = new ArrayList<String>(cols.size());
        for (String c : cols) {
            if (!Sql.validIdent(c)) {
                throw ApiException.validation(String.format("列名 %s 非法", GoFmt.q(c)));
            }
            String newName = src.getRename() == null ? null : src.getRename().get(c);
            if (!isBlank(newName) && !newName.equals(c)) {
                if (!Sql.validIdent(newName)) {
                    throw ApiException.validation(String.format("重命名别名 %s 非法", GoFmt.q(newName)));
                }
                parts.add(quoteCk(c) + " AS " + quoteCk(newName));
            } else {
                parts.add(quoteCk(c));
            }
        }
        // mdn 归一化列单独追加,这里不返回 mdn_norm 列(由调用方加)
        return parts;
    }

    /**
     * 把处理链统一成带参形式。
     * 优先用 steps;为空则把旧的 pipeline(List&lt;String&gt;)转成无参 steps(向后兼容)。
     */
    private static List<QueryPipelineStep> normalizeSteps(List<String> pipeline, List<QueryPipelineStep> steps) {
        if (steps != null && !steps.isEmpty()) {
            return steps;
        }
        List<QueryPipelineStep> out = new ArrayList<QueryPipelineStep>();
        if (pipeline != null) {
            for (String fn : pipeline) {
                QueryPipelineStep step = new QueryPipelineStep();
                step.setFn(fn);
                out.add(step);
            }
        }
        return out;
    }

    /**
     * 把处理链应用到一列上,生成 SQL 表达式。
     *
     * <p>每一步:输入(上一级结果或源列)永远是第一个参数,args 提供其余参数。
     * <pre>
     * 纯函数名 "trim"                → trim(输入)
     * 单参模板 "hex(MD5(%s))"        → hex(MD5(输入))
     * 带参模板 "substring(%s,%s,%s)" → substring(输入, 起始, 长度)
     * </pre>
     */
    private static String buildPipelineExpr(String col, List<QueryPipelineStep> steps) {
        return buildPipelineExprFrom(quoteCk(col), steps);
    }

    /**
     * 同上,但从一个【已渲染好的表达式】开始。
     *
     * <p>为什么要这个入口:需要限定表别名的场景 —— 写裸列名时 ClickHouse 会
     * 优先把标识符解析成 SELECT 列表里的【别名】。于是「时间列本身也是输出列」时,
     * 别的列里的 {@code toDateTime(login_at)} 会指到那个聚合别名上,报
     * <pre>
     * code 184 Aggregate function max(tuple(…)) AS login_at is found
     *          inside another aggregate function in query
     * </pre>
     * 实测复现:两列以上 + 裸列名 → 184;全部带 src. 限定 → 正常。
     *
     * <p>公开可见是给 {@code com.el2ck.arb.ArbPlanBuilder} 用的:多源仲裁的号码列与字段列
     * 同样要带 {@code src.} 限定,而处理链的算子白名单、参数校验、转义规则必须两边共用 ——
     * 各自抄一份迟早会漂移,漂移出来的是「同一个处理链在两处生成不同 SQL」。
     */
    public static String buildPipelineExprFrom(String expr, List<QueryPipelineStep> steps) {
        if (steps == null) {
            return expr;
        }

        String cur = expr;
        for (QueryPipelineStep st : steps) {
            String tpl = ALLOWED_FUNCTIONS.get(st.getFn());
            if (tpl == null) {
                throw ApiException.validation(String.format("未知函数 %s", GoFmt.q(st.getFn())));
            }
            if (ZERO_ARG_FUNCS.contains(st.getFn())) {
                throw ApiException.validation(String.format(
                        "算子 %s 不需要参数,不能作为处理链算子", GoFmt.q(st.getFn())));
            }
            if (ARG_REQUIRED_FUNCS.contains(st.getFn())) {
                throw ApiException.validation(String.format(
                        "算子 %s 不是单输入语义,不支持处理链;请改用 SQL 模式", GoFmt.q(st.getFn())));
            }
            List<QueryPipelineArg> args = st.getArgs() == null ? new ArrayList<QueryPipelineArg>() : st.getArgs();
            // %s 的个数 - 1 = 额外参数个数;0 个 %s 说明是纯函数名
            int need = countPlaceholders(tpl);
            if (need == 0) {
                if (!args.isEmpty()) {
                    throw ApiException.validation(String.format(
                            "算子 %s 不接受参数,实际给了 %d 个", GoFmt.q(st.getFn()), args.size()));
                }
                cur = tpl + "(" + cur + ")";
                continue;
            }
            if (need - 1 != args.size()) {
                throw ApiException.validation(String.format(
                        "算子 %s 需要 %d 个参数,实际给了 %d 个", GoFmt.q(st.getFn()), need - 1, args.size()));
            }
            if ("aes256_dec".equals(st.getFn()) || "aes256_cbc_dec".equals(st.getFn())) {
                QueryPipelineArg key = args.get(0);
                if (!"string".equals(key.getKind()) || key.getValue() == null
                        || key.getValue().getBytes(StandardCharsets.UTF_8).length != 32) {
                    throw ApiException.validation("AES-256 解密密钥必须是 32 字节字符串");
                }
                if ("aes256_cbc_dec".equals(st.getFn())) {
                    QueryPipelineArg iv = args.get(1);
                    if (!"string".equals(iv.getKind()) || iv.getValue() == null
                            || iv.getValue().getBytes(StandardCharsets.UTF_8).length != 16) {
                        throw ApiException.validation("AES-256-CBC IV 必须是 16 字节字符串");
                    }
                }
            }
            List<String> vals = new ArrayList<String>(need);
            vals.add(cur);
            for (QueryPipelineArg a : args) {
                try {
                    vals.add(renderPipelineArg(a));
                } catch (ApiException e) {
                    throw ApiException.validation(String.format(
                            "算子 %s 参数错误: %s", GoFmt.q(st.getFn()), e.getMessage()));
                }
            }
            cur = renderTemplate(tpl, vals);
        }
        return cur;
    }

    /** 数模板里 {@code %s} 的个数(对应 Go 的 {@code strings.Count(tpl, "%s")})。 */
    private static int countPlaceholders(String tpl) {
        int n = 0;
        for (int i = tpl.indexOf("%s"); i >= 0; i = tpl.indexOf("%s", i + 2)) {
            n++;
        }
        return n;
    }

    /**
     * 按模板顺序填入参数。
     *
     * <p>不用 {@code String.format}:模板里的 {@code %s} 后面不会再跟别的格式动词,
     * 但值本身可能含 {@code %} —— {@code String.format} 只解析模板、不解析参数,倒也无碍;
     * 真正的问题是模板里没有占位符之外的转义需求,手写替换更直白,也避免 {@code %} 出现在
     * 模板文字里时的歧义。这里假定 {@code vals.size() == 模板里 %s 的个数}。
     */
    private static String renderTemplate(String tpl, List<String> vals) {
        StringBuilder out = new StringBuilder(tpl.length() + 32);
        int arg = 0;
        int i = 0;
        while (i < tpl.length()) {
            if (tpl.startsWith("%s", i)) {
                out.append(vals.get(arg++));
                i += 2;
            } else {
                out.append(tpl.charAt(i++));
            }
        }
        return out.toString();
    }

    /**
     * 渲染处理链算子的一个参数(按 kind 转义,防止注入)。
     * <ul>
     *   <li>{@code number} → 校验后原样输出</li>
     *   <li>{@code ident} → 校验标识符后输出(如 cast 的目标类型)</li>
     *   <li>其余(含空)→ 加单引号并转义</li>
     * </ul>
     */
    private static String renderPipelineArg(QueryPipelineArg a) {
        String kind = a.getKind() == null ? "" : a.getKind().trim().toLowerCase();
        if ("number".equals(kind)) {
            String t = a.getValue() == null ? "" : a.getValue().trim();
            if (t.isEmpty()) {
                throw ApiException.validation("数字参数不能为空");
            }
            // ⚠️ 必须同时排除 NaN/Inf:Double.parseDouble 接受 "NaN"/"Infinity",
            //    直接输出会生成裸 `NaN` / `Infinity` 标识符,SQL 变成 f(x, Infinity)。
            //    (renderLiteral 那边早就这么防了,这里以前漏了。)
            if (!isPlainNumber(t)) {
                throw ApiException.validation(String.format("参数 %s 不是合法数字", GoFmt.q(a.getValue())));
            }
            return t;
        }
        if ("ident".equals(kind)) {
            String t = a.getValue() == null ? "" : a.getValue().trim();
            if (!Sql.validIdent(t)) {
                throw ApiException.validation(String.format("参数 %s 不是合法标识符", GoFmt.q(a.getValue())));
            }
            return t;
        }
        return Sql.quoteString(a.getValue());
    }

    /** 严格数字判定:可解析且非 NaN / Inf(Go 侧对应 ParseFloat + IsNaN/IsInf 两道判断)。 */
    private static boolean isPlainNumber(String t) {
        try {
            double d = Double.parseDouble(t);
            return !Double.isNaN(d) && !Double.isInfinite(d);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 判断一条过滤 / 聚合后过滤条件是否【已经填完】。
     *
     * <p>⚠️ 未填完 → 跳过,而不是报错。用户点「添加条件」后总有一段"还没选值"的中间状态,
     * 这段状态不该让整条 SQL 生成失败(数值列会报「数值条件不能为空」,
     * 字符串列更隐蔽 —— 不报错,直接生成 {@code col = ''} 静默筛成空结果)。
     *
     * <p>⚠️ 但「填了一半」不能当「没填」:BETWEEN 只给了一边属于填错,仍然报错,
     * 否则用户以为筛了区间,实际上下界被默默丢弃。
     */
    private static boolean whereCondFilled(QueryWorkflowWhere w) {
        String preset = trim(w.getPreset());
        // 时间预设自己算区间,不需要手填值
        if (!preset.isEmpty()) {
            // custom 要手填起止两个值:两个都空 = 还没填 → 当未填跳过(与前端 isCondFilled 一致)。
            // 只填一个算「填错了」,继续往下走让 renderTimePreset 报错。
            if ("custom".equalsIgnoreCase(preset)) {
                return !trim(w.getValue()).isEmpty() || !trim(w.getValue2()).isEmpty();
            }
            return true;
        }
        // ⚠️ 先把空格归一成下划线:'IS NULL' / 'IS NOT NULL' 也要认。
        //    否则它们落到 default 分支、因 value 为空而被当成「未填」跳过 ——
        //    条件静默消失(生成的 SQL 完全没有 WHERE),比报错危险得多。
        String op = trim(w.getOp()).toLowerCase().replace(' ', '_');
        if ("is_null".equals(op) || "is_not_null".equals(op)) {
            return true;
        }
        if ("between".equals(op)) {
            return !trim(w.getValue()).isEmpty() || !trim(w.getValue2()).isEmpty();
        }
        return !trim(w.getValue()).isEmpty();
    }

    /**
     * 用【给定的列表达式】渲染条件。
     *
     * <p>colExpr 必须已经是渲染好的 SQL 片段(裸标识符 {@code `city`} 或带表别名的 {@code a.`city`})。
     * 用途:「不输出」的字段不在外层 SELECT 里,只能用 CTE 侧的带别名表达式。
     */
    private static String buildWhereCondOn(QueryWorkflowWhere w, String colExpr) {
        // 时间预设优先:生成【左闭右开】区间,避免 BETWEEN 的边界重复
        if (!isBlank(w.getPreset())) {
            return renderTimePreset(colExpr, w);
        }
        String op = WHERE_OPS.get(trim(w.getOp()).toLowerCase());
        if (op == null) {
            throw ApiException.validation(String.format("不支持的过滤操作符 %s", GoFmt.q(w.getOp())));
        }
        if ("IS NULL".equals(op) || "IS NOT NULL".equals(op)) {
            return colExpr + " " + op;
        }
        if ("BETWEEN".equals(op)) {
            if (trim(w.getValue()).isEmpty() || trim(w.getValue2()).isEmpty()) {
                throw ApiException.validation(String.format(
                        "过滤字段 %s 的 BETWEEN 需要上下界两个值", w.getColumn()));
            }
            String lo = renderLiteral(w.getValue(), w.getValueKind());
            String hi = renderLiteral(w.getValue2(), w.getValueKind());
            return colExpr + " BETWEEN " + lo + " AND " + hi;
        }
        if ("IN".equals(op) || "NOT IN".equals(op)) {
            List<String> vals = splitList(w.getValue());
            if (vals.isEmpty()) {
                throw ApiException.validation(String.format("过滤字段 %s 的 IN 列表为空", w.getColumn()));
            }
            List<String> out = new ArrayList<String>(vals.size());
            for (String v : vals) {
                out.add(renderLiteral(v, w.getValueKind()));
            }
            return colExpr + " " + op + " (" + join(out, ", ") + ")";
        }
        return colExpr + " " + op + " " + renderLiteral(w.getValue(), w.getValueKind());
    }

    /** 拆分 IN / BETWEEN 的逗号分隔值,丢弃空项。 */
    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        for (String r : s.split(",", -1)) {
            String t = r.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 渲染字面量。
     * <ul>
     *   <li>string(默认)→ 加单引号并把单引号翻倍转义</li>
     *   <li>number → 校验后原样输出</li>
     * </ul>
     *
     * <p>⚠️ 这里【不能】提供「原样拼接」模式:where 来自 HTTP 请求体的 config JSON,
     * 任何直通路径都是 SQL 注入。时间预设走 renderTimePreset,自己调 quoteString,不需要它。
     */
    private static String renderLiteral(String v, String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        if (k.isEmpty() || "string".equals(k)) {
            return Sql.quoteString(v);
        }
        if ("number".equals(k)) {
            String t = trim(v);
            if (t.isEmpty()) {
                throw ApiException.validation("数值条件不能为空");
            }
            // ⚠️ 必须同时排除 NaN/Inf:Double.parseDouble 接受它们,直接输出会生成裸 `NaN` 标识符
            if (!isPlainNumber(t)) {
                throw ApiException.validation(String.format("值 %s 不是合法数字", GoFmt.q(v)));
            }
            return t;
        }
        throw ApiException.validation(String.format("未知的值类型 %s", GoFmt.q(kind)));
    }

    /**
     * 渲染时间预设为【左闭右开】区间。
     * 左闭右开({@code >= start AND < end})可避免 BETWEEN 在时间边界上的重复计数。
     */
    private static String renderTimePreset(String col, QueryWorkflowWhere w) {
        String preset = w.getPreset();
        if ("today".equals(preset)) {
            return String.format("(%s >= toDate(today()) AND %s < toDate(today()) + INTERVAL 1 DAY)", col, col);
        }
        if ("thisWeek".equals(preset)) {
            // ⚠️ 必须显式指定周起始 = 周一(toStartOfWeek 的 mode 参数)。
            //    CK 默认 mode=0 是【周日】开始,与中文用户「本周 = 周一起」的预期差一天,
            //    而前端把 thisWeek 标注成「左闭右开区间」,不指明就会静默少/多一天。
            return String.format("(%s >= toStartOfWeek(today(), 1) AND %s < toStartOfWeek(today(), 1) + INTERVAL 1 WEEK)",
                    col, col);
        }
        if ("thisMonth".equals(preset)) {
            return String.format("(%s >= toStartOfMonth(today()) AND %s < toStartOfMonth(today()) + INTERVAL 1 MONTH)",
                    col, col);
        }
        if ("last7d".equals(preset)) {
            // ⚠️ 补上界:以前只有下界,未来时间的数据也会被算进「最近 7 天」,
            //    与前端标注的「左闭右开区间」不符。
            return String.format("(%s >= now() - INTERVAL 7 DAY AND %s < now())", col, col);
        }
        if ("last30d".equals(preset)) {
            return String.format("(%s >= now() - INTERVAL 30 DAY AND %s < now())", col, col);
        }
        if ("custom".equals(preset)) {
            if (isBlank(w.getValue()) || isBlank(w.getValue2())) {
                throw ApiException.validation("自定义时间区间需要起止两个值");
            }
            return String.format("(%s >= %s AND %s < %s)",
                    col, Sql.quoteString(w.getValue()), col, Sql.quoteString(w.getValue2()));
        }
        throw ApiException.validation(String.format("未知的时间预设 %s", GoFmt.q(preset)));
    }

    /**
     * 把表达式中的列引用替换成带表别名的 colRef。
     * 简化策略:expr 里出现的 {@code `col`} 一律替换为 colRef。
     */
    private static String replaceColRef(String expr, String col, String colRef) {
        return expr.replace(quoteCk(col), colRef);
    }

    /**
     * 拼 FROM ... JOIN ... ON 子句。
     * 多张源表:第一张是主表,后续 LEFT/INNER/FULL/RIGHT/CROSS JOIN。
     */
    private static String buildJoinClause(QueryWorkflowConfig cfg) {
        List<QueryWorkflowSource> sources = cfg.getSources();
        if (sources.size() == 1) {
            return "s0 AS a";
        }
        // 逐表 ON 覆盖:key = 源表下标
        Map<Integer, QueryWorkflowJoinOn> onBySrc = new HashMap<Integer, QueryWorkflowJoinOn>();
        List<QueryWorkflowJoinOn> ons = cfg.getJoin() == null ? null : cfg.getJoin().getOns();
        if (ons != null) {
            for (QueryWorkflowJoinOn o : ons) {
                if (o.getSourceIdx() <= 0 || o.getSourceIdx() >= sources.size()) {
                    throw ApiException.validation(String.format(
                            "JOIN 的逐表 ON 引用了不存在的源表下标 %d", o.getSourceIdx()));
                }
                if (onBySrc.containsKey(o.getSourceIdx())) {
                    throw ApiException.validation(String.format(
                            "第 %d 张源表配置了多条 JOIN ON 条件", o.getSourceIdx() + 1));
                }
                onBySrc.put(o.getSourceIdx(), o);
            }
        }

        // 主表
        List<String> clauses = new ArrayList<String>();
        clauses.add("s0 AS a");
        // 主表的 mdn_norm JOIN 到下一张表的 mdn_norm
        for (int i = 1; i < sources.size(); i++) {
            String alias = tableAlias(i);
            String joinType = cfg.getJoin() == null || cfg.getJoin().getType() == null
                    ? "" : cfg.getJoin().getType().trim().toUpperCase();
            if (joinType.isEmpty()) {
                joinType = "LEFT";
            }
            // ⚠️ joinType / ON 的两侧都会原样进 SQL —— 必须白名单 + 格式校验,
            //    否则 config 直接来自 HTTP 请求体,等于开放的注入点。
            String jt = JOIN_TYPES.get(joinType);
            if (jt == null) {
                throw ApiException.validation(String.format(
                        "不支持的 JOIN 类型 %s(可用:LEFT / INNER / RIGHT / FULL / CROSS)", GoFmt.q(cfg.getJoin().getType())));
            }
            // ⚠️ CROSS JOIN 不能带 ON:它本身就是笛卡尔积,ClickHouse 对
            //    `CROSS JOIN t ON …` 直接报 Syntax error(已实测)。
            //    若用户还配了 ON,说明有误会 —— 明确报错而不是静默忽略。
            if ("CROSS".equals(jt)) {
                if (onBySrc.containsKey(i)) {
                    throw ApiException.validation(String.format(
                            "第 %d 张源表配了「该表 ON」,但 CROSS JOIN 不支持 ON 条件;请改回 LEFT/INNER 或清空 ON", i + 1));
                }
                if (!isBlank(cfg.getJoin().getOnLeft()) || !isBlank(cfg.getJoin().getOnRight())) {
                    throw ApiException.validation("CROSS JOIN 不支持 ON 条件(它是笛卡尔积);请改回 LEFT/INNER 或清空 ON");
                }
                clauses.add(jt + " JOIN s" + i + " AS " + alias);
                continue;
            }
            // ON 条件优先级:该表自己的逐表 ON > 全局 ON > 各自关联键的归一化列 mdn_norm
            String onLeft = trim(cfg.getJoin().getOnLeft());
            String onRight = trim(cfg.getJoin().getOnRight());
            QueryWorkflowJoinOn override = onBySrc.get(i);
            if (override != null) {
                if (!isBlank(override.getOnLeft())) {
                    onLeft = override.getOnLeft().trim();
                }
                if (!isBlank(override.getOnRight())) {
                    onRight = override.getOnRight().trim();
                }
            }
            if (onLeft.isEmpty()) {
                onLeft = "a." + MDN_ALIAS;
            } else {
                validateJoinSide(onLeft);
            }
            if (onRight.isEmpty()) {
                onRight = alias + "." + MDN_ALIAS;
            } else {
                validateJoinSide(onRight);
            }
            clauses.add(jt + " JOIN s" + i + " AS " + alias + " ON " + onLeft + " = " + onRight);
        }
        return join(clauses, " ");
    }

    /**
     * 校验自定义 ON 的一侧,只允许 <code>别名.列名</code>。
     * 不允许带表限定之外的任何东西(表达式、函数调用、子查询…)。
     */
    private static void validateJoinSide(String side) {
        Matcher m = JOIN_COL_RE.matcher(side);
        if (!m.matches()) {
            throw ApiException.validation(String.format(
                    "JOIN 的 ON 条件 %s 非法(只能是「表别名.列名」,如 a.mdn_norm)", GoFmt.q(side)));
        }
    }

    /** 第 i 张源表的别名(a/b/c/...)。超过 h 之后用 s&lt;i&gt;,与 CTE 名一致。 */
    private static String tableAlias(int i) {
        int idx = Math.max(i, 0);
        String[] letters = {"a", "b", "c", "d", "e", "f", "g", "h"};
        return idx < letters.length ? letters[idx] : "s" + idx;
    }

    /**
     * 用反引号引用一个 ClickHouse 标识符。
     *
     * <p>本类的调用点【全部】先过了 {@link Sql#validIdent}(或 JOIN 侧的
     * {@code 别名.列名} 正则),标识符里不可能出现反引号,故直接复用 {@link Sql#quoteCol}。
     */
    private static String quoteCk(String s) {
        return Sql.quoteCol(s);
    }

    /** trim,null 安全。 */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** null 安全的「空或全空白」判定。 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** null 安全的判非空(字符串与集合都用它,避免各处写 null 判断)。 */
    private static boolean isBlank(List<?> list) {
        return list == null || list.isEmpty();
    }

    /** null 安全的拼接。 */
    private static String join(List<String> items, String sep) {
        return String.join(sep, items);
    }
}
