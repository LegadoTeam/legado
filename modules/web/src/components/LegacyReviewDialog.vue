<template>
  <el-dialog
    v-model="visible"
    class="legacy-review-dialog"
    width="min(960px, calc(100vw - 32px))"
    append-to-body
    destroy-on-close
    @closed="previewUrl = ''"
    @click.stop
  >
    <template #header>
      <div class="legacy-review-title">
        <ChatDotRound aria-hidden="true" />
        <span>{{ kind === 'chapter' ? '章评' : '段评' }}</span>
      </div>
    </template>
    <iframe
      ref="frameRef"
      class="legacy-review-frame"
      :title="kind === 'chapter' ? '章评' : '段评'"
      :src="pageUrl"
      sandbox="allow-scripts allow-modals"
      allow="fullscreen"
      referrerpolicy="no-referrer"
    />
  </el-dialog>
  <el-image-viewer
    v-if="previewUrl"
    :url-list="[previewUrl]"
    teleported
    hide-on-click-modal
    @close="previewUrl = ''"
  />
</template>

<script setup lang="ts">
import { ChatDotRound } from '@element-plus/icons-vue'
import API from '@api'

const props = defineProps<{
  modelValue: boolean
  pageUrl: string
  sessionId: string
  sessionNonce: string
  kind: 'paragraph' | 'chapter'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})

const frameRef = ref<HTMLIFrameElement>()
const previewUrl = ref('')

const handleMessage = async (event: MessageEvent) => {
  const frameWindow = frameRef.value?.contentWindow
  const message = event.data
  if (
    !frameWindow ||
    !message ||
    event.source !== frameWindow ||
    message.nonce !== props.sessionNonce
  ) {
    return
  }

  if (message.type === 'legado-legacy-review-image') {
    if (typeof message.src === 'string' && message.src)
      previewUrl.value = message.src
    return
  }

  const replyPort = event.ports[0]
  if (
    !replyPort ||
    message.type !== 'legado-legacy-review-run' ||
    typeof message.script !== 'string'
  )
    return

  const sessionId = props.sessionId
  const sessionNonce = props.sessionNonce
  try {
    const response = await API.runLegacyReview(sessionId, message.script)
    if (sessionId !== props.sessionId || sessionNonce !== props.sessionNonce) return
    replyPort.postMessage({
      result: response.data.isSuccess ? response.data.data : undefined,
      error: response.data.isSuccess
        ? undefined
        : response.data.errorMsg || '评论加载失败',
    })
  } catch (error) {
    if (sessionId !== props.sessionId || sessionNonce !== props.sessionNonce) return
    replyPort.postMessage({
      error:
        (error instanceof Error ? error.message : String(error)) || '评论加载失败',
    })
  }
}

onMounted(() => window.addEventListener('message', handleMessage))
onBeforeUnmount(() => window.removeEventListener('message', handleMessage))
</script>

<style lang="scss">
.legacy-review-dialog {
  margin-top: 5vh;

  .el-dialog__body {
    padding: 0;
  }
}

.legacy-review-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;

  svg {
    width: 22px;
    height: 22px;
    color: var(--el-color-primary);
  }
}

.legacy-review-frame {
  display: block;
  width: 100%;
  height: min(82vh, 900px);
  background: var(--el-bg-color);
  border: 0;
}

@media screen and (max-width: 776px) {
  .legacy-review-dialog {
    width: 100vw !important;
    height: 100dvh;
    margin: 0;

    .el-dialog__header {
      margin-right: 0;
      padding: 14px 16px;
    }
  }

  .legacy-review-frame {
    height: calc(100dvh - 58px);
  }
}
</style>
