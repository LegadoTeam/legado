import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const dialog = readFileSync(
  new URL('../src/components/ReviewDialog.vue', import.meta.url),
  'utf8',
)

test('shows reply likes only when the count is positive', () => {
  assert.match(
    dialog,
    /reply\.time \|\|\s*\(reply\.likeCount != null && reply\.likeCount > 0\)/,
  )
  assert.equal(
    dialog.match(/reply\.likeCount != null && reply\.likeCount > 0/g)?.length,
    2,
  )
  assert.doesNotMatch(dialog, /v-if="reply\.likeCount != null"/)
})

test('shows the reply target before reply content', () => {
  assert.match(dialog, /v-if="reply\.replyToName" class="reply-target"/)
  assert.match(dialog, /回复 \{\{ reply\.replyToName \}\}：<\/span>\{\{ reply\.content \}\}/)
})

test('opens embedded replies without auto-loading empty reply groups', () => {
  assert.match(dialog, /:open="item\.replyItems\.length > 0"/)
  assert.match(
    dialog,
    /details\.open && item\.replyItems\.length === 0\) void loadReplies\(item\)/,
  )
})
