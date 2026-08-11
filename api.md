# 阅读[API](/app/src/main/java/io/legado/app/api/controller)

## 对于[Web](/app/src/main/java/io/legado/app/web/)的配置

您需要先在设置中启用"Web 服务"。

> Web 服务默认监听手机网络接口，多数接口不提供身份认证，请仅在可信局域网中启用，使用后及时关闭。“访问令牌保护”默认开启，保护书源写入、搜索、调试、HTTP 日志和 MCP 接口；关闭后这些接口均可不带令牌访问，仅应在可信网络中使用。

保护开启时，旧 JSON 书源写入接口也必须通过 `X-Legado-Token` 提供令牌。搜索和调试 WebSocket 在 Upgrade 握手时使用 `Sec-WebSocket-Protocol: legado, legado.token.<令牌 UTF-8 字节的 base64url，无填充>`；固定协议必须位于第一项，服务端会在读取任何 WebSocket 帧前完成验证。浏览器同源状态不作为身份凭据；Web 页面中的令牌只保存在当前浏览器标签页会话中，关闭标签页或切换服务地址后需要重新输入。关闭访问令牌保护时，WebSocket 只需使用固定协议 `legado`。

## 使用

### Web

以下说明假设您的操作在本机进行，且开放端口为1234。  
如果您要从远程计算机访问[阅读]()，请将`127.0.0.1`替换成手机IP。

#### 插入单个书源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)

```
URL = http://127.0.0.1:1234/saveBookSource
Method = POST
```

#### 插入纯 JavaScript 单文件书源

请求 BODY 为纯 JavaScript 书源脚本文本，`Content-Type` 使用 `text/plain; charset=utf-8`，最大 1 MiB，并且必须提供正确的 `Content-Length`。HTTP 层会去除脚本文本首尾空白，应用随后复用编辑器的提取、校验和保存逻辑；等待其他保存和解析脚本各自最多 30 秒。

访问令牌保护开启时，请先在“设置 > 其他设置 > Web 与 MCP 访问令牌”中配置令牌，并通过 `X-Legado-Token` 请求头提供完全相同的值。令牌验证会在读取请求体之前完成。令牌不会进入应用备份，恢复或更换设备后需要重新配置。关闭保护后可省略令牌；使用明文 HTTP 时仍只能在可信网络中使用。

覆盖已有书源时会保留启用状态、发现开关、排序、权重、响应时间，以及脚本未声明时的原分组。书源有实质变化时会更新时间并回写脚本中的 `lastUpdateTime`；内容未变化时保留原时间。

接口按脚本声明的 `bookSourceUrl` 新建或覆盖。编辑已有脚本时可通过 `openedSourceUrl` 查询参数传入原书源 URL，以获得与应用内编辑器一致的改名和冲突检查语义。

```
URL = http://127.0.0.1:1234/saveJsSource?openedSourceUrl=原书源URL（可选）
Method = POST
Content-Type = text/plain; charset=utf-8
X-Legado-Token = 设置中配置的令牌（访问令牌保护开启时）
```

#### 插入多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1234/saveBookSources
URL = http://127.0.0.1:1234/saveRssSources
Method = POST
```

#### 获取书源

```
URL = http://127.0.0.1:1234/getBookSource?url=xxx
URL = http://127.0.0.1:1234/getRssSource?url=xxx
Method = GET
``` 

#### 获取所有书源or订阅源

```
URL = http://127.0.0.1:1234/getBookSources
URL = http://127.0.0.1:1234/getRssSources
Method = GET
```

#### 删除多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1234/deleteBookSources
URL = http://127.0.0.1:1234/deleteRssSources
Method = POST
```

#### 调试源

key为书源搜索关键词，tag为源链接

```
URL = ws://127.0.0.1:1235/bookSourceDebug
URL = ws://127.0.0.1:1235/rssSourceDebug
Message = { key: [String], tag: [String] }
```

#### HTTP 请求日志

