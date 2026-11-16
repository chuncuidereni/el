package com.el2ck.repository.group;

/**
 * 【文件职责】持久化普通资源分组及其成员关系，提供分组增删改查和成员绑定操作。
 */

import com.el2ck.entity.group.DataSourceGroup;
import com.el2ck.entity.group.DatabaseGroup;
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
 * 分组持久层：数据源分组(ds_groups)与数据库分组(db_groups + 成员表 group_dbs)。
 * 只写 SQL 不做业务校验(名称合法性、目标是否存在都在 GroupService)；涉及多条语句的操作一律手写事务。
 */
@Repository
public class GroupRepository {
    @org.springframework.beans.factory.annotation.Autowired
    private com.el2ck.service.auth.ResourceAccessService access;
    /** 原生 JDBC 数据源:需要手写事务时不再走 JdbcTemplate,直接用 DataSource 取连接。 */
    private final DataSource dataSource;
    /** 常规单语句读写走 JdbcTemplate(自动获取/释放连接)。 */
    private final JdbcTemplate jdbc;

    // 时间列均为 TEXT(ISO 字符串),故 created_at/updated_at 用 getString 而非 getTimestamp
    private static final RowMapper<DataSourceGroup> DS_GROUP = (rs, row) -> {
        DataSourceGroup group = new DataSourceGroup();
        group.setId(rs.getLong("id"));
        group.setName(rs.getString("name"));
        group.setRemark(rs.getString("remark"));
        // count 来自 DS_SELECT 里的相关子查询别名(不是 ds_groups 的列)
        group.setCount(rs.getInt("count"));
        group.setCreatedAt(rs.getString("created_at"));
        group.setUpdatedAt(rs.getString("updated_at"));
        return group;
    };
    // count 用相关子查询实时统计每个分组下的数据源数;分组表本身不存冗余计数列,避免不一致
    private static final String DS_SELECT = "SELECT g.id, g.name, g.remark, g.created_at, g.updated_at, "
            + "(SELECT count(*) FROM data_sources d WHERE d.group_id=g.id) AS count FROM ds_groups g";

    private static final RowMapper<DatabaseGroup> DB_GROUP = (rs, row) -> {
        DatabaseGroup group = new DatabaseGroup();
        group.setId(rs.getLong("id"));
        group.setConnectionId(rs.getLong("connection_id"));
        group.setName(rs.getString("name"));
        group.setRemark(rs.getString("remark"));
        group.setCreatedAt(rs.getString("created_at"));
        group.setUpdatedAt(rs.getString("updated_at"));
        return group;
    };
    private static final String DB_SELECT = "SELECT id, connection_id, name, remark, created_at, updated_at FROM db_groups";


