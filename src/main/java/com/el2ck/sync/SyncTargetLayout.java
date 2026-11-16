package com.el2ck.sync;

import com.el2ck.ckpool.QueryResult;
import com.el2ck.entity.sync.SyncTask;
import java.util.*;

/**
 * 目标表的写入列检查与补值说明。只检查 DESCRIBE 元数据，不改任务映射、业务表或旧断点指纹。
 * 不向通用 Column 增加字段，避免改变已持久化结构的序列化结果。
 */
final class SyncTargetLayout {
    private SyncTargetLayout() { }

    static List<String> inspect(QueryResult description, SyncTask task, boolean directory) {
        Set<String> written = new HashSet<>();
        if (task.getMapping() != null) task.getMapping().forEach(m -> written.add(m.getTargetColumn().trim()));
        written.add(task.isOds() ? "ods_source_id" : "source_id");
        written.add(task.isOds() ? "ods_insert_at" : "create_time");
        if (directory) written.add("path");
        List<String> defaults = new ArrayList<>(), expressions = new ArrayList<>(), computed = new ArrayList<>();
        for (List<Object> row : description.getRows()) {
            if (row.size() < 4) throw new IllegalArgumentException("目标结构缺少默认值类型或表达式信息，请重新读取目标结构");
            String name = String.valueOf(row.get(0));
            String kind = row.get(2) == null ? "" : row.get(2).toString().trim().toUpperCase(Locale.ROOT);
            // ALIAS / MATERIALIZED 由目标计算；EPHEMERAL 不存储，不能作为持久化同步目的列。
            boolean writable = kind.isEmpty() || "DEFAULT".equals(kind);
            if (written.contains(name)) {
                if (!writable) throw new IllegalArgumentException("目标字段「" + name + "」为 " + kind
                        + " 列，不能作为同步写入列；请改为普通列或 DEFAULT 列");
            } else if (kind.isEmpty()) defaults.add(name);
            else if ("DEFAULT".equals(kind)) expressions.add(name);
            else computed.add(name + "（" + kind + "）");
        }
        // 旧任务的 null 保留原行为；新任务空名单则禁止任何未确认的可写列补值。
        // 名单仅控制是否允许省略列，不改变 INSERT 内容，因此不参与写入断点指纹。
        if (task.getAllowedTargetDefaults() != null) {
            Set<String> allowed = new HashSet<>(task.getAllowedTargetDefaults());
            List<String> unexpected = new ArrayList<>(defaults);
            unexpected.addAll(expressions);
            unexpected.removeIf(allowed::contains);
            if (!unexpected.isEmpty()) {
                List<String> errors = new ArrayList<>();
                addNote(errors, unexpected, "存在未确认补默认值的目标列，请在字段映射中补充映射或明确允许补值");
                throw new IllegalArgumentException(errors.get(0));
            }
        }
        List<String> notes = new ArrayList<>();
        addNote(notes, defaults, "未映射目标列由 ClickHouse 按类型补默认值（如空字符串、0 或 NULL）");
        addNote(notes, expressions, "未映射目标列由 ClickHouse 执行 DEFAULT 表达式");
        addNote(notes, computed, "未直接写入的目标计算或临时列按表定义处理");
        return notes;
    }

    private static void addNote(List<String> notes, List<String> names, String message) {
        if (names.isEmpty()) return;
        // 宽表只在运行摘要展示有限列名，完整清单在编辑映射页查看，避免成功后因日志过长而落库失败。
        StringBuilder preview = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown >= 30 || preview.length() + name.length() > 1500) break;
            if (shown > 0) preview.append('、');
            preview.append(name); shown++;
        }
        if (shown < names.size()) preview.append("…共 ").append(names.size()).append(" 列，完整列表见字段映射页面");
        notes.add(message + "：" + preview);
    }
}
