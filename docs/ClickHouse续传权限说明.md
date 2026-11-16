# ClickHouse 25.3 续传权限说明

## 报错原因

`el_user_3` 报 Code 497，缺少 `SELECT(query_id, user, query) ON system.processes`。
旧版本让业务账号执行按断点标记筛选的 `KILL QUERY ... SYNC`，ClickHouse 25.3 会先查询
这三个字段，再执行取消。因此错误发生在旧写入确认阶段，断点仍然保留，不能直接跳过检查后重放。

依据：[ClickHouse 25.3.14.14 InterpreterKillQueryQuery.cpp](https://github.com/ClickHouse/ClickHouse/blob/v25.3.14.14-lts/src/Interpreters/InterpreterKillQueryQuery.cpp)。
该版本读取 `system.processes` 后，对终止其他账号的查询另检查 `KILL QUERY` 权限。

## 本次修复

旧写入查询与终止改为明确的管理操作，复用部署中已有的 `CK_MANAGEMENT_USERNAME` 和
`CK_MANAGEMENT_PASSWORD`。业务账号不新增系统查询读取权限或全局取消权限。

- 先校验当前业务绑定仍有效，以及断点 token 格式合法。
- 管理账号检查该 token 的旧查询归属；若属于其他账号则中止恢复，保留断点，交由管理员核对。
- 使用同时限定业务账号和 token 的 `KILL QUERY ... SYNC`，排除取消指令自身。
- 仅在所有返回状态均为 `finished`，或无匹配查询时继续。
- 管理连接不可用、权限不足、绑定失效或取消未完成时均失败关闭；不会清除断点或越过检查写入。
- 目标清理与 INSERT 仍沿用业务账号及原有表权限、任务锁、目标断点锁。

这是服务端内部固定操作，不提供输入任意 SQL 的管理身份执行接口。

## 212 部署处理

1. 重新打包、按原流程上传并部署新版。
2. 确认已有配置中的 CK 管理账号具有下面两项权限；已有权限时不用重复授权。
3. 在页面点击失败任务的“继续传输”，不需要删除任务、清空断点或点击“重新传输”。

如需补权，在**目标 ClickHouse** 上使用有授权能力的管理员执行以下语句。
将示例中的 `your_management_user` 替换为已有配置 `CK_MANAGEMENT_USERNAME` 的值，
不是业务账号 `el_user_3`；无需修改账号密码。

```sql
GRANT SELECT(query_id, user, query) ON system.processes TO `your_management_user`;
GRANT KILL QUERY ON *.* TO `your_management_user`;
```

可使用配置的管理账号执行以下只读/不取消查询的检查：

```sql
SELECT query_id, user, query FROM system.processes LIMIT 0;
CHECK GRANT KILL QUERY ON *.*;
```

本次没有 MySQL 表结构变更，也没有自动修改 212 的权限或重新启动线上任务。
该恢复逻辑按当前单机 ClickHouse 部署实现；不扩展为跨节点取消。

## 不新增目标列的续传协议

2026-11-05 的新协议首次接入还需管理身份在目标表执行固定的 `ALTER TABLE ... MODIFY SETTING non_replicated_deduplication_window=20000`。执行前校验业务身份本来具有该表的 INSERT 和 ALTER DELETE 权限；不向业务账号授予通用 ALTER SETTINGS 权限，不添加任何续传列。已有大于等于 20000 的显式窗口时无需修改。配置变更及异常恢复边界见[无额外目标字段的续传](无额外目标字段的续传.md)。
