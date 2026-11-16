/**
 * queryBuilderMeta.ts
 *
 * 职责：可视化查询元数据：定义值类型、流水线/程序算子、聚合、过滤和时间预设，并渲染步骤表达式。
 * 本文件是「可视化查询能做什么」的唯一事实来源，只包含数据表与纯函数，不依赖 Vue、不发请求、无副作用。
 *
 * 消费方：useVisualQuery、VisualQueryBuilder 及子组件、ExploreWizard、ArbWorkflowsView、SqlQueryView、SourceTreePanel。
 * 边界：所有 key 必须与后端执行器白名单一致；算子、聚合、过滤操作符或时间预设新增时，两边必须同步修改。
 * key 是协议标识，label 仅供展示；`tpl` 的第一个 %s 固定表示上游值或列，其余按 params 顺序填入。
 */

/** 字段的值类型(由 ClickHouse 列类型推导) */
export type ValueKind = 'number' | 'string' | 'date' | 'bool' | 'other'

/** 参数渲染方式(与后端 renderPipelineArg 的 Kind 一致) */
export type ParamKind = 'number' | 'string' | 'ident'

/** 算子的一个参数 */
export interface PipelineParam {
  /** 参数名(用于 placeholder) */
  label: string
  /** 渲染方式 */
  kind: ParamKind
  /** 默认值 */
  def?: string
  /**
   * 枚举参数:非空时渲染成【下拉框】,取值必须是其中之一。
   *
   * ⚠️ 只对「后端本来就只认这几个值」的参数使用 —— 下拉里列出的就是后端的白名单,
   * 这样前端不可能选出后端会拒绝的值。自由文本参数不要加 options。
   */
  options?: { value: string; label: string }[]
  /**
   * 是否必填。不传时按 kind 推断:string 视为可空,number / ident 必填。
   *
   * ⚠️ 只给两条例外显式标注,不要滥用(也不要给 CK 处理链的同名参数加 ——
   *    它们的空串会渲染成合法的 '' 字面量,后端并不拒绝):
   *   - `required: true` —— string 但后端要求非空
   *     (prog_replace 的「查找」、prog_regex_replace 的「正则」、
   *      prog_trim_chars 的「字符集合」、3DES / FF1 的「密钥」)。
   *      不标的话前端会放行,点运行才被后端 400。
   *   - `required: false` —— number 但后端允许留空
   *     (prog_substr 的「长度」留空 = 到末尾)。
   *      不标的话前端会当成必填,把本来合法的配置卡成不能跑。
   */
  required?: boolean
}

// ─────────── 可复用参数选项(与后端校验保持一致) ───────────

/** 3DES 的密文编码:后端 des3_enc / des3_dec 只认这两种 */
export const ENC_FORMAT_OPTIONS = [
  { value: 'base64', label: 'base64(默认)' },
  { value: 'hex', label: 'hex(十六进制)' }
]

/** 时间戳单位 */
export const UNIX_UNIT_OPTIONS = [
  { value: 's', label: 's(秒)' },
  { value: 'ms', label: 'ms(毫秒)' }
]

/** 日期【输入】格式 —— 与后端 dateLayouts 的白名单一致 */
export const DATE_INPUT_FORMAT_OPTIONS = [
  { value: 'auto', label: 'auto(自动识别)' },
  { value: 'yyyyMMdd', label: 'yyyyMMdd(20240115)' },
  { value: 'yyyy-MM-dd', label: 'yyyy-MM-dd(2024-01-15)' },
  { value: 'yyyy/MM/dd', label: 'yyyy/MM/dd(2024/01/15)' },
  {
    value: 'yyyy-MM-dd HH:mm:ss',
    label: 'yyyy-MM-dd HH:mm:ss(2024-01-15 10:20:30)'
  },
  { value: 'yyyyMMddHHmmss', label: 'yyyyMMddHHmmss(20240115102030)' },
  { value: 'unix_s', label: 'unix_s(秒级时间戳)' },
  { value: 'unix_ms', label: 'unix_ms(毫秒级时间戳)' }
]

/** 日期【输出】格式 */
export const DATE_OUTPUT_FORMAT_OPTIONS = [
  { value: 'yyyy-MM-dd', label: 'yyyy-MM-dd(2024-01-15)' },
  {
    value: 'yyyy-MM-dd HH:mm:ss',
    label: 'yyyy-MM-dd HH:mm:ss(2024-01-15 10:20:30)'
  },
  { value: 'yyyyMMdd', label: 'yyyyMMdd(20240115)' },
  { value: 'yyyy/MM/dd', label: 'yyyy/MM/dd(2024/01/15)' },
  { value: 'yyyyMMddHHmmss', label: 'yyyyMMddHHmmss(20240115102030)' },
  { value: 'unix_s', label: 'unix_s(秒级时间戳)' },
  { value: 'unix_ms', label: 'unix_ms(毫秒级时间戳)' }
]

/** 单输入处理链算子(key = 后端 allowedFunctions 的键) */
export interface PipelineOp {
  key: string
  label: string
  /** fmt 模板:第一个 %s 是输入,其余是参数 */
  tpl: string
  desc?: string
  /** 适用类型(空 = 全部适用) */
  kinds?: ValueKind[]
  /** 额外参数定义(顺序 = 模板里 %s 的顺序) */
  params?: PipelineParam[]
  /**
   * 该算子执行后值的【类型变为】。
   * 用于渐进推导「处理链当前输出类型」—— 否则源列是 String 时,数值算子组永远不显示
   * (例如 mdn → toFloat64 → abs,中间那步把 String 变成了 number)。
   * 空 = 类型不变(如 ifNull / assumeNotNull)。
   */
  outKind?: ValueKind
  /**
   * 程序算子:由 el-to-ck(Go)计算,ClickHouse 不参与。
   *
   * ⚠️ 后果:该列的值 ClickHouse 不知道,【不能用于过滤/分组/排序】(后端会校验并拒绝)。
   * 与普通算子的区别:普通算子生成 SQL 交给 CK 算,全功能可用。
   *
   * ⚠️ 这个标志目前只是声明(前端没有读它):判断一步是不是程序算子靠它所在的数据(见 FieldPipelineEditor 的 listOf)。
   *    新增时必须显式写上,否则后端注册表与前端数据对不上,排查起来会兜圈子。
   */
  prog?: boolean
}

export interface PipelineOpGroup {
  key: string
  label: string
  accent: string
  ops: PipelineOp[]
}

/**
 * ClickHouse 处理链算子(生成的 SQL 交给 CK 执行的都在这里)。
 *
 * 分组只影响界面上「算子抽屉」的分类,不影响行为;组内顺序 = 抽屉里的显示顺序,
 * 常用的放前面(用户从上往下找)。
 *
 * ⚠️ 处理链是【顺序敏感】的:数组里算子的先后就是 SQL 里函数嵌套的先后,
 *    同一个算子换个位置结果就可能不同(如先 trim 再 substring 与反过来)。
 *    这里只负责列出"有哪些算子",顺序由用户在编辑器里排。
 */
