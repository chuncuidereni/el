# ClickHouse 大表全量导入修复（2026-11-05）

本次修复“读取上亿行、确认写入始终为 0，超时后又从头读取”的循环。符合条件的 ClickHouse 全量任务改为按小窗口读取并逐批写入，断点继续保存到现有 MySQL 表。新任务默认总超时为 **0（不限时）**。

## 本机打包

```powershell
cd E:\wenjian\el\el-to-ck-java
.\package.ps1 -Mode external
```

将 `release\el-to-ck-212.tar.gz` 和同目录的 `.sha256` 文件手动复制到服务器 `/home/wuhaiyang/upload/`。包内已包含当前部署配置，不需要重新填写账号密码。

## 212 服务器部署

先在页面暂停相关定时任务，并等待当前运行结束或取消当前运行。使用新目录解压，避免旧源码和旧配置残留：

```bash
set -euo pipefail
umask 077
cd /home/wuhaiyang/upload
sha256sum -c el-to-ck-212.tar.gz.sha256
release_dir="/home/wuhaiyang/el2ck-releases/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$release_dir"
tar -xzf el-to-ck-212.tar.gz -C "$release_dir"
cd "$release_dir"
sha256sum -c --status release-files.sha256
bash deploy.sh up
bash deploy.sh status
```

本次没有新增 MySQL DDL；已具备当前项目元数据库结构的 212 环境无需手工改表。应用仍使用原数据卷，不要删除卷。详情沿用 [Docker 部署说明](Docker部署说明.md)。

## 部署后运行

1. 编辑现有大表任务，将已保存的“任务超时 30 分钟”改为 **0** 并保存。新默认值不会批量覆盖旧任务的显式配置。
2. 对失败的任务选择“继续传输”。未完成快照且确认写入为 0 的兼容任务会自动转为分批模式，不需要删除任务，也不要为此选择重新传输。
3. 页面应显示“分批读取并写入”，确认写入行数随批次增加。再次中断后，已确认批次保留，只恢复后续批次；待确认批次按原有机制核验和重放。
4. 新模式不生成整表快照。原未完成快照在任务空闲后，由定时清理器持锁确认不再使用并回收；任务运行期间仍保守保留。

“重新传输”仍按原任务策略开启新一轮，可能清空目标表。对于已经开始写入的旧快照，升级保留原恢复方式，不强制丢弃断点。

## 边界与核验范围

自动分页适用于具有简单排序列、支持的非空类型、无 TTL 的普通 MergeTree 全量源。历史行在同步和恢复期间必须保持不变；这不是事务快照或 CDC，已读位置内的并发补写不保证被采集。完整限制见 [大表分段同步与断点续传](大表分段同步与断点续传.md)。

默认 0 取消任务总时限。2026-11-06 起，ClickHouse 行值分页也把该值传入数据查询的 `max_execution_time`，不再另设固定 60 秒限制；JDBC 分段及快照同样不再把 0 转为 30 分钟。网络无响应保护、短时元数据探测和人工取消仍然有效。已有 30 分钟任务即使升级程序，也只有编辑保存为 0 后才取消总时限。

本次核验仅对实际源执行只读查询，检查上界、窗口定位、重复边界和两批共 20,000 行的结果一致性；没有向生产目标写入或替用户执行任务。最终整表速度和完成状态需部署后观察。

## 部署预检查提示 MySQL 结构读取失败

2026-11-05 在 212 实际复现的原因是 MariaDB 错误码 `1021`：`/tmp` 无法创建临时文件，返回 `errno 28 / No space left on device`。连接成功不代表查询可用；查询 `information_schema.COLUMNS` 同样可能需要临时文件。此错误不能用补表或重新授权解决。

在数据库服务器检查容量及 inode：

```bash
df -h /tmp /www/server/data /var/lib/docker
df -i /tmp /www/server/data /var/lib/docker
```

根据实际占用清理可确认无用的文件；失败任务的断点缓存可能仍用于恢复，不能整卷删除。释放空间后，在刚才解压的新发布目录执行 `bash deploy.sh start`，复用已构建镜像重新检查并启动，无需再构建。新代码会直接显示磁盘空间不足及错误码，不再统一提示连接或权限问题。
