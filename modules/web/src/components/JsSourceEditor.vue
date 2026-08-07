<template>
  <div class="js-source-editor">
    <div class="js-source-toolbar">
      <el-select
        v-model="selectedSourceUrl"
        class="js-source-select"
        filterable
        :loading="loadingSources"
        placeholder="JavaScript 书源"
        @change="selectSource"
      >
        <el-option
          v-for="source in jsSources"
          :key="source.bookSourceUrl"
          :label="`${source.bookSourceName} · ${source.bookSourceUrl}`"
          :value="source.bookSourceUrl"
        />
      </el-select>
      <el-button :icon="DocumentAdd" @click="newSource">新建</el-button>
      <el-button :icon="FolderOpened" @click="openFile">打开</el-button>
      <el-button :icon="Download" :disabled="!script" @click="exportScript">
        导出
      </el-button>
      <el-button :icon="Refresh" @click="reloadSources">刷新</el-button>
      <el-button
        type="primary"
        :icon="Check"
        :loading="saving"
        :disabled="!script.trim()"
        @click="saveScript"
      >
        保存
      </el-button>
    </div>
    <el-input
      v-model="script"
      class="js-source-input"
      type="textarea"
      resize="none"
      :spellcheck="false"
      @keydown.ctrl.s.prevent="saveScript"
      @keydown.meta.s.prevent="saveScript"
    />
  </div>
</template>

<script setup lang="ts">
import API from '@api'
import type { BookSoure, Source } from '@/source'
import {
  Check,
  DocumentAdd,
  Download,
  FolderOpened,
  Refresh,
} from '@element-plus/icons-vue'

const props = defineProps<{ active: boolean }>()
const store = useSourceStore()
const script = ref('')
const savedScript = ref('')
const openedSourceUrl = ref('')
const selectedSourceUrl = ref('')
const loadingSources = ref(false)
const saving = ref(false)
let sourcesLoaded = false
let restoringCurrentSource = false

const jsSources = computed(() =>
  store.bookSources.filter(source => source.mainJs?.trim()),
)
const dirty = computed(() => script.value !== savedScript.value)

const isJsSource = (source: Source): source is BookSoure =>
  'bookSourceUrl' in source && !!source.mainJs?.trim()

const loadSource = (source: BookSoure) => {
  const sourceScript = source.mainJs || ''
  openedSourceUrl.value = source.bookSourceUrl
  selectedSourceUrl.value = source.bookSourceUrl
  script.value = sourceScript
  savedScript.value = sourceScript
  if (store.currentSource !== source) {
    store.changeCurrentSource(source)
  }
}

const resetEditor = () => {
  openedSourceUrl.value = ''
  selectedSourceUrl.value = ''
  script.value = ''
  savedScript.value = ''
}

const confirmDiscard = async () => {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('当前脚本尚未保存，是否放弃修改？', '未保存修改', {
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

const pullSources = async () => {
  loadingSources.value = true
  try {
    const response = await API.getSources()
    if (!response.data.isSuccess) {
      ElMessage.error(response.data.errorMsg || '书源加载失败')
      return false
    }
    store.saveSources(response.data.data)
    sourcesLoaded = true
    return true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '书源加载失败')
    return false
  } finally {
    loadingSources.value = false
  }
}

const selectSource = async (
  sourceUrl: string | number | boolean | undefined,
) => {
  if (typeof sourceUrl !== 'string') return
  const source = jsSources.value.find(item => item.bookSourceUrl === sourceUrl)
  if (!source) return
  if (!(await confirmDiscard())) {
    selectedSourceUrl.value = openedSourceUrl.value
    return
  }
  loadSource(source)
}

const newSource = async () => {
  if (!(await confirmDiscard())) return
  resetEditor()
}

const openFile = async () => {
  if (!(await confirmDiscard())) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.js,.txt'
  input.addEventListener('change', () => {
    const file = input.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      openedSourceUrl.value = ''
      selectedSourceUrl.value = ''
      script.value = String(reader.result || '')
      savedScript.value = ''
    }
    reader.onerror = () => ElMessage.error('脚本读取失败')
    reader.readAsText(file)
  })
  input.click()
}

const exportScript = () => {
  const source = jsSources.value.find(
    item => item.bookSourceUrl === openedSourceUrl.value,
  )
  const fileName = (source?.bookSourceName || 'bookSource').replace(
    /[\\/:*?"<>|]/g,
    '_',
  )
  const url = URL.createObjectURL(
    new Blob([script.value], { type: 'text/javascript;charset=utf-8' }),
  )
  const link = document.createElement('a')
  link.download = `${fileName}.js`
  link.href = url
  link.click()
  URL.revokeObjectURL(url)
}

const reloadSources = async () => {
  if (!(await confirmDiscard())) return
  const sourceUrl = openedSourceUrl.value
  if (!(await pullSources()) || !sourceUrl) return
  const source = jsSources.value.find(item => item.bookSourceUrl === sourceUrl)
  if (source) loadSource(source)
  else resetEditor()
}

const saveScript = async () => {
  if (!script.value.trim() || saving.value) return
  saving.value = true
  try {
    const oldUrl = openedSourceUrl.value || undefined
    const response = await API.saveJsSource(script.value, oldUrl)
    if (!response.data.isSuccess) {
      ElMessage.error(response.data.errorMsg || 'JavaScript 书源保存失败')
      return
    }
    const source = response.data.data
    store.saveJsSource(source, oldUrl)
    loadSource(source)
    ElMessage.success(`源《${source.bookSourceName}》保存成功`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'JavaScript 书源保存失败')
  } finally {
    saving.value = false
  }
}

watch(
  () => [props.active, store.currentSource] as const,
  async ([active, source], previous) => {
    if (restoringCurrentSource) {
      restoringCurrentSource = false
      return
    }
    if (!active) return
    if (!sourcesLoaded && store.bookSources.length === 0) await pullSources()
    if (!isJsSource(source) || source.bookSourceUrl === openedSourceUrl.value)
      return
    if (await confirmDiscard()) return loadSource(source)

    const previousSource = previous?.[1]
    if (previousSource) {
      restoringCurrentSource = true
      store.changeCurrentSource(previousSource)
    }
  },
  { immediate: true },
)

const beforeUnload = (event: BeforeUnloadEvent) => {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>

<style lang="scss" scoped>
.js-source-editor {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

.js-source-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 52px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--el-border-color-light);

  .el-button + .el-button {
    margin-left: 0;
  }
}

.js-source-select {
  flex: 1;
  min-width: 220px;
}

.js-source-input {
  flex: 1;
  min-height: 0;

  :deep(.el-textarea__inner) {
    height: 100%;
    min-height: 100% !important;
    padding: 14px 16px;
    border: 0;
    border-radius: 0;
    box-shadow: none;
    font-family: Consolas, 'Courier New', monospace;
    font-size: 14px;
    line-height: 1.55;
  }
}

@media screen and (max-width: 900px) {
  .js-source-toolbar {
    flex-wrap: wrap;
  }

  .js-source-select {
    flex-basis: 100%;
  }
}

@media screen and (max-width: 600px) {
  .js-source-toolbar {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .js-source-select {
    grid-column: 1 / -1;
    min-width: 0;
  }

  .js-source-toolbar > .el-button {
    width: 100%;
    margin: 0;
  }
}
</style>
