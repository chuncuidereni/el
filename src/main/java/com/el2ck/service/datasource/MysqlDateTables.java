package com.el2ck.service.datasource;

import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.MysqlDateTableRule;
import lombok.Data;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 只查 MySQL 目录元数据；严格按规则生成名称后匹配，不使用 LIKE 将下划线当通配符。 */
public final class MysqlDateTables {
    private MysqlDateTables() { }

    @Data
    public static class Preview {
        private List<String> tables = new ArrayList<>();
        private List<Column> columns = new ArrayList<>();
        private String effectiveEndDate;
        private String timeZone;
        private String primaryKey;
        private String referenceTable;
        private int missingCount;
        private boolean allDates;
    }

    public static Preview discover(Map<String,Object> config, MysqlDateTableRule rule) {
        rule.validate();
        Preview result = new Preview();
        result.setTimeZone(rule.getTimeZone());
        String[] parts = rule.getTablePattern().split("\\.", 2);
        String database = parts.length == 2 ? parts[0] : Objects.toString(config.get("database"), "");
        if (database.isEmpty()) throw new IllegalArgumentException("MySQL 数据源未指定数据库");
        // yyyyMMdd 字段等宽，按表名排序也就是按日期排序，保证预览和执行顺序一致。
        Set<String> existing = new TreeSet<>();
        try (Connection c = DbSource.openSegmentConnection("mysql", config, 30);
             PreparedStatement q = c.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'")) {
            q.setQueryTimeout(30);
            q.setString(1, database);
            try (ResultSet rows = q.executeQuery()) { while (rows.next()) existing.add(rows.getString(1)); }
        } catch (SQLException e) { throw new IllegalStateException("发现 MySQL 日期分表失败，请检查源连接与表读取权限", e); }
        if (!rule.hasStartDate()) {
            result.setAllDates(true);
            result.setEffectiveEndDate("");
            String prefix = parts.length == 2 ? parts[0] + "." : "";
            for (String table : existing) {
                if (rule.matchesTableName(table)) result.tables.add(prefix + table);
            }
            return result;
        }
        LocalDate start = MysqlDateTableRule.parseDate(rule.getStartDate());
        LocalDate end = rule.effectiveEnd(LocalDate.now(ZoneId.of(rule.getTimeZone())));
        result.setEffectiveEndDate(end.format(DateTimeFormatter.BASIC_ISO_DATE));
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String object = rule.objectOn(date);
            String name = object.substring(object.lastIndexOf('.') + 1);
            if (existing.contains(name)) result.tables.add(object);
            else result.missingCount++;
        }
        return result;
    }

    /** 预览仅读取第一张匹配表的结构；实际运行逐表校验，不能把预览当作全库校验。 */
    public static Preview preview(Map<String,Object> config, MysqlDateTableRule rule) {
        return preview(config, rule, null);
    }

    /** 已保存的参考表仍匹配时必须保留；失效时明确报错，不能偷偷换结构和映射。 */
    public static Preview preview(Map<String,Object> config, MysqlDateTableRule rule, String preferredTable) {
        Preview result = discover(config, rule);
        String preferred = preferredTable == null ? "" : preferredTable.trim();
        if (!preferred.isEmpty() && !result.tables.contains(preferred))
            throw new IllegalArgumentException("原参考表 " + preferred + " 已不存在或不在当前规则范围内，请重新预览规则并核对字段映射");
        if (!result.tables.isEmpty()) {
            String sample = preferred.isEmpty() ? result.tables.get(0) : preferred;
            result.referenceTable = sample;
            result.columns = DbSource.describe("mysql", config, sample);
            result.primaryKey = DbSource.singlePrimaryKey("mysql", config, sample);
        }
        return result;
    }
}
