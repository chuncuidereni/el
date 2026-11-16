/**
 * 【文件职责】SQL 结果上的程序算子实现，负责哈希、加密、日期和列变换等无法完全下推到数据库的处理。
 * <p>维护本文件时应同步检查调用方契约、失败恢复、并发边界和敏感数据处理，避免只修改单侧实现。
 */
package com.el2ck.workflow;

import com.el2ck.entity.query.ProgColumn;
import com.el2ck.entity.query.QueryPipelineArg;
import com.el2ck.entity.query.QueryPipelineStep;
import com.el2ck.exception.ApiException;
import com.el2ck.util.GoFmt;
import org.bouncycastle.crypto.digests.SM3Digest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 程序算子(Prog):由本程序而非 ClickHouse 计算的算子,移植自 Go
 * {@code internal/sync/prog.go} 与 {@code internal/sync/prog_ops.go}。
 *
 * <p>用途:ClickHouse 没有的能力(国密 SM3、3DES、FF1 保型加密,以及"CK 拿不到的值"上的后续加工)。
 * 这些算子不生成任何 SQL,而是在结果返回后由本程序逐行计算。
 *
 * <p>⚠️ 关键差异:
 * <ul>
 *   <li>{@code steps}(处理链)生成 ClickHouse SQL → 结果列可过滤 / 分组 / 排序</li>
 *   <li>{@code prog}(程序算子)在本程序计算 → ClickHouse 完全不知道,结果列【只能展示 / 导出】</li>
 * </ul>
 * 因此 {@link QueryPlanBuilder} 会校验:被 prog 处理的字段不得出现在
 * WHERE / GROUP BY / ORDER BY / HAVING 中。
 *
 * <p>设计:所有程序算子(含加密类)集中注册为一张表,「有哪些算子 / 怎么校验 / 怎么算」
 * 只有一处定义 —— 分成三处并列时,新增算子必然会漏改某一处。
 * 前端 {@code constants/queryBuilderMeta.ts} 的 {@code PROG_OP_GROUPS} 是同一张表的另一份副本,
 * 算子名必须两边同时改。
 */
public final class ProgOps {

    /** 工具类私有构造器:禁止实例化。 */
    private ProgOps() {
    }

    /** 参数校验器:参数个数与取值范围。alias 仅用于错误提示。 */
    private interface Validator {
        /** 校验失败抛 400 {@link ApiException}。 */
        void validate(String alias, List<QueryPipelineArg> args);
    }

    /** 算子执行器:输入上一级的值,返回本级的值。 */
    private interface Applier {
        /** 执行失败抛 400 {@link ApiException}(配置问题)或 502(数据问题,由调用方包装)。 */
        String apply(String in, List<QueryPipelineArg> args);
    }

    /** 一个程序算子的定义。 */
    private static final class Spec {
        /** 参数校验器。 */
        private final Validator validator;
        /** 执行器。 */
        private final Applier applier;

        private Spec(Validator validator, Applier applier) {
            this.validator = validator;
            this.applier = applier;
        }
    }

    /** 全部程序算子。新增算子只需在这里加一项 + 前端 PROG_OP_GROUPS 加一条。 */
    private static final Map<String, Spec> OPS = new LinkedHashMap<String, Spec>();

    /** 已编译正则的缓存(见 {@link #compiledRegex} 的说明)。 */
    private static final Map<String, Pattern> REGEX_CACHE = new HashMap<String, Pattern>();

    /** 正则缓存条目上限。 */
    private static final int REGEX_CACHE_MAX = 512;

    static {
        registerCryptoOps();
        registerStringOps();
        registerDateOps();
        registerNumberOps();
    }

    // ─────────────────────────── 对外入口 ───────────────────────────

    /** 校验程序算子的名称与参数(全部走注册表)。 */
    public static void validate(String alias, List<QueryPipelineStep> ops) {
        if (ops == null) {
            return;
        }
        for (QueryPipelineStep step : ops) {
            Spec spec = OPS.get(step.getFn());
            if (spec == null) {
                throw ApiException.validation(String.format(
                        "字段 %s 的未知程序算子 %s", alias, GoFmt.q(step.getFn())));
            }
            spec.validator.validate(alias, argsOf(step));
        }
    }

