/**
 * sqlSnippets.ts
 *
 * 职责：SQL 代码片段目录：按分组维护常用语句并提供按 id 查找和总数。
 * 边界：片段 id 必须唯一，SNIPPET_MAP 与 SNIPPET_TOTAL 均由目录派生；插入内容不应包含页面状态。
 */

/**
 * SQL 片段库(ClickHouse)
 *
 * 内容原取自 docs/SQL手册.md(该文件已不在本仓库,以这里的结论为准),
 * 每条都在 ClickHouse 26.7.5.10 / ods_data 库实测验证。
 *
 * 被谁消费:views/SqlQueryView.vue 的「片段库」抽屉。三处用得上:
 * - SQL_SNIPPETS:左栏按组渲染,点组切组;填了搜索词就跨全部组,按 label + desc + sql 做子串匹配
 * - SNIPPET_TOTAL:抽屉头部的「共 N 条」
 * - 插入动作把 sql 原样交给编辑器(有选中文本时会替换掉第一个占位符并选中它,便于直接改写)
 *
 * 约定:
 * - 占位符用 `{{...}}`,插入时若编辑器有选中文本,会替换第一个占位符并选中以便直接改写
 * - `warn: true` 的片段是反例/提醒,插入为 SQL 注释,防止误执行
 *
 * ⚠️ 新增一条时注意:
 * - key 全局唯一(SNIPPET_MAP 是扁平索引,重名会静默覆盖前一条)
 * - sql 是 TS 字符串:多行要写 \n,含单引号的语句用双引号包字符串,反引号别名直接写在里面即可
 * - 组内顺序 = 左栏显示顺序,各组都把最常用的排在前面,新增一般追加到组尾
 * - ⚠️ 开头的条目必须同时给 warn: true,否则反例会被当成可执行 SQL 插进去
 */

export interface SQLSnippet {
  /** 全局唯一 key(SNIPPET_MAP 的索引,重复会静默覆盖) */
  key: string
  /** 显示标题;也参与片段搜索,建议把关键函数名写进去 */
  label: string
  /** 一句话说明(实测结论);可省略,但省略后搜索只能命中 label 与 sql */
  desc?: string
  /** 插入到编辑器的原文:多行用 \n,占位符写 {{x}} */
  sql: string
  /** 反例/提醒:插入为注释(这类条目的 sql 本身就必须整段是注释) */
  warn?: boolean
}

export interface SQLSnippetGroup {
  /** 左栏分组的唯一 key,同时用于记住当前选中组 */
  key: string
  label: string
  /** 分组强调色 */
  accent: string
  items: SQLSnippet[]
}

