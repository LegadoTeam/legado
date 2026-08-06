<template>
  <div class="menu flex-column-center">
    <el-button
      v-for="button in buttons"
      size="large"
      :key="button.name"
      :icon="button.icon"
      @click="button.action"
    >
      {{ button.name }}
    </el-button>
    <el-button
      size="large"
      :icon="Setting"
      @click="() => (hotkeysDialogVisible = true)"
      >快捷键</el-button
    >
  </div>
  <el-dialog
    v-model="hotkeysDialogVisible"
    width="min(720px, calc(100vw - 32px))"
    :show-close="false"
    :before-close="stopRecordKeyDown"
  >
    <template #header="{ titleClass, titleId }">
      <div class="hotkeys-header flex-space-between">
        <div :id="titleId" :class="titleClass">
          快捷键设置
          <span v-if="recordKeyDowning">
            <el-text> / 录入中 </el-text>
          </span>
        </div>
        <el-button
          :disabled="recordKeyDowning"
          @click="saveHotKeys"
          :icon="CircleCheckFilled"
          >保存</el-button
        >
      </div>
    </template>

    <div class="hotkeys-settings flex-column-center">
      <div
        v-for="(button, buttonIndex) in buttons"
        :key="button.name"
        class="hotkeys-item flex-space-between"
      >
        <span class="title"
          ><el-text>{{ button.name }}</el-text></span
        >
        <div class="hotkeys-item__content">
          <div v-for="(key, hotKeysIndex) in button.hotKeys" :key="key">
            <kbd>{{ key }}</kbd>
            <span v-if="hotKeysIndex + 1 < button.hotKeys.length">
              <el-text>+</el-text>
            </span>
          </div>
          <span v-if="button.hotKeys.length == 0">未设置</span>
        </div>
        <el-button
          :disabled="recordKeyDowning"
          text
          :icon="Edit"
          @click="recordKeyDown(buttonIndex)"
          >编辑</el-button
        >
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import API from '@api'
import {
  Check,
  CircleCheckFilled,
  Delete,
  DocumentAdd,
  Download,
  Edit,
  EditPen,
  Search,
  Setting,
  Upload,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import hotkeys from 'hotkeys-js'
import { getSourceName, isInvaildSource, normalizeSource } from '../utils/souce'

const store = useSourceStore()
const pull = () => {
  const loadingMsg = ElMessage({
    message: '加载中……',
    showClose: true,
    duration: 0,
  })
  API.getSources()
    .then(({ data }) => {
      if (data.isSuccess) {
        store.changeTabName('editList')
        store.saveSources(data.data)
        ElMessage({
          message: `成功拉取${data.data.length}条源`,
          type: 'success',
        })
      } else {
        ElMessage({
          message: data.errorMsg ?? '后端错误',
          type: 'error',
        })
      }
    })
    .finally(() => loadingMsg.close())
}

const push = () => {
  const sources = store.sources
  store.changeTabName('editList')
  if (sources.length === 0) {
    return ElMessage({
      message: '空空如也',
      type: 'info',
    })
  }
  ElMessage({
    message: '正在推送中',
    type: 'info',
  })
  API.saveSources(sources).then(({ data }) => {
    if (data.isSuccess) {
      const okData = data.data
      if (Array.isArray(okData)) {
        let failMsg = ``
        if (sources.length > okData.length) {
          failMsg = '\n推送失败的源将用红色字体标注!'
          store.setPushReturnSources(okData)
        }
        ElMessage({
          message: `批量推送源到「阅读3.0APP」\n共计: ${
            sources.length
          } 条\n成功: ${okData.length} 条\n失败: ${
            sources.length - okData.length
          } 条${failMsg}`,
          type: 'success',
        })
      }
    } else {
      ElMessage({
        message: `批量推送源失败!\nErrorMsg: ${data.errorMsg}`,
        type: 'error',
      })
    }
  })
}

const conver2Tab = () => {
  store.changeTabName('editTab')
  store.changeEditTabSource(store.currentSource)
}
const conver2Source = () => {
  store.changeCurrentSource(store.editTabSource)
}

const clearEdit = async () => {
  try {
    await ElMessageBox.confirm(
      '当前表单尚未保存的内容会丢失，是否继续？',
      '清空表单',
      {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  store.clearEdit()
  ElMessage.success('已清空表单')
}

const saveSource = () => {
  const source = store.currentSource
  if (isInvaildSource(source)) {
    normalizeSource(source)
    API.saveSource(source).then(({ data }) => {
      const sourceName = getSourceName(source)
      if (data.isSuccess) {
        ElMessage({
          message: `源《${sourceName}》已成功保存到「阅读3.0APP」`,
          type: 'success',
        })
        //save to store
        store.saveCurrentSource()
      } else {
        ElMessage({
          message: `源《${sourceName}》保存失败!\nErrorMsg: ${data.errorMsg}`,
          type: 'error',
        })
      }
    })
  } else {
    ElMessage({
      message: `请检查<必填>项是否全部填写`,
      type: 'error',
    })
  }
}

const debug = () => {
  store.startDebug()
}

const buttons = ref<
  {
    name: string
    icon: Component
    hotKeys: string[]
    action: () => void
  }[]
>(
  Array.of(
    { name: '推送源', icon: markRaw(Upload), hotKeys: [], action: push },
    { name: '拉取源', icon: markRaw(Download), hotKeys: [], action: pull },
    {
      name: '生成源',
      icon: markRaw(DocumentAdd),
      hotKeys: [],
      action: conver2Tab,
    },
    {
      name: '编辑源',
      icon: markRaw(EditPen),
      hotKeys: [],
      action: conver2Source,
    },
    {
      name: '清空表单',
      icon: markRaw(Delete),
      hotKeys: [],
      action: clearEdit,
    },
    { name: '调试源', icon: markRaw(Search), hotKeys: [], action: debug },
    { name: '保存源', icon: markRaw(Check), hotKeys: [], action: saveSource },
  ),
)
const hotkeysDialogVisible = ref(false)

const recordKeyDowning = ref(false)

const recordKeyDownIndex = ref(-1)

const stopRecordKeyDown = () => {
  if (!recordKeyDowning.value) {
    hotkeysDialogVisible.value = false
  }
  recordKeyDowning.value = false
}

watch(
  hotkeysDialogVisible,
  visibale => {
    if (!visibale) {
      hotkeys.unbind('*')
      readHotkeysConfig()
      bindHotKeys()
      return
    }
    readHotkeysConfig()
    hotkeys.unbind()
    /**监听按键 */
    hotkeys('*', event => {
      event.preventDefault()
      const pressedKeys = hotkeys.getPressedKeyString()
      if (pressedKeys.length == 1 && pressedKeys[0] == 'esc') {
        //单独按下esc 不录入
        return
      }
      if (recordKeyDowning.value && recordKeyDownIndex.value > -1)
        buttons.value[recordKeyDownIndex.value].hotKeys = pressedKeys
    })
  },
  { immediate: true },
)

const recordKeyDown = (index: number) => {
  recordKeyDowning.value = true
  ElMessage({
    message: '按ESC键或者点击空白处结束录入',
    type: 'info',
  })
  buttons.value[index].hotKeys = []
  recordKeyDownIndex.value = index
}

const saveHotKeys = () => {
  const hotKeysConfig: string[][] = []
  buttons.value.forEach(({ hotKeys }) => {
    hotKeysConfig.push(hotKeys)
  })
  saveHotkeysConfig(hotKeysConfig)
  hotkeysDialogVisible.value = false
}

const bindHotKeys = () => {
  // hotkeys默认过滤INPUT SELECT TEXTAREA
  hotkeys.filter = () => true
  buttons.value.forEach(({ hotKeys, action }) => {
    if (hotKeys.length == 0) return
    hotkeys(hotKeys.join('+'), event => {
      if (store.sourceMode !== 'json') return
      event.preventDefault()
      action.call(null)
    })
  })
}
const saveHotkeysConfig = (config: string[][]) => {
  localStorage.setItem('legado_web_hotkeys', JSON.stringify(config))
}

/**
 * 读取快捷键配置
 * @return 是否成功读取配置
 */
function readHotkeysConfig() {
  try {
    const localStorageConfig = localStorage.getItem('legado_web_hotkeys')
    if (localStorageConfig === null) return false
    const storedConfig = JSON.parse(localStorageConfig)
    const config =
      Array.isArray(storedConfig) && storedConfig.length === 9
        ? [0, 1, 2, 3, 4, 7, 8].map(index => storedConfig[index])
        : storedConfig
    if (
      !Array.isArray(config) ||
      config.length !== buttons.value.length ||
      config.some(keys => !Array.isArray(keys))
    ) {
      localStorage.removeItem('legado_web_hotkeys')
      return false
    }
    if (config !== storedConfig) saveHotkeysConfig(config)
    buttons.value.forEach((button, index) => (button.hotKeys = config[index]))
    return true
  } catch {
    ElMessage({ message: '快捷键配置错误', type: 'error' })
    localStorage.removeItem('legado_web_hotkeys')
  }
  return false
}
</script>

<style lang="scss" scoped>
.flex-space-between {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.flex-column-center {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.menu {
  justify-content: safe center;
  overflow-y: auto;
}

.menu > .el-button {
  flex: none;
  margin: 4px;
  padding: 1em;
  width: 8em;
}

.hotkeys-item {
  .title {
    width: 5em;
    display: flex;
    justify-content: flex-end;
    margin-right: 1em;
  }
  .hotkeys-item__content {
    display: flex;
    flex-wrap: wrap;
    flex: 1;
    div {
      margin-bottom: 1em;
    }
    span {
      margin: 0.5em;
    }
  }
}

@media screen and (max-width: 900px) {
  .menu {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 6px;
    padding: 10px 12px;
  }

  .menu > .el-button {
    width: auto;
    min-width: 7em;
    margin: 0;
  }
}
</style>
