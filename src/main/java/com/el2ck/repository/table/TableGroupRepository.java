package com.el2ck.repository.table;

/**
 * 【文件职责】持久化 ClickHouse 表分组与表引用关系，支持分组管理和引用校验。
 */

import com.el2ck.repository.group.GroupRepository;

import com.el2ck.entity.table.TableGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 表分组持久层(tbl_groups + 成员表 tbl_group_tables)。
 *
 * <p>只写 SQL、不做业务校验(名称合法性、连接/分组是否存在都在 {@link com.el2ck.service.table.TableGroupService});
 * 涉及多条语句的操作一律手写事务,与 {@link GroupRepository} 的约定一致。
 *
 * <p>「一张表在同一个 (连接, 库) 内至多属于一个分组」这条约束由 {@link #setTableGroup} 保证:
 * 先摘掉该表在本连接本库的旧归属,再加入目标分组。
 */
@Repository
public class TableGroupRepository {
    /** 原生 JDBC 数据源:需要手写事务时不再走 JdbcTemplate,直接用 DataSource 取连接。 */
    private final DataSource dataSource;
    /** 常规单语句读写走 JdbcTemplate(自动获取/释放连接)。 */
    private final JdbcTemplate jdbc;

    /** 分组行的列映射(时间列是 TEXT,故用 getString 而非 getTimestamp)。 */
    private static final RowMapper<TableGroup> MAPPER = (rs, row) -> {
        TableGroup group = new TableGroup();
        group.setId(rs.getLong("id"));
        group.setConnectionId(rs.getLong("connection_id"));
        group.setDb(rs.getString("db"));
        group.setName(rs.getString("name"));
        group.setRemark(rs.getString("remark"));
        group.setCreatedAt(rs.getString("created_at"));
        group.setUpdatedAt(rs.getString("updated_at"));
        return group;
    };
    private static final String SELECT =
            "SELECT id, connection_id, db, name, remark, created_at, updated_at FROM tbl_groups";

    /**
     * 摘掉某表在「本连接本库」全部分组里的归属。
     * 子查询限定 connection_id + db,避免误删同名的表在其它库/连接下的归属。
     */
    private static final String UNASSIGN_SQL = "DELETE FROM tbl_group_tables WHERE table_name=?"
            + " AND group_id IN (SELECT id FROM tbl_groups WHERE connection_id=? AND db=?)";

    /** 构造器:数据源由 Spring 注入,同时派生一个 JdbcTemplate。 */
    public TableGroupRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 连接是否存在。分组是连接的下级概念,服务层用它兜底判 404。 */
    public boolean connectionExists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM connections WHERE id=?", Integer.class, id) > 0;
    }

    /** 某连接某库的全部分组,含各自的成员表名与成员数。 */
    public List<TableGroup> list(long connectionId, String db) {
        List<TableGroup> groups = jdbc.query(SELECT + " WHERE connection_id=? AND db=? ORDER BY id DESC",
                MAPPER, connectionId, db);
        // 逐个分组回填成员表名(N+1 查询;分组数量级很小,换取 SQL 与结果结构的简单)
        for (TableGroup group : groups) {
            List<String> tables = jdbc.query(
                    "SELECT table_name FROM tbl_group_tables WHERE group_id=? ORDER BY table_name",
                    (rs, row) -> rs.getString(1), group.getId());
            group.setTables(tables);
            // count 与成员列表同源,避免两处统计口径不一致
            group.setCount(tables.size());
        }
        return groups;
    }

    /** 取单个分组(不含成员表名);不存在返回 null。 */
    public TableGroup get(long id) {
        // 用 query + 判空而非 queryForObject:后者查不到会抛 EmptyResultDataAccessException,这里约定返回 null
        List<TableGroup> found = jdbc.query(SELECT + " WHERE id=?", MAPPER, id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 新增分组,返回自增主键;connectionId 由调用方保证已存在。 */
    public long create(long connectionId, String db, String name, String remark) {
        // GeneratedKeyHolder 承接 executeUpdate 后由驱动返回的自增主键
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tbl_groups(connection_id, db, name, remark) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, connectionId);
            statement.setString(2, db);
            statement.setString(3, name);
            statement.setString(4, remark);
            return statement;
        }, keys);
        // SQLite 未返回主键时视为异常状态,及早抛出而不是返回 0 让上层误用
        if (keys.getKey() == null) throw new IllegalStateException("无法获取新建表分组 ID");
        return keys.getKey().longValue();
    }

    /** 改名称/备注,顺带刷新 updated_at;返回影响行数,0 表示分组不存在。 */
    public int update(long id, String name, String remark) {
        return jdbc.update("UPDATE tbl_groups SET name=?, remark=?, updated_at=datetime('now') WHERE id=?",
                name, remark, id);
    }

    /**
     * 删除分组并清理其成员行;返回是否真的删掉了分组(0 行 = 不存在)。
     * 先删成员再删分组,两者必须同生共死 —— 否则会留下没有分组可归属的孤儿成员行。
     */
    public boolean delete(long id) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 先清理成员行(group_id 无外键约束,不手动清就会残留)
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM tbl_group_tables WHERE group_id=?")) {
                    statement.setLong(1, id);
                    statement.executeUpdate();
                }
                int deleted;
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM tbl_groups WHERE id=?")) {
                    statement.setLong(1, id);
                    deleted = statement.executeUpdate();
                }
                connection.commit();
                // 0 行表示分组本就不存在(此时删除成员行也是空操作,无副作用)
                return deleted > 0;
            } catch (SQLException e) {
                // 两条语句任一失败都整体回滚,不留下「组没了但成员行还在」的中间态
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除表分组失败", e);
        }
    }

    /**
     * 把表设到指定分组:先摘掉它在本连接本库的全部旧归属,再加入目标分组,整体在一个事务内。
     *
     * <p>调用方({@link com.el2ck.service.table.TableGroupService})必须已经确认目标分组存在且属于同一连接与库 ——
     * 本方法只负责「一张表至多属于一个分组」这条不变式,不再重复校验归属。
     */
    public void setTableGroup(long groupId, long connectionId, String db, String table) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 先清旧归属:保证同一张表不会同时挂在两个分组下
                try (PreparedStatement statement = connection.prepareStatement(UNASSIGN_SQL)) {
                    statement.setString(1, table);
                    statement.setLong(2, connectionId);
                    statement.setString(3, db);
                    statement.executeUpdate();
                }
                // INSERT OR IGNORE:重复设置到同一分组时静默成功,而不是撞主键报错
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR IGNORE INTO tbl_group_tables(group_id, table_name) VALUES (?, ?)")) {
                    statement.setLong(1, groupId);
                    statement.setString(2, table);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("设置表所属分组失败", e);
        }
    }

    /** 把表移出本连接本库的全部分组(即置为「未分组」);返回影响的成员行数。 */
    public int ungroupTable(long connectionId, String db, String table) {
        return jdbc.update(UNASSIGN_SQL, table, connectionId, db);
    }

    /**
     * 清理某表在本连接本库的分组归属,供表被删除后调用。
     * 与 {@link #ungroupTable} 是同一条语句:调用场景不同(用户主动移出 / 表已不存在),语义都是"这个表名不再有归属"。
     */
    public int removeTableMembers(long connectionId, String db, String table) {
        return ungroupTable(connectionId, db, table);
    }

    /** 表改名后,把其分组归属从旧表名迁移到新表名;返回迁移的成员行数。 */
    public int renameTableMembers(long connectionId, String db, String oldName, String newName) {
        return jdbc.update("UPDATE tbl_group_tables SET table_name=?"
                        + " WHERE table_name=?"
                        + " AND group_id IN (SELECT id FROM tbl_groups WHERE connection_id=? AND db=?)",
                newName, oldName, connectionId, db);
    }
}
