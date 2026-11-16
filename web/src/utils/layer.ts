/**
 * layer.ts
 *
 * 职责：数据层级元数据：映射 ods_data/dwd_data 的展示名、说明和主题色。
 * 边界：新增层级只维护 LAYERS；未知库名原样展示，主色使用 CSS 变量以兼容明暗主题。
 */

/**
 * 平台的「层级」封装。
 *
 * 展示形式:**层级名(物理库名)** —— 例如 `标准源表（ods_data）`。
 * 早期版本只显示层级名(`标准源表`)并刻意隐藏物理库名,理由是「库名属于实现细节」;
 * 但实际使用时定位不到具体是哪个 ClickHouse 库,故改为把物理库名以括号附在后面,
 * 两个信息都保留:看层级名能理解它在流程中的位置,看括号里的库名能直接对到 ClickHouse。
 *
 * 新增层级只改这一处。
 */

export interface Layer {
  /** 展示名(UI 上看到的):物理库名(层级名) */
  label: string
  /** 物理库名(也用于后端请求) */
  db: string
  /** 一句话说明 */
  desc: string
  /** 主色调 */
  accent: string
}

export const LAYERS: Layer[] = [
  {
    label: '标准源表（ods_data）',
    db: 'ods_data',
    desc: '同步任务落库的源表',
    // 用 CSS 变量而不是硬编码色值:同一串色不可能在白底和暗底上都合格
    //(#f59e0b 白底 2.1:1 / #6366f1 暗底 3.4:1)。两个模式各一套值,见 theme.css。
    accent: 'var(--layer-ods)'
  },
  {
    label: '目标库表（dwd_data）',
    db: 'dwd_data',
    desc: '加工后的宽表 / 明细表',
    accent: 'var(--layer-dwd)'
  }
]

const BY_DB = new Map(LAYERS.map((l) => [l.db, l]))

/** 按物理库名找层级 */
export function layerOf(db?: string): Layer | undefined {
  return db ? BY_DB.get(db) : undefined
}

/**
 * 库名 → 展示名。
 *
 * 已登记的库返回「层级名（物理库名）」(如 `标准源表（ods_data）`);
 * 未登记的库返回原样(兜底,避免信息丢失),但调用方应尽量只让用户选已登记的层级。
 */
export function dbLabel(db?: string): string {
  if (!db) return '—'
  return BY_DB.get(db)?.label ?? db
}

/** 层级主色(未登记返回 undefined,由调用方给默认色) */
export function dbAccent(db?: string): string | undefined {
  return db ? BY_DB.get(db)?.accent : undefined
}
