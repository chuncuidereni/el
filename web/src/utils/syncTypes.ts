/**
 * syncTypes.ts
 *
 * 职责：同步类型规则：推断源类型、判断增量键、生成系统列/目标列校验并填充默认目标名。
 * 边界：系统列规则依赖 ODS/目录模式；自动目标名必须避免与已有列冲突，所有提示需与后端保存校验一致。
 */

/**
 * 逐层剥离 Nullable(...)/LowCardinality(...)，让调用方基于实际源类型判断可映射性和增量键。
 * 当前只剥这两层包装，不解析嵌套复杂类型；默认映射仍保留整数位宽、Decimal 精度和时间精度。
 */
export function baseSourceType(type: string): string {
  let t = (type || '').trim()
  while (/^(Nullable|LowCardinality)\(/i.test(t) && t.endsWith(')')) {
    t = t.slice(t.indexOf('(') + 1, -1).trim()
  }
  return t
}

export function inferSourceType(type: string): string {
  const raw = baseSourceType(type)
  const t = raw.toUpperCase()
  const integer = t.match(/^(U?INT)(8|16|32|64|128|256)$/)
  if (integer) return `${integer[1] === 'UINT' ? 'UInt' : 'Int'}${integer[2]}`
  const sqlDatetime = t.match(/^DATETIME\((\d+)\)$/)
  if (sqlDatetime) return `DateTime64(${sqlDatetime[1]})`
  if (/^DATETIME(64)?(?:\(.*\))?$/.test(t)) return raw.replace(/^datetime(64)?/i, (_, n) => `DateTime${n || ''}`)
  if (t === 'DATE32') return 'Date32'
  if (t === 'DATE') return 'Date'
  const decimal = t.match(/^(?:DECIMAL|NUMERIC)\(\s*(\d+)\s*,\s*(\d+)\s*\)$/)
  if (decimal) return `Decimal(${decimal[1]}, ${decimal[2]})`
  const decimalStorage = raw.match(/^decimal(32|64|128|256)\(\s*(\d+)\s*\)$/i)
  if (decimalStorage) return `Decimal${decimalStorage[1]}(${decimalStorage[2]})`
  if (t === 'FLOAT32') return 'Float32'
  if (t === 'FLOAT64') return 'Float64'
  if (/^(?:TIMESTAMP|DATETIME)/.test(t)) {
    const scale = t.match(/\((\d+)\)/)?.[1]
    return `DateTime64(${scale || (t === 'TIMESTAMP_NS' ? '9' : t === 'TIMESTAMP_MS' ? '3' : t === 'TIMESTAMP_S' ? '0' : '6')})`
  }
  if (t.includes('BIGINT') || t.includes('BIGSERIAL')) return t.includes('UNSIGNED') ? 'UInt64' : 'Int64'
  if (t.includes('HUGEINT')) return t.includes('UNSIGNED') || t.startsWith('U') ? 'UInt128' : 'Int128'
  if (/INT|SERIAL|YEAR/.test(t)) return t.includes('UNSIGNED') ? 'UInt32' : 'Int32'
  if (/DOUBLE|FLOAT|REAL/.test(t)) return 'Float64'
  if (/^(BOOL|BOOLEAN|BIT\(1\))$/.test(t)) return 'UInt8'
  // 未给精度的 NUMERIC/DECIMAL 无法安全猜测，默认保留文本，用户可明确指定目标精度。
  return 'String'
}

/**
 * 当前允许整数、浮点、Decimal/Numeric、Date/Date32、DateTime/DateTime64 作为增量键。
 * 判断基于 baseSourceType 后的类型字符串，不代表后端一定接受该列的业务取值或索引条件。
 */
export function canBeIncrementalKey(type: string): boolean {
  const t = baseSourceType(type).toLowerCase()
  return /^(u?int(8|16|32|64|128|256)|float(32|64)|decimal.*|numeric.*|date|date32|datetime(64)?(\(.*\))?)$/.test(t)
}

/**
 * 普通映射不能占用系统自动维护列。通用保留列为 source_id/create_time/sync_time，
 * 以及续传/轮次字段 _el_file_row、_el_kafka_partition、_el_kafka_offset、_el_sync_round、_el_sync_row；
 * ODS 额外保留 ods_source_id/ods_insert_at，目录源额外保留 path。
 */
export function systemTargetColumnError(column: string, ods: boolean, directory: boolean): string {
  const name = column.trim().toLowerCase()
  if (['source_id', 'create_time', 'sync_time', '_el_file_row', '_el_kafka_partition', '_el_kafka_offset', '_el_sync_round', '_el_sync_row'].includes(name)
    || (ods && ['ods_source_id', 'ods_insert_at'].includes(name))
    || (directory && name === 'path')) {
    return `目标列「${column.trim()}」由系统自动维护，请删除该映射；需要保留源字段时请改用其他目标列名`
  }
  return ''
}

/**
 * 检查已有目标表的系统列类型。ODS/普通源分别要求源 ID 和写入时间；SFTP 续传额外要求
 * path=String、_el_file_row=UInt64，Kafka 续传额外要求分区 Int32、位点 Int64。
 * autoCreate=true 时缺失系统列不报错，但本函数只校验、不创建列；数字类型按字符串精确匹配。
 * _el_sync_round/_el_sync_row 目前只在保留列名检查中出现，尚未在此函数校验类型。
 */
export function systemTargetTypeIssues(
  columns: { name: string; type?: string }[], ods: boolean, directory: boolean, autoCreate: boolean,
  resumableSource = '',
): string[] {
  const expected: [string, 'string' | 'datetime' | 'String' | 'UInt64' | 'Int32' | 'Int64'][] = [
    [ods ? 'ods_source_id' : 'source_id', 'string'], [ods ? 'ods_insert_at' : 'create_time', 'datetime'],
    ...(directory ? [['path', 'string'] as [string, 'string']] : []),
  ]
  if (resumableSource === 'sftp') {
    const path = expected.find(([name]) => name === 'path')
    if (path) path[1] = 'String'
    expected.push(['_el_file_row', 'UInt64'])
  } else if (resumableSource === 'kafka') {
    expected.push(['_el_kafka_partition', 'Int32'], ['_el_kafka_offset', 'Int64'])
  }
  return expected.flatMap(([name, kind]) => {
    const column = columns.find((c) => c.name === name)
    if (!column) return autoCreate ? [] : [`目标表缺少系统字段「${name}」，请补齐字段或开启自动建表以补列`]
    const type = column.type || '', base = baseSourceType(type).toLowerCase()
    const valid = kind === 'string' ? /^(string|fixedstring\(.*\))$/.test(base)
      : kind === 'datetime' ? /^datetime(64)?(\(.*\))?$/.test(base) : type.toLowerCase() === kind.toLowerCase()
    return valid ? [] : [`系统字段「${name}」类型不匹配：实际为 ${type || '未知'}，需要 ${kind === 'string' ? '字符串类型' : kind === 'datetime' ? 'DateTime / DateTime64' : kind}`]
  })
}

/** 返回每一行的即时提示，自动维护行不应传入本函数。 */
export function mappingTargetIssues(
  rows: { sourceColumn: string; targetColumn: string }[],
  options: { ods: boolean; directory: boolean; existingColumns?: string[]; targetTable?: string; sourceColumns?: string[] },
): string[] {
  const counts = new Map<string, number>()
  const sourceCounts = new Map<string, number>()
  for (const row of rows) {
    const target = row.targetColumn.trim()
    const source = row.sourceColumn.trim()
    if (target) counts.set(target, (counts.get(target) || 0) + 1)
    if (source) sourceCounts.set(source, (sourceCounts.get(source) || 0) + 1)
  }
  return rows.map((row) => {
    const target = row.targetColumn.trim()
    const source = row.sourceColumn.trim()
    if (!target && !source) return ''
    if (!target) return `请为源列「${source}」填写目标列`
    const systemError = systemTargetColumnError(target, options.ods, options.directory)
    if (systemError) return systemError
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(target))
      return `目标列「${target}」仅允许字母、数字、下划线，不能以数字开头`
    if ((counts.get(target) || 0) > 1) return `目标列「${target}」被重复映射，请为每一列保留唯一的目标列`
    if (options.existingColumns?.length && !options.existingColumns.includes(target))
      return `目标列「${target}」不存在于目标表 ${options.targetTable || ''}，请从下拉选择现有列`
    if (!source) return '请选择对应的源列'
    if ((sourceCounts.get(source) || 0) > 1) return `源列「${source}」被重复映射，请保留一条映射`
    if (options.sourceColumns?.length && !options.sourceColumns.includes(source)) return `源列「${source}」已不在当前源结构中，请重新选择源列`
    return ''
  })
}

/** 先保留普通字段名，再为 MongoDB 点分路径分配无冲突的目标列名。 */
export function fillAutoTargetColumns(
  rows: { sourceColumn: string; targetColumn: string }[],
  allowNested: boolean,
  reserved: string[] = [],
): void {
  const ident = /^[A-Za-z_][A-Za-z0-9_]*$/
  const reservedNames = new Set(reserved.map((name) => name.toLowerCase()))
  const used = new Set([...reserved, ...rows.map((r) => r.targetColumn.trim()).filter(Boolean)])
  for (const nested of [false, true]) {
    for (const row of rows) {
      if (row.targetColumn.trim() || !row.sourceColumn) continue
      const path = row.sourceColumn.split('.')
      if ((path.length > 1) !== nested || (nested && !allowNested) || !path.every((p) => ident.test(p))) continue
      let base = path.join('_')
      if (reservedNames.has(base.toLowerCase())) base += '_value'
      let target = base
      for (let suffix = 2; used.has(target); suffix++) target = `${base}_${suffix}`
      row.targetColumn = target
      used.add(target)
    }
  }
}
