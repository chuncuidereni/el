package com.el2ck.repository.metadata;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * 【文件职责】启动时只读核对元数据表/列、关键字段、事务表引擎和关键索引，缺结构时给出手工修正路径并终止启动。
 * 不执行迁移、不写版本号或 checksum，不修改数据库数据、账号或权限。
 */
public final class MetadataSchemaValidator {
    private static final String COLUMN_QUERY = "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,IS_NULLABLE,"
            + "CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()";

    private MetadataSchemaValidator() { }

    public static void validate(DataSource database) {
        Properties required = contract();
        Properties critical = criticalContract();
        List<String> contractIssues = criticalCoverageIssues(required, critical);
        Map<String, Map<String, ColumnDefinition>> actual = new HashMap<>();
        List<String> indexIssues;
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(COLUMN_QUERY)) {
                query.setQueryTimeout(10);
                try (ResultSet columns = query.executeQuery()) {
                    while (columns.next()) {
                        long maximumLength = columns.getLong(5);
                        Long nullableLength = columns.wasNull() ? null : maximumLength;
                        actual.computeIfAbsent(columns.getString(1), ignored -> new HashMap<>())
                                .put(columns.getString(2), new ColumnDefinition(
                                        columns.getString(3), columns.getString(4), nullableLength));
                    }
                }
            }
            indexIssues = MetadataIndexValidator.check(connection,actual.keySet());
        } catch (SQLException e) {
            throw new IllegalStateException(databaseReadFailure(e), e);
        }
        List<String> missing = missing(required, actual);
        List<String> incompatible = incompatible(critical, actual);
        incompatible.addAll(contractIssues);
        incompatible.addAll(indexIssues);
        Collections.sort(incompatible);
        if (!missing.isEmpty() || !incompatible.isEmpty()) {
            StringBuilder message = new StringBuilder("MySQL 元数据结构检查失败。");
            if (!missing.isEmpty()) message.append("缺少：").append(String.join(", ", missing)).append("。");
            if (!incompatible.isEmpty()) message.append("定义不兼容：").append(String.join(", ", incompatible)).append("。");
            message.append("请按 docs/mysql/README.md 手工补齐结构后重启；应用不会自动修改表结构。");
            throw new IllegalStateException(message.toString());
        }
    }

    /**
     * 结构查询也可能因数据库磁盘满而失败，不能一律提示连接/权限。
     * 只输出错误码、SQLState 和固定建议；驱动原始消息可能包含连接信息，不送到部署终端。
     */
    private static String databaseReadFailure(SQLException error) {
        String state=error.getSQLState();
        String code="（数据库错误码 "+error.getErrorCode()
                +(state!=null && state.matches("[A-Z0-9]{5}") ? "，SQLState "+state : "")+"）";
        String prefix="MySQL 元数据结构检查失败：";
        if(error.getErrorCode()==1021)
            return prefix+"数据库磁盘或临时目录空间不足"+code
                    +"。请在数据库服务器检查磁盘可用空间和 inode，释放空间后重新运行部署；本次未修改表结构。";
        if(error instanceof SQLTimeoutException)
            return prefix+"读取元数据超时"+code+"。请检查数据库负载、锁等待和连接状态后重试。";
        return prefix+"无法读取元数据"+code+"。请根据错误码检查数据库状态、连接和读取权限；不要仅凭此提示修改表结构。";
    }

    static Properties contract() {
        return load("/metadata-schema.yaml", "metadata-schema.yaml");
    }

    /**
     * 覆盖断点、进度、观测及并发/权限依赖的基础身份字段；完整历史类型和默认值契约仍需逐表补齐。
     */
    static Properties criticalContract() {
        return load("/metadata-critical-schema.yaml", "metadata-critical-schema.yaml");
    }

    static Properties load(String resource, String displayName) {
        Properties properties = new Properties();
        try (InputStream input = MetadataSchemaValidator.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("缺少 " + displayName + " 结构契约");
            properties.load(input);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + displayName + " 结构契约失败", e);
        }
    }

    static List<String> missing(Properties required, Map<String, Map<String, ColumnDefinition>> actual) {
        List<String> missing = new ArrayList<>();
        for (String table : required.stringPropertyNames()) {
            Map<String, ColumnDefinition> columns = actual.get(table);
            if (columns == null) {
                missing.add(table);
                continue;
            }
            for (String column : required.getProperty(table).split(",")) {
                if (!column.isEmpty() && !columns.containsKey(column)) missing.add(table + "." + column);
            }
        }
        Collections.sort(missing);
        return missing;
    }

    static List<String> incompatible(Properties critical, Map<String, Map<String, ColumnDefinition>> actual) {
        List<String> incompatible = new ArrayList<>();
        for (String qualifiedName : critical.stringPropertyNames()) {
            int separator = qualifiedName.indexOf('.');
            if (separator <= 0 || separator == qualifiedName.length() - 1) {
                throw new IllegalStateException("关键字段契约名称无效：" + qualifiedName);
            }
            String table = qualifiedName.substring(0, separator);
            String column = qualifiedName.substring(separator + 1);
            Map<String, ColumnDefinition> columns = actual.get(table);
            if (columns == null) {
                incompatible.add(qualifiedName + "（关键字段所在表不存在）");
                continue;
            }
            if (!columns.containsKey(column)) {
                incompatible.add(qualifiedName + "（关键字段不存在）");
                continue;
            }

            ExpectedColumn expected = ExpectedColumn.parse(qualifiedName, critical.getProperty(qualifiedName));
            ColumnDefinition found = columns.get(column);
            if (!expected.matches(found)) {
                incompatible.add(qualifiedName + "（实际 " + found.describe() + "，要求 " + expected.describe() + "）");
            }
        }
        Collections.sort(incompatible);
        return incompatible;
    }

    /**
     * 校验关键字段契约不能脱离完整结构契约独立漂移。critical 中的每一项都必须被
     * metadata-schema.yaml 的基础表列契约覆盖，否则升级新增的关键字段可能在部署时
     * 被遗漏，而运行时校验仍误以为全部通过。
     */
    static List<String> criticalCoverageIssues(Properties required, Properties critical) {
        List<String> issues = new ArrayList<>();
        for (String qualifiedName : critical.stringPropertyNames()) {
            int separator = qualifiedName.indexOf('.');
            if (separator <= 0 || separator == qualifiedName.length() - 1) {
                issues.add("关键字段契约名称无效：" + qualifiedName);
                continue;
            }
            String table = qualifiedName.substring(0, separator);
            String column = qualifiedName.substring(separator + 1);
            String requiredColumns = required.getProperty(table);
            if (requiredColumns == null) {
                issues.add(qualifiedName + "（未包含在基础结构契约中）");
                continue;
            }
            if (!Arrays.asList(requiredColumns.split(",")).contains(column)) {
                issues.add(qualifiedName + "（基础结构契约缺少该列）");
            }
        }
        Collections.sort(issues);
        return issues;
    }

    static final class ColumnDefinition {
        final String dataType;
        final String nullable;
        final Long maximumLength;

        ColumnDefinition(String dataType, String nullable, Long maximumLength) {
            this.dataType = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
            this.nullable = nullable == null ? "" : nullable.toUpperCase(Locale.ROOT);
            this.maximumLength = maximumLength;
        }

        String describe() {
            return dataType + "/" + nullable + "/" + (maximumLength == null ? "-" : maximumLength);
        }
    }

    private static final class ExpectedColumn {
        final String dataType;
        final String nullable;
        final Long maximumLength;

        private ExpectedColumn(String dataType, String nullable, Long maximumLength) {
            this.dataType = dataType;
            this.nullable = nullable;
            this.maximumLength = maximumLength;
        }

        static ExpectedColumn parse(String qualifiedName, String value) {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new IllegalStateException("关键字段契约格式无效：" + qualifiedName);
            }
            try {
                Long maximumLength = parts[2].isEmpty() ? null : Long.valueOf(parts[2]);
                return new ExpectedColumn(parts[0].toLowerCase(Locale.ROOT),
                        parts[1].toUpperCase(Locale.ROOT), maximumLength);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("关键字段长度契约无效：" + qualifiedName, e);
            }
        }

        boolean matches(ColumnDefinition found) {
            return dataType.equals(found.dataType)
                    && nullable.equals(found.nullable)
                    // 空长度表示该类型不约束 CHARACTER_MAXIMUM_LENGTH。MySQL 对
                    // TEXT/LONGTEXT 会返回 65535/4294967295，不能据此判定契约不兼容。
                    && (maximumLength == null || maximumLength.equals(found.maximumLength));
        }

        String describe() {
            return dataType + "/" + nullable + "/" + (maximumLength == null ? "-" : maximumLength);
        }
    }
}
