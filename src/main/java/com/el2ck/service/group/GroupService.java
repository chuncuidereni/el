/**
 * 【文件职责】本文件定义分组管理模块的业务服务 GroupService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.group;

import com.el2ck.entity.group.DataSourceGroup;
import com.el2ck.entity.group.DatabaseGroup;
import com.el2ck.exception.ApiException;
import com.el2ck.repository.group.GroupRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 分组业务逻辑：同时管「数据源分组」(ds_groups)与「数据库分组」(db_groups + group_dbs)。
 * 校验与错误码集中在服务层——参数问题抛 400(ApiException.validation)、目标不存在抛 404(notFound)，
 * 控制器只做请求体的空保护，不再重复判断；分组只做归类，不会创建/删除数据源或库本身。
 */
@Service
public class GroupService {
    /** 分组仓储：SQL 与事务都在那里，本层只负责规则。 */
    private final GroupRepository repository;

    /** 构造器注入仓储(依赖不可变，用 final)。 */
    public GroupService(GroupRepository repository) {
        this.repository = repository;
    }

    /** 列出全部分组。count 字段由仓储的实时子查询得出，本层不额外统计也不做校验。 */
    public List<DataSourceGroup> listDataSourceGroups() {
        return repository.listDataSourceGroups();
    }

    /** 新建数据源分组。名称非法抛 400；返回的对象再查一次库，好把库生成的 count/时间戳带上。 */
    public DataSourceGroup createDataSourceGroup(String name, String remark) {
        long id = repository.createDataSourceGroup(validName(name), clean(remark));
        return repository.getDataSourceGroup(id);
    }

    /** 重命名/改备注。影响行数为 0 视为分组不存在(404)——先校验名称，故名称非法时是 400 而非 404。 */
    public DataSourceGroup updateDataSourceGroup(long id, String name, String remark) {
        if (repository.updateDataSourceGroup(id, validName(name), clean(remark)) == 0)
            throw ApiException.notFound("数据源分组不存在");
        return repository.getDataSourceGroup(id);
    }

    /** 删除数据源分组。仓储侧会把组内数据源解绑回默认分组，同一事务内完成，不会留下悬挂的 group_id。 */
    public void deleteDataSourceGroup(long id) {
        if (!repository.deleteDataSourceGroup(id)) throw ApiException.notFound("数据源分组不存在");
    }

    /** 把数据源归入某分组(groupId=0 表示移出分组回到默认)。 */
    public void setDataSourceGroup(long dataSourceId, long groupId) {
        if (groupId < 0) throw ApiException.validation("分组 ID 不能为负数");
        // groupId=0 表示「移出分组」，属合法取值，故仅在非 0 时才校验分组是否存在
        if (groupId != 0 && repository.getDataSourceGroup(groupId) == null)
            throw ApiException.notFound("数据源分组不存在");
        // 更新影响行数为 0 说明数据源本身不存在
        if (repository.setDataSourceGroup(dataSourceId, groupId) == 0)
            throw ApiException.notFound("数据源不存在");
    }

    /** 列出某连接下的数据库分组(含各组库名)。连接不存在直接 404，不返回空列表。 */
    public List<DatabaseGroup> listDatabaseGroups(long connectionId) {
        requireConnection(connectionId);
        return repository.listDatabaseGroups(connectionId);
    }

    /** 在某连接下新建数据库分组。先校验名称再校验连接：故两者同时非法时先报 400「名称」而不是 404。 */
    public DatabaseGroup createDatabaseGroup(long connectionId, String name, String remark) {
        String value = validName(name);
        requireConnection(connectionId);
        return repository.getDatabaseGroup(repository.createDatabaseGroup(connectionId, value, clean(remark)));
    }

    /** 重命名/改备注。影响行数为 0 即分组不存在(404)。 */
    public DatabaseGroup updateDatabaseGroup(long id, String name, String remark) {
        if (repository.updateDatabaseGroup(id, validName(name), clean(remark)) == 0)
            throw ApiException.notFound("数据库分组不存在");
        return repository.getDatabaseGroup(id);
    }

    /** 删除数据库分组，连带的 group_dbs 成员行由仓储在同一事务内清理，避免孤儿记录。 */
    public void deleteDatabaseGroup(long id) {
        if (!repository.deleteDatabaseGroup(id)) throw ApiException.notFound("数据库分组不存在");
    }

    /** 覆盖式设置分组的库名清单：dbs 为 null 或空白项被丢弃，等同于清空该分组的全部绑定。 */
    public void setGroupDatabases(long id, List<String> databases) {
        DatabaseGroup group = repository.getDatabaseGroup(id);
        if (group == null) throw ApiException.notFound("数据库分组不存在");
        // LinkedHashSet 在去重的同时保留提交顺序，避免同一库被重复写入分组
        LinkedHashSet<String> cleanNames = new LinkedHashSet<>();
        if (databases != null) {
            for (String db : databases) {
                // 空串来自清理后的 null/空白项，直接跳过不入组
                String value = clean(db);
                if (!value.isEmpty()) cleanNames.add(value);
            }
        }
        // 连接 ID 取自分组自身(而非入参)，防止把库挂到别的连接名下
        repository.setGroupDatabases(id, group.getConnectionId(), new ArrayList<>(cleanNames));
    }

    /** 数据库分组必须挂在一个已存在的连接下，校验不过即 404。 */
    private void requireConnection(long id) {
        if (!repository.connectionExists(id)) throw ApiException.notFound("连接不存在");
    }

    /** 分组名称校验：trim 后非空且不超过 50 字，返回清理后的值供直接入库。 */
    private static String validName(String name) {
        String value = clean(name);
        if (value.isEmpty()) throw ApiException.validation("分组名称不能为空");
        // 按码点计数而非 String.length：emoji 等增补字符在 UTF-16 中占两个 char，按 length 会误判超长
        if (value.codePointCount(0, value.length()) > 50)
            throw ApiException.validation("分组名称不能超过 50 字");
        return value;
    }

    /** 统一把 null 归一为空串再 trim：后续判空/入库都不必再处理 null。 */
    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
