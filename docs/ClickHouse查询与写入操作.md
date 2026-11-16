# ClickHouse 查询与写入操作

> 占位符说明：`{db}` 库名、`{table}` 表名、`{col}` 列名、`{type}` 列类型、`{expr}` 表达式、`{where}` 过滤条件、`{orderBy}` 排序表达式。标识符用反引号包裹并双写内部反引号；字符串用单引号并双写内部单引号。`SETTINGS` 必须放在语句最后。

## 一、结果列写法约定

| 源类型 | 投影写法 | 说明 |
|---|---|---|
| 普通列 | `` `{col}` `` | 原样返回 |
| 日期/时间列 | `` toString(`{col}`) AS `{col}` `` | 统一转成文本，避免驱动时区/格式化差异 |
| `Bool` / `Boolean` | `` toUInt8(`{col}`) AS `{col}` `` | 输出 0/1 |
| `SimpleAggregateFunction(...)` | `` toString(`{col}`) AS `{col}` `` | 驱动不认识该类型，转成聚合状态文本 |
| 其它类型 | `` `{col}` `` | 由调用方按类型转换 |

同一列名做别名时使用 `AS`，并配合 `prefer_column_name_to_alias=1`，避免别名与源列同名导致过滤条件走别名。

## 二、分页/流式查询

```sql
SELECT {投影}
FROM `{db}`.`{table}`
[WHERE {where}]
[ORDER BY {orderBy}]
LIMIT {limit} [OFFSET {offset}]
SETTINGS max_result_rows = 0,
         max_result_bytes = 0,
         max_execution_time = 0;
```

- 驱动默认会带 `max_result_rows=10000`；全量读取/导出时在 SQL 里显式写 `max_result_rows=0`。
- `max_execution_time=0` 用于长查询；旧版 ClickHouse 会按行数估算总耗时并中途中止查询，异常文本会混进行二进制流，表现为无意义的类型读取错误。
- 大结果集使用流式游标逐批读取，每批 10000 行，不把整份结果装进内存。

### 2.1 对用户 SQL 分页

```sql
-- 总行数
SELECT count() FROM ({user_sql}) AS `_page`;

-- 当前页
SELECT {投影}
FROM ({user_sql}) AS `_page`
LIMIT {size} OFFSET {offset};
```

### 2.2 导出

```sql
SELECT {导出列}
FROM ({user_sql}) AS `_page`
[LIMIT {n}]
SETTINGS max_result_rows = 0;
```

CSV 由客户端逐行写出（表头、分隔符、转义均在客户端处理），不依赖 `FORMAT CSV`。

### 2.3 控制台查询的 LIMIT 下推

对未带分页的只读语句追加一个上限，防止一次拉回整表：

```sql
{stmt} LIMIT {n + 1};
```

- 若语句里已经包含 ` LIMIT `、` FORMAT ` 或 ` SETTINGS `，不再追加（会破坏语法）。
- 常见上限 1001 行，用来判断“是否还有更多”。

### 2.4 表数据预览 / 过滤 / 分组分布

```sql
-- 数据页
SELECT {投影}
FROM `{db}`.`{table}`
[WHERE {where}]
[ORDER BY {orderBy}]
LIMIT {size} OFFSET {offset};

-- 精确总数
SELECT count() FROM `{db}`.`{table}` [WHERE {where}];

-- 分组分布（Top N）
SELECT {groupExpr}, count() AS cnt
FROM `{db}`.`{table}`
[WHERE {where}]
GROUP BY value
ORDER BY {orderExpr}
LIMIT {n};

-- 去重取一行：按去重键 LIMIT 1 BY，排序用整行文本做稳定的并列裁决
SELECT *
FROM `{db}`.`{table}`
[WHERE {where}]
ORDER BY {orderBy}, toString(tuple({allColumns})) ASC
LIMIT 1 BY ({distinctKey1}, ...);
```

## 三、按稳定键分段读取（keyset）

```sql
SELECT {投影}
FROM `{db}`.`{table}`
WHERE `{key}` <= {upper}
  [AND `{key}` > {last}]
ORDER BY `{key}`
LIMIT {batchSize + 1}
SETTINGS max_execution_time = 0,
         max_result_rows = 0,
         max_result_bytes = 0,
         prefer_column_name_to_alias = 1,
         optimize_read_in_order = 1,
         result_overflow_mode = 'throw',
         read_overflow_mode = 'throw',
         timeout_overflow_mode = 'throw';
```

要点：

- `{upper}` 是本轮快照上界，只在开始时取一次。
- 上界取法（利用排序键倒序读一行，避免 `max()` 全表聚合）：
  ```sql
  SELECT toString(`{key}`)
  FROM `{db}`.`{table}`
  ORDER BY `{key}` DESC
  LIMIT 1
  SETTINGS max_execution_time = 0,
           max_result_rows = 0,
           max_result_bytes = 0,
           optimize_read_in_order = 1,
           result_overflow_mode = 'throw',
           read_overflow_mode = 'throw',
           timeout_overflow_mode = 'throw';
  ```
