<template>
  <div v-if="authorized" class="source-editor">
    <div v-if="isBookSource" class="source-mode">
      <el-segmented v-model="sourceMode" :options="sourceModeOptions" />
    </div>
    <div
      v-show="!isBookSource || sourceMode === 'json'"
      class="editor"
      :class="{ 'with-mode': isBookSource }"
    >
      <source-tab-form class="left" :config="config" />
      <tool-bar />
      <source-tab-tools class="right" />
    </div>
    <JsSourceEditor
      v-if="isBookSource"
      v-show="sourceMode === 'javascript'"
      class="js-editor"
      :active="sourceMode === 'javascript'"
    />
  </div>
  <div v-else class="authorization">
    <el-button
      type="primary"
      :icon="Key"
      :loading="authorizing"
      @click="authorize"
    >
      输入访问令牌
    </el-button>
  </div>
</template>
<script setup lang="ts">
import bookSourceConfig from '@/config/bookSourceEditConfig'
import rssSourceConfig from '@/config/rssSourceEditConfig'
import '@/assets/sourceeditor.css'
import { useDark } from '@vueuse/core'
import type { SourceConfig } from '@/config/sourceConfig'
import { Key } from '@element-plus/icons-vue'
import { ElSegmented } from 'element-plus'
import 'element-plus/es/components/segmented/style/css'
import { requestSourceApiToken } from '@/api/sourceToken'
import JsSourceEditor from '@/components/JsSourceEditor.vue'

useDark()

const store = useSourceStore()
let config: SourceConfig
const authorized = ref(false)
const authorizing = ref(false)

const authorize = async () => {
  if (authorizing.value) return
  authorizing.value = true
  try {
    await requestSourceApiToken()
    authorized.value = true
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    authorizing.value = false
  }
}
const isBookSource = ref<boolean>(/bookSource/i.test(location.href))
const sourceMode = computed<'json' | 'javascript'>({
  get: () => store.sourceMode,
  set: value => (store.sourceMode = value),
})
const sourceModeOptions = [
  { label: 'JSON 书源', value: 'json' },
  { label: 'JavaScript 书源', value: 'javascript' },
]
provide('isBookSource', isBookSource)
if (isBookSource.value) {
  config = bookSourceConfig as SourceConfig
  document.title = '书源管理'
} else {
  config = rssSourceConfig as SourceConfig
  document.title = '订阅源管理'
}

onMounted(authorize)
</script>
<style lang="scss" scoped>
.source-editor {
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.source-mode {
  height: 48px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid var(--el-border-color-light);
}

.editor {
  display: flex;
  height: 100%;
  overflow: hidden;

  &.with-mode {
    height: calc(100% - 48px);
  }

  .left {
    flex: 1;
    min-width: 0;
    min-height: 0;
    margin-left: 20px;
  }
  .right {
    flex: 1;
    min-width: 0;
    min-height: 0;
    width: 360px;
    margin-right: 20px;
  }
}

@media screen and (max-width: 900px) {
  .source-editor {
    overflow: hidden;
  }

  .source-mode {
    position: sticky;
    top: 0;
    z-index: 3;
    background: var(--el-bg-color);
  }

  .editor {
    display: grid;
    grid-template-rows: minmax(0, 1fr) auto minmax(0, 1fr);
    overflow: hidden;

    .left,
    .right {
      flex: none;
      width: auto;
      height: auto;
      min-height: 0;
      margin: 0 12px;
    }

    .right {
      margin-bottom: 12px;
    }
  }

  .editor.with-mode {
    height: calc(100vh - 48px);
    height: calc(100dvh - 48px);
  }
}

.js-editor {
  height: calc(100% - 48px);
}

.authorization {
  height: 100vh;
  height: 100dvh;
  display: grid;
  place-items: center;
}
</style>
