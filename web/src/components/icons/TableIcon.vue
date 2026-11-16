<!--
  TableIcon.vue

  职责：数据表类型图标组件。
  边界：无脚本状态和异步副作用，只以表格造型标识数据表，可随尺寸、颜色等 CSS 上下文复用。
-->

<template>
  <!--
    表图标(带表头行的网格),用于表节点的前缀图标(如工作台左树、表选择弹窗的列表项)。

    与 DbIcon 同款约定:自绘 SVG、无 props,宽高 1em、stroke 用 currentColor,
    尺寸和颜色都由父级 font-size / color 决定 —— 换主题或换字号时无需改这个组件。
    两个图标共用同一套 viewBox(24×24)与线宽,并排显示时视觉重量才一致。
  -->
  <svg
    viewBox="0 0 24 24"
    width="1em"
    height="1em"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <!-- 表/网格:边框 + 表头行 + 两条列分隔线 -->
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="M3 9h18" />
    <path d="M9 9v11" />
    <path d="M15 9v11" />
  </svg>
</template>