- 每次多取 1 行（`batchSize + 1`）：最后一行用于判断是否还有下一批，同时能发现批次边界的重复键。若返回行数超过限定值，说明服务端没有遵守 `LIMIT`，应停止。
- 要求 `{key}` 非空、唯一、递增，且尽量是 `ORDER BY` 的第一列，否则每批都可能全表扫描。
- 批次内出现 `key` 不严格递增时立即停止，不要继续写入。

分段边界的两种取法：

```sql
-- 数值键：范围内行数 + 最大键（用于确定本批边界）
SELECT count(), max(k)
FROM (
  SELECT `{key}` AS k
  FROM `{db}`.`{table}`
  WHERE {range}
  GROUP BY k
  ORDER BY k
  LIMIT {size}
);

-- 文本键（如号码）：同样的 count/max
SELECT count(), max(k)
FROM (
  SELECT `{mdn}` AS k
  FROM `{db}`.`{table}`
  WHERE {range}
  GROUP BY k
  ORDER BY k
  LIMIT {size}
);
```

## 四、统计与元数据查询

```sql
-- 行数
SELECT count() FROM `{db}`.`{table}` [WHERE {where}];

-- 行数 + 去重数
SELECT count(), uniqExact(`{col}`) FROM `{db}`.`{table}` [WHERE {where}];

-- 最大值（数值可直接取，也可以 toString 后按文本处理）
SELECT max(`{col}`) FROM `{db}`.`{table}`;
SELECT toString(max(`{col}`)) FROM `{db}`.`{table}`;

-- 空表安全的最大值：无行时返回 NULL，而不是 0 或空串
SELECT count(), if(count() = 0, NULL, toString(max(`{col}`))) FROM `{db}`.`{table}`;

-- 行数 + 去重键数（判断分段键是否唯一）
SELECT count(), uniqExact(`{key}`) FROM `{db}`.`{table}`;

-- 按条件统计
SELECT count(), uniqExact(`{col}`) FROM `{db}`.`{table}` WHERE {where};

-- 用 system.parts 估算总量，不扫业务表（需要分片元数据权限）
SELECT toString(sum(rows))
FROM system.parts
WHERE active AND database = '{db}' AND table = '{table}'
SETTINGS max_execution_time = 3;

-- 表结构
DESCRIBE TABLE `{db}`.`{table}`;

-- 子查询结果列
DESCRIBE (SELECT ...);

-- 表引擎、键、建表语句
SELECT engine, partition_key, sorting_key, primary_key
FROM system.tables WHERE database = '{db}' AND name = '{table}';

SELECT engine, create_table_query, toString(uuid), toString(metadata_modification_time)
FROM system.tables WHERE database = '{db}' AND name = '{table}';

-- 列清单与类型
SELECT name, type
FROM system.columns WHERE database = '{db}' AND table = '{table}';

-- 按列名取注释（同名多库时优先指定库）
SELECT name, argMax(comment, database = '{db}') AS cmt
FROM system.columns
WHERE comment != '' AND name IN ('{col1}', '{col2}', ...)
GROUP BY name;

-- 按库/表/列批量取注释
SELECT database, table, name, comment
FROM system.columns
WHERE comment != ''
  AND (database, table, name) IN (('{db}', '{table}', '{col}'), ...);

-- 表清单 + 列数 + 表自身统计（不依赖 system.parts 权限）
SELECT t.name, COALESCE(c.cnt, 0), t.total_rows, t.total_bytes
FROM system.tables t
LEFT JOIN (
  SELECT database, table, count() AS cnt
  FROM system.columns
  WHERE database = '{db}'
  GROUP BY database, table
) c ON c.database = t.database AND c.table = t.name
WHERE t.database = '{db}' AND t.is_temporary = 0
ORDER BY t.name;

-- 分区内容校验指纹（不读业务数据，只看 part 元数据）
SELECT toString(groupBitXor(cityHash64(name, toString(rows), toString(bytes_on_disk))))
FROM system.parts
WHERE active AND database = '{db}' AND table = '{table}';

-- 查询设置
SELECT value FROM system.settings WHERE name = 'async_insert';
```

## 五、写入

### 5.1 字面量批量 INSERT

```sql
INSERT INTO `{db}`.`{table}` (`{col1}`, `{col2}`, ...) VALUES
  ({v11}, {v12}, ...),
  ({v21}, {v22}, ...),
  ...;
```

字面量规则：

| 值类型 | 写法 |
|---|---|
| `NULL` | `NULL` |
| 布尔 | `1` / `0` |
| 数字 | 原样 |
| 字节数组 | `unhex('{十六进制}')` |
| 时间 | `'YYYY-MM-DD HH:MM:SS'`（小数秒按目标类型保留） |
| 其它 | `'文本'`，内部单引号双写 |