export const PIPELINE_OP_GROUPS: PipelineOpGroup[] = [
  // 字符串:清洗与取子串,是四组里算子最多的一组。
  // 绝大多数带 kinds: ['string'],只对文本列显示 —— 数值列想用,得先在链上加一步 toString(见「类型转换」组)。
  {
    key: 'string',
    label: '字符串',
    accent: '#60a5fa',
    ops: [
      {
        key: 'trim',
        label: '去首尾空格',
        tpl: 'trim(%s)',
        desc: 'trim(x)',
        kinds: ['string'],
        outKind: 'string'
      },
      {
        key: 'trim_left',
        label: '去左空格',
        tpl: 'trimLeft(%s)',
        desc: 'trimLeft(x)',
        kinds: ['string'],
        outKind: 'string'
      },
      {
        key: 'trim_right',
        label: '去右空格',
        tpl: 'trimRight(%s)',
        desc: 'trimRight(x)',
        kinds: ['string'],
        outKind: 'string'
      },
      {
        key: 'upper',
        label: '转大写',
        tpl: 'upper(%s)',
        desc: 'upper(x)',
        kinds: ['string'],
        outKind: 'string'
      },
      {
        key: 'lower',
        label: '转小写',
        tpl: 'lower(%s)',
        desc: 'lower(x)',
        kinds: ['string'],
        outKind: 'string'
      },
      {
        key: 'length',
        label: '长度(字节)',
        tpl: 'length(%s)',
        desc: 'length(x)',
        kinds: ['string'],
        outKind: 'number'
      },
      {
        key: 'length_utf8',
        label: '长度(字符)',
        tpl: 'lengthUTF8(%s)',
        desc: 'lengthUTF8(x) 中文用',
        kinds: ['string'],
        outKind: 'number'
      },
      {
        key: 'reverse',
        label: '反转',
        tpl: 'reverse(%s)',
        desc: 'reverse(x)',
        kinds: ['string'],
        outKind: 'string'
      },
      {
        key: 'substring',
        label: '截取子串',
        tpl: 'substring(%s, %s, %s)',
        desc: 'substring(x, 起始, 长度) —— 下标从 1 开始。例：substring(\'13800138000\', 4, 4) → 0013',
        kinds: ['string'],
        params: [
          { label: '起始', kind: 'number', def: '1' },
          { label: '长度', kind: 'number', def: '7' }
        ]
      },
      {
        key: 'left',
        label: '取前 N 位',
        tpl: 'left(%s, %s)',
        desc: 'left(x, n)',
        kinds: ['string'],
        params: [{ label: '长度', kind: 'number', def: '3' }]
      },
      {
        key: 'right',
        label: '取后 N 位',
        tpl: 'right(%s, %s)',
        desc: 'right(x, n)',
        kinds: ['string'],
        params: [{ label: '长度', kind: 'number', def: '4' }]
      },
      {
        key: 'replace_regexp',
        label: '正则替换',
        tpl: 'replaceRegexpOne(%s, %s, %s)',
        desc: 'replaceRegexpOne(x, 正则, 替换)。⚠️ 反向引用用 \\1 不是 $1。例：(\\d{3})\\d{4}(\\d{4}) 替换为 \\1****\\2 可对手机号脱敏',
        kinds: ['string'],
        params: [
          { label: '正则', kind: 'string', def: '' },
          { label: '替换为', kind: 'string', def: '' }
        ]
      },
      {
        key: 'regexp_replace_all',
        label: '正则替换全部',
        tpl: 'replaceRegexpAll(%s, %s, %s)',
        desc: 'replaceRegexpAll(x, 正则, 替换)。例：把连续空白 \\s+ 替换为单个空格',
        kinds: ['string'],
        params: [
          { label: '正则', kind: 'string', def: '' },
          { label: '替换为', kind: 'string', def: '' }
        ]
      },
      {
        key: 'replace',
        label: '普通替换',
        tpl: 'replace(%s, %s, %s)',
        desc: 'replace(x, 旧, 新) —— 字面量替换,不支持正则',
        kinds: ['string'],
        params: [
          { label: '被替换', kind: 'string', def: '' },
          { label: '替换为', kind: 'string', def: '' }
        ]
      },
      {
        key: 'regexp_extract',
        label: '正则提取',
        tpl: 'extract(%s, %s)',
        desc: 'extract(x, 正则) —— 取第一个捕获组。例：提取【】内文字用 【(.+?)】；提取圆括号内用 \\((.+?)\\)；提取方括号内用 \\[(.+?)\\]',
        kinds: ['string'],
        params: [{ label: '正则', kind: 'string', def: '' }]
      },
      {
        key: 'starts_with',
        label: '前缀判断',
        tpl: 'startsWith(%s, %s)',
        desc: 'startsWith(x, 前缀) → 0/1',
        kinds: ['string'],
        params: [{ label: '前缀', kind: 'string', def: '' }]
      },
      {
        key: 'ends_with',
        label: '后缀判断',
        tpl: 'endsWith(%s, %s)',
        desc: 'endsWith(x, 后缀) → 0/1',
        kinds: ['string'],
        params: [{ label: '后缀', kind: 'string', def: '' }]
      },
      {
        key: 'match',
        label: '正则匹配',
        tpl: 'match(%s, %s)',
        desc: 'match(x, 正则) → 0/1。例：^1[3-9]\\d{9}$ 判断是否为 11 位手机号',
        kinds: ['string'],
        params: [{ label: '正则', kind: 'string', def: '' }]
      },
      {
        key: 'concat',
        label: '追加后缀',
        tpl: 'concat(%s, %s)',
        desc: 'concat(x, 串) —— 追加一段文本',
        kinds: ['string'],
        params: [{ label: '追加串', kind: 'string', def: '' }]
      },
      {
        key: 'left_pad',
        label: '左侧补位',
        tpl: 'leftPad(%s, %s, %s)',
        desc: 'leftPad(x, 总长度, 填充字符) —— 第 3 参必须是字符串',
        kinds: ['string'],
        params: [
          { label: '总长度', kind: 'number', def: '11' },
          { label: '填充', kind: 'string', def: '0' }
        ]
      },
      {
        key: 'right_pad',
        label: '右侧补位',
        tpl: 'rightPad(%s, %s, %s)',
        desc: 'rightPad(x, 总长度, 填充字符)',
        kinds: ['string'],
        params: [
          { label: '总长度', kind: 'number', def: '3' },
          { label: '填充', kind: 'string', def: '0' }
        ]
      }
    ]
  },
  // 哈希:模板里一律写死 hex(...) 而不是让用户自己选。原因是裸 MD5()/SHA256() 返回二进制,
  // 展示会乱码、JOIN 会匹配不上且【不报错】—— 属于用户自己很难定位的坑,从源头堵掉。
  // 默认 kinds 为空 = 任意类型可用(数值、日期都能当哈希输入)。
  {
    key: 'hash',
    label: '哈希 / 归一化',
    accent: '#f472b6',
    ops: [
      {
        key: 'hex_md5',
        label: 'MD5 · 32位',
        tpl: 'hex(MD5(%s))',
        desc: 'hex(MD5(x)) —— 与 md5 列比对必须用这个,裸 MD5() 是二进制'
      },
      {
        key: 'hex_sha1',
        label: 'SHA1 · 40位',
        tpl: 'hex(SHA1(%s))',
        desc: '已不安全,兼容老数据用',
        outKind: 'string'
      },
      {
        key: 'hex_sha224',
        label: 'SHA224 · 56位',
        tpl: 'hex(SHA224(%s))',
        desc: 'hex(SHA224(x))',
        outKind: 'string'
      },
      {
        key: 'hex_sha256',
        label: 'SHA256 · 64位',
        tpl: 'hex(SHA256(%s))',
        desc: 'hex(SHA256(x)) —— 最常用',
        outKind: 'string'
      },
      {
        key: 'hex_sha384',
        label: 'SHA384 · 96位',
        tpl: 'hex(SHA384(%s))',
        desc: 'hex(SHA384(x))',
        outKind: 'string'
      },
      {
        key: 'hex_sha512',
        label: 'SHA512 · 128位',
        tpl: 'hex(SHA512(%s))',
        desc: 'hex(SHA512(x))',
        outKind: 'string'
      },
      {
        key: 'hex_sha512_256',
        label: 'SHA512/256 · 64位',
        tpl: 'hex(SHA512_256(%s))',
        desc: 'hex(SHA512_256(x))',
        outKind: 'string'
      },
      {
        key: 'hex_md4',
        label: 'MD4 · 32位',
        tpl: 'hex(MD4(%s))',
        desc: '已不安全,仅兼容老数据',
        outKind: 'string'
      },
      {
        key: 'hex_ripemd160',
        label: 'RIPEMD160 · 40位',
        tpl: 'hex(RIPEMD160(%s))',
        desc: 'hex(RIPEMD160(x))',
        outKind: 'string'
      },
      {
        key: 'hex_blake3',
        label: 'BLAKE3 · 64位',
        tpl: 'hex(BLAKE3(%s))',
        desc: '比 SHA256 快很多',
        outKind: 'string'
      }
    ]
  },
  // 类型转换:除了改类型,它还是"换 kinds 归属"的唯一手段 ——
  // 例如 String 列想用数值算子,必须先接一步 toFloat64(outKind 会把链路当前类型改成 number)。
  // ⚠️ 除 accurateCastOrNull 外都是严格版本:转不动就报错、整条查询中断。
  {
    key: 'cast',
    label: '类型转换',
    accent: '#34d399',
    ops: [
      {
        key: 'to_string',
        label: 'toString',
        tpl: 'toString(%s)',
        outKind: 'string'
      },
      {
        key: 'to_int64',
        label: 'toInt64',
        tpl: 'toInt64(%s)',
        outKind: 'number'
      },
      {
        key: 'to_uint64',
        label: 'toUInt64',
        tpl: 'toUInt64(%s)',
        outKind: 'number'
      },
      {
        key: 'to_float64',
        label: 'toFloat64',
        tpl: 'toFloat64(%s)',
        outKind: 'number'
      },
      { key: 'to_date', label: 'toDate', tpl: 'toDate(%s)', outKind: 'date' },
      {
        key: 'to_datetime',
        label: 'toDateTime',
        tpl: 'toDateTime(%s)',
        outKind: 'date'
      },
      {
        key: 'cast_nullable',
        label: '安全转换',
        tpl: 'accurateCastOrNull(%s, %s)',
        desc: '转换失败给 NULL,不报错中断查询',
        params: [{ label: '类型', kind: 'ident', def: 'Int64' }]
      }
    ]
  },
  // 日期 / 时间:全部要求 kinds: ['date'],即先得是日期列(文本日期要先转换)。
  // 输出类型分两类:取年月日时之类的分量是 number(可继续接数值算子),
  // 截断(toStartOf*)与 formatDateTime 保持 date —— outKind 标错会让后续算子组显示得莫名其妙。
  {
    key: 'date',
    label: '日期 / 时间',
    accent: '#fb923c',
    ops: [
      {
        key: 'to_year',
        label: '取年',
        tpl: 'toYear(%s)',
        kinds: ['date'],
        outKind: 'number'
      },
      {
        key: 'to_month',
        label: '取月',
        tpl: 'toMonth(%s)',
        kinds: ['date'],
        outKind: 'number'
      },
      {
        key: 'to_day',
        label: '取日',
        tpl: 'toDayOfMonth(%s)',
        kinds: ['date'],
        outKind: 'number'
      },
      {
        key: 'to_hour',
        label: '取小时',
        tpl: 'toHour(%s)',
        kinds: ['date'],
        outKind: 'number'
      },
      {
        key: 'to_start_of_day',
        label: '截断到天',
        tpl: 'toStartOfDay(%s)',
        kinds: ['date'],
        outKind: 'date'
      },
      {
        key: 'to_start_of_month',
        label: '截断到月',
        tpl: 'toStartOfMonth(%s)',
        kinds: ['date'],
        outKind: 'date'
      },
      {
        key: 'to_start_of_week',
        label: '截断到周',
        tpl: 'toStartOfWeek(%s)',
        kinds: ['date'],
        outKind: 'date'
      },
      {
        key: 'to_yyyy_mm',
        label: '年月份(数值)',
        tpl: 'toYYYYMM(%s)',
        desc: '202401,适合分组',
        kinds: ['date'],
        outKind: 'number'
      },
      {
        key: 'to_yyyy_mm_dd',
        label: '年月日(数值)',
        tpl: 'toYYYYMMDD(%s)',
        kinds: ['date'],
        outKind: 'number'
      },
      {
        key: 'format_dt',
        label: '格式化',
        tpl: 'formatDateTime(%s, %s)',
        desc: '%Y年 %m月 %d日 %H时 %i分 %s秒',
        kinds: ['date'],
        params: [{ label: '格式', kind: 'string', def: '%Y-%m-%d' }]
      }
    ]
  },
  // 数值:只对 kinds: ['number'] 显示,所以文本列要先转类型。
  // 组内按"单参 / 带参"排列(单参的每个写在 1 行,带参的写成多行),便于扫读。
  {
    key: 'math',
    label: '数值',
    accent: '#c084fc',
    ops: [
      // —— 单参 ——
      { key: 'abs', label: '绝对值', tpl: 'abs(%s)', kinds: ['number'] },
      { key: 'negate', label: '取负', tpl: 'negate(%s)', kinds: ['number'] },
      {
        key: 'sign',
        label: '符号(-1/0/1)',
        tpl: 'sign(%s)',
        kinds: ['number']
      },
      { key: 'floor', label: '向下取整', tpl: 'floor(%s)', kinds: ['number'] },
      { key: 'ceil', label: '向上取整', tpl: 'ceil(%s)', kinds: ['number'] },
      { key: 'round', label: '四舍五入', tpl: 'round(%s)', kinds: ['number'] },
      { key: 'trunc', label: '截断小数', tpl: 'trunc(%s)', kinds: ['number'] },
      { key: 'sqrt', label: '平方根', tpl: 'sqrt(%s)', kinds: ['number'] },
      { key: 'cbrt', label: '立方根', tpl: 'cbrt(%s)', kinds: ['number'] },
      { key: 'exp', label: 'e^x', tpl: 'exp(%s)', kinds: ['number'] },
      { key: 'ln', label: '自然对数', tpl: 'ln(%s)', kinds: ['number'] },
      { key: 'log2', label: 'log2', tpl: 'log2(%s)', kinds: ['number'] },
      { key: 'log10', label: 'log10', tpl: 'log10(%s)', kinds: ['number'] },
      // —— 带参 ——
      {
        key: 'round_to',
        label: '保留 N 位小数',
        tpl: 'round(%s, %s)',
        kinds: ['number'],
        params: [{ label: '小数位', kind: 'number', def: '2' }]
      },
      {
        key: 'truncate_to',
        label: '截断到 N 位',
        tpl: 'truncate(%s, %s)',
        desc: '不四舍五入,直接截断',
        kinds: ['number'],
        params: [{ label: '小数位', kind: 'number', def: '2' }]
      },
      {
        key: 'pow',
        label: '乘方 x^n',
        tpl: 'pow(%s, %s)',
        desc: '⚠️ 该引擎的 ^ 不是乘方,要用 pow(a,b)',
        kinds: ['number'],
        params: [{ label: '指数', kind: 'number', def: '2' }]
      },
      {
        key: 'int_div',
        label: '整除 x÷n',
        tpl: 'intDiv(%s, %s)',
        kinds: ['number'],
        params: [{ label: '除数', kind: 'number', def: '1000' }]
      },
      {
        key: 'modulo_op',
        label: '取余 x%n',
        tpl: 'modulo(%s, %s)',
        kinds: ['number'],
        params: [{ label: '除数', kind: 'number', def: '10' }]
      },
      {
        key: 'greatest',
        label: '取较大值',
        tpl: 'greatest(%s, %s)',
        desc: '与给定值比较,取大的',
        kinds: ['number'],
        params: [{ label: '比较值', kind: 'number', def: '0' }]
      },
      {
        key: 'least',
        label: '取较小值',
        tpl: 'least(%s, %s)',
        desc: '与给定值比较,取小的',
        kinds: ['number'],
        params: [{ label: '比较值', kind: 'number', def: '0' }]
      },
      {
        key: 'bit_and',
        label: '按位与 x&n',
        tpl: 'bitAnd(%s, %s)',
        kinds: ['number'],
        params: [{ label: '掩码', kind: 'number', def: '255' }]
      },
      {
        key: 'bit_or',
        label: '按位或 x|n',
        tpl: 'bitOr(%s, %s)',
        kinds: ['number'],
        params: [{ label: '掩码', kind: 'number', def: '1' }]
      },
      {
        key: 'bit_xor',
        label: '按位异或 x^n',
        tpl: 'bitXor(%s, %s)',
        kinds: ['number'],
        params: [{ label: '掩码', kind: 'number', def: '1' }]
      },
      {
        key: 'bit_shift_left',
        label: '左移 n 位',
        tpl: 'bitShiftLeft(%s, %s)',
        kinds: ['number'],
        params: [{ label: '位数', kind: 'number', def: '1' }]
      },
      {
        key: 'bit_shift_right',
        label: '右移 n 位',
        tpl: 'bitShiftRight(%s, %s)',
        kinds: ['number'],
        params: [{ label: '位数', kind: 'number', def: '1' }]
      }
    ]
  },
  // 编码 / 解码:base64 主要用来把二进制产物(加密结果等)塞进字符串列。
  // ⚠️ 解码优先用 tryBase64Decode:严格版遇到非法输入会直接中断查询,而脏数据在贴源层很常见。
  {
    key: 'base64',
    label: '编码 / 解码',
    accent: '#22d3ee',
    ops: [
      {
        key: 'base64_encode',
        label: 'Base64 编码',
        tpl: 'base64Encode(%s)',
        outKind: 'string',
        desc: 'base64Encode(x)'
      },
      {
        key: 'base64_decode_safe',
        label: 'Base64 解码(容错)',
        tpl: 'tryBase64Decode(%s)',
        outKind: 'string',
        desc: '非法输入返回空串,不报错 —— 推荐'
      },
      {
        key: 'base64_decode',
        label: 'Base64 解码(严格)',
        tpl: 'base64Decode(%s)',
        outKind: 'string',
        desc: '非法输入会报错中断查询'
      },
      {
        key: 'base64url_encode',
        label: 'Base64URL 编码',
        tpl: 'base64URLEncode(%s)',
        outKind: 'string',
        desc: 'URL 安全字符集:- _ 代替 + /,无 = 填充'
      },
      {
        key: 'base64url_decode',
        label: 'Base64URL 解码',
        tpl: 'tryBase64URLDecode(%s)',
        outKind: 'string'
      },
      {
        key: 'aes256_dec',
        label: 'AES-256 解密后关联',
        tpl: "decrypt('aes-256-ecb', base64Decode(%s), %s)",
        kinds: ['string'],
        outKind: 'string',
        desc: '用于 Base64 编码的 AES-256-ECB 密文；可放在连接键处理链中。仅用测试密钥演示。',
        params: [{ label: '32 字节测试密钥', kind: 'string', def: '', required: true }]
      },
      {
        key: 'aes256_cbc_dec',
        label: 'AES-256-CBC 解密后关联',
        tpl: "decrypt('aes-256-cbc', base64Decode(%s), %s, %s)",
        kinds: ['string', 'string'],
        outKind: 'string',
        desc: '用于 Base64 编码的 AES-256-CBC 密文；依次填写测试密钥和 IV。',
        params: [
          { label: '32 字节测试密钥', kind: 'string', def: '', required: true },
          { label: '16 字节 IV', kind: 'string', def: '', required: true }
        ]
      }
    ]
  },
  // 空值:Nullable 相关兜底。assumeNotNull 不改数据,只是声明"这列实际不会为空"以去掉类型上的 Nullable
  // (后续算子因此少一次判空);真正要填默认值用 ifNull / coalesce。
  {
    key: 'null',
    label: '空值',
    accent: '#a5b4fc',
    ops: [
      {
        key: 'assume_nn',
        label: '去 Nullable',
        tpl: 'assumeNotNull(%s)',
        desc: 'assumeNotNull(x)'
      },
      {
        key: 'if_null',
        label: '空值兜底',
        tpl: 'ifNull(%s, %s)',
        desc: '为空时给默认值',
        params: [{ label: '默认值', kind: 'string', def: '' }]
      },
      {
        key: 'coalesce',
        label: '取第一个非空',
        tpl: 'coalesce(%s, %s)',
        params: [{ label: '候选值', kind: 'string', def: '' }]
      }
    ]
  }
]

