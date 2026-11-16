/**
 * useNavReset.ts
 *
 * 职责：全局导航重置信号：用递增 tick 通知同一路由下的页面重新定位或刷新。
 * 边界：tick 是模块级单例且不持久化；页面应 watch 该值并自行避免重复初始化。
 */

/**
 * 「点击当前所在菜单项 → 回到该菜单的列表页」。
 *
 * 背景:有些页面在同一个路由内切换「列表 / 详情」(数据探索的编辑器、目标库表（dwd_data）的表数据、
 * 标准源表（ods_data）的表数据)。此时路由 path 没变,再点一次侧边栏菜单不会触发任何导航,
 * 用户被困在详情里,只能找页面内的「返回列表」按钮。
 *
 * 做法:App.vue 在菜单项被点击且与当前 path 相同时 bump 一次计数,
 * 各页 watch 这个计数,把自己的详情态清掉回到列表。
 *
 * 为什么不干脆 push 同一个 path:vue-router 会判定为重复导航(什么都不发生)。
 * 为什么不用 query 参数:会污染 URL、还会让「刷新/分享」的地址变脏。
 */
import { ref } from 'vue'

const navResetTick = ref(0)

/** 菜单项被再次点击(且已是当前页)时调用 —— App.vue 用 */
export function bumpNavReset(): void {
  navResetTick.value++
}

/** 读计数(只读引用,供各页 watch) */
export function useNavReset() {
  return navResetTick
}
