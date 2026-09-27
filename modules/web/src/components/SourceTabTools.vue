<template>
  <el-tabs v-model="current_tab" class="source-tools">
    <el-tab-pane
      v-for="(tab, index) in tabData"
      :key="tab[0]"
      :name="tab[0]"
      :label="tab[1]"
    >
      <source-json v-if="index == 0" />
      <source-debug v-if="index == 1" />
      <source-list v-if="index == 2" />
      <source-help v-if="index == 3" />
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import { useSourceStore } from '@/store'

const store = useSourceStore()

const current_tab = computed({
  get: () => store.currentTab,
  set: val => store.changeTabName(val),
})

const tabData = ref([
  ['editTab', '编辑源'],
  ['editDebug', '调试源'],
  ['editList', '源列表'],
  ['editHelp', '帮助信息'],
])
</script>

<style lang="scss" scoped>
.source-tools {
  height: 100%;
  display: flex;
  flex-direction: column;
}
:deep(.el-tabs__header) {
  margin-bottom: 5px;
}
:deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
:deep(.el-tab-pane) {
  height: 100%;
  overflow-y: auto;
}
</style>