/**
 * 程序算子:由 el-to-ck(Go)计算而非 ClickHouse。
 *
 * ⚠️ 为什么单独一组:CK 处理链(steps)先执行、程序算子(prog)后执行。
 *    凡是【CK 拿不到的值】(解密产物、哈希产物)都必须在这一侧继续加工 ——
 *    例如 3DES 解出来的明文要 trim、FF1 解出来的手机号要脱敏展示。
 *
 * ⚠️ 后果:这些算子不生成任何 SQL,结果列【只能展示/导出】,
 *    不能用于过滤 / 分组 / 排序(后端会校验并拒绝)。
 *
 * 按用途分为四组,与后端 prog_ops.go 的注册表一一对应。
 *
 * ⚠️ 新增一个程序算子时:
 * - key 必须与后端注册名一致,且【显式写 prog: true】—— 漏了会被当成 CK 算子,生成的 SQL 直接报函数不存在
 * - tpl 里的大写函数名(如 MASK(%s))只是给人看的样式,真正决定行为的是 key
 * - desc 会显示在算子卡片上,建议带上"输入 → 输出"的例子
 */
export const PROG_OP_GROUPS: PipelineOpGroup[] = [
  // ═══════════ ① 加密 / 哈希 ═══════════
  // 国密/国际算法与保型加密。用本组算子要成对:加密与解密必须用同一套参数
  // (3DES 的密钥/编码、FF1 的密钥/tweak/字母表),参数对不上会直接解密失败或解出乱码。
  {
    key: 'prog-crypto',
    label: '加密 / 哈希',
    accent: '#f59e0b',
    ops: [
      {
        key: 'sm3',
        label: 'SM3 哈希',
        tpl: 'SM3(%s)',
        prog: true,
        outKind: 'string',
        desc: '国密 SM3 → 64 位大写十六进制。数据库没有此函数,由本程序计算。'
      },
      {
        key: 'des3_enc',
        label: '3DES 加密',
        tpl: 'DES3_ENC(%s)',
        prog: true,
        outKind: 'string',
        desc:
          '3DES-ECB / PKCS7 加密。与 Java DESede/ECB/PKCS5Padding 兼容。' +
          '「输出编码」决定密文长什么样:base64(默认,可直接存字符串列)或 hex(全十六进制,便于肉眼比对)。',
        params: [
          { label: '密钥', kind: 'string', def: '', required: true },
          {
            label: '输出编码',
            kind: 'string',
            def: 'base64',
            options: ENC_FORMAT_OPTIONS
          }
        ]
      },
      {
        key: 'des3_dec',
        label: '3DES 解密',
        tpl: 'DES3_DEC(%s)',
        prog: true,
        outKind: 'string',
        desc: '3DES-ECB / PKCS7 解密。' + '「输入编码」必须与加密时的输出编码一致 —— 选错了会报 base64/hex 解码失败。',
        params: [
          { label: '密钥', kind: 'string', def: '', required: true },
          {
            label: '输入编码',
            kind: 'string',
            def: 'base64',
            options: ENC_FORMAT_OPTIONS
          }
        ]
      },
      {
        key: 'ff1_enc',
        label: 'FF1 保型加密',
        tpl: 'FF1_ENC(%s)',
        prog: true,
        outKind: 'string',
        desc:
          'FF1 (NIST SP 800-38G) 保型加密 —— 密文与明文【长度相同、字符集相同】,' +
          '11 位手机号加密后仍是 11 位数字,可直接落库不改列结构。确定性加密:同 key/tweak/输入 → 同密文,可用于等值关联。',
        params: [
          { label: '密钥', kind: 'string', def: '', required: true },
          { label: 'tweak', kind: 'string', def: '' },
          { label: '字母表', kind: 'string', def: '0123456789' }
        ]
      },
      {
        key: 'ff1_dec',
        label: 'FF1 保型解密',
        tpl: 'FF1_DEC(%s)',
        prog: true,
        outKind: 'string',
        desc: 'FF1 保型解密。密钥 / tweak / 字母表必须与加密时完全一致。',
        params: [
          { label: '密钥', kind: 'string', def: '', required: true },
          { label: 'tweak', kind: 'string', def: '' },
          { label: '字母表', kind: 'string', def: '0123456789' }
        ]
      }
    ]
  },

  // ═══════════ ② 字符串 ═══════════
  // 解密产物、哈希产物的二次加工(清洗、截取、脱敏)。
  // 与 CK 侧同名算子不同的一点:这里的 SUBSTR / MASK 按【字符】而非字节处理,中文不会被截半。
  {
    key: 'prog-str',
    label: '字符串',
    accent: '#60a5fa',
    ops: [
      {
        key: 'prog_mask',
        label: '掩码脱敏',
        tpl: 'MASK(%s)',
        prog: true,
        outKind: 'string',
        desc:
          '保留前 N 后 M,中间打码:13800001234 → 138****1234。' +
          '「掩码长度」填 0 = 按实际被遮挡的字符数;填正数 = 固定长度(掩码后总长与原长无关)。' +
          '按【字符】处理,中文不会截断。',
        params: [
          { label: '保留前', kind: 'number', def: '3' },
          { label: '保留后', kind: 'number', def: '4' },
          { label: '掩码字符', kind: 'string', def: '*' },
          { label: '掩码长度', kind: 'number', def: '0' }
        ]
      },
      {
        key: 'prog_lower',
        label: '转小写',
        tpl: 'LOWER(%s)',
        prog: true,
        outKind: 'string',
        desc: '转小写。常用于把 SM3 的十六进制输出统一成小写。'
      },
      {
        key: 'prog_upper',
        label: '转大写',
        tpl: 'UPPER(%s)',
        prog: true,
        outKind: 'string',
        desc: '转大写。'
      },
      {
        key: 'prog_trim',
        label: '去首尾空白',
        tpl: 'TRIM(%s)',
        prog: true,
        outKind: 'string',
        desc: '去掉首尾空白字符。解密产物常带填充或换行,先用它清一下。'
      },
      {
        key: 'prog_strip',
        label: '去所有空白',
        tpl: 'STRIP(%s)',
        prog: true,
        outKind: 'string',
        desc: '去掉【所有】空白(含中间的空格 / Tab / 换行)。比「去首尾空白」更强。'
      },
      {
        key: 'prog_trim_chars',
        label: '去首尾指定字符',
        tpl: 'TRIM_CHARS(%s)',
        prog: true,
        outKind: 'string',
        desc: '去掉首尾出现在字符集合里的字符。如填 "" 可去掉两端引号,填 "=" 可去 base64 填充。',
        params: [{ label: '字符集合', kind: 'string', def: '', required: true }]
      },
      {
        key: 'prog_substr',
        label: '按字符截取',
        tpl: 'SUBSTR(%s)',
        prog: true,
        outKind: 'string',
        desc: '按【字符】截取(不是字节),起始从 1 开始。长度留空 = 到末尾。常配合 SM3:「起始 1 长度 16」取哈希前 16 位。',
        params: [
          { label: '起始', kind: 'number', def: '1' },
          { label: '长度', kind: 'number', def: '', required: false }
        ]
      },
      {
        key: 'prog_replace',
        label: '文本替换',
        tpl: 'REPLACE(%s)',
        prog: true,
        outKind: 'string',
        desc: '字面文本全量替换。替换为留空 = 删除。',
        params: [
          { label: '查找', kind: 'string', def: '', required: true },
          { label: '替换为', kind: 'string', def: '' }
        ]
      },
      {
        key: 'prog_regex_replace',
        label: '正则替换',
        tpl: 'REGEX_REPLACE(%s)',
        prog: true,
        outKind: 'string',
        desc:
          '按正则替换,支持 $1 反向引用(Go RE2 语法)。' +
          '例:正则 (\\d{3})\\d{4}(\\d{4}) 替换为 $1****$2 可对手机号脱敏；' +
          '提取【】内文字用正则 【(.+?)】、替换为 $1。',
        params: [
          { label: '正则', kind: 'string', def: '', required: true },
          { label: '替换为', kind: 'string', def: '' }
        ]
      },
      {
        key: 'prog_pad_left',
        label: '左侧补位',
        tpl: 'PAD_LEFT(%s)',
        prog: true,
        outKind: 'string',
        desc: '左侧补齐到指定长度(已超长则不截断)。',
        params: [
          { label: '目标长度', kind: 'number', def: '' },
          { label: '填充字符', kind: 'string', def: '0' }
        ]
      },
      {
        key: 'prog_pad_right',
        label: '右侧补位',
        tpl: 'PAD_RIGHT(%s)',
        prog: true,
        outKind: 'string',
        desc: '右侧补齐到指定长度(已超长则不截断)。',
        params: [
          { label: '目标长度', kind: 'number', def: '' },
          { label: '填充字符', kind: 'string', def: '0' }
        ]
      },
      {
        key: 'prog_concat',
        label: '加前后缀',
        tpl: 'CONCAT(%s)',
        prog: true,
        outKind: 'string',
        desc: '在值的前后各拼一段固定文本(任一段留空则不拼)。',
        params: [
          { label: '前缀', kind: 'string', def: '' },
          { label: '后缀', kind: 'string', def: '' }
        ]
      }
    ]
  },

  // ═══════════ ③ 时间 ═══════════
  // 日期解析/格式化/加减/换算时间戳。本组大量使用「输入格式」参数:
  // 选 auto 能吃掉常见写法,但格式不统一的数据源建议显式指定,免得"有的行解析出来有的没有"。
  // 另外本组入参都是【字符串形式】的时间(按「输入格式」解析),不是 CK 的日期类型 ——
  // 这也是为什么格式必须先选对:格式对不上会直接解析失败。
  {
    key: 'prog-date',
    label: '时间',
    accent: '#fb923c',
    ops: [
      {
        key: 'prog_date_format',
        label: '日期格式转换',
        tpl: 'DATE_FORMAT(%s)',
        prog: true,
        outKind: 'date',
        desc: '按输入格式解析,再按输出格式输出。输入格式选 auto 可自动识别常见写法。',
        params: [
          {
            label: '输入格式',
            kind: 'string',
            def: 'auto',
            options: DATE_INPUT_FORMAT_OPTIONS
          },
          {
            label: '输出格式',
            kind: 'string',
            def: 'yyyy-MM-dd',
            options: DATE_OUTPUT_FORMAT_OPTIONS
          }
        ]
      },
      {
        key: 'prog_date_add',
        label: '日期加减天',
        tpl: 'DATE_ADD(%s)',
        prog: true,
        outKind: 'date',
        desc: '加减天数(可填负数)。跨月 / 跨年由日历自动处理。',
        params: [
          {
            label: '输入格式',
            kind: 'string',
            def: 'auto',
            options: DATE_INPUT_FORMAT_OPTIONS
          },
          { label: '天数', kind: 'number', def: '0' },
          {
            label: '输出格式',
            kind: 'string',
            def: 'yyyy-MM-dd',
            options: DATE_OUTPUT_FORMAT_OPTIONS
          }
        ]
      },
      {
        key: 'prog_date_diff',
        label: '相差天数',
        tpl: 'DATE_DIFF(%s)',
        prog: true,
        outKind: 'number',
        desc:
          '与参照日期相差的自然天数(本值 - 参照值)。' +
          '参照日期填 today,或按同样的输入格式写一个具体日期(如 2024-01-01)。',
        params: [
          {
            label: '输入格式',
            kind: 'string',
            def: 'auto',
            options: DATE_INPUT_FORMAT_OPTIONS
          },
          { label: '参照日期', kind: 'string', def: 'today' }
        ]
      },
      {
        key: 'prog_to_unix',
        label: '转时间戳',
        tpl: 'TO_UNIX(%s)',
        prog: true,
        outKind: 'number',
        desc: '日期 → Unix 时间戳。',
        params: [
          {
            label: '输入格式',
            kind: 'string',
            def: 'auto',
            options: DATE_INPUT_FORMAT_OPTIONS
          },
          {
            label: '单位',
            kind: 'string',
            def: 's',
            options: UNIX_UNIT_OPTIONS
          }
        ]
      },
      {
        key: 'prog_from_unix',
        label: '时间戳转日期',
        tpl: 'FROM_UNIX(%s)',
        prog: true,
        outKind: 'date',
        desc: 'Unix 时间戳 → 日期。单位要与时间戳的实际精度一致。',
        params: [
          {
            label: '单位',
            kind: 'string',
            def: 's',
            options: UNIX_UNIT_OPTIONS
          },
          {
            label: '输出格式',
            kind: 'string',
            def: 'yyyy-MM-dd',
            options: DATE_OUTPUT_FORMAT_OPTIONS
          }
        ]
      }
    ]
  },

  // ═══════════ ④ 数值 ═══════════
  // 数值处理。注意这些算子的结果是"程序的数值",不再有 CK 的列类型概念,
  // outKind 只用于界面上的类型推导(决定后面还能接哪些算子)。
  {
    key: 'prog-num',
    label: '数值',
    accent: '#34d399',
    ops: [
      {
        key: 'prog_round',
        label: '四舍五入',
        tpl: 'ROUND(%s)',
        prog: true,
        outKind: 'number',
        desc: '四舍五入到指定小数位(0~12)。输出不带多余的 0(123.00 → 123)。',
        params: [{ label: '小数位', kind: 'number', def: '0' }]
      },
      {
        key: 'prog_trunc',
        label: '截断小数',
        tpl: 'TRUNC(%s)',
        prog: true,
        outKind: 'number',
        desc: '直接截断(不四舍五入)到指定小数位。',
        params: [{ label: '小数位', kind: 'number', def: '0' }]
      },
      {
        key: 'prog_floor',
        label: '向下取整',
        tpl: 'FLOOR(%s)',
        prog: true,
        outKind: 'number',
        desc: '向下取整(3.9 → 3,-3.1 → -4)。'
      },
      {
        key: 'prog_ceil',
        label: '向上取整',
        tpl: 'CEIL(%s)',
        prog: true,
        outKind: 'number',
        desc: '向上取整(3.1 → 4,-3.9 → -3)。'
      },
      {
        key: 'prog_abs',
        label: '绝对值',
        tpl: 'ABS(%s)',
        prog: true,
        outKind: 'number',
        desc: '取绝对值。'
      },
      {
        key: 'prog_thousands',
        label: '千分位',
        tpl: 'THOUSANDS(%s)',
        prog: true,
        outKind: 'string',
        desc: '千分位格式化(1234567.5 → 1,234,567.5)。'
      }
    ]
  }
]

