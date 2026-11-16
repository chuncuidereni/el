/**
 * format.ts
 *
 * 职责：元数据时间格式化工具，把后端按 UTC 保存的时间转换为浏览器本地时间。
 *
 * 时间有两个来源，必须区别对待：
 * 1. SQLite 元数据中的 created_at、last_run_at、startedAt 等由 datetime('now') 写入，语义是 UTC；
 *    来自普通 /api/ 接口的这类字段必须经 fmtDt 转成本地时间。
 * 2. ClickHouse 查询结果中的 DateTime 和 system.parts.modification_time 已带服务器时区，直接显示即可。
 *    再次调用 fmtDt 会重复偏移；拓扑图中的最后摊平、聚合表最后写入属于这一类。
 * 边界：元数据按 UTC 存储，时区转换只在展示层完成，不能混入查询结果。
 */

/** 'YYYY-MM-DD HH:MM:SS'(后端 UTC)→ 本地 'YYYY-MM-DD HH:MM:SS'。空值原样返回。 */
export function fmtDt(s?: string | null): string {
  if (!s) return ''
  const t = parseUtc(s)
  if (!t) return s
  const p = (n: number) => String(n).padStart(2, '0')
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())} ${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}`
}

/** 把 'YYYY-MM-DD HH:MM:SS'(视为 UTC)解析为本地 Date */
function parseUtc(s: string): Date | null {
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/)
  if (!m) return null
  const [, y, mo, d, h, mi, se] = m.map(Number)
  return new Date(Date.UTC(y, mo - 1, d, h, mi, se))
}