    /**
     * 按计划就地修改结果行。
     *
     * <p>columns 是结果列名(仅用于错误提示),rows 会被原地改写 —— 调用方持有的正是这些行对象。
     */
    public static void applyProg(List<String> columns, List<List<Object>> rows, List<ProgColumn> plan) {
        if (plan == null || plan.isEmpty() || rows == null || rows.isEmpty()) {
            return;
        }
        // 先校验下标,避免处理到一半才失败
        for (ProgColumn pc : plan) {
            if (pc.getIndex() < 0 || pc.getIndex() >= columns.size()) {
                throw ApiException.badGateway(String.format(
                        "程序算子列下标 %d 越界(共 %d 列)", pc.getIndex(), columns.size()));
            }
            validate(pc.getAlias(), pc.getOps());
        }
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Object> row = rows.get(ri);
            for (ProgColumn pc : plan) {
                if (pc.getIndex() >= row.size()) {
                    continue;
                }
                Object v = row.get(pc.getIndex());
                if (v == null) {
                    continue; // NULL 保持 NULL
                }
                String out;
                try {
                    out = applyOps(String.valueOf(v), pc.getOps());
                } catch (ApiException e) {
                    // 逐行定位:一行算错时只说算子名,用户无从知道是哪一行的数据
                    throw ApiException.badGateway(String.format(
                            "第 %d 行 列 %s: %s", ri + 1, columns.get(pc.getIndex()), e.getMessage()));
                }
                row.set(pc.getIndex(), out);
            }
        }
    }

    /** 依次应用程序算子链(全部走注册表)。 */
    private static String applyOps(String v, List<QueryPipelineStep> ops) {
        String cur = v;
        if (ops == null) {
            return cur;
        }
        for (QueryPipelineStep step : ops) {
            Spec spec = OPS.get(step.getFn());
            if (spec == null) {
                throw ApiException.validation(String.format("未知程序算子 %s", GoFmt.q(step.getFn())));
            }
            try {
                cur = spec.applier.apply(cur, argsOf(step));
            } catch (ApiException e) {
                throw ApiException.validation(String.format("%s 执行失败: %s", step.getFn(), e.getMessage()));
            }
        }
        return cur;
    }

    // ─────────────────────────── 注册表 ───────────────────────────

    /** 注册一个程序算子。 */
    private static void register(String fn, Validator validator, Applier applier) {
        OPS.put(fn, new Spec(validator, applier));
    }

    /**
     * 加密 / 哈希类程序算子。
     *
     * <p>SM3 用成熟实现(BouncyCastle)而不是自写 —— 计划文档明确禁止自写简化算法:
     * 加密算法的正确性只能靠固定测试向量验证,自写实现"能跑"不等于"结果对"。
     */
    private static void registerCryptoOps() {
        register("sm3",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        noArgs(alias, "sm3", args);
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        return sm3Hex(in);
                    }
                });

        register("des3_enc",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        validateDes3(alias, "des3_enc", args);
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        byte[] key = des3Key(argAt(args, 0));
                        byte[] out = des3Encrypt(in, key);
                        if ("hex".equalsIgnoreCase(argOr(args, 1, "base64"))) {
                            return toHexUpper(out);
                        }
                        return java.util.Base64.getEncoder().encodeToString(out);
                    }
                });

        register("des3_dec",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        validateDes3(alias, "des3_dec", args);
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        byte[] key = des3Key(argAt(args, 0));
                        byte[] raw;
                        if ("hex".equalsIgnoreCase(argOr(args, 1, "base64"))) {
                            raw = fromHexUpper(in);
                        } else {
                            raw = fromBase64(in);
                        }
                        return des3Decrypt(raw, key);
                    }
                });

        register("ff1_enc", ff1Validator("ff1_enc"), ff1Apply(true));
        register("ff1_dec", ff1Validator("ff1_dec"), ff1Apply(false));
    }

    /** 字符串类程序算子。 */
    private static void registerStringOps() {
        register("prog_lower", noArgsValidator("prog_lower"), new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                return in.toLowerCase();
            }
        });
        register("prog_upper", noArgsValidator("prog_upper"), new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                return in.toUpperCase();
            }
        });
        register("prog_trim", noArgsValidator("prog_trim"), new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                return trimSpace(in);
            }
        });

        register("prog_substr",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 2) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_substr 需要 2 个参数(起始、长度)", alias));
                        }
                        // 只有【长度】允许空(=到末尾);起始为空会在运行期 Atoi("")=0 → 报「起始位置需 >= 1」
                        if (argAt(args, 0).isEmpty()) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_substr 起始位置不能为空(从 1 开始)", alias));
                        }
                        for (int i = 0; i < 2; i++) {
                            String v = argAt(args, i);
                            if (!v.isEmpty() && !isInt(v)) {
                                throw ApiException.validation(String.format(
                                        "字段 %s 的 prog_substr 参数 %s 不是整数", alias, GoFmt.q(v)));
                            }
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        int start = parseInt(argAt(args, 0), 0);
                        int length = argAt(args, 1).isEmpty() ? 0 : parseInt(argAt(args, 1), 0);
                        return progSubstr(in, start, length);
                    }
                });

        // ── 掩码脱敏 ──
        register("prog_mask",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 4) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_mask 需要 4 个参数(保留前 N、保留后 M、掩码字符、掩码长度)", alias));
                        }
                        for (int i = 0; i < 2; i++) {
                            String v = argAt(args, i);
                            requireNonNegativeInt(v, String.format(
                                    "字段 %s 的 prog_mask 参数 %s 必须是非负整数", alias, GoFmt.q(v)));
                        }
                        try {
                            oneRuneOr(args, 2, '*');
                        } catch (ApiException e) {
                            throw ApiException.validation(String.format("字段 %s 的 prog_mask %s", alias, e.getMessage()));
                        }
                        String maskLen = argAt(args, 3);
                        requireNonNegativeInt(maskLen, String.format(
                                "字段 %s 的 prog_mask 掩码长度 %s 必须是非负整数", alias, GoFmt.q(maskLen)));
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        int front = intArg(args, 0, 3);
                        int back = intArg(args, 1, 4);
                        int mark = oneRuneOr(args, 2, '*');
                        int maskLen = intArg(args, 3, 0);
                        return progMask(in, front, back, mark, maskLen);
                    }
                });

        // ── 替换 ──
        register("prog_replace",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 2) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_replace 需要 2 个参数(查找、替换为)", alias));
                        }
                        // ⚠️ 用原始值判空,不能用 argAt(它会 trim)——
                        //    「把空格替换掉」是合法且常见的用法,trim 后会变成空串而被误拒。
                        if (rawArg(args, 0).isEmpty()) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_replace 查找内容不能为空", alias));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        String old = rawArg(args, 0);
                        String now = args.size() > 1 ? rawArg(args, 1) : "";
                        return in.replace(old, now);
                    }
                });

        register("prog_regex_replace",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 2) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_regex_replace 需要 2 个参数(正则、替换为)", alias));
                        }
                        String pat = argAt(args, 0);
                        if (pat.isEmpty()) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_regex_replace 正则不能为空", alias));
                        }
                        try {
                            compiledRegex(pat);
                        } catch (ApiException e) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_regex_replace 正则非法: %s", alias, e.getMessage()));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        Pattern re = compiledRegex(argAt(args, 0));
                        String now = args.size() > 1 ? rawArg(args, 1) : "";
                        // Java 的 replaceAll 用 $1 / ${name} 做反向引用,与 Go 的 ReplaceAllString 一致
                        return re.matcher(in).replaceAll(now);
                    }
                });

        // ── 补位 ──
        register("prog_pad_left", padValidator("prog_pad_left"), new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                int n = intArg(args, 0, 0);
                int ch = oneRuneOr(args, 1, '0');
                return progPad(in, n, ch, true);
            }
        });
        register("prog_pad_right", padValidator("prog_pad_right"), new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                int n = intArg(args, 0, 0);
                int ch = oneRuneOr(args, 1, '0');
                return progPad(in, n, ch, false);
            }
        });

        // ── 空白 / 首尾字符 ──
        register("prog_strip", noArgsValidator("prog_strip"), new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                // 去掉内部与首尾的全部空白字符(不是只去首尾)
                StringBuilder out = new StringBuilder(in.length());
                int i = 0;
                while (i < in.length()) {
                    int cp = in.codePointAt(i);
                    if (!isGoSpace(cp)) {
                        out.appendCodePoint(cp);
                    }
                    i += Character.charCount(cp);
                }
                return out.toString();
            }
        });

        register("prog_trim_chars",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 1) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_trim_chars 需要 1 个参数(要去掉的字符集合)", alias));
                        }
                        // ⚠️ 同 prog_replace:用原始值判空(去首尾空格是最常见的用法)
                        if (rawArg(args, 0).isEmpty()) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_trim_chars 字符集不能为空", alias));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        return trimChars(in, rawArg(args, 0));
                    }
                });

        // ── 前后缀拼接 ──
        register("prog_concat",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 2) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_concat 需要 2 个参数(前缀、后缀)", alias));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        String pre = args.size() > 0 ? rawArg(args, 0) : "";
                        String suf = args.size() > 1 ? rawArg(args, 1) : "";
                        return pre + in + suf;
                    }
                });
    }

    /** 时间类程序算子。 */
    private static void registerDateOps() {
        // 日期格式词汇表(前后端共用,前端 PROG_OP_GROUPS 的 def 与 desc 里同步列出):
        //   auto                自动识别常见格式(unix 秒/毫秒按位数判断)
        //   yyyyMMdd            20240115
        //   yyyy-MM-dd          2024-01-15
        //   yyyy/MM/dd          2024/01/15
        //   yyyy-MM-dd HH:mm:ss 2024-01-15 10:20:30
        //   yyyyMMddHHmmss      20240115102030
        //   unix_s / unix_ms    秒 / 毫秒级时间戳
        register("prog_date_format",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        dateArgsValidator(alias, "prog_date_format", args, 2, 2);
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        LocalDateTime t = parseDate(in, argOr(args, 0, "auto"));
                        return formatDate(t, argOr(args, 1, "yyyy-MM-dd"));
                    }
                });

        register("prog_date_add",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        dateArgsValidator(alias, "prog_date_add", args, 3, 3);
                        if (!isPlainNumberV(argAt(args, 1))) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_date_add 天数 %s 不是数字", alias, GoFmt.q(argAt(args, 1))));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        LocalDateTime t = parseDate(in, argOr(args, 0, "auto"));
                        double days = doubleArg(args, 1, 0);
                        LocalDateTime out = t.plusDays((long) days);
                        return formatDate(out, argOr(args, 2, "yyyy-MM-dd"));
                    }
                });

        register("prog_date_diff",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 2) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_date_diff 需要 2 个参数(输入格式、参照日期或 today)", alias));
                        }
                        String ref = argAt(args, 1);
                        if (ref.isEmpty() || "today".equalsIgnoreCase(ref)) {
                            return;
                        }
                        try {
                            parseDate(ref, argOr(args, 0, "auto"));
                        } catch (ApiException e) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_date_diff 参照日期无法解析: %s", alias, e.getMessage()));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        LocalDateTime t = parseDate(in, argOr(args, 0, "auto"));
                        String raw = argAt(args, 1);
                        LocalDateTime ref;
                        if (raw.isEmpty() || "today".equalsIgnoreCase(raw)) {
                            ref = LocalDate.now().atStartOfDay();
                        } else {
                            ref = parseDate(raw, argOr(args, 0, "auto"));
                        }
                        // 以「自然日」为单位:去掉时分秒再相减,避免 23:59 与 00:01 算成 0 天
                        return String.valueOf(ChronoUnit.DAYS.between(ref.toLocalDate(), t.toLocalDate()));
                    }
                });

        register("prog_to_unix",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        dateArgsValidator(alias, "prog_to_unix", args, 2, 2);
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        LocalDateTime t = parseDate(in, argOr(args, 0, "auto"));
                        if ("ms".equalsIgnoreCase(argOr(args, 1, "s"))) {
                            return String.valueOf(t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                        }
                        return String.valueOf(t.atZone(ZoneId.systemDefault()).toEpochSecond());
                    }
                });

        register("prog_from_unix",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        dateArgsValidator(alias, "prog_from_unix", args, 2, 2);
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        String v = trimSpace(in);
                        long n;
                        try {
                            n = Long.parseLong(v);
                        } catch (NumberFormatException e) {
                            throw ApiException.validation(String.format("%s 不是时间戳", GoFmt.q(v)));
                        }
                        LocalDateTime t;
                        if ("ms".equalsIgnoreCase(argOr(args, 0, "s"))) {
                            t = LocalDateTime.ofInstant(Instant.ofEpochMilli(n), ZoneId.systemDefault());
                        } else {
                            t = LocalDateTime.ofInstant(Instant.ofEpochSecond(n), ZoneId.systemDefault());
                        }
                        return formatDate(t, argOr(args, 1, "yyyy-MM-dd"));
                    }
                });
    }

    /** 数值类程序算子。 */
    private static void registerNumberOps() {
        register("prog_round",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 1) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_round 需要 1 个参数(保留小数位)", alias));
                        }
                        String v = argAt(args, 0);
                        if (!v.isEmpty()) {
                            requireIntInRange(v, 0, 12, String.format(
                                    "字段 %s 的 prog_round 小数位 %s 必须是 0~12 的整数", alias, GoFmt.q(v)));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        int digits = intArg(args, 0, 0);
                        double n = parseNumber(in);
                        double pow = Math.pow(10, digits);
                        return trimFloat(roundHalfAwayFromZero(n * pow) / pow);
                    }
                });

        register("prog_floor", noArgsValidator("prog_floor"), numApply(MathOp.FLOOR));
        register("prog_ceil", noArgsValidator("prog_ceil"), numApply(MathOp.CEIL));
        register("prog_abs", noArgsValidator("prog_abs"), numApply(MathOp.ABS));

        register("prog_trunc",
                new Validator() {
                    public void validate(String alias, List<QueryPipelineArg> args) {
                        if (args.size() != 1) {
                            throw ApiException.validation(String.format(
                                    "字段 %s 的 prog_trunc 需要 1 个参数(保留小数位)", alias));
                        }
                    }
                },
                new Applier() {
                    public String apply(String in, List<QueryPipelineArg> args) {
                        int digits = intArg(args, 0, 0);
                        double n = parseNumber(in);
                        double pow = Math.pow(10, digits);
                        // 截断而不是四舍五入:负数要向上取整方向截(-1.9 → -1),故用 ceil/floor 分情况
                        double truncated = n < 0 ? Math.ceil(n * pow) / pow : Math.floor(n * pow) / pow;
                        return trimFloat(truncated);
                    }
                });

        register("prog_thousands", noArgsValidator("prog_thousands"), new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                return thousands(parseNumber(in));
            }
        });
    }

    /** 一元数学运算的枚举,避免为每个算子写一遍包装。 */
    private enum MathOp {
        /** 向下取整。 */
        FLOOR,
        /** 向上取整。 */
        CEIL,
        /** 绝对值。 */
        ABS
    }

    /** 生成一个「单输入、无参数」的数值算子执行器。 */
    private static Applier numApply(final MathOp op) {
        return new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                double n = parseNumber(in);
                double out;
                if (op == MathOp.FLOOR) {
                    out = Math.floor(n);
                } else if (op == MathOp.CEIL) {
                    out = Math.ceil(n);
                } else {
                    out = Math.abs(n);
                }
                if (Double.isNaN(out) || Double.isInfinite(out)) {
                    throw ApiException.validation("计算结果不是有限数值");
                }
                return trimFloat(out);
            }
        };
    }

    // ─────────────────────────── 参数辅助 ───────────────────────────

    /** 步的参数列表(null 安全)。 */
    private static List<QueryPipelineArg> argsOf(QueryPipelineStep step) {
        return step.getArgs() == null ? new ArrayList<QueryPipelineArg>() : step.getArgs();
    }

    /** 取第 i 个参数的【原始值】(不 trim);越界返回空串。 */
    private static String rawArg(List<QueryPipelineArg> args, int i) {
        if (i < 0 || i >= args.size()) {
            return "";
        }
        String v = args.get(i).getValue();
        return v == null ? "" : v;
    }

    /** 取第 i 个参数(trim 后);越界返回空串。 */
    private static String argAt(List<QueryPipelineArg> args, int i) {
        return trimSpace(rawArg(args, i));
    }

    /** 取第 i 个参数,空则用默认值。 */
    private static String argOr(List<QueryPipelineArg> args, int i, String def) {
        String v = argAt(args, i);
        return v.isEmpty() ? def : v;
    }

    /** 取整数参数,空则用默认值。 */
    private static int intArg(List<QueryPipelineArg> args, int i, int def) {
        String v = argAt(args, i);
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.validation(String.format("参数 %s 不是整数", GoFmt.q(v)));
        }
    }

    /** 取单字符参数,空则用默认值。 */
    private static int oneRuneOr(List<QueryPipelineArg> args, int i, char def) {
        String v = argAt(args, i);
        if (v.isEmpty()) {
            return def;
        }
        if (v.codePointCount(0, v.length()) != 1) {
            throw ApiException.validation(String.format(
                    "填充/掩码字符必须是 1 个字符,实际 %s", GoFmt.q(v)));
        }
        return v.codePointAt(0);
    }

    /** 要求无参数。 */
    private static void noArgs(String alias, String fn, List<QueryPipelineArg> args) {
        if (args != null && !args.isEmpty()) {
            throw ApiException.validation(String.format(
                    "字段 %s 的 %s 不接受参数,实际给了 %d 个", alias, fn, args.size()));
        }
    }

    /** 生成一个「不接受参数」的校验器。 */
    private static Validator noArgsValidator(final String fn) {
        return new Validator() {
            public void validate(String alias, List<QueryPipelineArg> args) {
                noArgs(alias, fn, args);
            }
        };
    }

    /** 补位类算子的校验器(目标长度、填充字符)。 */
    private static Validator padValidator(final String fn) {
        return new Validator() {
            public void validate(String alias, List<QueryPipelineArg> args) {
                if (args.size() != 2) {
                    throw ApiException.validation(String.format(
                            "字段 %s 的 %s 需要 2 个参数(目标长度、填充字符)", alias, fn));
                }
                String v = argAt(args, 0);
                if (v.isEmpty()) {
                    throw ApiException.validation(String.format(
                            "字段 %s 的 %s 目标长度不能为空", alias, fn));
                }
                if (!isInt(v) || parseInt(v, -1) < 0) {
                    throw ApiException.validation(String.format(
                            "字段 %s 的 %s 目标长度 %s 必须是非负整数", alias, fn, GoFmt.q(v)));
                }                try {
                    oneRuneOr(args, 1, '0');
                } catch (ApiException e) {
                    throw ApiException.validation(String.format("字段 %s 的 %s %s", alias, fn, e.getMessage()));
                }
            }
        };
    }

    /** 3DES 算子的参数校验(密钥、编码)。 */
    private static void validateDes3(String alias, String fn, List<QueryPipelineArg> args) {
        if (args.size() != 2) {
            throw ApiException.validation(String.format(
                    "字段 %s 的 %s 需要 2 个参数(密钥、编码)", alias, fn));
        }
        try {
            des3Key(argAt(args, 0));
        } catch (ApiException e) {
            throw ApiException.validation(String.format(
                    "字段 %s 的 %s 密钥无效: %s", alias, fn, e.getMessage()));
        }
        String enc = argOr(args, 1, "base64").toLowerCase();
        if (!"base64".equals(enc) && !"hex".equals(enc)) {
            throw ApiException.validation(String.format(
                    "字段 %s 的 %s 编码只能是 base64 或 hex,实际 %s", alias, fn, GoFmt.q(argAt(args, 1))));
        }
    }

    /** FF1 算子的参数校验(密钥、tweak、字母表)。 */
    private static Validator ff1Validator(final String fn) {
        return new Validator() {
            public void validate(String alias, List<QueryPipelineArg> args) {
                if (args.size() != 3) {
                    throw ApiException.validation(String.format(
                            "字段 %s 的 %s 需要 3 个参数(密钥、tweak、字母表)", alias, fn));
                }
                try {
                    FF1.parseKey(argAt(args, 0));
                } catch (ApiException e) {
                    throw ApiException.validation(String.format(
                            "字段 %s 的 %s 密钥无效: %s", alias, fn, e.getMessage()));
                }
                String alpha = argOr(args, 2, FF1.DEFAULT_DIGITS_ALPHABET);
                // ⚠️ 字符数而不是字节数;并且禁止重复字符 ——
                //    下标表是「字符 → 下标」,重复字符会后者覆盖前者,
                //    加解密不再互为逆运算(往返静默不还原)。
                int count = alpha.codePointCount(0, alpha.length());
                if (count < 2) {
                    throw ApiException.validation(String.format(
                            "字段 %s 的 %s 字母表至少 2 个字符", alias, fn));
                }
                Map<Integer, Boolean> seen = new HashMap<Integer, Boolean>();
                for (int i = 0; i < alpha.length(); ) {
                    int cp = alpha.codePointAt(i);
                    if (seen.containsKey(cp)) {
                        throw ApiException.validation(String.format(
                                "字段 %s 的 %s 字母表里有重复字符 %s", alias, fn,
                                GoFmt.q(new String(Character.toChars(cp)))));
                    }
                    seen.put(cp, Boolean.TRUE);
                    i += Character.charCount(cp);
                }
            }
        };
    }

    /** 生成 FF1 加密 / 解密的执行器。 */
    private static Applier ff1Apply(final boolean encrypt) {
        return new Applier() {
            public String apply(String in, List<QueryPipelineArg> args) {
                byte[] key = FF1.parseKey(argAt(args, 0));
                byte[] tweak = FF1.parseTweak(argAt(args, 1));
                FF1.Params params = new FF1.Params(key, tweak, argOr(args, 2, FF1.DEFAULT_DIGITS_ALPHABET));
                return encrypt ? FF1.encrypt(params, in) : FF1.decrypt(params, in);
            }
        };
    }

    // ─────────────────────────── 通用工具 ───────────────────────────

    /** 整数判定(严格,与 {@code Integer.parseInt} 一致)。 */
    private static boolean isInt(String v) {
        try {
            Integer.parseInt(v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 取浮点参数,空则用默认值。 */
    private static double doubleArg(List<QueryPipelineArg> args, int i, double def) {
        String v = argAt(args, i);
        if (v.isEmpty()) {
            return def;
        }
        if (!isPlainNumberV(v)) {
            throw ApiException.validation(String.format("参数 %s 不是合法数字", GoFmt.q(v)));
        }
        return Double.parseDouble(v);
    }

    /**
     * 参数为空时跳过校验;非空则必须是非负整数,否则抛给定错误。
     *
     * <p>「空 = 用默认值」是这些算子的既有语义(见各算子的 apply),
     * 所以校验只针对「填了的值」,不能因为没填就报错。
     */
    private static void requireNonNegativeInt(String v, String errorMessage) {
        if (v.isEmpty()) {
            return;
        }
        if (isInt(v) && parseInt(v, -1) >= 0) {
            return;
        }
        throw ApiException.validation(errorMessage);
    }

    /** 参数为空时跳过校验;非空则必须是 [min, max] 内的整数,否则抛给定错误。 */
    private static void requireIntInRange(String v, int min, int max, String errorMessage) {
        if (v.isEmpty()) {
            return;
        }
        if (isInt(v)) {
            int n = parseInt(v, min);
            if (n >= min && n <= max) {
                return;
            }
        }
        throw ApiException.validation(errorMessage);
    }

    /** 解析整数;失败返回默认值(调用方已校验过格式)。 */
    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 严格数字判定:可解析且非 NaN / Inf(对应 Go 的 ParseFloat + IsNaN/IsInf 两道判断)。 */
    private static boolean isPlainNumberV(String t) {
        try {
            double d = Double.parseDouble(t);
            return !Double.isNaN(d) && !Double.isInfinite(d);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 解析数值字面量。
     *
     * <p>⚠️ 必须显式拒绝 NaN/Inf —— {@code Double.parseDouble} 接受它们,
     * 放过去会让后续运算产出 "NaN" 这样的脏值写进结果列。
     */
    private static double parseNumber(String v) {
        String t = trimSpace(v);
        if (!isPlainNumberV(t)) {
            throw ApiException.validation(String.format("%s 不是合法数字", GoFmt.q(v)));
        }
        return Double.parseDouble(t);
    }

    /**
     * 输出尽量不带多余的 0 与小数点(123.00 → 123,123.50 → 123.5),且不用科学计数法
     * (对应 Go 的 {@code strconv.FormatFloat(f, 'f', -1, 64)})。
     */
    private static String trimFloat(double f) {
        if (Double.isNaN(f) || Double.isInfinite(f)) {
            throw ApiException.validation("计算结果不是有限数值");
        }
        // 从 Double.toString 的标准写法出发(与 Go 的 -1 精度同为"最短可往返表示"),
        // 再用 BigDecimal 去掉末尾的 0 并转成朴素写法(1.0E20 → 100000000000000000000)
        return new BigDecimal(Double.toString(f)).stripTrailingZeros().toPlainString();
    }

    /**
     * 千分位格式化(保留原有小数部分)。
     */
    private static String thousands(double f) {
        boolean negative = f < 0;
        double abs = negative ? -f : f;
        String s = trimFloat(abs);
        String intPart = s;
        String frac = "";
        int dot = s.indexOf('.');
        if (dot >= 0) {
            intPart = s.substring(0, dot);
            frac = s.substring(dot + 1);
        }
        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) {
                out.append(',');
            }
            out.append(intPart.charAt(i));
        }
        if (!frac.isEmpty()) {
            out.append('.').append(frac);
        }
        return out.toString();
    }

    /** 取(必要时编译并缓存)一个正则。 */
    private static Pattern compiledRegex(String pat) {
        synchronized (REGEX_CACHE) {
            Pattern cached = REGEX_CACHE.get(pat);
            if (cached != null) {
                return cached;
            }
        }
        // 编译放在锁外:正则编译可能很慢(复杂模式),不该阻塞其它行
        Pattern compiled;
        try {
            compiled = Pattern.compile(pat);
        } catch (PatternSyntaxException e) {
            throw ApiException.validation(String.format("正则非法: %s", e.getMessage()));
        }
        synchronized (REGEX_CACHE) {
            // 缓存随时可重建,且不这样做就是又一个无界 map
            if (REGEX_CACHE.size() >= REGEX_CACHE_MAX) {
                REGEX_CACHE.clear();
            }
            REGEX_CACHE.put(pat, compiled);
        }
        return compiled;
    }

    /**
     * 掩码脱敏:保留前 front 个、后 back 个字符,中间用 mark 填充。
     *
     * <p>maskLen = 0 表示「按实际被遮挡的字符数」;大于 0 表示固定长度
     * (有些场景要求掩码后的总长度与原长度无关,例如统一显示 138****1234)。
     * 长度不足以同时保留前后时,退化为「只保留前 front 个」,避免泄露全部。
     */
    private static String progMask(String s, int front, int back, int mark, int maskLen) {
        int[] rs = codePoints(s);
        int n = rs.length;
        if (front < 0) {
            front = 0;
        }
        if (back < 0) {
            back = 0;
        }
        if (n == 0) {
            return s;
        }
        if (front + back >= n) {
            // 太短:一律打码中间,至少保留「开头 front 个」以外的内容
            if (front >= n) {
                front = n - 1;
            }
            if (front < 0) {
                front = 0;
            }
            back = 0;
        }
        int hidden = n - front - back;
        if (maskLen > 0) {
            hidden = maskLen;
        }
        if (hidden == 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < front; i++) {
            out.appendCodePoint(rs[i]);
        }
        for (int i = 0; i < hidden; i++) {
            out.appendCodePoint(mark);
        }
        for (int i = n - back; i < n; i++) {
            out.appendCodePoint(rs[i]);
        }
        return out.toString();
    }

    /** 定长补齐:toLeft=true 在左侧补。已超长则原样返回(不截断)。 */
    private static String progPad(String s, int width, int ch, boolean toLeft) {
        int len = s.codePointCount(0, s.length());
        if (width <= len) {
            return s;
        }
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < width - len; i++) {
            pad.appendCodePoint(ch);
        }
        return toLeft ? pad + s : s + pad.toString();
    }

    /**
     * 按【字符】截取(不是字节),start 从 1 开始,length &lt;= 0 表示到末尾。
     * 与 ClickHouse 的 substring 语义保持一致(中文不会被截断)。
     */
    private static String progSubstr(String s, int start, int length) {
        if (start < 1) {
            throw ApiException.validation(String.format("起始位置需 >= 1,实际 %d", start));
        }
        int[] rs = codePoints(s);
        if (start > rs.length) {
            return ""; // 起点越界 → 空串(与 CK 行为一致)
        }
        int from = start - 1;
        int to = (length <= 0 || length >= rs.length - from) ? rs.length : from + length;
        return codePointsToString(rs, from, to);
    }

    /** 按码点切分字符串。 */
    private static int[] codePoints(String s) {
        int count = s.codePointCount(0, s.length());
        int[] out = new int[count];
        int at = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out[at++] = cp;
            i += Character.charCount(cp);
        }
        return out;
    }

    /** 取码点数组的 [from, to) 区间拼成字符串。 */
    private static String codePointsToString(int[] cps, int from, int to) {
        StringBuilder out = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            out.appendCodePoint(cps[i]);
        }
        return out.toString();
    }

    /** 去掉首尾出现在 chars 里的字符(对应 Go 的 {@code strings.Trim})。 */
    private static String trimChars(String s, String chars) {
        Map<Integer, Boolean> set = new HashMap<Integer, Boolean>();
        for (int i = 0; i < chars.length(); ) {
            int cp = chars.codePointAt(i);
            set.put(cp, Boolean.TRUE);
            i += Character.charCount(cp);
        }
        int[] rs = codePoints(s);
        int start = 0;
        int end = rs.length;
        while (start < end && set.containsKey(rs[start])) {
            start++;
        }
        while (end > start && set.containsKey(rs[end - 1])) {
            end--;
        }
        return codePointsToString(rs, start, end);
    }

    /** 去掉首尾空白(对应 Go 的 {@code strings.TrimSpace},按 unicode 空白判定)。 */
    private static String trimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!isGoSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isGoSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    /**
     * 判定是否为「空白字符」,对齐 Go 的 {@code unicode.IsSpace}。
     *
     * <p>⚠️ 不能直接用 {@code Character.isWhitespace}:它对 U+00A0(不换行空格)
     * 返回 false、对 U+0085 也返回 false,而 Go 两者都算空白 —— 用它会漏剪掉
     * 从 Excel / 网页复制来的手机号两端的空格。
     */
    private static boolean isGoSpace(int cp) {
        if (cp == 0x00A0 || cp == 0x0085) {
            return true;
        }
        return Character.isWhitespace(cp);
    }

    // ─────────────────────────── CRC / SM3 / 3DES ───────────────────────────

    /** 计算 SM3 并返回大写十六进制。 */
    private static String sm3Hex(String s) {
        SM3Digest digest = new SM3Digest();
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        digest.update(data, 0, data.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return toHexUpper(out);
    }

    /** 字节数组 → 大写十六进制。 */
    private static String toHexUpper(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
            out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
        }
        return out.toString();
    }

    /** 大写/小写十六进制 → 字节数组;非法输入报错。 */
    private static byte[] fromHexUpper(String in) {
        String s = trimSpace(in);
        if (s.length() % 2 != 0) {
            throw ApiException.validation("hex 解码失败: 长度不是偶数");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw ApiException.validation("hex 解码失败: 含非法字符");
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Base64(标准字母表)→ 字节数组;非法输入报错。 */
    private static byte[] fromBase64(String in) {
        try {
            return java.util.Base64.getDecoder().decode(trimSpace(in));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(String.format("base64 解码失败: %s", e.getMessage()));
        }
    }

    /**
     * 解析 3DES 密钥:接受 24 字节明文,或 48 位十六进制。
     *
     * <p>长度判定用【字节数】:非 ASCII 密钥按字符数判会放行一个 AES/3DES 收不下的密钥。
     */
    private static byte[] des3Key(String v) {
        String s = trimSpace(v);
        if (s.isEmpty()) {
            throw ApiException.validation("密钥不能为空");
        }
        int bytes = s.getBytes(StandardCharsets.UTF_8).length;
        // 48 位十六进制 → 24 字节
        if (s.length() == 48) {
            try {
                return fromHexUpper(s);
            } catch (ApiException ignored) {
                // 不是合法十六进制 → 落到下面的长度判定
            }
        }
        if (bytes != 24) {
            throw ApiException.validation(String.format(
                    "密钥需为 24 字节(或 48 位十六进制),实际 %d 字节", bytes));
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 3DES-ECB / PKCS7 加密。
     *
     * <p>与 Go 的 {@code des.NewTripleDESCipher} 逐字节兼容(同为 ECB + PKCS5/7 填充,
     * 在 8 字节块下 PKCS5 与 PKCS7 等价)。
     */
    private static byte[] des3Encrypt(String plain, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DESede"));
            return cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw ApiException.validation(String.format("3DES 加密失败: %s", e.getMessage()));
        }
    }

    /** 3DES-ECB / PKCS7 解密。 */
    private static String des3Decrypt(byte[] cipherText, byte[] key) {
        if (cipherText.length == 0 || cipherText.length % 8 != 0) {
            throw ApiException.validation(String.format(
                    "密文长度 %d 不是 %d 的整数倍", cipherText.length, 8));
        }
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "DESede"));
        } catch (GeneralSecurityException e) {
            throw ApiException.validation(String.format("3DES 初始化失败: %s", e.getMessage()));
        }
        try {
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 最典型的原因是填充校验失败(密钥不对 / 密文被改),与 Go 的 pkcs7Unpad 报错同义
            throw ApiException.validation(String.format("PKCS7 填充无效: %s", e.getMessage()));
        }
    }

    // ─────────────────────────── 日期解析 ───────────────────────────

    /** 日期格式词汇表(键为小写形式)。 */
    private static final Map<String, String> DATE_LAYOUTS = new LinkedHashMap<String, String>();

    /** 便于错误提示里列出可选格式。 */
    private static final String DATE_FORMAT_NAMES =
            "auto / yyyyMMdd / yyyy-MM-dd / yyyy/MM/dd / yyyy-MM-dd HH:mm:ss / yyyyMMddHHmmss / unix_s / unix_ms";

    static {
        DATE_LAYOUTS.put("yyyymmdd", "yyyyMMdd");
        DATE_LAYOUTS.put("yyyy-mm-dd", "yyyy-MM-dd");
        DATE_LAYOUTS.put("yyyy/mm/dd", "yyyy/MM/dd");
        DATE_LAYOUTS.put("yyyy-mm-dd hh:mm:ss", "yyyy-MM-dd HH:mm:ss");
        DATE_LAYOUTS.put("yyyymmddhhmmss", "yyyyMMddHHmmss");
    }

    /** 按指定格式解析。auto 时依次尝试常见格式。 */
    private static LocalDateTime parseDate(String s, String format) {
        String v = trimSpace(s);
        if (v.isEmpty()) {
            throw ApiException.validation("值为空,无法解析为日期");
        }
        String f = format == null ? "" : format.trim().toLowerCase();
        if (f.isEmpty()) {
            f = "auto";
        }
        if (!"auto".equals(f)) {
            LocalDateTime t = tryParseByFormat(v, f);
            if (t != null) {
                return t;
            }
            throw ApiException.validation(String.format(
                    "按格式 %s 解析 %s 失败(可选格式:%s)", format, GoFmt.q(v), DATE_FORMAT_NAMES));
        }
        // auto:按「最可能」的顺序试。先 unix(纯数字且位数够),再常见文本格式。
        String[] candidates = {"unix_s", "unix_ms", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd",
                "yyyyMMddHHmmss", "yyyyMMdd"};
        for (String cand : candidates) {
            LocalDateTime t = tryParseByFormat(v, cand.toLowerCase());
            if (t != null) {
                return t;
            }
        }
        throw ApiException.validation(String.format(
                "无法自动识别日期 %s(可选格式:%s)", GoFmt.q(v), DATE_FORMAT_NAMES));
    }

    /** 按格式尝试解析;失败返回 null。 */
    private static LocalDateTime tryParseByFormat(String v, String lowerFormat) {
        ZoneId zone = ZoneId.systemDefault();
        if ("unix_s".equals(lowerFormat) || "unix_ms".equals(lowerFormat)) {
            long n;
            try {
                n = Long.parseLong(v);
            } catch (NumberFormatException e) {
                return null;
            }
            if ("unix_s".equals(lowerFormat)) {
                // 10 位左右才是秒级;太短的数字(如 20240115)不该被当成时间戳
                if (n < 100_000_000L || n > 10_000_000_000L) {
                    return null;
                }
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(n), zone);
            }
            if (n < 100_000_000_000L || n > 10_000_000_000_000L) {
                return null;
            }
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(n), zone);
        }
        String layout = DATE_LAYOUTS.get(lowerFormat);
        if (layout == null) {
            return null;
        }
        try {
            if (layout.indexOf('H') >= 0) {
                return LocalDateTime.parse(v, DateTimeFormatter.ofPattern(layout));
            }
            // 只有日期的格式:补上 00:00:00,与 Go 的 time.Parse(缺少的部分取零值)一致
            return LocalDate.parse(v, DateTimeFormatter.ofPattern(layout)).atStartOfDay();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 按指定格式输出(unix_s / unix_ms 输出时间戳)。 */
    private static String formatDate(LocalDateTime t, String format) {
        String f = format == null ? "" : format.trim().toLowerCase();
        if (f.isEmpty() || "auto".equals(f) || "yyyy-mm-dd".equals(f)) {
            return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        if ("unix_s".equals(f)) {
            return String.valueOf(t.atZone(ZoneId.systemDefault()).toEpochSecond());
        }
        if ("unix_ms".equals(f)) {
            return String.valueOf(t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        String layout = DATE_LAYOUTS.get(f);
        if (layout == null) {
            throw ApiException.validation(String.format(
                    "不支持的输出格式 %s(可选:%s)", GoFmt.q(format), DATE_FORMAT_NAMES));
        }
        return t.format(DateTimeFormatter.ofPattern(layout));
    }

    /**
     * 校验「格式类」参数是否在词汇表内。
     *
     * <p>天数 / 参照日期等非格式参数由各自的校验处理,这里只放行。
     */
    private static void dateArgsValidator(String alias, String fn, List<QueryPipelineArg> args, int min, int max) {
        if (args.size() < min || (max >= 0 && args.size() > max)) {
            throw ApiException.validation(String.format("字段 %s 的 %s 需要 %d 个参数", alias, fn, min));
        }
        for (int i = 0; i < args.size(); i++) {
            String v = argAt(args, i);
            String f = v.toLowerCase();
            if (f.isEmpty() || "auto".equals(f) || "today".equals(f) || "s".equals(f) || "ms".equals(f)) {
                continue;
            }
            if (DATE_LAYOUTS.containsKey(f) || "unix_s".equals(f) || "unix_ms".equals(f)) {
                continue;
            }
            if (isPlainNumberV(f)) {
                continue;
            }
            try {
                parseDate(v, "auto");
                continue;
            } catch (ApiException ignored) {
                // 既不是格式、也不是数字、也解析不成日期 —— 走下面的报错
            }
            throw ApiException.validation(String.format(
                    "字段 %s 的 %s 参数 %s 不是可识别的格式或日期(可选:%s)",
                    alias, fn, GoFmt.q(v), DATE_FORMAT_NAMES));
        }
    }

    /** 供测试与前端对齐用的算子名清单(按注册顺序)。 */
    public static List<String> opNames() {
        return new ArrayList<String>(OPS.keySet());
    }

    /**
     * 四舍五入,「半数远离零」(对应 Go 的 {@code math.Round})。
     *
     * <p>⚠️ 不能用 {@code Math.round}:它等价于 {@code floor(x + 0.5)},对负半数会向零取整 ——
     * {@code Math.round(-2.5) = -2},而 Go 的 {@code math.Round(-2.5) = -3}。
     * 财务口径的场景下这个差异是用户可见的。
     */
    private static double roundHalfAwayFromZero(double x) {
        return x < 0 ? -Math.floor(-x + 0.5) : Math.floor(x + 0.5);
    }

    /** 保留:便于将来按名字取全部算子名做前后端一致性测试。 */
}