/** 聚合视图(供 PROG_OP_MAP 建立扁平索引) */
export const PROG_OP_GROUP: PipelineOpGroup = {
  key: 'prog',
  label: '程序算子',
  accent: '#f59e0b',
  ops: PROG_OP_GROUPS.flatMap((g) => g.ops)
}

/**
 * 程序算子扁平索引(把 PROG_OP_GROUPS 摊平)。
 *
 * 目前没有调用方:界面上区分"是不是程序算子"看的是这一步落在 steps 还是 prog 数组里
 * (见 FieldPipelineEditor 的 listOf),并不查这张表。留着是给按 fn 名判断的场景用(见 isProgOp)。
 */
export const PROG_OP_MAP: Record<string, PipelineOp> = PROG_OP_GROUP.ops.reduce(
  (acc, op) => {
    acc[op.key] = op
    return acc
  },
  {} as Record<string, PipelineOp>
)

/**
 * 是否是程序算子(按 key 查表,而不是看 op.prog 标志)。
 *
 * 目前无调用方;保留它是因为"这一步会不会生成 SQL"直接决定该列能否用于过滤/分组/排序,
 * 将来做这类校验时按 fn 名一查即可,不必关心它挂在哪个数组里。
 */
export function isProgOp(fn: string): boolean {
  return !!PROG_OP_MAP[fn]
}

