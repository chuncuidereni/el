<!--
  DbIcon.vue

  职责：数据库类型图标组件。
  边界：无脚本状态和异步副作用，只以圆柱造型表达数据库或层级节点，可随尺寸、颜色等 CSS 上下文复用。
-->

<template>
  <!--
    数据库图标(圆柱造型),用于库/层级类的节点与列表项(如工作台左树、分组下拉的前缀图标)。

    自绘 SVG 而非用 Element Plus 内置图标:内置图标里没有「库」这种语义的图形。
    尺寸与颜色都跟随上下文 —— 宽高 1em、stroke 用 currentColor,
    因此父级 font-size / color 是什么样,图标就呈现什么样,不需要额外传 props。
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
    <!-- 数据库圆柱:顶部椭圆 + 两侧 + 两条水平线 -->
    <ellipse cx="12" cy="5" rx="8" ry="3" />
    <path d="M4 5v14c0 1.66 3.58 3 8 3s8-1.34 8-3V5" />
    <path d="M4 12c0 1.66 3.58 3 8 3s8-1.34 8-3" />
  </svg>
</template>
