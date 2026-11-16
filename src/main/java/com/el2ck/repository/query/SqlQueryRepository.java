package com.el2ck.repository.query;

/**
 * 【文件职责】持久化保存的 SQL 查询定义及其归属信息，支持查询列表、读取、保存和删除。
 */

import com.el2ck.config.AuthContext;
import com.el2ck.exception.ApiException;
import com.el2ck.service.auth.ResourceAccessService;

import com.el2ck.entity.query.SqlQuery;
import com.el2ck.entity.query.SqlQueryGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 已保存 SQL 查询与查询分组的持久层(sql_queries + sql_query_groups)。
 *
 * <p>所有读写均检查当前用户归属；草稿、分组和归属记录在同一事务内变更。
 * 这里只保存 SQL 文本，不执行其中的语句。
 */
@Repository
public class SqlQueryRepository {
    private final ResourceAccessService access;
    /** 常规单语句读写走 JdbcTemplate(自动获取/释放连接)。 */
    private final JdbcTemplate jdbc;

    /** 查询行的列映射(时间列是 TEXT,故用 getString 而非 getTimestamp)。 */
    private static final RowMapper<SqlQuery> QUERY_MAPPER = (rs, row) -> {
        SqlQuery query = new SqlQuery();
        query.setId(rs.getLong("id"));
        query.setName(rs.getString("name"));
        query.setDescription(rs.getString("description"));
        query.setDatabase(rs.getString("database"));
        query.setSqlText(rs.getString("sql_text"));
        query.setVisualConfig(rs.getString("visual_config"));
        query.setGroupId(rs.getLong("group_id"));
        query.setCreatedAt(rs.getString("created_at"));
        query.setUpdatedAt(rs.getString("updated_at"));
        return query;
    };
    private static final String QUERY_COLS =
            "id, name, description, `database`, sql_text, visual_config, group_id, created_at, updated_at";
    private static final String OWN_QUERIES = "EXISTS(SELECT 1 FROM el_resource_owners o"
            + " WHERE o.resource_type='sql-query' AND o.resource_id=q.id AND o.owner_user_id=?)";

    /** 分组行的列映射;count 来自 LEFT JOIN 的别名 cnt,不是表上的列。 */
    private static final RowMapper<SqlQueryGroup> GROUP_MAPPER = (rs, row) -> {
        SqlQueryGroup group = new SqlQueryGroup();
        group.setId(rs.getLong("id"));
        group.setName(rs.getString("name"));
        group.setRemark(rs.getString("remark"));
        group.setCount(rs.getInt("cnt"));
        group.setCreatedAt(rs.getString("created_at"));
        group.setUpdatedAt(rs.getString("updated_at"));
        return group;
    };
    /**
     * 分组列表带各自的查询数。
     * LEFT JOIN 而非相关子查询:分组数量级很小,一次扫描把计数全算出来比逐行子查询更直白。
     */
    private static final String GROUP_SELECT = "SELECT g.id, g.name, g.remark, g.created_at, g.updated_at,"
            + " COALESCE(c.cnt, 0) AS cnt FROM sql_query_groups g"
            + " LEFT JOIN (SELECT group_id, COUNT(*) AS cnt FROM sql_queries q WHERE " + OWN_QUERIES + " GROUP BY group_id) c"
            + " ON c.group_id = g.id";