/**
 * 扁平索引:算子 key → 定义(用于查 label / params / 生成表达式)。
 *
 * 下面紧接着会把程序算子也并进来,于是【所有算子共用一套查表逻辑】——
 * 渲染表达式、算短名、判断参数是否填全都不必区分它到底是 CK 算子还是程序算子。
 */
export const PIPELINE_OP_MAP: Record<string, PipelineOp> = PIPELINE_OP_GROUPS.reduce(
  (acc, g) => {
    for (const op of g.ops) acc[op.key] = op
    return acc
  },
  {} as Record<string, PipelineOp>
)

// 程序算子也放进索引(便于统一查 label / params),但它们不在 PIPELINE_OP_GROUPS 里
for (const op of PROG_OP_GROUP.ops) PIPELINE_OP_MAP[op.key] = op

/**
 * 按值类型筛选可用的处理链算子(算子没写 kinds 视为通用)。
 *
 * 为什么非要按类型过滤:给 String 列摆出 abs() 只会让用户配出一条必然报错的链,
 * 过滤后剩下的都是"点了就大概率能用"的算子。
 */
export function opsForKind(kind: ValueKind): PipelineOpGroup[] {
  return PIPELINE_OP_GROUPS.map((g) => ({
    ...g,
    ops: g.ops.filter((op) => !op.kinds || op.kinds.includes(kind))
  })).filter((g) => g.ops.length > 0)
}