HTTP 日志仅在设置中启用“记录 HTTP 日志”后写入内存，最多保留最近 50 条；请求和响应正文单次最多记录 8 KiB，
记录会在写入时脱敏认证信息、Cookie 和常见密钥字段。完整日志仍可能包含敏感业务数据，因此访问令牌保护开启时，
两个接口都要求通过 `X-Legado-Token` 提供访问令牌；关闭保护后也只应在可信局域网中使用。

```text
URL = http://127.0.0.1:1234/getHttpLogs?limit=50
URL = http://127.0.0.1:1234/getHttpLog?id=1
Method = GET
X-Legado-Token = 设置中配置的令牌（访问令牌保护开启时）
```

`getHttpLogs` 返回 `{ recording, logs }`，其中 `logs` 为摘要列表；`getHttpLog` 按 id 返回完整的已脱敏记录。

#### MCP 服务

应用可直接提供 Streamable HTTP MCP 服务，默认端口为 `1236`，端点为 `/mcp`。服务与 Web 服务相互独立，
并复用“Web 与 MCP 访问令牌”。访问令牌保护开启时，启动前必须先配置令牌，所有 MCP 请求都必须携带 `X-Legado-Token`；关闭保护后 MCP 可在未配置令牌时启动，请求也可省略该请求头。
无论是否开启令牌保护，服务都保留 SDK 的 Host 和 Origin 校验，只允许本机地址与设备当前局域网地址。服务只应在可信局域网中使用。
端点面向可设置自定义请求头的本地或桌面客户端，不提供浏览器跨域 CORS 预检。

通用 HTTP MCP 客户端可按以下字段配置：

```json
{
  "url": "http://<设备IP>:1236/mcp",
  "headers": {
    "X-Legado-Token": "设置中配置的令牌（关闭访问令牌保护时可省略）"
  }
}
```

服务提供 13 个工具：`save_source`、`debug_source`、`list_sources`、`get_source`、`delete_sources`、
`get_http_logs`、`get_http_log`、`set_http_log_recording`、`get_cookies`、`set_cookie`、
`clear_cookies`、`eval_js`、`check_source`。书源写入、删除、调试、日志开关、Cookie 持久层写入和清理、
脚本求值及批量校验均可能修改应用状态；`check_source` 会按当前应用配置更新书源分组、错误备注和响应时间。
校验期间若书源被删除或重新保存，该项会标记为未完成，旧校验结果不会覆盖新记录。
客户端取消 MCP 请求不会中止已经启动的应用内校验，可在应用的校验通知中手动停止。
书源全文、未脱敏 Cookie 与已脱敏 HTTP 日志仍可能包含敏感业务数据，请只向可信客户端开放服务。
`debug_source` 返回的调试输出不会脱敏，也可能包含请求参数、书源正文或其他敏感内容。
调试期间会发送 `notifications/message` 日志；请求 `_meta.progressToken` 时还会同步发送
`notifications/progress`。通知超时或客户端不支持通知不会影响最终的完整有界日志结果。
`check_source` 也会在每个书源得到明确结果后发送日志和可选进度通知，最终汇总仍以该次校验会话的结果快照为准。
`get_cookies` 返回持久层与会话层合并后的未脱敏 Cookie；`set_cookie` 只合并写入持久层，
同名会话 Cookie 在当前会话中仍优先；`clear_cookies` 会同时清理持久层、会话层和 WebView Cookie。
`eval_js` 可在应用书源环境执行任意 JavaScript，令牌等同于书源脚本执行权限；求值结果和 `java.log`
输出不会脱敏，也可能访问网络、Cookie、缓存及已绑定书源的数据。传入书源 URL 只绑定其运行时身份，
不会自动执行该书源的 `mainJs`。

