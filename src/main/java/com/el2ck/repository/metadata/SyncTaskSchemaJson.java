package com.el2ck.repository.metadata;

import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * schema_json 的版本化配置。旧数组视为未显式配置，使用默认开启的 CSV 尾列策略。
 * v2 保存 CSV 策略；v3 额外保存 MySQL 日期分表规则，旧版本无法理解 v3 时应停止而非误按单表运行。
 * v4 保存未映射列补默认值的明确名单；缺失名单不降级为历史兼容模式。
 * v5 额外保存 SFTP CSV 编码策略，不识别新版本的旧程序必须拒绝执行。
 * 复用现有 MySQL 文本列，API 的 schema 仍为列数组，目标库无需增加字段。
 */
final class SyncTaskSchemaJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SyncTaskSchemaJson() { }

    static String encode(SyncTask task) {
        List<Column> columns = task.getSchema() == null ? Collections.emptyList() : task.getSchema();
        Map<String,Object> config = new LinkedHashMap<>();
        config.put("version", task.getCsvEncoding() != null ? 5 : task.getAllowedTargetDefaults() != null ? 4 : task.getMysqlDateTables() == null ? 2 : 3);
        config.put("columns", columns);
        config.put("ignoreExtraCsvColumns", task.isIgnoreExtraCsvColumns());
        if (task.getMysqlDateTables() != null) config.put("mysqlDateTables", task.getMysqlDateTables());
        if (task.getAllowedTargetDefaults() != null || task.getCsvEncoding() != null)
            config.put("allowedTargetDefaults", task.getAllowedTargetDefaults() == null ? Collections.emptyList() : task.getAllowedTargetDefaults());
        if (task.getCsvEncoding() != null) config.put("csvEncoding", com.el2ck.util.CsvEncoding.normalize(task.getCsvEncoding()));
        try { return JSON.writeValueAsString(config); }
        catch (Exception e) { throw new IllegalStateException("同步任务结构配置无法保存", e); }
    }

    static void decode(SyncTask task) {
        try {
            String text = task.getSchemaJson();
            task.setIgnoreExtraCsvColumns(true);
            task.setMysqlDateTables(null);
            task.setAllowedTargetDefaults(null);
            task.setCsvEncoding(null);
            if (text == null || text.trim().isEmpty()) {
                task.setSchema(new ArrayList<>());
                return;
            }
            JsonNode root = JSON.readTree(text);
            JsonNode columns = root;
            if (root != null && root.isObject()) {
                JsonNode version = root.get("version"), policy = root.get("ignoreExtraCsvColumns");
                if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || (version.intValue() < 2 || version.intValue() > 5)
                        || policy == null || !policy.isBoolean())
                    throw new IllegalArgumentException("不支持的任务结构配置版本或尾列策略");
                columns = root.get("columns");
                task.setIgnoreExtraCsvColumns(policy.booleanValue());
                if (version.intValue() == 3 || (version.intValue() >= 4 && root.has("mysqlDateTables"))) {
                    JsonNode rule = root.get("mysqlDateTables");
                    if (rule == null || !rule.isObject()) throw new IllegalArgumentException("缺少 MySQL 分表规则");
                    task.setMysqlDateTables(JSON.convertValue(rule, com.el2ck.entity.sync.MysqlDateTableRule.class));
                    task.getMysqlDateTables().validate();
                }
                if (version.intValue() >= 4) {
                    JsonNode allowed = root.get("allowedTargetDefaults");
                    if (allowed == null || !allowed.isArray()) throw new IllegalArgumentException("缺少未映射目标列补值名单");
                    Set<String> names = new LinkedHashSet<>();
                    for (JsonNode name : allowed) {
                        if (!name.isTextual() || name.textValue().trim().isEmpty()
                                || !names.add(name.textValue().trim()))
                            throw new IllegalArgumentException("未映射目标列补值名单包含空列名、重复列名或非文本内容");
                    }
                    task.setAllowedTargetDefaults(new ArrayList<>(names));
                }
                if (version.intValue() == 5) {
                    JsonNode encoding = root.get("csvEncoding");
                    if (encoding == null || !encoding.isTextual() || encoding.textValue().trim().isEmpty())
                        throw new IllegalArgumentException("缺少 CSV 编码策略");
                    task.setCsvEncoding(com.el2ck.util.CsvEncoding.normalize(encoding.textValue()));
                }
            }
            if (columns == null || !columns.isArray()) throw new IllegalArgumentException("任务列结构必须是数组");
            task.setSchema(JSON.convertValue(columns, new TypeReference<List<Column>>() { }));
        } catch (Exception e) {
            throw new IllegalStateException("同步任务结构配置无法解析: " + task.getId(), e);
        }
    }
}