/**
 * 生成算子的默认参数列表(与后端 QueryPipelineArg 结构一致)。
 *
 * 给新建的算子预填默认值,这样刚加进来就能渲染出完整表达式;
 * def 为空的参数(正则、密钥这类没有通用默认值的)会留空,由 stepArgsFilled 在提交前拦住。
 */
export function defaultArgs(op: PipelineOp): { kind: ParamKind; value: string }[] {
  return (op.params ?? []).map((p) => ({ kind: p.kind, value: p.def ?? '' }))
}

/**
 * 渲染处理链表达式预览 —— 与后端 buildPipelineExpr 同构。
 * 第一个 %s 填入上一级结果,其余按 params 顺序填入参数值。
 */
export function renderStepsExpr(
  col: string,
  steps: { fn: string; args?: { kind: ParamKind; value: string }[] }[]
): string {
  let expr = `\`${col}\``
  for (const st of steps) {
    const op = PIPELINE_OP_MAP[st.fn]
    // ⚠️ 未收录的算子不能兜底成 '%s'(恒等)—— 那会让这一步在预览里【凭空消失】,
    //    而后端是按 fn 名生成 SQL 的,两边对不上。
    //    老配置(向导给的 md5/sha256)会走到这里,渲染成 `md5(…)` 并标注未知。
    const tpl = op?.tpl ?? `${st.fn}(%s)`
    const parts = tpl.split('%s')
    // parts[0] + 输入 + parts[1] + 参数1 + parts[2] + ...
    let out = parts[0]
    for (let i = 1; i < parts.length; i++) {
      out += (i === 1 ? expr : renderArg(st.args?.[i - 2])) + parts[i]
    }
    expr = out
  }
  return expr
}