服务还会将应用内置 Markdown 帮助文档作为只读 resources 暴露，URI 格式为 `legado://help/<文件名>`，
例如 `legado://help/jsHelp` 和 `legado://help/ruleHelp`。客户端可先列出 resources，再按 URI 读取；
返回内容使用 UTF-8 和 `text/markdown`，不会修改应用状态。

#### 获取替换规则

```
URL = http://127.0.0.1:1234/getReplaceRules
Method = GET
```

#### 替换规则管理

请求BODY内容为`JSON`字符串，  
替换规则参考[这个文件](/app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt)。

##### 删除

```
URL = http://127.0.0.1:1234/deleteReplaceRule
Method = POST
Body = [ReplaceRule]
```
##### 插入

```
URL = http://127.0.0.1:1234/saveReplaceRule
Method = POST
Body = [ReplaceRule]
```

##### 测试

返回测试文本text替换结果

```
URL = http://127.0.0.1:1234/testReplaceRule
Method = POST
Body = { rule: [ReplaceRule], text: [String] }
```

#### 搜索在线书籍

若想获取对应的书籍的目录正文 请先**插入书籍**以启用缓存，如果试读后决定不添加到书籍，请**删除书籍**

```
URL = ws://127.0.0.1:1235/searchBook
Message = { key: [String] }
```

#### 插入书籍

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/Book.kt)。

```
URL = http://127.0.0.1:1234/saveBook
Method = POST
```

#### 删除书籍

```
URL = http://127.0.0.1:1234/deleteBook
Method = POST
```

#### 获取所有书籍

```
URL = http://127.0.0.1:1234/getBookshelf
Method = GET
```

获取APP内的所有书籍。

#### 获取书籍章节列表

```
URL = http://127.0.0.1:1234/getChapterList?url=xxx
Method = GET
```

获取指定图书的章节列表。

#### 获取书籍内容

```
URL = http://127.0.0.1:1234/getBookContent?url=xxx&index=1
Method = GET
```

获取指定图书的第`index`章节的文本内容。

#### 获取封面

```
URL = http://127.0.0.1:1234/cover?path=xxxxx
Method = GET
```

#### 获取正文图片

```
URL = http://127.0.0.1:1234/image?url=${bookUrl}&path=${picUrl}&width=${width}
Method = GET
```

#### 保存书籍进度

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookProgress.kt)。

```
URL = http://127.0.0.1:1234/saveBookProgress
Method = POST
```

### [Content Provider](/app/src/main/java/io/legado/app/api/ReaderProvider.kt)


* 需声明`io.legado.READ_WRITE`权限
* `providerHost`为`包名.readerProvider`, 如`io.legado.app.release.readerProvider`,不同包的地址不同,防止冲突安装失败
* 以下出现的`providerHost`请自行替换

#### 插入单个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)

```
URL = content://providerHost/bookSource/insert
URL = content://providerHost/rssSource/insert
Method = insert
```

#### 插入多个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = content://providerHost/bookSources/insert
URL = content://providerHost/rssSources/insert
Method = insert
```

#### 获取书源or订阅源

获取指定URL对应的书源信息。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/bookSource/query?url=xxx
URL = content://providerHost/rssSource/query?url=xxx
Method = query
```

#### 获取所有书源or订阅源

获取APP内的所有订阅源。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/bookSources/query
URL = content://providerHost/rssSources/query
Method = query
```

#### 删除多个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = content://providerHost/bookSources/delete
URL = content://providerHost/rssSources/delete
Method = delete
```

#### 插入书籍

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/Book.kt)。

```
URL = content://providerHost/book/insert
Method = insert
```

#### 获取所有书籍

获取APP内的所有书籍。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/books/query
Method = query
```

#### 获取书籍章节列表

获取指定图书的章节列表。   
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/book/chapter/query?url=xxx
Method = query
```

#### 获取书籍内容

获取指定图书的第`index`章节的文本内容。     
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/book/content/query?url=xxx&index=1
Method = query
```

#### 获取封面

```
URL = content://providerHost/book/cover/query?path=xxxx
Method = query
```
