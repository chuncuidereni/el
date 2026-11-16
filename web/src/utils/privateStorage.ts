/**
 * privateStorage.ts
 *
 * 职责：账号隔离的浏览器存储适配：按服务端确认的 userId 命名草稿并提供安全的 Storage 子集。
 * 边界：未确认账号时不得读写个人数据；切换账号或存储不可用时返回空/忽略异常，不能让隐私数据跨账号复用。
 */

/** Browser drafts are namespaced by the server-confirmed account, never by a form value. */
let activeUserId = 0
let generation = 0

export function setStorageUser(userId: number): void {
  const next = Number.isSafeInteger(userId) && userId > 0 ? userId : 0
  if (next !== activeUserId) generation++
  activeUserId = next
  // Old shared keys have no reliable owner. Do not assign them to the next person who signs in.
  try {
    const legacy: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key === 'el2ck.sqlHistory' || key?.startsWith('el2ck.visualDraft.') || key?.startsWith('el2ck:query:')) legacy.push(key)
    }
    legacy.forEach(key => localStorage.removeItem(key))
  } catch { /* Storage may be unavailable in private browsing. */ }
}

/** Capture the mounted page's identity so delayed responses cannot write under a new login. */
export function privateStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
  const owner = activeUserId, version = generation
  const valid = () => owner > 0 && owner === activeUserId && version === generation
  const keyFor = (key: string) => `el2ck.private.${owner}.${key}`
  return {
    getItem(key) {
      try { return valid() ? localStorage.getItem(keyFor(key)) : null } catch { return null }
    },
    setItem(key, value) {
      try { if (valid()) localStorage.setItem(keyFor(key), value) } catch { /* Best effort. */ }
    },
    removeItem(key) {
      try { if (valid()) localStorage.removeItem(keyFor(key)) } catch { /* Best effort. */ }
    },
  }
}