function renderArg(a?: { kind: ParamKind; value: string }): string {
  // 参数没填时渲染成 '?' 而不是空串:预览里出现问号,用户一眼能看出"这一步还没配完"
  if (!a) return '?'
  if (a.kind === 'number' || a.kind === 'ident') return a.value || '?'
  return `'${a.value}'`
}

/** 算子的短名(用于 chip 展示) */
export function opShortName(fn: string): string {
  const op = PIPELINE_OP_MAP[fn]
  if (!op) return fn
  const m = /^([A-Za-z_][A-Za-z0-9_]*)\(/.exec(op.tpl)
  return m ? m[1] : fn
}

/**
 * 算子的必填参数是否都填了。
 *
 * ⚠️ 与后端 buildPipelineExpr / ValidateProgOps 对应:数字/标识符参数为空时后端会直接报错
 *    (「数字参数不能为空」),所以前端必须能在发出请求前就拦住。
 *    string 类型参数允许为空(空串是合法的字面量),不算未填。
 *
 * 同时覆盖处理链(steps)与程序算子(prog)—— 二者共用 PIPELINE_OP_MAP。
 */
export function stepArgsFilled(st: { fn: string; args?: { value: string }[] }): boolean {
  const params = PIPELINE_OP_MAP[st.fn]?.params ?? []
  return params.every((p, i) => {
    const v = st.args?.[i]?.value ?? ''
    // 显式标注优先;否则按 kind 推断(string 可空,number/ident 必填)
    const required = p.required ?? p.kind !== 'string'
    return required ? v !== '' : true
  })
}

/**
 * 聚合选项(agg key 必须与后端 allowedAggs 一致;空串 = 明细,即不聚合)。
 *
 * 每个字段规则上都挂一个 agg key:空串 = 直接取列,其余 = 对该列套聚合函数。
 * isAgg 决定该字段进不进 GROUP BY、以及"用了聚合还能不能选明细列"这类校验。
 *
 * ⚠️ 空串在这里是【合法取值】而不是"没填":它代表明细列,不要当成缺失来兜底成 countAll。
 * ⚠️ countAll 是特殊值(不接列,生成 count());count 则针对具体列,只数非空值。
 */
export interface AggOption {
  /** 传给后端的聚合名;'' = 明细 */
  key: string
  label: string
  /** 是否真聚合(影响 GROUP BY 与明细列的互斥校验) */
  isAgg: boolean
  /** 生成别名用的后缀(见 makeAlias):同名列聚合后自动区分成 mdn_cnt / mdn_uniq */
  suffix?: string
}

// 顺序 = 下拉里的显示顺序:明细在最前(最常用),其余按「计数 → 求和/均值 → 极值/中位数 → 聚合成数组 → 任意值」排
export const AGG_OPTIONS: AggOption[] = [
  { key: '', label: '明细(不聚合)', isAgg: false },
  { key: 'countAll', label: '计数(行数)', isAgg: true, suffix: 'cnt' },
  { key: 'count', label: '计数(非空)', isAgg: true, suffix: 'cnt' },
  { key: 'uniqExact', label: '去重计数', isAgg: true, suffix: 'uniq' },
  { key: 'sum', label: '求和', isAgg: true, suffix: 'sum' },
  { key: 'avg', label: '平均值', isAgg: true, suffix: 'avg' },
  { key: 'max', label: '最大值', isAgg: true, suffix: 'max' },
  { key: 'min', label: '最小值', isAgg: true, suffix: 'min' },
  { key: 'median', label: '中位数', isAgg: true, suffix: 'med' },
  { key: 'groupArray', label: '聚合成数组', isAgg: true, suffix: 'arr' },
  { key: 'any', label: '任意值', isAgg: true, suffix: 'any' },
  { key: 'anyLast', label: '最后一个值', isAgg: true, suffix: 'last' }
]

/**
 * 聚合的扁平索引(key → AggOption)。
 *
 * 目前没有调用方(界面直接用 AGG_OPTIONS 渲染下拉),留着给"按 key 查后缀/标签"的场景用;
 * 也正因为是 Record 索引,key 重复会静默覆盖,新增聚合时注意别与已有的撞名。
 */
export const AGG_MAP: Record<string, AggOption> = AGG_OPTIONS.reduce(
  (acc, o) => {
    acc[o.key] = o
    return acc
  },
  {} as Record<string, AggOption>
)

/**
 * 过滤操作符,按字段的值类型分成几组 —— 界面按字段的类型取对应那一组渲染操作符下拉。
 *
 * ⚠️ key 必须与后端 whereOps 一致:它既是操作符标识,后端也直接用 key 去拼 SQL。
 *
 * arity 决定值输入的形态(前端据此渲染几个输入框):
 *   'none' 无需值(is_null / is_not_null)
 *   'one'  一个值
 *   'two'  两个值(between 的下界与上界)
 *   'list' 多值列表(in / not_in)
 */
export interface FilterOp {
  key: string
  label: string
  arity: 'none' | 'one' | 'two' | 'list'
}

const FIELD_DISTINCT: FilterOp = { key: 'distinct', label: '字段去重', arity: 'none' }

export const FILTER_OPS_BY_KIND: Record<ValueKind, FilterOp[]> = {
  // 数值:全套比较 + 区间 + 判空
  number: [
    FIELD_DISTINCT,
    { key: '=', label: '等于', arity: 'one' },
    { key: '!=', label: '不等于', arity: 'one' },
    { key: '>', label: '大于', arity: 'one' },
    { key: '>=', label: '大于等于', arity: 'one' },
    { key: '<', label: '小于', arity: 'one' },
    { key: '<=', label: '小于等于', arity: 'one' },
    { key: 'between', label: '介于', arity: 'two' },
    { key: 'is_null', label: '为空', arity: 'none' },
    { key: 'is_not_null', label: '不为空', arity: 'none' }
  ],
  string: [
    FIELD_DISTINCT,
    { key: '=', label: '等于', arity: 'one' },
    { key: '!=', label: '不等于', arity: 'one' },
    { key: 'like', label: '包含', arity: 'one' },
    { key: 'not_like', label: '不包含', arity: 'one' },
    // 大于/小于对文本列也有意义(如 手机号段、编号),后端 whereOps 本来就支持。
    // ⚠️ ClickHouse 对两个字符串是按【字典序】比较:'9' > '10' 成立。
    //    纯数字语义请用数值列,或先给字段加处理链转数值 —— UI 会就此给出提醒(见 STRING_CMP_NOTE)。
    { key: '>', label: '大于(按文本)', arity: 'one' },
    { key: '>=', label: '大于等于(按文本)', arity: 'one' },
    { key: '<', label: '小于(按文本)', arity: 'one' },
    { key: '<=', label: '小于等于(按文本)', arity: 'one' },
    { key: 'between', label: '介于(按文本)', arity: 'two' },
    { key: 'in', label: '在列表中', arity: 'list' },
    { key: 'not_in', label: '不在列表中', arity: 'list' },
    { key: 'is_null', label: '为空', arity: 'none' },
    { key: 'is_not_null', label: '不为空', arity: 'none' }
  ],
  // 日期:等值 + 区间两端。「晚于等于(>=)」配「早于(<)」正好拼成左闭右开区间;
  // 这里本来就不提供 between / in,所以用户要写区间只有这一种拼法
  date: [
    FIELD_DISTINCT,
    { key: '=', label: '等于', arity: 'one' },
    { key: '!=', label: '不等于', arity: 'one' },
    { key: '>=', label: '晚于等于', arity: 'one' },
    { key: '<', label: '早于', arity: 'one' },
    { key: 'is_null', label: '为空', arity: 'none' },
    { key: 'is_not_null', label: '不为空', arity: 'none' }
  ],
  // 布尔:等值比较就够 —— 没有大小概念,between / in 这些形式对布尔也不适用
  bool: [
    FIELD_DISTINCT,
    { key: '=', label: '等于', arity: 'one' },
    { key: '!=', label: '不等于', arity: 'one' }
  ],
  // 其他(Array / Tuple / Map 等未识别的类型):只给最保守的等值与判空,
  // 免得生成一条 CK 根本不支持的操作符、点运行才报错
  other: [
    FIELD_DISTINCT,
    { key: '=', label: '等于', arity: 'one' },
    { key: '!=', label: '不等于', arity: 'one' },
    { key: 'is_null', label: '为空', arity: 'none' },
    { key: 'is_not_null', label: '不为空', arity: 'none' }
  ]
}

/**
 * 时间预设(key 必须与后端 renderTimePreset 一致)。
 *
 * 前 5 个是固定区间:只把 key 传给后端,由后端按【服务器当前时间】渲染成条件 ——
 * 这样"最近 7 天"的口径由后端定义,前端各处的算法不会各算各的。
 * 'custom' 是唯一需要前端补值的:value 是开始(含)、value2 是结束(不含),只在这条路径下才出现两个日期输入框。
 */
export interface TimePreset {
  key: string
  label: string
}

export const TIME_PRESETS: TimePreset[] = [
  { key: 'today', label: '今天' },
  { key: 'thisWeek', label: '本周' },
  { key: 'thisMonth', label: '本月' },
  { key: 'last7d', label: '最近 7 天' },
  { key: 'last30d', label: '最近 30 天' },
  { key: 'custom', label: '自定义区间' }
]

// 类型判定的正则放在模块级:chTypeToKind 会被每个字段规则反复调用,写在函数体内等于每次调用都重建正则
const NUM_RE = /^(U?Int|Float|Decimal)/
const DATE_RE = /^Date(Time)?/
const STR_RE = /^(String|FixedString|Enum)/
const BOOL_RE = /^(Bool|Boolean)/

/** 把 ClickHouse 列类型映射成值类型(Nullable/LowCardinality 会被拆掉) */
export function chTypeToKind(type: string): ValueKind {
  let t = (type || '').trim()
  // ⚠️ 循环剥包装 —— 只剥一层时 LowCardinality(Nullable(String)) 会落到 'other',
  //    后续用不了文本算子,过滤操作符也全错。
  for (;;) {
    const wrapper = /^(?:Nullable|LowCardinality|Array|Tuple)\((.*)\)$/.exec(t)
    if (!wrapper) break
    t = wrapper[1].trim()
  }
  if (BOOL_RE.test(t)) return 'bool'
  if (NUM_RE.test(t)) return 'number'
  if (DATE_RE.test(t)) return 'date'
  if (STR_RE.test(t)) return 'string'
  return 'other'
}

/**
 * 文本列做「大于/小于」时的提醒。
 *
 * ClickHouse 两个 String 比较是字典序，对存数字的文本列(手机号/编号)可能与直觉不符，
 * 所以操作符名带「(按文本)」，鼠标悬停再说明一次。
 */
export const STRING_CMP_NOTE =
  '文本列按字典序比较(逐字符比),如 "9" > "10" 成立。要按数值大小比较,请用数值列,或先给该字段加处理链转为数值。'

/** 该操作符是否是「比较」类(用于给文本列加提醒) */
export function isCmpOp(op: string): boolean {
  return ['>', '>=', '<', '<=', 'between'].includes(op)
}

/** 值类型的中文名(处理链编辑器上显示「当前类型 → 处理后类型」时用) */
export const KIND_LABEL: Record<ValueKind, string> = {
  number: '数值',
  string: '文本',
  date: '日期',
  bool: '布尔',
  other: '其他'
}

/**
 * 生成一个合法的 ASCII 别名(符合后端 sqlIdentRe)。
 *
 * 源列名可能是中文或带点号(如 a.mdn),直接用会被后端拒,所以先把非法字符换成下划线;
 * 重名时追加 _2 / _3 后缀(见 useVisualQuery 里跨表收集别名的地方)。
 *
 * ⚠️ taken 只读不写:本函数不会把生成结果塞回集合,调用方必须自己在循环里累积,
 *    否则同一批里会出现两个一样的别名(这个坑在 useVisualQuery 里踩过)。
 */
export function makeAlias(col: string, agg: string, taken: Set<string>): string {
  const base = (col || 'col').replace(/[^A-Za-z0-9_]/g, '_').replace(/^[0-9]/, '_$&') || 'col'
  const suffix = agg ? AGG_MAP[agg]?.suffix || '' : ''
  const name = suffix ? `${base}_${suffix}` : base
  if (!taken.has(name)) return name
  let i = 2
  while (taken.has(`${name}_${i}`)) i++
  return `${name}_${i}`
}

/**
 * 别名合法性(与后端 sqlIdentRe 一致)。
 *
 * 只允许 ASCII 标识符(中文别名在 CK 里要加反引号,这里直接不允许)。前端拿它做前置校验,
 * 后端还会按同一条正则再校验一次 —— 两边必须一致,否则前端放行、用户点了运行才被拒。
 */
export const SQL_IDENT_RE = /^[A-Za-z_][A-Za-z0-9_]*$/
