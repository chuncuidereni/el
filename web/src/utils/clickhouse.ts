/**
 * clickhouse.ts
 *
 * 职责：ClickHouse 编辑器补全元数据：提供关键字、函数和按上下文生成补全项的能力。
 * 边界：关键字/函数集合应去重并与当前 ClickHouse 语法匹配；补全只读表列元数据，不触发网络请求。
 */

import type { CompletionContext, CompletionResult } from '@codemirror/autocomplete'

/** ClickHouse 常用关键词 */
const KEYWORDS_RAW = [
  'SELECT', 'DISTINCT', 'FROM', 'WHERE', 'PREWHERE', 'GROUP BY', 'HAVING', 'ORDER BY',
  'LIMIT', 'OFFSET', 'WITH', 'TOTALS', 'INTO', 'OUTFILE', 'FORMAT', 'SETTINGS', 'SAMPLE',
  'FINAL', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'FULL', 'OUTER', 'CROSS', 'GLOBAL', 'ANY',
  'ALL', 'ASOF', 'ON', 'AS', 'AND', 'OR', 'NOT', 'IN', 'EXISTS', 'BETWEEN', 'LIKE', 'ILIKE',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'IF', 'NULL', 'TRUE', 'FALSE', 'IS',
  'CREATE', 'TABLE', 'DATABASE', 'VIEW', 'IF NOT EXISTS', 'IF EXISTS', 'ON CLUSTER',
  'ENGINE', 'PARTITION BY', 'PRIMARY KEY', 'ORDER BY', 'SAMPLE BY', 'TTL', 'CODEC',
  'COMMENT', 'DEFAULT', 'MATERIALIZED', 'ALIAS', 'ALTER', 'ADD', 'DROP', 'DELETE',
  'UPDATE', 'MODIFY', 'RENAME', 'TRUNCATE', 'INSERT', 'INTO', 'VALUES', 'SHOW',
  'DESCRIBE', 'DESC', 'EXPLAIN', 'OPTIMIZE', 'SYSTEM', 'KILL', 'USE', 'SET', 'GRANT',
  'REVOKE', 'DICTIONARY', 'INDEX', 'CONSTRAINT', 'PROJECTION', 'AGGREGATE',
  'UNION', 'INTERSECT', 'EXCEPT', 'UNIQUE',
]


/** ClickHouse 常用函数(括号结尾便于补全后直接输入参数) */
const FUNCTIONS_RAW = [
  // 日期时间
  'now', 'today', 'yesterday', 'toDate', 'toDateTime', 'toDateTime64', 'toStartOfDay',
  'toStartOfMonth', 'toStartOfQuarter', 'toStartOfYear', 'toMonday', 'toWeek', 'toYear',
  'toMonth', 'toDayOfMonth', 'toDayOfWeek', 'toHour', 'toMinute', 'toSecond', 'toYYYYMM',
  'toYYYYMMDD', 'toYYYYMMDDhhmmss', 'dateDiff', 'age', 'addDays', 'addMonths', 'addYears',
  'subtractDays', 'subtractMonths', 'subtractYears', 'formatDateTime', 'parseDateTimeBestEffort',
  // 聚合
  'count', 'countDistinct', 'sum', 'avg', 'min', 'max', 'median', 'quantile', 'quantiles',
  'uniq', 'uniqExact', 'uniqHLL12', 'groupArray', 'groupUniqArray', 'any', 'anyLast',
  'argMin', 'argMax', 'topK', 'corr', 'covarSamp', 'stddevPop', 'varPop',
  // 字符串
  'length', 'lower', 'upper', 'concat', 'substring', 'substr', 'replaceAll', 'replaceRegexpAll',
  'splitByString', 'splitByChar', 'trim', 'ltrim', 'rtrim', 'leftPad', 'rightPad', 'reverse',
  'format', 'base64Encode', 'base64Decode', 'extract', 'match',
  // 数值
  'abs', 'round', 'floor', 'ceil', 'greatest', 'least', 'pow', 'exp', 'log', 'log10', 'sqrt',
  'sign', 'intDiv', 'intDivOrZero', 'modulo', 'gcd', 'lcm', 'rand', 'rand64',
  // 条件/空值
  'if', 'multiIf', 'nullIf', 'coalesce', 'assumeNotNull', 'isNull', 'isNotNull',
  // 数组/元组/映射
  'arrayJoin', 'arrayMap', 'arrayFilter', 'arraySum', 'arraySort', 'arrayReverseSort',
  'has', 'indexOf', 'arrayLength', 'arrayConcat', 'tuple', 'map', 'mapKeys', 'mapValues',
  // JSON
  'JSONExtractString', 'JSONExtractInt', 'JSONExtractFloat', 'JSONHas', 'JSONLength', 'JSONKeys',
  // 转换
  'cast', 'toString', 'toInt8', 'toInt16', 'toInt32', 'toInt64', 'toUInt8', 'toUInt16',
  'toUInt32', 'toUInt64', 'toFloat32', 'toFloat64', 'toDecimal32', 'toDecimal64', 'toUUID',
  'toIPv4', 'toIPv6', 'toFixedString', 'toNullable', 'toLowCardinality', 'toBool',
  // 其他
  'dictGet', 'joinGet', 'runningDifference', 'neighbor', 'bar', 'formatReadableSize',
]

