# Kafka / Hive 本机模拟数据源

本目录为开发联调环境，端口仅监听本机回环地址。Hive 通过 HiveServer2 JDBC 访问，不是 WebHDFS。

## 已配置的数据源

| 类型 | 本机地址 | 样例对象 | 平台数据源 |
| --- | --- | --- | --- |
| Kafka 3.9.1 | `127.0.0.1:19092`，PLAINTEXT，无认证 | Topic `demo_events`，5 条 JSON 消息 | `mock_kafka`，ID 28 |
| Hive 4.0.1 | `jdbc:hive2://127.0.0.1:21000/demo`，用户名 `hive`，密码留空 | `demo.orders`，5 行、4 列 | `mock_hive`，ID 29 |

平台记录位于本机 `127.0.0.1:13308/eltock`，归属平台 admin（ID 1）。没有写入 212。

手机号模拟数据沿用原字段，每份仅增加一列，均为 5 条。Hive `demo.phones_plain` / `demo.phones_md5` 保留 orders 的四列，分别增加 `phone` / `phone_md5`；Kafka `demo_phones_plain` / `demo_phones_md5` 保留 demo_events 的四个字段，分别增加同名手机号字段。同一 user_id 使用相同的模拟手机号；MD5 为手机号原字符串 UTF-8、无盐、32 位小写，不含空格或换行。明文与 MD5 可按订单/事件 ID 对应。

在已有 `mock_hive` 或 `mock_kafka` 数据源下选择上述表/Topic 即可，无需新增平台数据源。Hive 可执行 `samples/phones.sql` 重载；Kafka 对应 `samples/phones_plain.jsonl` 和 `samples/phones_md5.jsonl`，重复生产会追加消息。

手机号同步规则：启用 `app.sync-mdn-check.enabled` 时，校验任务选定的手机号列（优先映射到 mdn/phone/mobile 的源列），发现明文手机号即拒绝，MD5 等非明文值不触发本规则。空值及其它格式仍由字段映射约束处理。本地 Hive/Kafka 的 plain 版本已验证被拒绝并保留目标旧数据，md5 版本已验证写入 5 条。带区号或分隔符的明文也会识别，错误信息仅显示掩码号码。

仍沿用已有阈值与抽样配置，超过阈值并非逐行检查。未知行数的流式源在清表前先预读校验到阈值，然后继续原抽样策略；后续批次发现明文会停止，已写批次不自动回滚。该校验不代表对所有字段、所有数据进行泄露扫描。
启动项目时显式指定 `--spring.profiles.active=dev`，避免项目默认 test 配置连接 212。

## 启停

在项目根目录运行：

```powershell
docker compose -f docker/mock-sources/compose.yaml up -d
docker compose -f docker/mock-sources/compose.yaml ps
docker compose -f docker/mock-sources/compose.yaml stop
docker compose -f docker/mock-sources/compose.yaml start
```

Kafka 使用持久卷；`kafka-init` 是一次性的数据卷权限初始化容器，退出码 0 表示正常。
Hive 的 warehouse 使用持久卷，内嵌 Derby 元数据保存在 Hive 容器中；普通 stop/start 保留库表。删除并重建 Hive 容器后，请重新运行下面的样例建表脚本。

Hive Web UI：`http://127.0.0.1:21002`。宿主 10000 端口不可用，因此 JDBC 使用 21000。

## 样例初始化

```powershell
docker compose -f docker/mock-sources/compose.yaml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic demo_events --partitions 1 --replication-factor 1
Get-Content docker/mock-sources/samples/events.jsonl -Encoding UTF8 | docker compose -f docker/mock-sources/compose.yaml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic demo_events
docker compose -f docker/mock-sources/compose.yaml exec -T hive beeline -u jdbc:hive2://localhost:10000/default -n hive --silent=true -f /samples/hive.sql
```

重复执行生产消息会追加 5 条消息；Hive 初始化会将 `demo.orders` 重置为提供的 5 行样例。
示例 CSV、JSON 和 SQL 是本模拟环境的种子数据，需保留。

## 本次功能范围

- 数据源新增、编辑、加密保存、密码脱敏与留空保留、归属隔离。
- Kafka 真实集群元数据连接测试、Topic 枚举和存在性检查；探测不消费消息、不提交位点。
- HiveServer2 JDBC 连接测试、表枚举、表存在性检查和字段读取。
- Kafka 支持 PLAINTEXT、SSL、SASL_PLAINTEXT、SASL_SSL（PLAIN / SCRAM）；自签名 TLS 需运行环境信任证书。
- Hive 当前使用二进制 JDBC 连接，暂不支持 Kerberos、HTTP transport 或 ZooKeeper 服务发现。
- 同步映射支持选择 Hive 表和 Kafka Topic、读取字段并保存和执行全量同步。两类源暂不支持增量，前后端均限制。
- Hive 使用单个 JDBC 结果游标分批读取；Kafka 从各分区最早保留位点读取到本次开始时的结束位点，不加入消费组、不提交位点。事务消息仅读取已提交部分。重复运行会重读保留数据，应使用全量清空重写策略避免追加重复。
- Kafka 消息需为 JSON 对象；前 100 条消息用于发现字段，默认映射为 String，嵌套值保留 JSON 文本。空 Topic 无法自动识别字段；非法 JSON 明确报错，tombstone 跳过。未在样本中出现的字段需在源数据稳定后重新识别。

真实服务验收已通过：两个连接、存在和不存在对象、Hive 四个字段和五行数据、两条记录的 admin 归属。临时验收源码在验证后删除。

同步修复验收：真实 Kafka / Hive 各 5 行通过 SyncEngine 写入本地 ClickHouse 临时表；各执行两次全量清空重写后均保持 5 行。另验证 Kafka 小批次分页和重读、增量请求在写入前拒绝。临时表和临时测试源码均清理。浏览器工具没有可连接页面，未进行本次页面操作验收。

官方参考：[Kafka Docker 示例](https://github.com/apache/kafka/blob/trunk/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml)、[Hive Docker](https://hive.apache.org/docs/latest/admin/setting-up-hive-with-docker/)、[Hive JDBC](https://hive.apache.org/docs/latest/user/hiveserver2-clients/)。