    /** 构造器:数据源由 Spring 注入,同时派生一个 JdbcTemplate。 */
    public SqlQueryRepository(DataSource dataSource, ResourceAccessService access) {
        this.access = access;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 列出已保存查询(按 id 倒序,最近保存的在最前)。groupId 为 null 表示不过滤;0 表示只看默认分组。 */
    public List<SqlQuery> listQueries(Long groupId) {
        if (groupId != null) requireGroup(groupId, false);
        if (groupId == null) {
            return jdbc.query("SELECT " + QUERY_COLS + " FROM sql_queries q WHERE " + OWN_QUERIES
                    + " ORDER BY id DESC", QUERY_MAPPER, AuthContext.userId());
        }
        return jdbc.query("SELECT " + QUERY_COLS + " FROM sql_queries q WHERE " + OWN_QUERIES
                + " AND group_id = ? ORDER BY id DESC", QUERY_MAPPER, AuthContext.userId(), groupId);
    }

    /** 取单个查询;不存在返回 null。 */
    public SqlQuery getQuery(long id) {
        access.requireOwner("sql-query", id);
        // 用 query + 判空而非 queryForObject:后者查不到会抛 EmptyResultDataAccessException,这里约定返回 null
        List<SqlQuery> found = jdbc.query("SELECT " + QUERY_COLS + " FROM sql_queries WHERE id = ?", QUERY_MAPPER, id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 新建查询,返回自增主键;database 为空时落默认库(与 Go 一致,默认值定在持久层)。 */
    @Transactional
    public long createQuery(SqlQuery query) {
        AuthContext.userId();
        requireGroup(query.getGroupId(), true);
        String database = query.getDatabase();
        if (database == null || database.trim().isEmpty()) {
            database = "ods_data";
        }
        KeyHolder keys = new GeneratedKeyHolder();
        // 在 lambda 里被引用,必须是 effectively final
        final String target = database;
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sql_queries (name, description, `database`, sql_text, visual_config, group_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, query.getName());
            statement.setString(2, query.getDescription());
            statement.setString(3, target);
            statement.setString(4, query.getSqlText());
            statement.setString(5, query.getVisualConfig());
            statement.setLong(6, query.getGroupId());
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("无法获取新建 SQL 查询 ID");
        long id = keys.getKey().longValue();
        access.register("sql-query", id);
        return id;
    }

    /** 更新查询;返回影响行数,0 表示查询不存在。 */
    @Transactional
    public int updateQuery(SqlQuery query) {
        access.requireOwner("sql-query", query.getId());
        requireGroup(query.getGroupId(), true);
        return jdbc.update("UPDATE sql_queries SET name=?, description=?, `database`=?, sql_text=?,"
                        + " visual_config=?, group_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                query.getName(), query.getDescription(), query.getDatabase(), query.getSqlText(),
                query.getVisualConfig(), query.getGroupId(), query.getId());
    }

    /** 删除查询;返回影响行数,0 表示查询不存在。 */
    @Transactional
    public int deleteQuery(long id) {
        access.requireOwner("sql-query", id);
        int deleted = jdbc.update("DELETE FROM sql_queries WHERE id = ?", id);
        access.remove("sql-query", id);
        return deleted;
    }

    /** 把查询移入指定分组(0 = 默认分组);返回影响行数,0 表示查询不存在。 */
    @Transactional
    public int setQueryGroup(long id, long groupId) {
        access.requireOwner("sql-query", id);
        requireGroup(groupId, true);
        return jdbc.update("UPDATE sql_queries SET group_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", groupId, id);
    }

    /** 全部查询分组(按 id 升序),count 为该组下的查询数。 */
    public List<SqlQueryGroup> listGroups() {
        long uid = AuthContext.userId();
        return jdbc.query(GROUP_SELECT + " WHERE EXISTS(SELECT 1 FROM el_resource_owners o"
                + " WHERE o.resource_type='sql-query-group' AND o.resource_id=g.id AND o.owner_user_id=?)"
                + " ORDER BY g.id DESC", GROUP_MAPPER, uid, uid);
    }

    /** 取单个分组;不存在返回 null。 */
    public SqlQueryGroup getGroup(long id) {
        access.requireOwner("sql-query-group", id);
        List<SqlQueryGroup> found = jdbc.query(GROUP_SELECT + " WHERE g.id = ?", GROUP_MAPPER, AuthContext.userId(), id);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 新建分组,返回自增主键。 */
    @Transactional
    public long createGroup(String name, String remark) {
        AuthContext.userId();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sql_query_groups (name, remark) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, remark);
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("无法获取新建查询分组 ID");
        long id = keys.getKey().longValue();
        access.register("sql-query-group", id);
        return id;
    }

    /** 更新分组名称/备注;返回影响行数,0 表示分组不存在。 */
    public int updateGroup(long id, String name, String remark) {
        access.requireOwner("sql-query-group", id);
        return jdbc.update("UPDATE sql_query_groups SET name=?, remark=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                name, remark, id);
    }

    /**
     * 删除分组,并把组内查询归到默认分组(group_id=0)而不是连查询一起删掉。
     * 两步必须同生共死:先删组会把查询的 group_id 指向一个不存在的分组,它们既不属于任何分组、也不再显示在默认分组里。
     *
     * @return 是否真的删掉了分组(0 行 = 不存在)
     */
    @Transactional
    public boolean deleteGroup(long id) {
        access.requireOwner("sql-query-group", id);
        requireGroup(id, true);
        // 历史脏关联不能让分组所有者修改他人的草稿。
        jdbc.update("UPDATE sql_queries q SET group_id=0, updated_at=CURRENT_TIMESTAMP"
                + " WHERE group_id=? AND " + OWN_QUERIES, id, AuthContext.userId());
        int deleted = jdbc.update("DELETE FROM sql_query_groups WHERE id=?", id);
        access.remove("sql-query-group", id);
        return deleted > 0;
    }

    /** 分组是否存在;id=0 是「默认分组」这个虚位,始终视为存在。 */
    public boolean groupExists(long id) {
        AuthContext.userId();
        if (id == 0) return true;
        if (id < 0) throw ApiException.validation("分组 ID 不能为负数");
        access.requireOwner("sql-query-group", id);
        return jdbc.queryForObject("SELECT count(*) FROM sql_query_groups WHERE id = ?", Integer.class, id) > 0;
    }

    private void requireGroup(long id, boolean lock) {
        AuthContext.userId();
        if (id == 0) return;
        if (id < 0) throw ApiException.validation("分组 ID 不能为负数");
        access.requireOwner("sql-query-group", id);
        if (jdbc.queryForList("SELECT id FROM sql_query_groups WHERE id=?" + (lock ? " FOR UPDATE" : ""), id).isEmpty())
            throw ApiException.notFound("分组不存在或无权访问");
    }
}
