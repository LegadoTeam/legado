<template>
  <el-dialog
    v-model="visible"
    class="review-dialog"
    width="min(680px, calc(100vw - 32px))"
    append-to-body
    destroy-on-close
    @click.stop
    @closed="previewUrl = ''"
  >
    <template #header>
      <div class="dialog-title">
        <ChatDotRound aria-hidden="true" />
        <span>第 {{ target?.paraIndex }} 段 · {{ target?.count }} 条段评</span>
      </div>
    </template>

    <div class="review-body" @click.stop>
      <div
        v-if="loading && items.length === 0"
        class="status"
        role="status"
        aria-live="polite"
      >
        <Loading class="spin" aria-hidden="true" />
        <span>正在加载段评</span>
      </div>

      <div
        v-else-if="error && items.length === 0"
        class="status error"
        role="alert"
      >
        <span>{{ error }}</span>
        <el-button @click.stop="retryDetails">
          <RefreshRight aria-hidden="true" />
          重试
        </el-button>
      </div>

      <div v-else-if="items.length === 0" class="status">暂无段评</div>

      <template v-else>
        <article v-for="item in items" :key="item.key" class="review-item">
          <header class="author">
            <div class="avatar">
              <img
                v-if="item.avatar"
                :src="proxyImageUrl(item.avatar, 48)"
                alt=""
                loading="lazy"
              />
              <span v-else>{{ avatarInitial(item.name) }}</span>
            </div>
            <div class="author-text">
              <strong>{{ item.name || '匿名用户' }}</strong>
              <div v-if="item.badges?.length" class="badges">
                <template v-for="badge in item.badges || []" :key="badge">
                  <img
                    v-if="isImageBadge(badge)"
                    class="badge-image"
                    :src="proxyImageUrl(badge, 160)"
                    alt=""
                    loading="lazy"
                  />
                  <span v-else>{{ badge }}</span>
                </template>
              </div>
            </div>
          </header>

          <p v-if="item.content" class="review-content">{{ item.content }}</p>
          <img
            v-if="item.imageUrl"
            class="review-image"
            :src="proxyImageUrl(item.imageUrl, 620)"
            alt="段评图片"
            loading="lazy"
            role="button"
            tabindex="0"
            @click.stop="openImage(item.imageUrl)"
            @keydown.enter.stop="openImage(item.imageUrl)"
            @keydown.space.stop.prevent="openImage(item.imageUrl)"
          />
          <div v-if="item.time || item.likeCount != null" class="meta">
            <span v-if="item.time">{{ item.time }}</span>
            <span v-if="item.likeCount != null">{{ item.likeCount }} 赞</span>
          </div>

          <details
            v-if="hasReplies(item)"
            class="reply-details"
            :open="item.replyItems.length > 0"
            @toggle="onReplyToggle($event, item)"
            @click.stop
          >
            <summary>{{ replyTotal(item) }} 条回复</summary>
            <div class="replies">
              <article
                v-for="(reply, index) in item.replyItems"
                :key="`${reply.id || 'reply'}-${index}`"
                class="reply-item"
              >
                <header class="reply-author">
                  <strong>{{ reply.name || '匿名用户' }}</strong>
                  <template v-for="badge in reply.badges || []" :key="badge">
                    <img
                      v-if="isImageBadge(badge)"
                      class="badge-image"
                      :src="proxyImageUrl(badge, 160)"
                      alt=""
                      loading="lazy"
                    />
                    <span v-else>{{ badge }}</span>
                  </template>
                </header>
                <p v-if="reply.content" class="review-content">
                  <span v-if="reply.replyToName" class="reply-target">回复 {{ reply.replyToName }}：</span>{{ reply.content }}
                </p>
                <img
                  v-if="reply.imageUrl"
                  class="review-image"
                  :src="proxyImageUrl(reply.imageUrl, 560)"
                  alt="回复图片"
                  loading="lazy"
                  role="button"
                  tabindex="0"
                  @click.stop="openImage(reply.imageUrl)"
                  @keydown.enter.stop="openImage(reply.imageUrl)"
                  @keydown.space.stop.prevent="openImage(reply.imageUrl)"
                />
                <div
                  v-if="
                    reply.time ||
                    (reply.likeCount != null && reply.likeCount > 0)
                  "
                  class="meta"
                >
                  <span v-if="reply.time">{{ reply.time }}</span>
                  <span v-if="reply.likeCount != null && reply.likeCount > 0">
                    {{ reply.likeCount }} 赞
                  </span>
                </div>
              </article>

              <div v-if="item.replyLoading" class="reply-status">
                <Loading class="spin" aria-hidden="true" />
                正在加载回复
              </div>
              <div v-else-if="item.replyError" class="reply-status error">
                <span>{{ item.replyError }}</span>
                <el-button text @click.stop="loadReplies(item)">
                  <RefreshRight aria-hidden="true" />
                  重试
                </el-button>
              </div>
              <el-button
                v-else-if="!item.replyDone"
                text
                class="reply-more"
                @click.stop="loadReplies(item)"
              >
                <ArrowDown aria-hidden="true" />
                加载更多回复
              </el-button>
              <div
                v-else-if="item.replyItems.length === 0"
                class="reply-status"
              >
                暂无可加载回复
              </div>
            </div>
          </details>
        </article>

        <div v-if="error" class="load-more-error error">{{ error }}</div>
        <el-button
          v-if="hasMore || loading"
          class="load-more"
          :disabled="loading"
          @click.stop="error ? retryDetails() : loadDetails()"
        >
          <Loading v-if="loading" class="spin" aria-hidden="true" />
          <ArrowDown v-else aria-hidden="true" />
          {{ loading ? '正在加载' : error ? '重新加载' : '加载更多' }}
        </el-button>
      </template>
    </div>
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
import API from '@api'
import type { ReviewItem, ReviewTarget } from '@/book'
import {
  ArrowDown,
  ChatDotRound,
  Loading,
  RefreshRight,
} from '@element-plus/icons-vue'

