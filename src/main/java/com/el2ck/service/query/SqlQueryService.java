/**
 * 【文件职责】本文件定义查询执行模块的业务服务 SqlQueryService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.query;

import com.el2ck.service.group.GroupService;

import com.el2ck.entity.query.SqlQuery;
import com.el2ck.entity.query.SqlQueryGroup;
import com.el2ck.entity.query.SqlQueryInput;
import com.el2ck.exception.ApiException;
import com.el2ck.repository.query.SqlQueryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 已保存 SQL 查询与查询分组的业务逻辑(对应 Go internal/api/sql_query_api.go + store/sql_query_store.go)。
 *
 * <p>这里的查询只是「存起来」,不执行 SQL —— 真正跑语句走 QueryService。
 * 校验与错误码集中在本层:名称非法与目标分组不存在都抛 400(与 Go 一致,Go 这两处显式返回 400),
 * 记录不存在抛 404。
 *
 * <p>⚠️ 与 Go 的一处小分歧:Go 只判 {@code name == ""},空白串能存进去;这里按本仓库
 * {@link GroupService} 的既有约定统一 trim 后再判空,空白名称会被拒。前端本来也不会提交空白名称。
 */
@Service
public class SqlQueryService {
    /** 查询仓储:SQL 与事务都在那里,本层只负责规则。 */
    private final SqlQueryRepository repository;

    /** 构造器注入仓储(依赖不可变,用 final)。 */
    public SqlQueryService(SqlQueryRepository repository) {
        this.repository = repository;
    }

    /** 已保存查询列表。groupId 为 null 表示不过滤(0 是有效值,表示只看默认分组)。 */
    public List<SqlQuery> list(Long groupId) {
        return repository.listQueries(groupId);
    }

    /** 单条查询;不存在抛 404。 */
    public SqlQuery get(long id) {
        SqlQuery query = repository.getQuery(id);
        if (query == null) throw ApiException.notFound("SQL 查询不存在");
        return query;
    }

    /** 新建查询,返回落库后的完整对象(带 id 与时间戳)。 */
    public SqlQuery create(SqlQueryInput input) {
        SqlQuery query = fromInput(input);
        requireGroup(query.getGroupId());
        query.setId(repository.createQuery(query));
        // 回读一次:database 为空时持久层会补默认库,直接返回入参对象会少这个默认值
        return repository.getQuery(query.getId());
    }

    /** 更新查询;不存在抛 404。 */
    public SqlQuery update(long id, SqlQueryInput input) {
        SqlQuery query = fromInput(input);
        query.setId(id);
        requireGroup(query.getGroupId());
        if (repository.updateQuery(query) == 0) throw ApiException.notFound("SQL 查询不存在");
        // 同新建:回读以拿到库侧刷新的 updated_at 与补过的 database
        return repository.getQuery(id);
    }

    /** 删除查询;不存在抛 404。 */
    public void delete(long id) {
        if (repository.deleteQuery(id) == 0) throw ApiException.notFound("SQL 查询不存在");
    }

    /** 把查询移入分组(groupId=0 表示默认分组)。 */
    public void setQueryGroup(long id, long groupId) {
        if (groupId < 0) throw ApiException.validation("分组 ID 不能为负数");
        requireGroup(groupId);
        if (repository.setQueryGroup(id, groupId) == 0) throw ApiException.notFound("SQL 查询不存在");
    }

    // ---------------- 分组 ----------------

    /** 全部分组(含各自的查询数)。 */
    public List<SqlQueryGroup> listGroups() {
        return repository.listGroups();
    }

    /** 新建分组,返回落库后的完整对象。 */
    public SqlQueryGroup createGroup(String name, String remark) {
        long id = repository.createGroup(validName(name, "请输入分组名称"), clean(remark));
        return repository.getGroup(id);
    }

    /** 更新分组;不存在抛 404。 */
    public SqlQueryGroup updateGroup(long id, String name, String remark) {
        if (repository.updateGroup(id, validName(name, "请输入分组名称"), clean(remark)) == 0)
            throw ApiException.notFound("分组不存在");
        return repository.getGroup(id);
    }

    /** 删除分组;组内查询由仓储在同一事务内退回默认分组,不会连同查询一起删掉。 */
    public void deleteGroup(long id) {
        if (!repository.deleteGroup(id)) throw ApiException.notFound("分组不存在");
    }

    /** 目标分组必须存在;groupId=0 是「默认分组」虚位,始终可用。 */
    private void requireGroup(long groupId) {
        if (!repository.groupExists(groupId)) throw ApiException.validation("分组不存在: " + groupId);
    }

    /** 入参 → 实体。名称统一 trim 后判空;groupId 缺省视为 0(默认分组)。 */
    private static SqlQuery fromInput(SqlQueryInput input) {
        SqlQuery query = new SqlQuery();
        query.setName(validName(input == null ? null : input.getName(), "请输入名称"));
        query.setDescription(clean(input == null ? null : input.getDescription()));
        query.setDatabase(clean(input == null ? null : input.getDatabase()));
        query.setSqlText(input == null || input.getSqlText() == null ? "" : input.getSqlText());
        query.setVisualConfig(clean(input == null ? null : input.getVisualConfig()));
        query.setGroupId(input == null || input.getGroupId() == null ? 0L : input.getGroupId());
        return query;
    }

    /** 名称校验:trim 后非空,返回清理后的值供直接入库。 */
    private static String validName(String name, String emptyMessage) {
        String value = clean(name);
        if (value.isEmpty()) throw ApiException.validation(emptyMessage);
        return value;
    }

    /** 统一把 null 归一为空串再 trim:后续判空/入库都不必再处理 null。 */
    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
