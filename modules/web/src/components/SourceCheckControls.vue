<template>
  <div class="source-check-controls">
    <el-select v-model="store.checkStatusFilter" aria-label="检验状态">
      <el-option v-for="option in sourceCheckOptions" :key="option.value" v-bind="option" />
    </el-select>
    <el-button :disabled="!sources.length || !!store.checkSessionToken" :loading="starting" @click="start">检验选中</el-button>
    <el-button v-if="store.checkSessionToken" @click="stop">停止检验</el-button>
    <el-button @click="refresh(true)">刷新状态</el-button>
  </div>
</template>

<script setup lang="ts">
import API from '@api'
import type { Source } from '@/source'
import { sourceCheckOptions } from '@/utils/sourceCheckState'

const props = defineProps<{ sources: Source[]; active: boolean }>()
const store = useSourceStore()
const starting = ref(false)
let refreshing = false
let timer: ReturnType<typeof setInterval> | undefined

const refresh = async (report = false) => {
  if (refreshing || (!report && !props.active)) return
  refreshing = true
  try {
    const { data } = await API.getBookSourceCheckStates()
    if (data.isSuccess) store.setCheckStates(data.data.states, data.data.sessionToken)
    else if (report) ElMessage.error(data.errorMsg)
  } catch (error) {
    if (report) ElMessage.error(String(error))
  } finally { refreshing = false }
}
const start = async () => {
  const selected = JSON.parse(JSON.stringify(props.sources)) as Source[]
  try {
    const { value } = await ElMessageBox.prompt('使用设备上的检验设置，输入搜索词', '检验书源', { inputValue: store.searchKey })
    starting.value = true
    const { data } = await API.startBookSourceCheck(selected, value)
    if (!data.isSuccess) return ElMessage.error(data.errorMsg)
    store.rememberCheckStart(selected, data.data.sourceRevisions)
    store.checkSessionToken = data.data.sessionToken
    await refresh()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(String(error))
  } finally { starting.value = false }
}
const stop = async () => {
  const token = store.checkSessionToken
  if (!token) return
  try {
    const { data } = await API.stopBookSourceCheck(token)
    if (!data.isSuccess) ElMessage.error(data.errorMsg)
    await refresh(true)
  } catch (error) { ElMessage.error(String(error)) }
}
onMounted(() => {
  void refresh()
  timer = setInterval(() => { if (!document.hidden) void refresh() }, 3000)
})
onBeforeUnmount(() => clearInterval(timer))
watch(() => props.active, active => { if (active) void refresh() })
</script>

<style scoped>
.source-check-controls { display: flex; flex-wrap: wrap; gap: 4px; margin: 4px 0; }
.el-select { min-width: 150px; flex: 1; }
.el-button + .el-button { margin-left: 0; }
</style>
