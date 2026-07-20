<template>
  <div v-if="authorized" class="editor">
    <source-tab-form class="left" :config="config" />
    <tool-bar />
    <source-tab-tools class="right" />
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
import {
  clearSourceApiToken,
  requestSourceApiToken,
} from '@/api/sourceToken'

useDark()

let config: SourceConfig
const authorized = ref(false)
const authorizing = ref(false)

const authorize = async () => {
  if (authorizing.value) return
  authorizing.value = true
  try {
    await requestSourceApiToken({ force: true })
    authorized.value = true
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    authorizing.value = false
  }
}
const isBookSource = ref<boolean>(/bookSource/i.test(location.href))
provide('isBookSource', isBookSource)
if (isBookSource.value) {
  config = bookSourceConfig as SourceConfig
  document.title = '书源管理'
} else {
  config = rssSourceConfig as SourceConfig
  document.title = '订阅源管理'
}

onMounted(authorize)
onUnmounted(clearSourceApiToken)
</script>
<style lang="scss" scoped>
.editor {
  display: flex;
  height: 100vh;
  overflow: hidden;
  .left {
    flex: 1;
    margin-left: 20px;
  }
  .right {
    flex: 1;
    width: 360px;
    margin-right: 20px;
  }
}

.authorization {
  height: 100vh;
  display: grid;
  place-items: center;
}
</style>
