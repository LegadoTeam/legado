# 自动任务

## Cron 表达式

自动任务按五段 Cron 表达式运行 JavaScript：

```txt
分钟 小时 日期 月份 星期
```

各字段支持数字、`*`、列表、范围和步长。星期使用 `0-7`，其中 `0` 和 `7` 都表示星期日。日期与星期字段同时受限时，任一字段匹配就会执行任务。

示例：

```txt
*/30 * * * *    每 30 分钟
0 9 * * 1-5     周一至周五 09:00
0 9 1,15 * *    每月 1 日和 15 日 09:00
```

系统会保存下一次任务，设备重启后仍会继续调度；受 Android 电量管理影响，实际执行时间可能延迟。关闭“运行自动任务”会取消等待中的任务。

## 返回协议

脚本可使用书源 JavaScript 相同的 `java`、`source`、`sourceApi`、`cookie` 和 `cache` 绑定，也支持 `@js:` 与 `<js>...</js>` 包装。

脚本可返回一个动作对象、动作数组，或带有 `actions` 数组的对象。`notify` 用于发送通知，`{task}` 和 `{time}` 会替换为任务名与执行时间：

```js
{"type":"notify","title":"任务完成","content":"{task} 已于 {time} 完成"}
```

`notify` 还可设置 `level`（`high`、`error`、`fail`、`failed` 或 `low`）和整数 `id`；相同 `id` 会复用同一通知位置。

## 更新书籍目录

`refreshToc` 按书籍地址刷新目录，可在新增章节达到指定数量时通知，并缓存新增正文：

```js
{"type":"refreshToc","bookUrl":"BOOK_URL","notify":{"enable":true,"minCount":1},"cache":{"enable":true}}
```

`respectCanUpdate` 默认为 `false`；设为 `true` 时，已关闭目录更新的书籍会跳过刷新。`notify` 对象还支持自定义 `title` 和 `content`，其中可使用 `{book}`、`{author}`、`{newCount}`、`{chapter}` 和 `{time}` 占位符。

`cache.enable` 为 `true` 时，自动任务会在当前任务中顺序缓存新增的非卷章节。每章最多尝试 3 次，全部新增章尝试完成后仍有失败会将本次任务标记为失败。

## 调试

启用任务前建议先调试。系统通知被关闭或无通知权限时会跳过通知，不会把任务判定为失败。
