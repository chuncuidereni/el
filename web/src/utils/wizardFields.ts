/**
 * wizardFields.ts
 *
 * 职责：探索向导字段合并工具：把最新列元数据与已有输出字段、关联键和处理链合并。
 * 边界：已删除的列应被过滤，仍存在的用户选择和步骤必须保留；合并结果不能修改输入对象。
 */

import type { QueryWorkflowFieldRule } from '@/types'

/**
 * 把「探索向导」勾选的输出列合并到已有的字段配置上。
 *
 * 为什么抽成纯函数:向导第 3 步只勾「要哪些列」,不知道列上还挂着什么。
 * 以前重建字段时把 steps / pipeline / prog / agg 一律置空 —— 于是给列配好
 * SM3/3DES 脱敏链之后,点一次向导就静默退回明文投影(hidden 也是后来才补上的,
 * 不补则「不输出」标记会被全部解开)。这类失败方式是"配置静默消失",
 * 靠人工点界面很难发现,所以必须能单测。
 *
 * 匹配按【源表名 + 列名】(不是下标):向导里换了表或改了列名时,按新列处理(空链),
 * 并把「原本有配置却被丢弃」的列数返回,由调用方决定怎么提示。
 */
export function mergeWizardFields(
  prevFields: QueryWorkflowFieldRule[],
  /** 取某个源下标对应的表名(表名用于和向导回传的 table 对齐) */
  tableOfSourceIdx: (idx: number) => string,
  wizFields: { table: string; col: string; alias: string }[],
  idxByTable: Map<string, number>
): { fields: QueryWorkflowFieldRule[]; droppedConfigured: number } {
  // 键是「表名.列名」,列名来自用户表结构(可以是任意字符串)—— 所以用 Map 而不是
  // Record:普通对象的 `obj[key]` / `key in obj` 会命中原型链上的名字
  // (constructor / toString 等列名会被当成"命中"),这正是 arbFields.ts 踩过的坑。
  //
  // ⚠️ 同一个「表名.列名」出现两条是【可达】的:源树点一下列就 addField(不判重),
  // 字段下拉换列(setFieldCol)也能撞到另一条已有的列。这种情况必须按「任一命中即算」
  // 聚合,不能让后一条覆盖前一条 —— 用户真正配过 steps/hidden 的往往是先加的那条,
  // 后加的那条 steps/prog 都是空的;覆盖过去不但丢配置,连 droppedConfigured 都会
  // 少报(同一键只留一条),于是连提示都没有。这里先 clone 再用,不碰调用方的对象。
  const prevBySrc = new Map<string, QueryWorkflowFieldRule>()
  for (const f of prevFields) {
    const key = `${tableOfSourceIdx(f.sourceIdx)}.${f.sourceCol}`
    const cur = prevBySrc.get(key)
    if (!cur) {
      prevBySrc.set(key, { ...f, steps: [...(f.steps ?? [])], prog: [...(f.prog ?? [])] })
      continue
    }
    cur.hidden = cur.hidden || f.hidden
    if (!cur.steps?.length && f.steps?.length) cur.steps = [...f.steps]
    if (!cur.prog?.length && f.prog?.length) cur.prog = [...f.prog]
    if (!cur.agg && f.agg) cur.agg = f.agg
    if (!cur.pipeline?.length && f.pipeline?.length) cur.pipeline = [...f.pipeline]
  }

  const fields: QueryWorkflowFieldRule[] = wizFields.map((f) => {
    const prev = prevBySrc.get(`${f.table}.${f.col}`)
    return {
      alias: f.alias,
      sourceIdx: idxByTable.get(f.table) ?? 0,
      sourceCol: f.col,
      pipeline: prev?.pipeline ? [...prev.pipeline] : undefined,
      steps: prev?.steps ? [...prev.steps] : [],
      prog: prev?.prog ? [...prev.prog] : [],
      agg: prev?.agg ?? '',
      hidden: prev?.hidden || undefined
    }
  })

  // 原本挂着处理链/程序算子/聚合、但这次向导里已经没有的列:它们的配置会随之消失,
  // 计数交给调用方提示(不静默)。列数很少,直接扫,不额外建集合。
  let droppedConfigured = 0
  for (const [key, f] of prevBySrc) {
    const configured =
      (f.steps?.length ?? 0) > 0 || (f.pipeline?.length ?? 0) > 0 || (f.prog?.length ?? 0) > 0 || !!f.agg
    if (!configured) continue
    if (!wizFields.some((w) => `${w.table}.${w.col}` === key)) droppedConfigured++
  }

  return { fields, droppedConfigured }
}
