package com.el2ck.repository.widetable;

/**
 * 【文件职责】持久化宽表相关元数据，为宽表查询和映射配置提供存储访问。
 */

import com.el2ck.entity.widetable.WideTableTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JOIN 型宽表任务的只读仓储：与 Go 程序共用同一份 SQLite 库文件，Java 侧只做 list，
 * 任务的增删改与运行记录都由 Go 侧写入，故这里没有任何写方法。
 */
@Repository
public class WideTableRepository {
    private static final Logger LOG = LoggerFactory.getLogger(WideTableRepository.class);
    /** ObjectMapper 是线程安全的，作为常量复用即可，不必每次解析新建。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 带泛型的 TypeReference：避免 readValue 退化为裸 Map，省掉调用处的强制转换与告警。 */
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE =
            new TypeReference<Map<String, Object>>() {};
    /** 项目未注册 JdbcTemplate bean，故由注入的 DataSource 现场构造；JdbcTemplate 自身线程安全。 */
    private final JdbcTemplate jdbc;


    public WideTableRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 与 Go ListWideTables 一样，最新在前，最多 500 条；坏配置仅跳过该行。 */
    public List<WideTableTask> list() {
        List<WideTableTask> result = new ArrayList<>();
        // 可空列(last_run_at/last_status/last_error)用 coalesce 归一为 ''、last_rows 归一为 0,
        // 使实体字段拿到的永远不会是 null;LIMIT 500 与 Go 侧一致,任务过多时只取最新 500 条
        jdbc.query("SELECT id, name, description, enabled, config_json, target_db, target_table, "
                + "order_key, coalesce(last_run_at, '') AS last_run_at, "
                + "coalesce(last_status, '') AS last_status, "
                + "coalesce(last_rows, 0) AS last_rows, "
                + "coalesce(last_error, '') AS last_error, created_at, updated_at "
                + "FROM wide_table_tasks ORDER BY id DESC LIMIT 500", rs -> {
            // RowCallbackHandler 逐行回调:单行解析失败(如 config_json 损坏)只跳过该行,不影响其余任务
            try {
                result.add(readTask(rs));
            } catch (Exception error) {
                LOG.warn("跳过无法解析的宽表任务记录 id={}: {}", rs.getLong("id"), error.getMessage());
            }
        });
        return result;
    }

    /**
     * 逐行把结果集映射成实体。抽成静态方法是为了让「解析失败」的异常集中抛给 list 的逐行回调，
     * 由它决定跳过该行而不是让整条查询失败。
     */
    private static WideTableTask readTask(ResultSet rs) throws Exception {
        WideTableTask task = new WideTableTask();
        task.setId(rs.getLong("id"));
        task.setName(rs.getString("name"));
        task.setDescription(rs.getString("description"));
        // SQLite 无布尔类型:INTEGER 0/1 → boolean,非 0 一律视为启用
        task.setEnabled(rs.getInt("enabled") != 0);
        // config_json 存整段 JSON 对象文本,反序列化为 Map;格式非法时抛异常,由调用方跳过该行
        task.setConfig(JSON.readValue(rs.getString("config_json"), CONFIG_TYPE));
        task.setTargetDb(rs.getString("target_db"));
        task.setTargetTable(rs.getString("target_table"));
        task.setOrderKey(rs.getString("order_key"));
        task.setLastRunAt(rs.getString("last_run_at"));
        task.setLastStatus(rs.getString("last_status"));
        task.setLastRows(rs.getLong("last_rows"));
        task.setLastError(rs.getString("last_error"));
        task.setCreatedAt(rs.getString("created_at"));
        task.setUpdatedAt(rs.getString("updated_at"));
        return task;
    }
}
