/**
 * 【文件职责】本文件定义表管理与数据编辑模块的业务服务 TableGroupService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.table;

import com.el2ck.entity.table.TableGroup;
import com.el2ck.exception.ApiException;
import com.el2ck.repository.table.TableGroupRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 表分组业务逻辑(对应 Go internal/service/tbl_group_service.go)。
 *
 * <p>校验与错误码集中在本层:参数问题抛 400(validation)、目标不存在抛 404(notFound),
 * 控制器只做请求体的空保护。分组只做归类,不会创建/删除表本身。
 *
 * <p>⚠️ 与 Go 的一处**有意分歧**:Go 的 {@code SetTableGroup} 先删旧归属、再校验目标分组,
 * 于是传一个不存在的 groupId 时,**表已经被移出原分组**了(请求报错但副作用已发生)。
 * 这里改为**先校验后写入**,失败时不留任何副作用。前端在报错后会重新拉取分组列表,
 * 两者的最终可见状态因此不同 —— 这是有意为之,不是遗漏。
 */
@Service
public class TableGroupService {
    /** 表分组仓储:SQL 与事务都在那里,本层只负责规则。 */
    private final TableGroupRepository repository;

    /** 构造器注入仓储(依赖不可变,用 final)。 */
    public TableGroupService(TableGroupRepository repository) {
        this.repository = repository;
    }

    /** 某连接某库的全部分组(含成员表)。连接不存在直接 404,不返回空列表。 */
    public List<TableGroup> list(long connectionId, String db) {
        requireConnection(connectionId);
        return repository.list(connectionId, clean(db));
    }

    /** 新增分组。名称非法抛 400;连接不存在抛 404。返回的对象再查一次库,好把时间戳带上。 */
    public TableGroup create(long connectionId, String db, String name, String remark) {
        String value = validName(name);
        requireConnection(connectionId);
        return repository.get(repository.create(connectionId, clean(db), value, clean(remark)));
    }

    /** 改名称/备注。先校验名称(故名称非法时是 400),影响行数为 0 视为分组不存在(404)。 */
    public TableGroup update(long id, String name, String remark) {
        String value = validName(name);
        if (repository.update(id, value, clean(remark)) == 0)
            throw ApiException.notFound("表分组不存在");
        return repository.get(id);
    }

    /** 删除分组,连带的成员行由仓储在同一事务内清理,避免孤儿记录。 */
    public void delete(long id) {
        if (!repository.delete(id)) throw ApiException.notFound("表分组不存在");
    }

    /**
     * 把某表设到指定分组;groupId=0 表示移出分组回到「未分组」。
     *
     * <p>先做完全部校验再落库:目标分组不存在(404)或不属于该库(400)时,原分组归属保持不变。
     */
    public void setTableGroup(long connectionId, String db, String table, long groupId) {
        // 表名是定位成员行的唯一依据,为空即无从下手
        String tableName = clean(table);
        if (tableName.isEmpty()) throw ApiException.validation("表名不能为空");
        if (groupId < 0) throw ApiException.validation("分组 ID 不能为负数");
        String database = clean(db);
        // groupId=0 是合法取值,表示"移出分组",故不做存在性校验
        if (groupId == 0) {
            repository.ungroupTable(connectionId, database, tableName);
            return;
        }
        TableGroup group = repository.get(groupId);
        if (group == null) throw ApiException.notFound("表分组不存在");
        // 分组归属于「连接 + 库」,跨库挂表会让侧栏把别的库的表显示进来
        if (group.getConnectionId() != connectionId || !database.equals(clean(group.getDb())))
            throw ApiException.validation("分组不属于该库");
        repository.setTableGroup(groupId, connectionId, database, tableName);
    }

    /** 表被删除后清理其分组归属,避免分组里残留已不存在的表。 */
    public void removeTableMembers(long connectionId, String db, String table) {
        repository.removeTableMembers(connectionId, clean(db), clean(table));
    }

    /** 表改名后把分组归属迁移到新表名,避免归属掉进「未分组」或残留旧名。 */
    public void renameTableMembers(long connectionId, String db, String oldName, String newName) {
        repository.renameTableMembers(connectionId, clean(db), clean(oldName), clean(newName));
    }

    /** 表分组必须挂在一个已存在的连接下,校验不过即 404。 */
    private void requireConnection(long id) {
        if (!repository.connectionExists(id)) throw ApiException.notFound("连接不存在");
    }

    /** 分组名称校验:trim 后非空且不超过 50 字,返回清理后的值供直接入库。 */
    private static String validName(String name) {
        String value = clean(name);
        if (value.isEmpty()) throw ApiException.validation("分组名称不能为空");
        // 按码点计数而非 String.length:emoji 等增补字符在 UTF-16 中占两个 char,按 length 会误判超长
        if (value.codePointCount(0, value.length()) > 50)
            throw ApiException.validation("分组名称不能超过 50 字");
        return value;
    }

    /** 统一把 null 归一为空串再 trim:后续判空/入库都不必再处理 null。 */
    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
