<template>
  <div class="title" data-chapterpos="0">
    <span class="title-text">{{ title }}</span>
    <button
      v-if="reviewCount(-1) > 0"
      type="button"
      class="review-trigger"
      :aria-label="`查看标题的 ${reviewCount(-1)} 条段评`"
      :title="`查看 ${reviewCount(-1)} 条段评`"
      @click.stop="openReview(-1)"
    >
      <ChatDotRound aria-hidden="true" />
      <span>{{ reviewCount(-1) }}</span>
    </button>
  </div>
  <div
    v-for="(para, index) in contents"
    :key="index"
    class="paragraph"
    ref="paragraphRef"
    :data-chapterpos="chapterPos[index]"
  >
    <img
      class="full"
      v-if="/^\s*<img[^>]*src[^>]+>$/.test(String(para))"
      :src="getImageSrc(para)"
      @error.once="proxyImage"
      @click="handleReviewImageClick"
      loading="lazy"
    />
    <p v-else-if="isPlainText(para)" :style="{ fontFamily, fontSize }">{{ para }}</p>
    <p
      v-else
      :style="{ fontFamily, fontSize }"
      v-html="sanitizeContent(para)"
      @error.capture="handleImgLoadError"
      @click="handleReviewImageClick"
    />
    <button
      v-if="reviewCount(index + 1) > 0"
      type="button"
      class="review-trigger"
      :aria-label="`查看第 ${index + 1} 段的 ${reviewCount(index + 1)} 条段评`"
      :title="`查看 ${reviewCount(index + 1)} 条段评`"
      @click.stop="openReview(index + 1)"
    >
      <ChatDotRound aria-hidden="true" />
      <span>{{ reviewCount(index + 1) }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { isLegadoUrl, lazyRegex } from '@/utils/utils'
import API from '@api'
import jump from '@/plugins/jump'
import { parseLegacyReviewClick } from '@/utils/reviewClick'
import type { LegacyReviewClick } from '@/utils/reviewClick'
import type { ParagraphReview, ReviewTarget } from '@/book'
import type { webReadConfig } from '@/web'
import DOMPurify from 'dompurify'
import { ChatDotRound } from '@element-plus/icons-vue'

const store = useBookStore()
const readWidth = computed(() => store.config.readWidth)
const lineImgWidth = computed(() => store.config.fontSize * 2)
const bookUrl = computed(() => store.readingBook.bookUrl)

const props = defineProps<{
  contents: Array<string>
  title: string
  spacing: webReadConfig['spacing']
  fontFamily: string
  fontSize: string
  chapterIndex: number
  reviews: Record<number, ParagraphReview>
}>()

const emit = defineEmits<{
  openReview: [target: ReviewTarget]
  openLegacyReview: [target: LegacyReviewClick & { chapterIndex: number }]
}>()

const reviewCount = (paraIndex: number) => props.reviews[paraIndex]?.count || 0

const openReview = (paraIndex: number) => {
  const review = props.reviews[paraIndex]
  if (!review || review.count <= 0) return
  emit('openReview', { ...review, chapterIndex: props.chapterIndex, paraIndex })
}

const handleReviewImageClick = (event: MouseEvent) => {
  const image = event.target
  if (!(image instanceof HTMLImageElement)) return
  const legacy = parseLegacyReviewClick(image.getAttribute('src') || '')
  if (!legacy) return

  event.stopPropagation()
  emit('openLegacyReview', { ...legacy, chapterIndex: props.chapterIndex })
}

const imgPatternStr = '<img[^>]*src=[\'"]([^\'"]*(?:[\'"][^>]+\\})?)[\'"][^>]*>'
const imgPattern = lazyRegex(imgPatternStr)
const imgPatternAll = lazyRegex(imgPatternStr, 'g')
const imgDataUrlPattern = lazyRegex('data:image[^;]+;base64,[^,]{39,}')

const replaceImage = (content: string) => {
  return content.replace(imgPatternAll(), (match, src) => {
    const dataUrl = src.match(imgDataUrlPattern())
    if (dataUrl) {
      return dataUrl[0]
    }
    if (isLegadoUrl(src)) {
      const proxySrc = API.getProxyImageUrl(
        bookUrl.value,
        src,
        lineImgWidth.value,
      )
      return match.replace(src, proxySrc)
    }
    return match
  })
}

const sanitizeContent = (content: string) => {
  if (!content.includes('<')) return content
  return DOMPurify.sanitize(replaceImage(content), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['form', 'iframe', 'object', 'embed', 'style'],
    FORBID_ATTR: ['srcdoc'],
  })
}
const isPlainText = (content: string) =>
  !content.includes('<') && !content.includes('&')

