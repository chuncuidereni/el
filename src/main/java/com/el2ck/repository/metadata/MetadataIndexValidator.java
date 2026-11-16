package com.el2ck.repository.metadata;

import java.sql.*;
import java.util.*;

/**
 * 只读核对事务所依赖的 InnoDB 引擎和关键索引。列存在并不意味着唯一约束存在；
 * 缺失资产唯一键或断点唯一键时，并发检查/恢复会失去数据库兜底，不能继续启动。
 */
final class MetadataIndexValidator {
    private static final String TABLE_QUERY = "SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()";
    private static final String INDEX_QUERY = "SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART"
            + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX";

    private MetadataIndexValidator() { }

    static Properties contract() {
        return MetadataSchemaValidator.load("/metadata-critical-indexes.yaml", "metadata-critical-indexes.yaml");
    }

    static List<String> check(Connection connection, Set<String> presentTables) throws SQLException {
        Map<String,String> engines = new HashMap<>();
        Map<String,Map<String,IndexDefinition>> indexes = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement(TABLE_QUERY)) {
            query.setQueryTimeout(10);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) engines.put(rows.getString(1), rows.getString(2));
            }
        }
        try (PreparedStatement query = connection.prepareStatement(INDEX_QUERY)) {
            query.setQueryTimeout(10);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Map<String,IndexDefinition> table = indexes.computeIfAbsent(rows.getString(1), ignored -> new HashMap<>());
                    String name = rows.getString(2);
                    IndexDefinition index = table.get(name);
                    if (index == null) { index = new IndexDefinition(rows.getInt(3) == 0); table.put(name,index); }
                    index.add(rows.getInt(4),rows.getString(5),rows.getObject(6) != null);
                }
            }
        }
        return incompatible(contract(),presentTables,engines,indexes);
    }

    static List<String> incompatible(Properties required, Set<String> presentTables, Map<String,String> engines,
                                     Map<String,Map<String,IndexDefinition>> indexes) {
        List<String> errors = new ArrayList<>();
        Set<String> checkedEngines = new HashSet<>();
        for (String key : required.stringPropertyNames()) {
            int separator = key.indexOf('.');
            if (separator <= 0) throw new IllegalStateException("关键索引契约名称无效：" + key);
            String table = key.substring(0,separator);
            ExpectedIndex expected = ExpectedIndex.parse(key,required.getProperty(key));
            if (!presentTables.contains(table)) continue; // 缺表由列契约统一报告，避免重复噪声。
            if (checkedEngines.add(table) && !"InnoDB".equalsIgnoreCase(engines.get(table)))
                errors.add(table + "（表引擎须为 InnoDB，实际 " + Objects.toString(engines.get(table),"未知") + "）");
            Collection<IndexDefinition> actual = indexes.getOrDefault(table,Collections.emptyMap()).values();
            if (actual.stream().noneMatch(expected::matches))
                errors.add(key + "（缺少兼容的 " + (expected.unique ? "UNIQUE" : "INDEX")
                        + "(" + String.join(",",expected.columns) + ")）");
        }
        Collections.sort(errors);
        return errors;
    }

    static final class IndexDefinition {
        final boolean unique;
        final SortedMap<Integer,String> columns = new TreeMap<>();
        boolean fullColumns = true;
        IndexDefinition(boolean unique) { this.unique = unique; }
        void add(int position, String column, boolean prefix) {
            // 表达式索引和字符串前缀索引不等同于所需的完整字段键。
            if (position < 1 || column == null || prefix) fullColumns = false;
            columns.put(position,column);
        }
    }

    private static final class ExpectedIndex {
        final boolean unique;
        final List<String> columns;
        ExpectedIndex(boolean unique,List<String> columns) { this.unique=unique;this.columns=columns; }

        static ExpectedIndex parse(String key,String value) {
            String[] parts=value.split("\\|",-1);
            if (parts.length!=2 || !("unique".equals(parts[0]) || "index".equals(parts[0])))
                throw new IllegalStateException("关键索引契约格式无效：" + key);
            List<String> columns=Arrays.asList(parts[1].split(",",-1));
            if (columns.stream().anyMatch(column -> !column.matches("[a-z][a-z0-9_]*"))
                    || new HashSet<>(columns).size()!=columns.size())
                throw new IllegalStateException("关键索引契约列名无效：" + key);
            return new ExpectedIndex("unique".equals(parts[0]),columns);
        }

        boolean matches(IndexDefinition actual) {
            if (!actual.fullColumns || actual.columns.size()<columns.size()) return false;
            List<String> found=new ArrayList<>(actual.columns.values());
            if (unique) {
                // 唯一键列顺序不影响唯一性；多一列则会放过原本应冲突的行，不能视为等价。
                return actual.unique && found.size()==columns.size() && new HashSet<>(found).equals(new HashSet<>(columns));
            }
            // 查询索引必须匹配最左列顺序；允许末尾还有其他覆盖字段。
            return found.subList(0,columns.size()).equals(columns);
        }
    }
}