const props = defineProps<{
  modelValue: boolean
  target: ReviewTarget | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})

type ReviewView = ReviewItem & {
  key: string
  replyItems: ReviewItem[]
  replyPage: number
  replyLoading: boolean
  replyError: string
  replyDone: boolean
}

const store = useBookStore()
const bookUrl = computed(() => store.readingBook.bookUrl)
const items = ref<ReviewView[]>([])
const page = ref(1)
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref('')
const previewUrl = ref('')
let requestVersion = 0
let itemSequence = 0

const reviewIdentity = (item: ReviewItem) =>
  item.id?.trim() ||
  [
    item.avatar,
    item.name,
    item.replyToName,
    item.content,
    item.imageUrl,
    item.audioUrl,
    item.time,
  ]
    .map(value => value || '')
    .join('\u0000')

const toReviewView = (item: ReviewItem): ReviewView => {
  const replies = [...(item.replies || [])]
  const reviewId = item.id?.trim()
  return {
    ...item,
    key: `${item.id || 'review'}-${itemSequence++}`,
    replyItems: replies,
    replyPage: 1,
    replyLoading: false,
    replyError: '',
    replyDone:
      !reviewId ||
      item.replyCount == null ||
      replies.length >= item.replyCount,
  }
}

const reset = () => {
  requestVersion++
  items.value = []
  page.value = 1
  nextCursor.value = null
  hasMore.value = false
  loading.value = false
  error.value = ''
  previewUrl.value = ''
  itemSequence = 0
}