- 普通批量：每 1000 行拼一条 INSERT。
- 断点批量：每 10000 行拼一条，SQL 长度约 1M 字符时在完整行边界拆分（单行可能更长）。
- 分块之间没有事务；前一块成功、后一块失败时前面已落库。需要幂等时配合目标表键去重或先清理待写区间。
- 断点写入可在语句最前面加 `/*{marker}*/` 注释，用于后续按标记 `KILL QUERY`。
- 部分驱动会自行解析 `INSERT ... VALUES`，对转义引号处理不完整；可在语句前加 `/*{guard}*/` 之类的块注释，让语句走服务端解析（注释对 ClickHouse 等同空白）。
- 两个批量写入口的字面量规则并不完全相同，混用时注意：
  - 同步批量：`NULL`→`NULL`，布尔→`1`/`0`，数字原样，字节→`unhex('...')`，时间→`'YYYY-MM-DD HH:MM:SS'`，其它字符串单引号双写（不额外转义反斜杠）。
  - 表格单元格写入：布尔→`true`/`false`；字符串类（String/FixedString/日期时间/UUID/Enum/IP）同时转义反斜杠和单引号；数字先做合法性校验再原样拼接。

### 5.2 INSERT ... SELECT

```sql
INSERT INTO `{db}`.`{target}` (`{col1}`, `{col2}`, ...)
SELECT ... FROM ...;

-- 或（目标列与源列完全一致时）
INSERT INTO `{db}`.`{target}`
SELECT ... FROM ...;
```

- 必须写列名清单：源查询只返回部分列时，不写列名会按位置匹配失败。
- `CREATE TABLE ... AS SELECT` 需要目标表的 `INSERT` 权限。

## 六、行级修改与删除

```sql
-- 更新
ALTER TABLE `{db}`.`{table}`
  UPDATE `{col1}` = {value1}, `{col2}` = {value2}
  WHERE {条件}
  SETTINGS mutations_sync = 2;

-- 删除
ALTER TABLE `{db}`.`{table}`
  DELETE WHERE {条件}
  SETTINGS mutations_sync = 2;
```

- `mutations_sync = 2` 表示等待 mutation 在所有副本上完成后再返回；单机环境 1 或 2 均可。
- 条件尽量命中排序键/主键；否则会扫描并重写大量数据。
- 大批量删除/更新会生成 mutation 任务，注意 `system.mutations` 的进度。
- 按区间清理（例如分段重放的未确认区间）：
  ```sql
  ALTER TABLE `{db}`.`{table}`
    DELETE WHERE `{key}` <= {upper} [AND `{key}` > {last}]
      AND `{source_column}` = '{source_id}'
    SETTINGS mutations_sync = 2;
  ```

- 文本键也可以按区间清理（如号码）：
  ```sql
  ALTER TABLE `{db}`.`{table}`
    DELETE WHERE `{key}` > '{last}' AND `{key}` <= '{pending}'
    SETTINGS mutations_sync = 2;
  ```

## 七、终止查询

```sql
-- 按 query_id
KILL QUERY WHERE query_id = '{query_id}' SYNC;

-- 按语句注释标记
KILL QUERY
WHERE position(query, '/*{marker}*/') > 0
  AND position(query, 'KILL QUERY') = 0
SYNC;
```

## 八、SETTINGS 速查

| 设置 | 常用值 | 作用 |
|---|---|---|
| `max_result_rows` | `0` | 不限制返回行数；驱动默认 10000 |
| `max_result_bytes` | `0` | 不限制返回字节数 |
| `max_execution_time` | `0` | 不限制单条查询总时长；由调用方超时控制 |
| `prefer_column_name_to_alias` | `1` | `WHERE` 里优先按源列名而不是同名列别名解析 |
| `optimize_read_in_order` | `1` | 按排序键顺序读，keyset 分段更省资源 |
| `result_overflow_mode` | `'throw'` | 超过 `max_result_rows/bytes` 时抛错而不是静默截断 |
| `read_overflow_mode` | `'throw'` | 读取阶段超限抛错 |
| `timeout_overflow_mode` | `'throw'` | 超时抛错而不是返回部分结果 |
| `mutations_sync` | `1` / `2` | 等待 `ALTER UPDATE/DELETE` 完成 |
| `async_insert` | `0` | 关闭异步插入，确保写入落库后再确认进度 |
| `index_granularity` | 建表时按需 | 索引粒度，影响扫描/合并成本 |

连接级默认值（每个新连接生效）：

```text
max_result_rows = 10000
result_overflow_mode = break
```

需要全量读取时在具体语句上覆盖为 `0`。native 协议可用 `SET {setting} = {value}` 设置会话参数；HTTP 协议通过连接参数下发。

## 九、标识符与字符串转义

- 标识符：`` `name` ``，内部反引号写成两个反引号：`` `a``b` ``。
- 字符串：`'text'`，内部单引号写成两个：`'O''Brien'`。
- 反斜杠在 ClickHouse 字符串里是转义符；拼接原始文本时按 ClickHouse 规则处理，不要依赖数据库方言。
- 列别名与源列同名时，`WHERE` 中的引用受 `prefer_column_name_to_alias` 影响；需要按源列过滤时显式设置该参数。
