/**
 * connection.ts
 *
 * 职责：当前工作连接 Pinia 仓库：保存内存中的完整连接对象并恢复上次选择。
 * 边界：localStorage 只保存连接 id；恢复时用最新列表重新查找，连接被删除或列表为空时回退首项或 null。
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Connection } from '@/types'

const ACTIVE_KEY = 'el2ck.activeId'

/** 全局「当前工作连接」状态 */
export const useConnectionStore = defineStore('connection', () => {
  const active = ref<Connection | null>(null)

  function setActive(c: Connection | null) {
    active.value = c
    if (c) {
      // 只持久化 id(而不是整个对象):连接配置可能被别处改过,
      // 存 id 下次启动重新拉列表,拿到的永远是最新配置
      localStorage.setItem(ACTIVE_KEY, String(c.id))
    } else {
      localStorage.removeItem(ACTIVE_KEY)
    }
  }

  /** 从列表恢复上次选中的连接,没有则取第一个 */
  function restore(list: Connection[]): Connection | null {
    // 没存过时 getItem 返回 null,Number(null)===0;真实连接 id 从 1 起,不会误命中
    const saved = Number(localStorage.getItem(ACTIVE_KEY))
    // find 落空(连接被删了)就退回第一个;list 为空则 null,由调用方决定空态怎么展示
    const found = list.find((c) => c.id === saved) || list[0] || null
    if (found) setActive(found)
    return found
  }

  return { active, setActive, restore }
})