const loadDetails = async () => {
  const target = props.target
  if (!target || loading.value) return
  const version = requestVersion
  loading.value = true
  error.value = ''
  try {
    const response = await API.getReviewDetail(
      bookUrl.value,
      target.chapterIndex,
      target.paraIndex,
      target.paraData,
      page.value,
      nextCursor.value,
    )
    if (version !== requestVersion) return
    if (!response.data.isSuccess) {
      error.value = response.data.errorMsg || '加载段评失败'
      return
    }
    const result = response.data.data
    const seen = new Set(items.value.map(reviewIdentity))
    const nextItems = (result.items || []).filter(item => {
      const key = reviewIdentity(item)
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    items.value.push(...nextItems.map(toReviewView))
    page.value++
    nextCursor.value = result.nextCursor || null
    hasMore.value = result.hasMore && nextItems.length > 0
  } catch {
    if (version === requestVersion) error.value = '加载段评失败，请重试'
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

const retryDetails = () => {
  reset()
  void loadDetails()
}

const loadReplies = async (item: ReviewView) => {
  const target = props.target
  const reviewId = item.id?.trim()
  if (!target || !reviewId || item.replyLoading || item.replyDone) return
  const version = requestVersion
  item.replyLoading = true
  item.replyError = ''
  try {
    const response = await API.getReviewReplies(
      bookUrl.value,
      target.chapterIndex,
      target.paraIndex,
      target.paraData,
      reviewId,
      item.replyPage,
    )
    if (version !== requestVersion) return
    if (!response.data.isSuccess) {
      item.replyError = response.data.errorMsg || '加载回复失败'
      return
    }
    const result = response.data.data
    const replies = result.items || []
    const seen = new Set(item.replyItems.map(reviewIdentity))
    const nextReplies = replies.filter(reply => {
      const key = reviewIdentity(reply)
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    item.replyItems.push(...nextReplies)
    item.replyPage++
    item.replyDone =
      !result.hasMore ||
      nextReplies.length === 0 ||
      (item.replyCount != null && item.replyItems.length >= item.replyCount)
  } catch {
    if (version === requestVersion) item.replyError = '加载回复失败，请重试'
  } finally {
    if (version === requestVersion) item.replyLoading = false
  }
}

const onReplyToggle = (event: Event, item: ReviewView) => {
  const details = event.currentTarget as HTMLDetailsElement
  if (details.open && item.replyItems.length === 0) void loadReplies(item)
}

const replyTotal = (item: ReviewView) =>
  Math.max(item.replyCount || 0, item.replyItems.length)

const hasReplies = (item: ReviewView) =>
  replyTotal(item) > 0 && (!!item.id?.trim() || item.replyItems.length > 0)

const avatarInitial = (name?: string | null) => name?.trim().slice(0, 1) || '匿'

const isImageBadge = (badge: string) => /^(?:data:|blob:|https?:\/\/)/i.test(badge)

const proxyImageUrl = (url: string, width: number) => {
  if (url.startsWith('data:') || url.startsWith('blob:')) return url
  return API.getProxyImageUrl(bookUrl.value, url, width)
}

const openImage = (url: string) => {
  previewUrl.value = proxyImageUrl(url, 2048)
}

watch(visible, open => {
  reset()
  if (open) void loadDetails()
})
</script>

<style lang="scss" scoped>
.dialog-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding-right: 32px;
  font-size: 18px;
  font-weight: 600;
  overflow-wrap: anywhere;

  svg {
    flex: 0 0 auto;
    width: 21px;
    height: 21px;
    color: var(--el-color-primary);
  }
}

.review-body {
  min-height: 160px;
}

.status {
  min-height: 160px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--el-text-color-secondary);

  svg {
    width: 24px;
    height: 24px;
  }
}

.error {
  color: var(--el-color-danger);
}

.review-item {
  padding: 18px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);

  &:first-child {
    padding-top: 2px;
  }
}

.author {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 50%;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.author-text {
  min-width: 0;

  strong {
    display: block;
    overflow-wrap: anywhere;
  }
}

.badges,
.reply-author {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.badges span,
.reply-author span {
  color: var(--el-color-primary);
  font-size: 12px;
}

.badge-image {
  width: auto;
  max-width: 140px;
  height: 20px;
  object-fit: contain;
}

.review-content {
  margin: 12px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.reply-target {
  color: var(--el-text-color-secondary);
}

.review-image {
  display: block;
  max-width: 100%;
  max-height: 60vh;
  margin-top: 12px;
  object-fit: contain;
  cursor: zoom-in;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.reply-details {
  margin-top: 12px;

  summary {
    width: fit-content;
    color: var(--el-color-primary);
    cursor: pointer;
  }
}

.replies {
  margin-top: 10px;
  padding-left: 16px;
  border-left: 2px solid var(--el-border-color-lighter);
}

.reply-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}

.reply-status,
.load-more-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 0;

  svg {
    width: 16px;
    height: 16px;
  }
}

.reply-more,
.load-more {
  display: flex;
  margin: 12px auto 0;

  svg {
    width: 16px;
    height: 16px;
    margin-right: 5px;
  }
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

:global(.review-dialog .el-dialog__body) {
  max-height: 72vh;
  overflow-y: auto;
}

@media screen and (max-width: 520px) {
  .review-item {
    padding: 15px 0;
  }

  :global(.review-dialog .el-dialog__body) {
    max-height: 76vh;
    padding: 12px 16px 18px;
  }
}
</style>