// ⚠️ 两个原数组都有重复项(关键词里的 ORDER BY / INTO / ANY,函数里的 any …),
//    不去重会让补全列表出现重复候选 → Set 去重。
export const CLICKHOUSE_KEYWORDS = [...new Set(KEYWORDS_RAW)]
export const CLICKHOUSE_FUNCTIONS = [...new Set(FUNCTIONS_RAW)]

/** option 数组只建一次(每次击键都重建 ~250 个对象是纯浪费) */
const KEYWORD_OPTIONS = CLICKHOUSE_KEYWORDS.map((k) => ({ label: k, type: 'keyword' as const }))
const FUNCTION_OPTIONS = CLICKHOUSE_FUNCTIONS.map((f) => ({ label: f + '(', type: 'function' as const }))

export interface CompletionData {
  /** 当前可用的表名(通常是已加载的表清单) */
  getTables: () => string[]
  /** 某张表的列;返回 undefined 表示「不知道」(结构未加载),而不是「没有列」 */
  getColumns: (table: string) => string[] | undefined
}

const IDENT_RE = /[\w$]+/

/** 组装 ClickHouse 补全源:表名/列名(含 table. 上下文)/关键词/函数 */
export function clickHouseCompletion(data: CompletionData) {
  return (context: CompletionContext): CompletionResult | null => {
    // 只取光标之前的文本做前缀匹配;补全源不缓存文档,重建成本可接受
    const before = context.state.sliceDoc(0, context.pos)

    // 形如 schema.table. 或 table. → 补全列
    const dotMatch = before.match(/([A-Za-z_][A-Za-z0-9_]*)\.([\w$]*)$/)
    if (dotMatch) {
      const table = dotMatch[1]
      const cols = data.getColumns(table)
      if (cols && cols.length) {
        const prefix = dotMatch[2]
        return {
          // from 只跳到点号之后:已输入的「表名.」保留,只替换正在敲的那半截列名
          from: context.pos - prefix.length,
          options: cols.map((c) => ({ label: c, type: 'property' })),
          validFor: IDENT_RE,
        }
      }
      // 拿不到该表的列(结构未加载/表名不对)就交还给其他补全源,别弹一个空列表
      return null
    }

    // 普通上下文 → 表名/关键词/函数
    const word = context.matchBefore(IDENT_RE)
    // 光标前是空白且非手动触发(Ctrl+Space)时不弹:否则每敲一个空格都会闪出全量候选
    if (!word || (word.from === word.to && !context.explicit)) return null

    const options = [
      // 表名放在最前:同一前缀下用户更常想选表,而不是关键词
      ...data.getTables().map((t) => ({ label: t, type: 'type' as const })),
      ...KEYWORD_OPTIONS,
      ...FUNCTION_OPTIONS,
    ]
    return {
      from: word.from,
      options,
      validFor: IDENT_RE,
    }
  }
}
