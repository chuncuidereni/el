/**
 * syncMappingTypes.ts
 *
 * 职责：同步字段类型兼容性检查：解析源/目标类型并给出错误或警告。
 * 边界：日期时间、Decimal、整数范围、复杂对象和增量键规则必须与后端写入能力一致，警告不能静默升级为成功。
 */

import { baseSourceType, inferSourceType } from './syncTypes'

type Kind = 'string' | 'boolean' | 'integer' | 'float' | 'decimal' | 'date' | 'datetime' | 'complex' | 'other'
type TypeInfo = { kind: Kind; bits?: number; unsigned?: boolean; precision?: number; scale?: number }
export type MappingTypeIssue = { level: 'error' | 'warning'; message: string }

/** 校验字段类型语法并拒绝语句分隔符、SQL 注释和未闭合括号，避免把任意表达式带入 DDL。 */
function typeSyntaxError(type: string): string {
  const raw = type.trim()
  if (!/^[A-Za-z][A-Za-z0-9_]*(?:\s*\([\s\S]*\))?$/.test(raw)) return '字段类型格式不正确，请检查名称和括号'
  let depth = 0, quote = ''
  for (let i = 0; i < raw.length; i++) {
    const char = raw[i]
    if (quote) {
      if (char === '\\') { i++; continue }
      if (char === quote && raw[i + 1] === quote) { i++; continue }
      if (char === quote) quote = ''
    } else if (char === "'" || char === '"' || char === '`') quote = char
    else if (char === '(') depth++
    else if (char === ')' && --depth < 0) return '字段类型括号不匹配'
    else if (char === ';' || (char === '-' && raw[i + 1] === '-') || (char === '/' && raw[i + 1] === '*'))
      return '字段类型不能包含 SQL 语句或注释'
  }
  if (depth || quote) return '字段类型的括号或引号未闭合'
  if (/^datetime\(/i.test(raw) && !/^datetime\(\s*'[^']*'\s*\)$/i.test(raw)) return 'DateTime 只接受时区；小数秒精度请使用 DateTime64，例如 DateTime64(6)'
  if (/^datetime64(?:\(|$)/i.test(raw)
    && !/^datetime64(?:\(\s*\d+\s*(?:,\s*'[^']*'\s*)?\))?$/i.test(raw))
    return 'DateTime64 请填写小数秒精度，可选时区，例如 DateTime64(6) 或 DateTime64(6, \'UTC\')'
  if (/^fixedstring(?:\(|$)/i.test(raw) && !/^fixedstring\(\s*[1-9]\d*\s*\)$/i.test(raw))
    return 'FixedString 请填写正整数长度，例如 FixedString(32)'
  if (/^(u?int\d+|float\d+|bfloat16|bool|boolean|date|date32|string)\(/i.test(raw))
    return '该标量类型不接受参数，请使用不带括号的类型名'
  const integer = raw.match(/^u?int(\d+)$/i)
  if (integer && ![8, 16, 32, 64, 128, 256].includes(Number(integer[1]))) return '整数位宽请选择 8、16、32、64、128 或 256'
  if (/^decimal(?:32|64|128|256)?(?:\(|$)/i.test(raw)
    && !/^decimal(?:\(\s*\d+\s*,\s*\d+\s*\)|(?:32|64|128|256)\(\s*\d+\s*\))$/i.test(raw))
    return 'Decimal 请填写总精度和小数位，例如 Decimal(18, 6) 或 Decimal64(6)'
  return ''
}

/** 把常见 ClickHouse/源端类型归一为类别和数值精度，供兼容性规则比较。 */
function describe(type: string): TypeInfo {
  const raw = baseSourceType(type).toLowerCase()
  if (/^(array|map|tuple|nested|object|json)(\(|$)/.test(raw)) return { kind: 'complex' }
  if (/^(bool|boolean|bit\(1\))$/.test(raw)) return { kind: 'boolean' }
  if (/^(string|fixedstring\(|varchar|char|text|clob)/.test(raw)) return { kind: 'string' }
  if (raw === 'numeric' || raw === 'decimal') return { kind: 'decimal' }
  if (raw === 'bfloat16') return { kind: 'float', bits: 16 }
  const normalized = baseSourceType(inferSourceType(type)).toLowerCase()
  const integer = normalized.match(/^(u?)int(8|16|32|64|128|256)$/)
  if (integer) return { kind: 'integer', bits: Number(integer[2]), unsigned: !!integer[1] }
  const floating = normalized.match(/^float(32|64)$/)
  if (floating) return { kind: 'float', bits: Number(floating[1]) }
  const decimal = normalized.match(/^decimal\(\s*(\d+)\s*,\s*(\d+)\s*\)$/)
  if (decimal) return { kind: 'decimal', precision: Number(decimal[1]), scale: Number(decimal[2]) }
  const storageDecimal = normalized.match(/^decimal(32|64|128|256)\(\s*(\d+)\s*\)$/)
  if (storageDecimal) return { kind: 'decimal', precision: ({ 32: 9, 64: 18, 128: 38, 256: 76 } as Record<number, number>)[Number(storageDecimal[1])], scale: Number(storageDecimal[2]) }
  if (normalized === 'date' || normalized === 'date32') return { kind: 'date', bits: normalized === 'date32' ? 32 : 16 }
  if (/^datetime64(?:\(|$)/.test(normalized)) return { kind: 'datetime', scale: Number(normalized.match(/^datetime64\(\s*(\d+)/)?.[1] ?? 3) }
  if (/^datetime(?:\(|$)/.test(normalized)) return { kind: 'datetime', scale: 0 }
  return { kind: 'other' }
}

const numeric = (type: TypeInfo) => ['integer', 'float', 'decimal'].includes(type.kind)
const temporal = (type: TypeInfo) => ['date', 'datetime'].includes(type.kind)

/** 比较源/目标字段类型；返回 error 或 warning，完全兼容时返回 undefined。 */
export function mappingTypeIssue(sourceType: string, targetType: string, incremental = false): MappingTypeIssue | undefined {
  if (!targetType.trim()) return { level: 'error', message: '请选择字段类型' }
  const syntaxError = typeSyntaxError(baseSourceType(targetType))
  if (syntaxError) return { level: 'error', message: syntaxError }
  if (!sourceType.trim()) return undefined
  const source = describe(sourceType), target = describe(targetType)
  const label = `${sourceType} → ${targetType}`
  const error = (reason: string): MappingTypeIssue => ({ level: 'error', message: `${label}：${reason}` })
  const risk = (reason: string): MappingTypeIssue => ({ level: incremental ? 'error' : 'warning', message: `${label}：${reason}${incremental ? '，请提高增量字段目标范围或精度' : '，请检查实际数据或调整目标类型'}` })
  if (target.kind === 'datetime' && target.scale! > 9) return error('DateTime64 小数秒精度必须在 0–9 之间')
  if (target.kind === 'decimal' && (target.precision! < 1 || target.precision! > 76 || target.scale! < 0 || target.scale! > target.precision!))
    return error('Decimal 精度必须在 1–76 之间，小数位不能超过总精度')
  if (incremental && (source.bits === 16 && source.kind === 'float' || target.bits === 16 && target.kind === 'float'))
    return error('BFloat16 不能作为当前同步引擎的增量键类型')
  if (incremental && ((!numeric(source) && !temporal(source)) || (!numeric(target) && !temporal(target))))
    return error('增量键的源和目标必须同为数值类型或同为日期时间类型')
  if ((numeric(source) && temporal(target)) || (temporal(source) && numeric(target)))
    return error('数值与日期时间不能直接按当前映射转换')
  if ((source.kind === 'boolean' && temporal(target)) || (temporal(source) && target.kind === 'boolean'))
    return error('布尔与日期时间不能直接按当前映射转换')
  if (incremental && temporal(source) && temporal(target) && source.kind !== target.kind)
    return error('增量键不能在日期与时间戳之间转换')
  if (source.kind === 'complex' && target.kind !== 'string' && target.kind !== 'complex')
    return error('对象或数组不能直接写入该类型，可改用 String 保存内容')
  if (target.kind === 'complex' && source.kind !== 'complex')
    return error('普通值不能直接转换成数组或对象类型')
  if (source.kind === 'complex' && target.kind === 'complex')
    return { level: 'warning', message: `${label}：请核对数组元素或对象字段的结构与目标定义是否一致` }
  if (source.kind === 'string' && (numeric(target) || temporal(target) || target.kind === 'boolean'))
    return { level: 'warning', message: `${label}：需要解析源字段值，请确认所有非空值均符合目标格式` }
  if (/^fixedstring\(/i.test(baseSourceType(targetType)))
    return { level: 'warning', message: `${label}：请检查源值的字节长度是否超过 FixedString 的长度限制` }
  if (target.kind === 'string') return undefined
  if (source.kind === 'boolean' && target.kind !== 'boolean' && !(target.kind === 'integer' && target.unsigned && target.bits === 8))
    return error('布尔源值请映射为 UInt8、Bool 或 String')
  if (source.kind === 'integer' && target.kind === 'integer') {
    if ((source.unsigned && !target.unsigned && target.bits! <= source.bits!)
      || (!source.unsigned && target.unsigned) || target.bits! < source.bits!) return risk('目标整数范围无法覆盖源类型')
  }
  if (source.kind === 'integer' && target.kind === 'float' && source.bits! - (source.unsigned ? 0 : 1) > (target.bits === 16 ? 8 : target.bits === 32 ? 24 : 53))
    return risk('大整数写入浮点类型可能丢失精度')
  if (source.kind === 'integer' && target.kind === 'decimal') {
    const digits = Math.ceil((source.bits! - (source.unsigned ? 0 : 1)) * Math.LOG10E * Math.LN2)
    if (target.precision! - target.scale! < digits) return risk('目标 Decimal 的整数位不足')
  }
  if (source.kind === 'decimal' && target.kind === 'decimal'
    && (target.scale! < source.scale! || target.precision! - target.scale! < source.precision! - source.scale!))
    return risk('目标 Decimal 的小数位或整数位不足')
  if ((source.kind === 'decimal' || source.kind === 'float') && target.kind === 'integer')
    return risk('小数转整数可能被截断或超出范围')
  if (source.kind === 'float' && target.kind === 'float' && target.bits! < source.bits!) return risk('目标浮点精度不足')
  if ((source.kind === 'decimal' && target.kind === 'float') || (source.kind === 'float' && target.kind === 'decimal'))
    return risk('浮点与 Decimal 转换可能改变数值精度')
  if (source.kind === 'datetime' && target.kind === 'date') return risk('日期类型无法保留时分秒')
  if (source.kind === 'datetime' && target.kind === 'datetime' && target.scale! < source.scale!)
    return risk('目标时间戳小数秒精度不足')
  if (source.kind === 'date' && target.kind === 'date' && target.bits! < source.bits!) return risk('目标日期范围小于源类型')
  if (target.kind === 'boolean' && source.kind !== 'boolean')
    return { level: 'warning', message: `${label}：请确认源值为 0/1 或有效布尔值` }
  if (target.kind === 'other') return { level: 'warning', message: `${label}：当前无法判断该类型的转换兼容性，请核对目标库支持及实际数据格式` }
  if (source.kind === 'other') return { level: 'warning', message: `${label}：源类型需要按实际内容检查，无法仅凭类型保证转换成功` }
  return undefined
}