// 下面 9 个分组按「从查数到排坑」的顺序排列:左栏一次列全,顺序即使用频度。
export const SQL_SNIPPETS: SQLSnippetGroup[] = [
  // ────────────────────────────── 哈希与归一化 ──────────────────────────────
  // 跨表对齐的地基:两边的 mdn 只有用同一套归一化+同样的编码(hex 文本)才连得上。
  // 组内反复强调的一点:CH 的 MD5()/SHA256() 返回二进制,展示和 JOIN 比对都必须套 hex()。
  {
    key: 'hash',
    label: '哈希 / 归一化',
    accent: '#f472b6',
    items: [
      {
        key: 'hex-md5',
        label: 'hex(MD5(x))  ✅',
        desc: 'CH 的 MD5() 返回二进制,必须套 hex();hex() 本身就是大写,不用再 upper()',
        sql: 'SELECT hex(MD5({{列}})) AS md5_hex FROM {{表}}'
      },
      {
        key: 'hex-sha256',
        label: 'hex(SHA256(x))',
        desc: 'SHA256() 返回 FixedString(32),同样要套 hex()',
        sql: 'SELECT hex(SHA256({{列}})) AS sha256_hex FROM {{表}}'
      },
      {
        key: 'norm-key',
        label: '归一化连接键',
        desc: '先 trim 再哈希;两边表必须用同一套归一化,否则匹配不上',
        sql: 'SELECT hex(MD5(trim({{列}}))) AS norm_key FROM {{表}}'
      },
      {
        key: 'norm-case',
        label: '大小写不敏感比对',
        desc: '存储值可能是小写 hex,比对时统一归到大写',
        // 注意两边不对称的原因:右边要 upper() 是因为库里存的可能已是小写 hex;
        // 左边不用 —— hex() 产出的就是大写,多套一个 upper 反而会让人以为 hex 结果大小写不定
        sql: 'SELECT a.{{列}} FROM {{表A}} AS a\nINNER JOIN {{表B}} AS b ON hex(MD5(a.{{列}})) = upper(b.{{哈希列}})'
      },
      {
        key: 'unhex',
        label: 'unhex 反向还原',
        desc: 'unhex() 大小写不敏感;左边等于右边返回 1',
        // 反直觉点:这里比较的是【二进制】而不是 hex 文本 —— unhex 后的值正好与 MD5() 的原始返回同类型,
        // 所以不需要再套 hex()。想给人看 hex 结果仍然要 hex(MD5(...))
        sql: "SELECT unhex('{{十六进制}}') = MD5('{{原文}}') AS `是否相等`"
      },
      {
        key: 'cityhash',
        label: 'cityHash64 分桶',
        desc: '比 md5 更快更省,适合分桶/取模场景',
        sql: 'SELECT cityHash64({{列}}) % {{桶数}} AS bucket FROM {{表}}'
      },
      {
        key: 'warn-md5-raw',
        label: '⚠️ 反例:裸用 MD5()',
        desc: '返回 FixedString(16) 二进制 → 结果页乱码、JOIN 永远匹配不上且不报错',
        sql: '-- ❌ 反例:MD5() 返回原始二进制,会乱码且 JOIN 匹配不上\n-- SELECT MD5(mdn) FROM ods_user_base;        -- 错\n-- SELECT hex(MD5(mdn)) FROM ods_user_base;   -- 对\n-- 注意:upper(MD5(x)) ≠ hex(MD5(x)),实测返回 0',
        warn: true
      }
    ]
  },

  // ────────────────────────────── 手机号 / mdn ──────────────────────────────
  // 本平台的主键字段(标准源表（ods_data）就是按 mdn 关联的),所以归一化/校验/脱敏都集中在这一组。
  // ⚠️ 「链式归一化」那条是可视化构建器算子链的等价手写版,两边语义必须一致:
  //    改了这里,engine_query_workflow.go 的对应算子也要跟着改(见 constants/queryBuilderMeta.ts 顶部说明)。
  {
    key: 'mdn',
    label: '手机号 / mdn',
    accent: '#eab308',
    items: [
      {
        key: 'mdn-trim',
        label: '去首尾空格',
        desc: '',
        sql: 'SELECT trim({{列}}) FROM {{表}}'
      },
      {
        key: 'mdn-prefix',
        label: '去国际区号前缀',
        desc: '去掉开头的 +86 / 86',
        sql: "SELECT replaceRegexpOne({{列}}, '^\\+?86', '') AS `纯号码` FROM {{表}}"
      },
      {
        key: 'mdn-digits',
        label: '只保留数字',
        desc: '清理 - 空格 等分隔符',
        sql: "SELECT replaceRegexpAll({{列}}, '[^0-9]', '') AS `纯数字` FROM {{表}}"
      },
      {
        key: 'mdn-chain',
        label: '链式归一化',
        desc: '与「可视化」构建器的算子链一一对应',
        sql: "SELECT upper(trim(replaceRegexpOne({{列}}, '^\\+?86', ''))) AS `归一化` FROM {{表}}"
      },
      {
        key: 'mdn-seg',
        label: '取前 7 位号段',
        desc: 'substring 下标从 1 开始',
        sql: 'SELECT substring({{列}}, 1, 7) AS `号段`, count() AS `用户数`\nFROM {{表}}\nGROUP BY `号段`\nORDER BY `用户数` DESC'
      },
      {
        key: 'mdn-mask',
        label: '脱敏(中间 4 位打码)',
        desc: '',
        sql: "SELECT concat(left({{列}}, 3), '****', right({{列}}, 4)) AS `脱敏` FROM {{表}}"
      },
      {
        key: 'mdn-len-check',
        label: '长度校验',
        desc: '先去掉空格/分隔符与 +86 前缀;归一化后长度 ≠ 11 的记为非法',
        sql: 'SELECT countIf(norm <> \'\' AND length(norm) <> 11) AS `非法长度`, countIf(norm = \'\') AS `空值`, count() AS `总数`\nFROM (\n  SELECT replaceRegexpOne(replaceRegexpAll(ifNull(toString({{列}}), \'\'), \'[^0-9]\', \'\'), \'^0*86\', \'\') AS norm\n  FROM {{表}}\n)'
      },
      {
        key: 'mdn-fmt-check',
        label: '格式校验',
        desc: '先归一化(去空格/分隔符、+86 前缀)再校验:1 开头 + 第二位 3-9 + 9 位数字,覆盖 16x/17x/19x 等新号段;MD5/AES 等加工后的号码不适用',
        sql: "SELECT\n  countIf(norm = '') AS `空值`,\n  countIf(norm != '' AND NOT match(norm, '^1[3-9]\\d{9}$')) AS `非法格式`,\n  count() AS `总数`\nFROM (\n  SELECT replaceRegexpOne(replaceRegexpAll(ifNull(toString({{列}}), ''), '[^0-9]', ''), '^0*86', '') AS norm\n  FROM {{表}}\n)"
      },
      {
        key: 'mdn-dup',
        label: '重复检查',
        desc: '总数 vs 唯一数,不等即有重复',
        sql: 'SELECT count() AS `总行数`, uniqExact({{列}}) AS `唯一数` FROM {{表}}'
      },
      {
        key: 'mdn-carrier',
        label: '运营商识别',
        desc: '按号段前缀粗略归类(含 16x/17x/19x 等新号段;先归一化再取前 3 位)',
        sql: "SELECT multiIf(\n  match(p, '^1(3[4-9]|4[78]|5[0-27-9]|7[28]|8[2-478]|9[578])'), '移动',\n  match(p, '^1(3[0-2]|4[56]|5[56]|6[67]|7[56]|8[5-6]|9[6])'),    '联通',\n  match(p, '^1(33|49|53|7[347]|8[01]|9[0139])'),                '电信',\n  match(p, '^192'),                                             '广电',\n  match(p, '^1(62|65|7[01])'),                                  '虚拟运营商',\n  '其他'\n) AS `运营商`, count() AS `用户数`\nFROM (\n  SELECT replaceRegexpOne(replaceRegexpAll(ifNull(toString({{列}}), ''), '[^0-9]', ''), '^0*86', '') AS p\n  FROM {{表}}\n)\nGROUP BY `运营商`"
      },
      {
        key: 'len-utf8',
        label: 'lengthUTF8(中文按字符)',
        desc: 'length() 返回字节数,中文场景要用 lengthUTF8()',
        sql: 'SELECT length({{列}}) AS `字节数`, lengthUTF8({{列}}) AS `字符数` FROM {{表}}'
      }
    ]
  },

  // ────────────────────────────── JOIN 模板 ──────────────────────────────
  // 手写 JOIN 的参照写法。可视化构建器生成的是另一套(后端拼的),这组是"人对着写"的版本。
  // 两条最常见的坑都在本组:行数膨胀(先聚合再 join)与 join_use_nulls=0(未匹配是空串不是 NULL)。
  // 新增时优先给「推荐写法」,反例统一放结尾并标 warn。
  {
    key: 'join',
    label: 'JOIN 模板',
    accent: '#60a5fa',
    items: [
      {
        key: 'join-detail',
        label: '明细 JOIN(推荐 USING)',
        desc: 'USING 合并连接键,避免出现 a.mdn / b.mdn 重复列',
        sql: 'SELECT a.{{列}}, b.{{列}}\nFROM {{表A}} AS a\nINNER JOIN {{表B}} AS b USING ({{连接键}})'
      },
      {
        key: 'join-3',
        label: '三表 JOIN',
        desc: '注意行数会相乘:1000 × 5 × 3 = 15000',
        sql: 'SELECT a.{{列}}, b.{{列}}, c.{{列}}\nFROM {{表A}} AS a\nINNER JOIN {{表B}} AS b USING ({{连接键}})\nINNER JOIN {{表C}} AS c USING ({{连接键}})'
      },
      {
        key: 'join-agg',
        label: '汇总 JOIN(先聚合防膨胀)',
        desc: '⭐ 每用户只 1 行;不这样做三表 join 会从 1000 涨到 15000 行',
        sql: 'SELECT a.{{键}}, a.{{列}}, o.cnt, o.total, l.login_cnt\nFROM {{主表}} AS a\nLEFT JOIN (\n  SELECT {{键}}, count() AS cnt, sum({{金额列}}) AS total\n  FROM {{明细表}} GROUP BY {{键}}\n) AS o USING ({{键}})\nLEFT JOIN (\n  SELECT {{键}}, count() AS login_cnt\n  FROM {{登录表}} GROUP BY {{键}}\n) AS l USING ({{键}})'
      },
      {
        key: 'join-norm',
        label: '归一化 JOIN(明文 ↔ md5)',
        desc: '⭐ 左侧函数、右侧列,CH 仍认作等值连接可走 hash join',
        sql: 'SELECT a.{{键}}, a.{{列}}, m.{{取列}}\nFROM {{明文表}} AS a\nINNER JOIN {{md5表}} AS m ON hex(MD5(a.{{键}})) = upper(m.{{哈希列}})'
      },
      {
        key: 'join-left-miss',
        label: 'LEFT JOIN 找未匹配',
        desc: "⚠️ 默认 join_use_nulls=0,未匹配是空串不是 NULL,所以用 = '' 判断",
        sql: "SELECT a.{{键}}\nFROM {{表A}} AS a\nLEFT JOIN {{表B}} AS b USING ({{键}})\nWHERE b.{{非空列}} = ''"
      },
      {
        key: 'join-exists',
        label: 'EXISTS 半连接',
        desc: '只判断存在,不产生重复行',
        sql: 'SELECT count() FROM {{表A}} AS a\nWHERE EXISTS (SELECT 1 FROM {{表B}} AS b WHERE b.{{键}} = a.{{键}})'
      },
      {
        key: 'warn-comma-cross',
        label: '⚠️ 反例:无条件多表 FROM',
        desc: '逗号连接只在「等值条件」下才优化成 inner join;否则退化成笛卡尔积(实测三表 150 亿行)',
        sql: '-- ❌ 反例:无条件多表 FROM = 笛卡尔积\n-- SELECT count() FROM ods_user_base AS b, ods_user_orders AS o, ods_user_login AS l;\n-- 实测结果:15000000000 行(150 亿)\n--\n-- ✅ 正确:每个连接条件都必须是等值\n-- SELECT ... FROM a, b, c WHERE a.k = b.k AND b.k = c.k;\n-- 拿不准就先 EXPLAIN,看 Type: 是 inner 还是 cross',
        warn: true
      },
      {
        key: 'warn-joinnulls',
        label: '⚠️ 提醒:join_use_nulls = 0',
        desc: "LEFT JOIN 未匹配时,右侧列返回类型默认值(String→'',数值→0),isNull() 恒为 0",
        sql: "-- ⚠️ 默认 join_use_nulls = 0\n-- LEFT JOIN 未匹配:右侧 String 列是 ''、数值列是 0,不是 NULL\n-- 因此 isNull(右表列) 永远返回 0,判断不了「有没有匹配」\n--\n-- 要用真 NULL 得显式开 setting:\nSELECT a.{{键}}, isNull(b.{{非空列}}) AS is_null\nFROM {{表A}} AS a\nLEFT JOIN {{表B}} AS b ON b.{{键}} = a.{{键}}\nSETTINGS join_use_nulls = 1;\n--\n-- 或者不改 setting,用空串/0 判断:\n-- WHERE b.{{非空列}} = ''",
        warn: true
      },
      {
        key: 'warn-cross-check',
        label: 'EXPLAIN 检查连接类型',
        desc: 'Type: inner 正常;Type: cross 就是笛卡尔积,必须改',
        sql: 'EXPLAIN SELECT a.{{键}} FROM {{表A}} AS a, {{表B}} AS b WHERE a.{{键}} = b.{{键}}'
      }
    ]
  },

  // ────────────────────────────── 表结构探查 ──────────────────────────────
  // 摸清一张表/一个库长什么样用的,全部只读、不产生结果集负担,可以随时插进去跑。
  // 组内唯一"必做"的是「多表基数速查」:JOIN 前先看每张表的行数与唯一键数,才能预判会不会膨胀。
  {
    key: 'inspect',
    label: '表结构探查',
    accent: '#34d399',
    items: [
      {
        key: 'describe',
        label: 'DESCRIBE 看结构',
        desc: '最常用;返回 列名/类型/默认值/注释',
        sql: 'DESCRIBE TABLE {{表}}'
      },
      {
        key: 'sys-columns',
        label: 'system.columns 取列',
        desc: '适合程序处理,可加 WHERE 过滤',
        sql: "SELECT name, type FROM system.columns\nWHERE database = '{{库}}' AND table = '{{表}}'\nORDER BY position"
      },
      {
        key: 'show-create',
        label: 'SHOW CREATE 看建表语句',
        desc: '看引擎 / ORDER BY / 分区键',
        sql: 'SHOW CREATE TABLE {{库}}.{{表}}'
      },
      {
        key: 'table-list',
        label: '表清单 + 行数 + 引擎',
        desc: '',
        sql: "SELECT name, engine, total_rows\nFROM system.tables\nWHERE database = '{{库}}'\nORDER BY name"
      },
      {
        key: 'count-uniq',
        label: '行数 + 唯一键',
        desc: '做 JOIN 前必看,决定会不会行数膨胀',
        sql: 'SELECT count() AS `行数`, uniqExact({{键}}) AS `唯一数` FROM {{表}}'
      },
      {
        key: 'cardinality',
        label: '多表基数速查',
        desc: '⭐ JOIN 前先跑这个,算出每个人的平均条数',
        sql: "SELECT '{{表A}}' AS `表`, count() AS `行数`, uniqExact({{键}}) AS `唯一数` FROM {{表A}}\nUNION ALL SELECT '{{表B}}', count(), uniqExact({{键}}) FROM {{表B}}"
      },
      {
        key: 'preview',
        label: '预览 10 行',
        desc: '',
        sql: 'SELECT * FROM {{表}} LIMIT 10'
      },
      {
        key: 'exists-table',
        label: '表是否存在',
        desc: '',
        // 反直觉:表不存在时它【不报错】,而是返回单行 0/1 —— 判断"能不能直接查"要靠它,别靠 DESCRIBE 报错来试
        sql: 'EXISTS TABLE {{库}}.{{表}}'
      }
    ]
  },

  // ────────────────────────────── 聚合统计 ──────────────────────────────
  // 出指标用的模板。共同前提:列式存储下"多扫一列就多读一次数据",
  // 所以 countIf/sumIf 这类条件聚合比写多个 WHERE 子查询更划算(参见「性能 / 陷阱」组的同名条目)。
  {
    key: 'agg',
    label: '聚合统计',
    accent: '#c084fc',
    items: [
      {
        key: 'agg-basic',
        label: '基础聚合',
        desc: '去重计数优先 uniqExact(精确) / uniq(近似更快)',
        sql: 'SELECT count()       AS `行数`,\n       uniqExact({{键}}) AS `唯一数`,\n       sum({{数值列}})   AS `合计`,\n       avg({{数值列}})   AS `均值`,\n       min({{数值列}})   AS `最小`,\n       max({{数值列}})   AS `最大`\nFROM {{表}}'
      },
      {
        key: 'agg-cond',
        label: '条件聚合 countIf / sumIf',
        desc: '⭐ 一次扫描算多个指标,比写多个 WHERE 子查询快得多',
        sql: 'SELECT countIf({{条件}})      AS `满足数`,\n       count()                AS `总数`,\n       sumIf({{数值列}}, {{条件}}) AS `满足合计`,\n       countIf({{条件}}) / count() AS `占比`\nFROM {{表}}'
      },
      {
        key: 'agg-group',
        label: '分组 + HAVING',
        desc: '',
        sql: 'SELECT {{分组列}}, count() AS cnt\nFROM {{表}}\nGROUP BY {{分组列}}\nHAVING cnt > {{阈值}}\nORDER BY cnt DESC\nLIMIT 20'
      },
      {
        key: 'agg-array',
        label: '聚合到数组 / 任意值',
        desc: '',
        sql: 'SELECT groupUniqArray({{列}}) AS `去重数组`,\n       groupArray({{列}})     AS `全量数组`,\n       any({{列}})            AS `任意值`,\n       anyLast({{列}})        AS `末值`\nFROM {{表}}'
      },
      {
        key: 'agg-topk',
        label: 'topK 近似 TopN',
        desc: '返回数组,比 GROUP BY + ORDER BY 更省资源',
        sql: 'SELECT topK({{N}})({{列}}) FROM {{表}}'
      },
      {
        key: 'agg-quantile',
        label: '分位数 / 中位数',
        desc: '',
        sql: 'SELECT quantile(0.5)({{数值列}}) AS `中位数`,\n       quantile(0.9)({{数值列}}) AS p90,\n       quantile(0.99)({{数值列}}) AS p99\nFROM {{表}}'
      },
      {
        key: 'agg-grouping-sets',
        label: 'GROUPING SETS 多维汇总',
        desc: '一次查询出多个维度的汇总,替代多次 UNION ALL',
        sql: 'SELECT {{维度A}}, {{维度B}}, count() AS cnt\nFROM {{表}}\nGROUP BY GROUPING SETS (({{维度A}}), ({{维度B}}))'
      },
      {
        key: 'agg-totals',
        label: 'WITH TOTALS 带总计',
        desc: '结果额外多一行总计',
        sql: 'SELECT {{分组列}}, count() AS cnt\nFROM {{表}}\nGROUP BY {{分组列}} WITH TOTALS'
      }
    ]
  },

  // ────────────────────────────── 去重取最新 ──────────────────────────────
  // 「每组取一条」在 CH 里有 4 种写法,本组都收了:
  // argMax / argMin(只取值)、LIMIT 1 BY(取整行,语法最短)、row_number 窗口(需要 rn 列时)、DISTINCT ON。
  // 选型建议:能用 LIMIT 1 BY 就别上窗口函数;「去重 / 取最新」里的排序键写错,留下的就不是最新那条。
  {
    key: 'dedup',
    label: '去重 / 取最新',
    accent: '#22d3ee',
    items: [
      {
        key: 'distinct',
        label: 'DISTINCT 去重',
        desc: '',
        sql: 'SELECT DISTINCT {{列}} FROM {{表}} ORDER BY {{列}}'
      },
      {
        key: 'dedup-count',
        label: '组合去重计数',
        desc: '按多个列去重后计数',
        sql: 'SELECT count() FROM (SELECT DISTINCT {{列A}}, {{列B}} FROM {{表}})'
      },
      {
        key: 'argmax',
        label: 'argMax 每组取最新 ⭐',
        desc: 'argMax(取值列, 排序列) —— CH 招牌写法,比窗口函数更简洁更快',
        sql: 'SELECT {{键}}, argMax({{取值列}}, {{时间列}}) AS `最新值`\nFROM {{表}}\nGROUP BY {{键}}'
      },
      {
        key: 'argmin',
        label: 'argMin 每组取最早',
        desc: '',
        sql: 'SELECT {{键}}, argMin({{取值列}}, {{时间列}}) AS `最早值`\nFROM {{表}}\nGROUP BY {{键}}'
      },
      {
        key: 'limit-by',
        label: 'LIMIT 1 BY 每组取整行 ⭐',
        desc: 'CH 特有语法,比窗口函数写法短得多',
        sql: 'SELECT *\nFROM {{表}}\nORDER BY {{键}}, {{时间列}} DESC\nLIMIT 1 BY {{键}}'
      },
      {
        key: 'limit-n-by',
        label: 'LIMIT N BY 每组 TopN',
        desc: '',
        sql: 'SELECT *\nFROM {{表}}\nORDER BY {{键}}, {{时间列}} DESC\nLIMIT {{N}} BY {{键}}'
      },
      {
        key: 'row-number',
        label: 'row_number 窗口取 TopN',
        desc: '需要保留完整行且要带 rn 列时用这个',
        sql: 'SELECT * FROM (\n  SELECT *,\n         row_number() OVER (PARTITION BY {{键}} ORDER BY {{时间列}} DESC) AS rn\n  FROM {{表}}\n) WHERE rn = 1'
      },
      {
        key: 'distinct-on',
        label: 'DISTINCT ON 每组第一行',
        desc: 'CH 扩展语法',
        sql: 'SELECT DISTINCT ON ({{键}}) {{键}}, {{列}} FROM {{表}}'
      }
    ]
  },

  // ────────────────────────────── 时间处理 ──────────────────────────────
  // 几乎所有查询都带时间条件,本组因此条目最多:区间、粒度、加减、格式化、解析各一条。
  // 本组最值得记住的两条:区间一律写成左闭右开(>= 起 AND < 止);单位格式记不住就用 addDays 系列。
  // 组末那条 date_add / dateDiff 的单位引号差异属于反复踩的坑,单独用反例列了出来。
  {
    key: 'time',
    label: '时间处理',
    accent: '#fb923c',
    items: [
      {
        key: 'time-range',
        label: '时间区间(左闭右开)',
        desc: '⭐ 推荐写法,避免边界重复;增量同步常用',
        sql: "SELECT count()\nFROM {{表}}\nWHERE {{时间列}} >= toDateTime('{{开始}}')\n  AND {{时间列}} <  toDateTime('{{结束}}')"
      },
      {
        key: 'time-7d',
        label: '最近 7 天',
        desc: '',
        sql: 'SELECT count() FROM {{表}} WHERE {{时间列}} >= now() - INTERVAL 7 DAY'
      },
      {
        key: 'time-today',
        label: '今天',
        desc: '',
        sql: 'SELECT count() FROM {{表}} WHERE toDate({{时间列}}) = today()'
      },
      {
        key: 'time-month',
        label: '按月统计',
        desc: '⭐ 用 toDate(toStartOfMonth(x)) 得到规范月份首日',
        sql: 'SELECT toDate(toStartOfMonth({{时间列}})) AS `月份`, count() AS `数量`\nFROM {{表}}\nGROUP BY `月份`\nORDER BY `月份`'
      },
      {
        key: 'time-day',
        label: '按天统计',
        desc: '',
        sql: 'SELECT toDate({{时间列}}) AS `日期`, count() AS `数量`\nFROM {{表}}\nGROUP BY `日期`\nORDER BY `日期`'
      },
      {
        key: 'time-hour',
        label: '按小时统计',
        desc: '',
        sql: 'SELECT toStartOfHour({{时间列}}) AS `小时`, count() AS `数量`\nFROM {{表}}\nGROUP BY `小时`\nORDER BY `小时`'
      },
      {
        key: 'time-extract',
        label: '提取年月日时分',
        desc: 'toYYYYMM 返回数值型 202401,适合做分区键',
        sql: 'SELECT toYear({{时间列}})       AS `年`,\n       toMonth({{时间列}})      AS `月`,\n       toDayOfMonth({{时间列}}) AS `日`,\n       toHour({{时间列}})       AS `时`,\n       toYYYYMM({{时间列}})     AS `年月数值`\nFROM {{表}}'
      },
      {
        key: 'time-add',
        label: '日期加减(addDays 系列)',
        desc: '⭐ 比 date_add 省心,不用记单位要不要加引号',
        sql: 'SELECT addDays({{时间列}}, {{N}})      AS `加天`,\n       addMonths({{时间列}}, {{N}})    AS `加月`,\n       subtractDays({{时间列}}, {{N}}) AS `减天`\nFROM {{表}}'
      },
      {
        key: 'time-interval',
        label: '日期加减(INTERVAL 语法)',
        desc: '最清晰可读',
        sql: 'SELECT {{时间列}} + INTERVAL {{N}} DAY  AS `加天`,\n       {{时间列}} - INTERVAL {{N}} HOUR AS `减小时`\nFROM {{表}}'
      },
      {
        key: 'time-diff',
        label: '日期差 dateDiff',
        desc: '⚠️ dateDiff 的单位要加引号;date_add 却不加(签名不一致)',
        sql: "SELECT dateDiff('day', {{开始列}}, {{结束列}}) AS `相差天数` FROM {{表}}"
      },
      {
        key: 'time-format',
        label: '格式化 formatDateTime',
        desc: '%Y年 %m月 %d日 %H时 %i分 %s秒',
        sql: "SELECT formatDateTime({{时间列}}, '%Y-%m-%d %H:%i:%s') AS `格式化时间` FROM {{表}}"
      },
      {
        key: 'time-parse',
        label: '宽松解析 parseDateTimeBestEffort',
        desc: '能吃掉多种格式;要容错用 OrNull 版本',
        sql: "SELECT parseDateTimeBestEffort('{{时间字符串}}') AS `解析结果`"
      },
      {
        key: 'warn-dateadd',
        label: '⚠️ 提醒:date_add 单位不加引号',
        desc: "date_add(day, 7, d) 裸写单位;但 dateDiff('day', a, b) 要加引号 —— 同一类函数两种写法",
        sql: "-- ⚠️ 这两个函数签名不一致,极易记错\n--\n-- ✅ date_add 单位【不加】引号:\n-- SELECT date_add(day, 7, toDate('2024-01-01'));\n--\n-- ✅ dateDiff / date_diff / age 单位【要加】引号:\n-- SELECT dateDiff('day', toDate('2024-01-01'), toDate('2024-01-15'));\n--\n-- ✅ 最省心:用 addDays / addMonths 系列,不用记单位格式\n-- SELECT addDays(toDate('2024-01-01'), 7);",
        warn: true
      }
    ]
  },

  // ────────────────────────────── 空值与类型转换 ──────────────────────────────
  // 清洗脏数据用。贯穿本组的一条:CH 的类型转换默认「转不动就抛错、整条查询中断」,
  // 凡是内容不确定干不干净,都改用 OrNull / OrDefault 版本;另外 NULL 会传染(concat 遇 NULL 全变 NULL)。
  {
    key: 'nullcast',
    label: '空值 / 类型转换',
    accent: '#a5b4fc',
    items: [
      {
        key: 'ifnull',
        label: 'ifNull 空值兜底',
        desc: '',
        sql: "SELECT ifNull({{列}}, '默认值') FROM {{表}}"
      },
      {
        key: 'coalesce',
        label: 'coalesce 取第一个非空',
        desc: '',
        sql: "SELECT coalesce({{列A}}, {{列B}}, '兜底') FROM {{表}}"
      },
      {
        key: 'safenull',
        label: '安全转换 OrNull',
        desc: "⭐ toDate('abc') 会直接报错中断查询;OrNull 版本返回 NULL",
        sql: 'SELECT toInt64OrNull({{列}})    AS `整型或空`,\n       toFloat64OrNull({{列}})  AS `浮点或空`,\n       toDateOrNull({{列}})     AS `日期或空`\nFROM {{表}}'
      },
      {
        key: 'safedefault',
        label: '安全转换 OrDefault',
        desc: '转换失败给默认值而不是 NULL',
        sql: 'SELECT toInt64OrDefault({{列}}) AS `整型或零`,\n       toDateOrDefault({{列}})   AS `日期或零值`\nFROM {{表}}'
      },
      {
        key: 'accuratest',
        label: 'accurateCastOrNull 安全 cast',
        desc: '等价于 CAST 的容错版本',
        sql: "SELECT accurateCastOrNull({{列}}, 'Int64') FROM {{表}}"
      },
      {
        key: 'filthy',
        label: '筛出脏数据',
        desc: '用 OrNull 版本找出转换失败的记录',
        sql: 'SELECT {{键}}, {{列}}\nFROM {{表}}\nWHERE toDateOrNull(toString({{列}})) IS NULL'
      },
      {
        key: 'isnull',
        label: '判空',
        desc: '⚠️ NULL = NULL 返回 NULL,必须用 IS NULL / isNull()',
        sql: 'SELECT isNull({{列}}) AS `是否为空`, count()\nFROM {{表}}\nGROUP BY `是否为空`'
      },
      {
        key: 'concat-null',
        label: 'concat 防 NULL',
        desc: "⚠️ concat('a', NULL, 'b') 返回 NULL —— NULL 会传染",
        sql: "SELECT concat(ifNull({{列A}}, ''), '-', ifNull({{列B}}, '')) AS `拼接` FROM {{表}}"
      },
      {
        key: 'typecast',
        label: '类型转换 / 看类型',
        desc: 'toString(Decimal) 会丢尾零:1.50 → "1.5"',
        sql: 'SELECT toString({{列}}) AS `字符串`,\n       toTypeName({{列}}) AS `类型`\nFROM {{表}}'
      }
    ]
  },

  // ────────────────────────────── 性能与陷阱 ──────────────────────────────
  // 几乎全是 warn 条目(插入即注释),属于"写给下一个踩坑的人"的部分,不是拿来执行的模板。
  // 新增反例时务必带上实测现象(行数 / 报错码),只写"这样不好"没有说服力,也没法验证。
  {
    key: 'pitfall',
    label: '性能 / 陷阱',
    accent: '#ef4444',
    items: [
      {
        key: 'warn-select-star',
        label: '⚠️ 反例:多表 SELECT *',
        desc: '重复列会被自动改名成 b.mdn(带点号),导出表头很难用;而且多取一堆用不到的列',
        sql: "-- ❌ 多表 SELECT * 的问题\n-- 1. 重复列自动改名:SELECT * 双表 → 出现 'orders.mdn' 这种带点号的列名\n-- 2. 三表 SELECT * → 15 列,其中 3 个是 mdn\n-- 3. 列式存储下多取列 = 多读数据 = 慢\n--\n-- ✅ 正确:明确列名,或用 USING 合并连接键\nSELECT a.mdn, a.name, b.order_id, b.amount\nFROM ods_user_base AS a\nINNER JOIN ods_user_orders AS b USING (mdn);",
        warn: true
      },
      {
        key: 'warn-non-equi',
        label: '⚠️ 反例:非等值逗号连接',
        desc: '只要条件不是等值,逗号连接立刻退化成 cross join',
        sql: '-- ❌ 非等值条件 → 退化成笛卡尔积\n-- SELECT b.mdn FROM ods_user_base b, ods_user_orders o WHERE b.mdn != o.mdn;\n-- 实测 EXPLAIN: Type: cross, Result rows: 5000000\n--\n-- ✅ 只有【两表列之间的纯等值比较】才会被优化成 inner hash join\n-- 需要非等值连接时,写显式 JOIN 并务必先 EXPLAIN 确认 Type',
        warn: true
      },
      {
        key: 'warn-no-orderby',
        label: '⚠️ 反例:LIMIT 不带 ORDER BY',
        desc: '无 ORDER BY 时返回顺序不确定,结果不可复现',
        sql: '-- ❌ SELECT * FROM ods_user_base LIMIT 100;   -- 顺序随机\n--\n-- ✅ 明确排序\nSELECT *\nFROM ods_user_base\nORDER BY mdn\nLIMIT 100;',
        warn: true
      },
      {
        key: 'warn-agg-after-join',
        label: '⚠️ 反例:三表 join 后再聚合',
        desc: '行数已经膨胀到 15000,再 count() 得到的是组合数不是用户数',
        sql: '-- ❌ 三表 join 后 count() = 15000(1000 用户 × 5 单 × 3 次登录)\n-- SELECT count() FROM base b JOIN orders o USING(mdn) JOIN login l USING(mdn);\n--\n-- ✅ 要「用户数」就直接数主表,或先聚合再 join\nSELECT count() FROM ods_user_base;\n-- 或:\n-- SELECT b.mdn, o.cnt, l.cnt\n-- FROM base b\n-- LEFT JOIN (SELECT mdn, count() cnt FROM orders GROUP BY mdn) o USING(mdn)\n-- LEFT JOIN (SELECT mdn, count() cnt FROM login  GROUP BY mdn) l USING(mdn);',
        warn: true
      },
      {
        key: 'warn-zh-alias',
        label: '⚠️ 提醒:中文别名要加反引号',
        desc: '不允许裸写非 ASCII 标识符,列别名/分组列/表别名都要用反引号包起来',
        sql: '-- ❌ 裸写中文别名 → 语法报错(MySQL 能跑,CH 不行)\n-- SELECT 1 AS 月份;\n-- code: 62, Unrecognized token: Syntax error: failed at position 13 (月)\n--\n-- ✅ 反引号包起来\nSELECT 1 AS `月份`;\n--\n-- ✅ 分组列、排序键、表别名同样要加\nSELECT city AS `城市`, count() AS `数量`\nFROM ods_user_base AS `用户表`\nGROUP BY `城市`\nORDER BY `数量` DESC\nLIMIT 5;',
        warn: true
      },
      {
        key: 'warn-limit-1000',
        label: '⚠️ 提醒:界面只显示 1000 行',
        desc: '单条查询默认上限 maxResultRows = 1000,超出会截断。看真实行数要用 count()',
        sql: '-- ⚠️ 「数据探索」页单条查询最多返回 1000 行(超出截断并标记)\n-- 界面显示 1000 行 ≠ 实际只有 1000 行\n--\n-- 看真实行数:\nSELECT count() FROM {{表}};\n-- 要看全部数据:用「导出」功能(分批 5 万,不截断),或打开服务端分页',
        warn: true
      },
      {
        key: 'perf-countif',
        label: '性能:一次扫描多指标',
        desc: '用 countIf/sumIf 替代多个 WHERE 子查询,避免多次扫表',
        sql: 'SELECT countIf({{条件A}}) AS `指标A`,\n       countIf({{条件B}}) AS `指标B`,\n       sumIf({{数值列}}, {{条件A}}) AS `金额A`,\n       count() AS `总数`\nFROM {{表}}'
      },
      {
        key: 'perf-explain',
        label: '性能:EXPLAIN 看计划',
        desc: '重点看 Type(inner/cross)、Algorithm、Result rows 三个值',
        sql: 'EXPLAIN {{把你的查询粘贴在这里}}'
      },
      {
        key: 'perf-settings',
        label: '性能:查看关键设置',
        desc: '⚠️ 注意 join_use_nulls 默认是 0',
        sql: "SELECT name, value FROM system.settings\nWHERE name IN ('max_result_rows','max_threads','join_use_nulls','max_memory_usage')"
      }
    ]
  }
]

/**
 * 扁平化索引,便于按 key 查找。
 *
 * 目前没有调用方(view 直接用 SQL_SNIPPETS 渲染),留着是给"按 key 取单条片段"的入口;
 * 也正因为是 Record 索引,新增条目时 key 必须唯一 —— 重名会静默覆盖,不会报错。
 */
export const SNIPPET_MAP: Record<string, SQLSnippet> = SQL_SNIPPETS.reduce(
  (acc, g) => {
    for (const it of g.items) acc[it.key] = it
    return acc
  },
  {} as Record<string, SQLSnippet>
)

/** 片段总数(用于 UI 展示);现算而不是写死数字 —— 手写的数字迟早会跟数组对不上 */
export const SNIPPET_TOTAL = SQL_SNIPPETS.reduce((n, g) => n + g.items.length, 0)
