<!--
  DetailPageTemplate.vue

  职责：为“表详情 / 表体检”等详情型页面提供统一的全高骨架。
  边界：只负责页面高度、固定区和可伸缩主体，不承载业务状态，也不改变调用方传入的内容。

  布局约定：
  - 页面根节点始终占满父级；
  - #header 与 #footer 保持自身高度；
  - 默认插槽所在的主体区吃掉剩余高度，并把 min-height 设为 0，
    让编辑器、表格等内部滚动容器能够真正收缩，而不是把页面继续向下撑长。
-->
<template>
  <section class="detail-page-template">
    <header v-if="$slots.header" class="detail-page-template__fixed">
      <slot name="header" />
    </header>

    <div class="detail-page-template__body">
      <slot />
    </div>

    <footer v-if="$slots.footer" class="detail-page-template__fixed">
      <slot name="footer" />
    </footer>
  </section>
</template>

<style scoped>
.detail-page-template {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

/* 顶部说明、工具栏和底部操作区只占内容需要的高度。 */
.detail-page-template__fixed {
  flex: none;
}

/*
  业务主体必须同时设置 flex: 1 与 min-height: 0。
  只写 flex: 1 时，Flex 子项的默认最小尺寸仍可能取内容高度，
  最终会把 CodeMirror 或结果表格撑出可视区域。
*/
.detail-page-template__body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>