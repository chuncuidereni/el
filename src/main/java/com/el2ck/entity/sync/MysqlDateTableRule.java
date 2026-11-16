package com.el2ck.entity.sync;

import lombok.Data;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MySQL 每日分表规则。配置随任务 schema_json 保存，不改变源表或目标表字段。 */
@Data
public class MysqlDateTableRule {
    public static final String DATE_TOKEN = "{yyyyMMdd}";
    private String tablePattern;
    private String startDate;
    private String endDate;
    private String dateFormat = "yyyyMMdd";
    /**
     * 增量任务第一次发现新日期表时的读取策略：
     * 空 = 只接上次扫描时间之后（不补历史）；{@code full} = 从头全量补齐。
     * 与日期区间放在同一组配置，避免新表策略和执行参数分散。
     *
     * <p>空值不参与 JSON：旧任务没有此字段，必须保持断点指纹不变。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String newTablePolicy;
    /** 明确业务日期的时区，避免容器默认 UTC 导致跨日发现晚八小时。 */
    private String timeZone = "Asia/Shanghai";

    public void validate() {
        if (!"yyyyMMdd".equals(dateFormat)) throw new IllegalArgumentException("每日分表日期格式目前支持 yyyyMMdd（8 位）");
        // 空串表示默认策略（只接基线之后）；显式 full 才补历史，避免旧任务被静默改成全量回填。
        if (newTablePolicy != null) {
            newTablePolicy = newTablePolicy.trim();
            if (newTablePolicy.isEmpty()) newTablePolicy = null;
            else if (!"full".equals(newTablePolicy))
                throw new IllegalArgumentException("新表策略仅支持“只接基线之后”或“全量补齐”");
        }
        if (tablePattern == null) throw new IllegalArgumentException("请填写表名规则，如 xx_xx_{yyyyMMdd}");
        tablePattern = tablePattern.trim();
        if (!tablePattern.matches("(?:[A-Za-z_][A-Za-z0-9_]*\\.)?[A-Za-z_][A-Za-z0-9_]*\\{yyyyMMdd\\}[A-Za-z0-9_]*"))
            throw new IllegalArgumentException("表名规则需包含一个 {yyyyMMdd}，其余仅允许字母、数字、下划线，可带数据库前缀");
        // MySQL 单个表名最长 64 字符，数据库前缀不计入表名长度。
        String rendered = tablePattern.replace("{yyyyMMdd}", "20000101");
        for (String part : rendered.split("\\."))
            if (part.length() > 64) throw new IllegalArgumentException("数据库名或分表名不能超过 64 个字符");
        ZoneId zone;
        try { zone = ZoneId.of(timeZone); }
        catch (RuntimeException e) { throw new IllegalArgumentException("分表业务时区无效"); }
        if (hasEndDate() && !hasStartDate())
            throw new IllegalArgumentException("填写结束日期时必须同时填写开始日期");
        // 不限定日期时直接匹配真实表清单，不构造虚拟的起止日期。
        if (!hasStartDate()) return;
        LocalDate start = parseDate(startDate);
        // 结束日期为空表示持续读取到业务当天；显式填写时仍不得超过 3661 天。
        LocalDate end = endDate == null || endDate.trim().isEmpty() ? LocalDate.now(zone) : parseDate(endDate);
        if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于起始日期");
        if (ChronoUnit.DAYS.between(start, end) > 3660)
            throw new IllegalArgumentException("每日分表的日期范围不能超过 3661 天");
    }

    public static LocalDate parseDate(String value) {
        try {
            if (value != null) value = value.trim();
            if (value == null) throw new IllegalArgumentException();
            // Compatibility for older cached frontends and previously saved tasks: accept both
            // 20260101 and 2026-01-01, then compare dates as the canonical 8-digit form.
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) value = value.replace("-", "");
            if (!value.matches("[0-9]{8}")) throw new IllegalArgumentException();
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException e) { throw new IllegalArgumentException("起止日期必须是真实有效的 yyyyMMdd 日期（8 位）"); }
    }

    public LocalDate effectiveEnd(LocalDate today) {
        LocalDate end = endDate == null || endDate.trim().isEmpty() ? today : parseDate(endDate);
        return end.isBefore(today) ? end : today;
    }

    public String objectOn(LocalDate date) {
        return tablePattern.replace(DATE_TOKEN, date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    public boolean hasStartDate() {
        return startDate != null && !startDate.trim().isEmpty();
    }

    public boolean hasEndDate() {
        return endDate != null && !endDate.trim().isEmpty();
    }

    /** 精确匹配目录中的表名，并排除无效日期和备份表；下划线按普通字符处理。 */
    public boolean matchesTableName(String tableName) {
        return dateOf(tableName) != null;
    }

    /** 从表名中解析日期；不匹配规则或日期非法时返回 null。 */
    public LocalDate dateOf(String tableName) {
        if (tablePattern == null || tableName == null) return null;
        String name = tableName.substring(tableName.lastIndexOf('.') + 1);
        String template = tablePattern.substring(tablePattern.lastIndexOf('.') + 1);
        int token = template.indexOf(DATE_TOKEN);
        if (token < 0) return null;
        Matcher match = Pattern.compile(Pattern.quote(template.substring(0, token))
                + "([0-9]{8})" + Pattern.quote(template.substring(token + DATE_TOKEN.length())))
                .matcher(name);
        if (!match.matches()) return null;
        try {
            return parseDate(match.group(1));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