    public GroupRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 连接是否存在。分组是连接的下级概念，新建数据库分组前由服务层用它兜底判 404。 */
    public boolean connectionExists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM connections WHERE id=?", Integer.class, id) > 0;
    }

    /** 全部数据源分组，按 id 升序；count 每次实时算，故列表与详情用的是同一条 SQL。 */
    public List<DataSourceGroup> listDataSourceGroups() {
        return jdbc.query(DS_SELECT + " WHERE EXISTS (SELECT 1 FROM el_resource_owners o WHERE o.resource_type='datasource-group' AND o.resource_id=g.id AND o.owner_user_id=?) ORDER BY g.id DESC", DS_GROUP, com.el2ck.config.AuthContext.userId());
    }

    /** 取单个数据源分组；不存在返回 null(与下面取数据库分组的约定一致)。 */
    public DataSourceGroup getDataSourceGroup(long id) {
        access.requireOwner("datasource-group", id);
        // 用 query + 判空而非 queryForObject:后者查不到会抛 EmptyResultDataAccessException,这里约定返回 null
        List<DataSourceGroup> found = jdbc.query(DS_SELECT + " WHERE g.id=?", DS_GROUP, id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 新建数据源分组，返回自增主键；名称唯一性由库约束/上层决定，本层不判重。 */
    @org.springframework.transaction.annotation.Transactional
    public long createDataSourceGroup(String name, String remark) {
        long id = insert("INSERT INTO ds_groups(name, remark) VALUES (?, ?)", name, remark);
        access.register("datasource-group", id);
        return id;
    }

    /** 重命名/改备注，顺带刷新 updated_at；返回影响行数，0 表示分组不存在。 */
    public int updateDataSourceGroup(long id, String name, String remark) {
        access.requireOwner("datasource-group", id);
        return jdbc.update("UPDATE ds_groups SET name=?, remark=?, updated_at=datetime('now') WHERE id=?", name, remark, id);
    }

    /** 删除数据源分组并解绑其成员；返回是否真的删掉了分组(0 行 = 不存在)。 */
    @org.springframework.transaction.annotation.Transactional
    public boolean deleteDataSourceGroup(long id) {
        access.requireOwner("datasource-group", id);
        int deleted=jdbc.update("DELETE FROM ds_groups WHERE id=?",id);
        if(deleted>0) {
            jdbc.update("UPDATE data_sources SET group_id=0 WHERE group_id=?",id);
            access.remove("datasource-group",id);
        }
        return deleted>0;
    }

    /** 改绑数据源的分组，返回影响行数(0 = 数据源不存在)。 */
    public int setDataSourceGroup(long dataSourceId, long groupId) {
        access.requireOwner("datasource", dataSourceId);
        if (groupId > 0) access.requireOwner("datasource-group", groupId);
        // 单条 UPDATE 完成改绑;调用方需自行校验 groupId 指向的分组存在
        return jdbc.update("UPDATE data_sources SET group_id=?, updated_at=datetime('now') WHERE id=?", groupId, dataSourceId);
    }

    /** 某连接下的全部分组，含各自的库名清单。 */
    public List<DatabaseGroup> listDatabaseGroups(long connectionId) {
        List<DatabaseGroup> groups = jdbc.query(DB_SELECT + " WHERE connection_id=? ORDER BY id DESC", DB_GROUP, connectionId);
        // 逐个分组回填库名列表(N+1 查询;分组数量级很小,换取 SQL 与结果结构的简单)
        for (DatabaseGroup group : groups) {
            group.setDbs(jdbc.query("SELECT db_name FROM group_dbs WHERE group_id=? ORDER BY db_name",
                    (rs, row) -> rs.getString(1), group.getId()));
        }
        return groups;
    }

    /** 取单个数据库分组(不含库名清单；成员要另查 group_dbs)；不存在返回 null。 */
    public DatabaseGroup getDatabaseGroup(long id) {
        // 同 getDataSourceGroup:查不到返回 null 而非抛异常
        List<DatabaseGroup> found = jdbc.query(DB_SELECT + " WHERE id=?", DB_GROUP, id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 新建数据库分组，返回自增主键；connectionId 由调用方保证已存在。 */
    public long createDatabaseGroup(long connectionId, String name, String remark) {
        return insert("INSERT INTO db_groups(connection_id, name, remark) VALUES (?, ?, ?)", connectionId, name, remark);
    }

    /** 重命名/改备注；影响行数 0 表示分组不存在。 */
    public int updateDatabaseGroup(long id, String name, String remark) {
        return jdbc.update("UPDATE db_groups SET name=?, remark=?, updated_at=datetime('now') WHERE id=?", name, remark, id);
    }

    /** 删除数据库分组并清理成员行；返回是否真的删掉了分组。 */
    public boolean deleteDatabaseGroup(long id) {
        // 与删除数据源分组同理:删组 + 清成员表需在同一事务内完成
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int deleted;
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM db_groups WHERE id=?")) {
                    statement.setLong(1, id);
                    deleted = statement.executeUpdate();
                }
                // group_dbs 无外键约束,删组后必须手动清理其成员行,否则会残留孤儿记录
                if (deleted > 0) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM group_dbs WHERE group_id=?")) {
                        statement.setLong(1, id);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return deleted > 0;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除数据库分组失败", e);
        }
    }

    /** 覆盖式保存分组成员：先清空该组，再逐个建立归属，并顺手摘掉这些库在同连接其他组里的旧归属。 */
    public void setGroupDatabases(long groupId, long connectionId, List<String> dbs) {
        // 全量覆盖式保存:先清空该组现有成员,再按传入列表重新建立归属,整体在一个事务内
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // 三条语句都预处理一次、循环内反复执行,避免每次迭代重新编译
            try (PreparedStatement clear = connection.prepareStatement("DELETE FROM group_dbs WHERE group_id=?");
                 PreparedStatement unassign = connection.prepareStatement("DELETE FROM group_dbs WHERE db_name=? AND group_id IN (SELECT id FROM db_groups WHERE connection_id=?)");
                 PreparedStatement add = connection.prepareStatement("INSERT INTO group_dbs(group_id, db_name) VALUES (?, ?)")) {
                clear.setLong(1, groupId);
                clear.executeUpdate();
                for (String db : dbs) {
                    // 先摘掉该库在「同一连接下其他分组」里的旧归属,保证一个库同时只属于一个分组
                    unassign.setString(1, db);
                    unassign.setLong(2, connectionId);
                    unassign.executeUpdate();
                    add.setLong(1, groupId);
                    add.setString(2, db);
                    add.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                // 中途失败(如违反 (group_id,db_name) 主键)则整体回滚,不会留下半套成员关系
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存数据库分组成员失败", e);
        }
    }

    /** 插入并回读自增主键的公共实现(两条 INSERT 语句共用)。 */
    private long insert(String sql, Object... params) {
        // GeneratedKeyHolder 承接 executeUpdate 后由驱动返回的自增主键
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            // 参数逐个按 Object 绑定(调用方只传 String/long),Object[] 从下标 0 起,占位符从 1 起
            for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
            return statement;
        }, keys);
        // SQLite 未返回主键时视为异常状态,及早抛出而不是返回 0 让上层误用
        if (keys.getKey() == null) throw new IllegalStateException("无法获取新建分组 ID");
        return keys.getKey().longValue();
    }
}