const getImageSrc = (content: string) => {
  const src = content.match(imgPattern())![1] //reg tested in template
  const dataUrl = src.match(imgDataUrlPattern())
  if (dataUrl) {
      return dataUrl[0] //现成的base64图片，去掉阅读格式后缀
  }
  if (isLegadoUrl(src))
    return API.getProxyImageUrl(
      bookUrl.value,
      src,
      readWidth.value,
    )
  return src
}
const proxyImage = (event: Event) => {
  /* 获取IMG标签原始的src
    <img src="/test" />
    假设location.href = http://example.com
    event.target.src 返回 http://example.com/test
    (event.target as HTMLImageElement)?.getAttribute("src")  返回/test
  */
  const src = (event.target as HTMLImageElement)?.getAttribute("src")
  if (src != null && src.length > 0) {
    (event.target as HTMLImageElement).src = API.getProxyImageUrl(
      bookUrl.value,
      src,
      readWidth.value,
    )
  }
}

/**
 * 处理传入的IMG标签错误事件，自动替换图片的代理链接
 */
const handleImgLoadError = (event: Event) => {
  const target = event.target
  if (target instanceof HTMLImageElement) {
    const srcUrl = target.getAttribute("src")
    console.log(
      "[ChapterContent]: IMG Load Error, replace src:",
      srcUrl,
      "=>",
      API.getProxyImageUrl(
        bookUrl.value,
        srcUrl ?? "",
        readWidth.value,
      )
    )
    proxyImage(event)
  }
}

const calculateWordCount = (paragraph: string) => {
  if (!paragraph.includes('<')) return paragraph.length
  //内嵌图片文字为1
  const imagePlaceHolder = ' '
  return paragraph.replace(imgPatternAll(), imagePlaceHolder).length
}
const chapterPos = computed(() => {
  let pos = -1
  return Array.from(props.contents, content => {
    pos += calculateWordCount(content) + 1 //计算上一段的换行符
    return pos
  })
})

const paragraphRef = ref<HTMLElement[]>()
const scrollToReadedLength = (length: number) => {
  if (length === 0) return
  const paragraphIndex = chapterPos.value.findIndex(
    wordCount => wordCount >= length,
  )
  if (paragraphIndex === -1) return
  nextTick(() => {
    jump(paragraphRef.value![paragraphIndex], {
      duration: 0,
    })
  })
}
defineExpose({
  scrollToReadedLength,
})
</script>

<style lang="scss" scoped>
.title {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 57px;
  font:
    24px / 32px PingFangSC-Regular,
    HelveticaNeue-Light,
    'Helvetica Neue Light',
    'Microsoft YaHei',
    sans-serif;

  .title-text {
    flex: 1 1 auto;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .review-trigger {
    flex: 0 0 auto;
    margin-top: 2px;
  }
}

.paragraph {
  position: relative;
}

.review-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 28px;
  margin: 2px 0 0 auto;
  padding: 3px 8px;
  color: var(--el-color-primary);
  font: inherit;
  font-size: 13px;
  background: transparent;
  border: 0;
  border-radius: 14px;
  cursor: pointer;

  &:hover {
    background: var(--el-color-primary-light-9);
  }

  &:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 2px;
  }

  svg {
    width: 17px;
    height: 17px;
  }
}

p {
  display: block;
  word-wrap: break-word;
  /*   word-break: break-all; */
  letter-spacing: calc(v-bind('props.spacing.letter') * 1em);
  line-height: calc(1 + v-bind('props.spacing.line'));
  margin: calc(v-bind('props.spacing.paragraph') * 1em) 0;

  :deep(img) {
    height: 1em;
  }
}

.full {
  display: block;
  width: 100%;
}
</style>
