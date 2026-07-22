# js变量和函数
> 阅读使用 [HtmlUnit Core JS 5.3.0-legado.3](https://github.com/skybbk1001/htmlunit-core-js/tree/e31799f290b50f99fe2cef1f14acd9725f69653c) 提供的 Rhino 兼容 JavaScript 引擎，以便于[调用Java类和方法](https://m.jb51.net/article/92138.htm)

> [JavaScript运行时](https://github.com/skybbk1001/htmlunit-core-js/blob/e31799f290b50f99fe2cef1f14acd9725f69653c/src/repackaged-rhino/java/org/htmlunit/corejs/javascript/ScriptRuntime.java)懒加载导入的Java类和方法

|构造函数|函数|对象|调用类|简要说明|
|------|-----|------|----|------|
|JavaImporter|importClass importPackage| |[ImporterTopLevel](https://github.com/HtmlUnit/htmlunit-core-js/blob/master/src/main/java/org/htmlunit/corejs/javascript/ImporterTopLevel.java)|导入Java类到JavaScript|
||getClass|Packages java javax ...|[NativeJavaTopPackage](https://github.com/HtmlUnit/htmlunit-core-js/blob/master/src/main/java/org/htmlunit/corejs/javascript/NativeJavaTopPackage.java)|默认导入JavaScript中的Java类|
|JavaAdapter|||[JavaAdapter](https://github.com/HtmlUnit/htmlunit-core-js/blob/master/src/main/java/org/htmlunit/corejs/javascript/JavaAdapter.java)|继承Java类|

> 注意`java`变量指向已经被阅读修改，如果想要调用`java.*`下的包，请使用`Packages.java.*`

> 在书源规则中使用`@js` `<js>` `{{}}`可使用JavaScript调用阅读部分内置的类和方法

> 注意为了安全，阅读会屏蔽部分java类调用，见[RhinoClassShutter](https://github.com/LegadoTeam/legado/blob/master/modules/rhino/src/main/java/com/script/rhino/RhinoClassShutter.kt)　

> 不同的书源规则中支持的调用的Java类和方法可能有所不同

|变量名|调用类|
|------|-----|
|java|当前类|
|baseUrl|当前url,String  |
|result|上一步的结果|
|book|[书籍类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/data/entities/Book.kt)|
|rssArticle|[Article类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/data/entities/RssArticle.kt)|
|chapter|[章节类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BookChapter.kt)|
|source|[基础书源类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/data/entities/BaseSource.kt)|
|cookie|[cookie操作类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/help/http/CookieStore.kt)| 
|cache|[缓存操作类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/help/CacheManager.kt)|
|title|章节当前标题 String|
|src| 请求返回的源码|
|nextChapterUrl|下一章节url|
|isFromBookInfo|是否为详情页刷新|

## 当前类对象的可使用的部分方法
函数带有默认值的函数会自动重载，可以不填。  

### [RssJsExtensions](https://github.com/LegadoTeam/legado/blob/main/app/src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt)独有函数
> 在订阅源`shouldOverrideUrlLoading`规则中使用  
> 被下方`SourceLoginJsExtensions`类包含，也能使用这些函数  
> 订阅添加跳转url拦截, js, 返回true拦截,js变量url,可以通过js打开url  
> url跳转拦截规则不能执行耗时操作

* 调用阅读搜索  
```js
* @param key 搜索关键词
* @param searchScope 搜索作用域，为空时调用所以书源搜索
//searchScope作用域,形式为`源名称::源地址`、或者`,`符号隔开的源分组名称
//在书源调用时可写为java.searchBook(key, source)，仅本书源进行搜索
java.searchBook(key: String, searchScope: String? = null)
```

* 添加书架  
```js
java.addBook(bookUrl: String)
```

* 打开源界面  
```js
* @param name 为"sort"打开订阅源分类界面、为"rss"打开订阅源正文界面、为"explore"打开书源发现界面、"search"打开书籍搜索界面、"login"打开源登录界面
* @param url 为传递到界面的链接，"sort"时为分类链接、"rss"时为正文链接、"explore"时为发现链接，"search"、"login"时该参数无意义
//特别说明，"sort"时url可以传序列化后的键值对用来打开多个分类界面
* @param title 为对应界面的标题，"search"时为搜索关键词，"login"时该参数无意义
* @param origin 打开指定源界面的源地址
java.open(name: String, url: String? = null, title: String? = null, origin: String? = null)
```

* 展示图片  
```js
java.showPhoto(src: String)
```

### [SourceLoginJsExtensions](https://github.com/LegadoTeam/legado/blob/main/app/src/main/java/io/legado/app/ui/login/SourceLoginJsExtensions.kt)独有函数
> 只在`登录界面按钮`被触发、`界面按钮的回调`事件、`发现按钮`函数、`图片链接click键`、`购买规则`中有效
```js
//用内置浏览器打开本地html
* @param url 指定网页的基础URL，解决本地网页跨越问题
* @param html 加载的内容
* @param preloadJs 预注入js，用法同订阅源预注入js规则
* @param config 界面配置，json字符串，
java.showBrowser(url: String, html: String, preloadJs: String? = null, config: String? = null)
//复制文本到剪贴板
java.copyText(text: String)
//实时更新登录界面用户信息，upLoginData(null)会全部重置为默认值
java.upLoginData(data: Map<String, String?>?)
//刷新登录界面
java.reLoginView(deltaUp: Boolean = false)
//刷新书籍详情页
java.refreshBookInfo()
//刷新书籍目录页
java.refreshBookToc()
//刷新书籍正文内容
java.refreshContent()
//清除tts源的缓存，仅限tts源的登录界面
java.clearTtsCache()
//刷新发现，仅限发现按钮
java.refreshExplore()
```

`config` 高度配置示例：

```js
// 固定为 600 像素高
java.showBrowser(url, html, null, JSON.stringify({dialogHeight: 600}))

// 初始占屏幕高度 45%，上滑可展开到 90%
java.showBrowser(url, html, null, JSON.stringify({
    heightPercentage: 0.45,
    expandedHeightPercentage: 0.9
}))
```

`expandedHeight` 和 `expandedHeightPercentage` 分别表示展开后的像素高度和屏幕高度比例。展开高度必须大于 `dialogHeight` 或 `heightPercentage` 计算出的折叠高度，否则继续使用固定高度模式。百分比与像素值同时提供时优先使用百分比。

[showBrowser](https://github.com/Luoyacheng/legado/wiki/java.showBrowser%E5%87%BD%E6%95%B0%E4%BB%8B%E7%BB%8D)函数介绍

### [AnalyzeUrl](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt) 部分函数
> js中通过java.调用,只在`登录检查JS`规则中有效
```js
initUrl() //重新解析url,可以用于登录检测js登录后重新解析url重新访问
getHeaderMap().putAll(source.getHeaderMap(true)) //重新设置登录头
getStrResponse( jsStr: String? = null, sourceRegex: String? = null) //返回访问结果,文本类型,书源内部重新登录后可调用此方法重新返回结果
getResponse(): Response //返回访问结果,网络朗读引擎采用的是这个,调用登录后在调用这方法可以重新访问,参考阿里云登录检测
```

### [AnalyzeRule](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt) 部分函数
* 获取文本/文本列表
> `mContent` 待解析源代码，默认为当前页面  
> `isUrl` 链接标识，默认为`false`
```js
java.getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
java.getStringList(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false)
```
* 设置解析内容

```js
java.setContent(content: Any?, baseUrl: String? = null):
```

* 获取Element/Element列表

> 如果要改变解析源代码，请先使用`java.setContent`

```js
java.getElement(ruleStr: String)
java.getElements(ruleStr: String)
```

* 重新搜索书籍/重新获取目录url

> 只能在刷新目录之前使用,有些书源书籍地址和目录url会变

```js
java.reGetBook()
java.refreshTocUrl()
```
* 变量存取

```js
java.get(key)
java.put(key, value)
```

* 并发合并(single-flight)

> 同一书源、同一 name 的并发调用只执行一次 action，其他调用等待完成后跳过；action 失败时，后续调用可重新执行。timeoutMs 默认 15000，范围为 0 到 300000 毫秒。
> jsLib 中的函数如需访问 java/source/book 等执行对象，请使用 fn.bind(this) 绑定当前执行环境。

```js
java.singleFlight(name: String, action: Function, timeoutMs: Long = 15000)
```

* 互斥锁

> 同一书源、同一 name 的调用按顺序逐个执行，适合把读取、修改、写回组合成不可分割的操作；超时范围与 singleFlight 相同。

```js
java.lock(name: String, action: Function, timeoutMs: Long = 15000)
```

* 轮询计数器

> 返回同一书源、同一 name 的进程内非负递增序号，可用于多账号或多个地址轮流选择。计数器最多保留 4096 项，长期未使用的项目可能被回收并从 0 重新开始。

```js
java.tick(name: String): Int
```

### [js扩展类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/help/JsExtensions.kt) 部分函数

* 链接解析[JsURL](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/utils/JsURL.kt)　
```js
java.toURL(url: String, baseUrl: String? = null): JsURL
```
* 获取SystemWebView User-Agent
```js
java.getWebViewUA(): String
```
* 网络请求
```js

java.ajax(urlStr, callTimeout: Int? = null): String

* 并发访问网络
* @param skipRateLimit 为true时不受源并发率限制
java.ajaxAll(urlList: Array<String>, skipRateLimit: Boolean = false): Array<StrResponse>

//ajaxTestAll会忽略网络访问错误，错误类型由callTime()获取，对应的错误码值（-1超过设定时间，-2超时，-3域名错误，-4连接被拒绝，-5连接被重置，-6SSL证书错误，-7其它错误），无错误时callTime()为响应时间
java.ajaxTestAll(urlList: Array<String>, timeout: Int, skipRateLimit: Boolean = false): Array<StrResponse>

java.connect(urlStr, header = null, callTimeout: Int? = null): StrResponse
//返回的StrResponse对象具有的方法 body() code() message() headers() raw() toString() callTime()

java.post(url: String, body: String, headers: Map<String, String> | JSON String, timeout: Int? = null): Connection.Response

java.get(url: String, headers: Map<String, String> | JSON String, timeout: Int? = null): Connection.Response

java.head(url: String, headers: Map<String, String> | JSON String, timeout: Int? = null): Connection.Response

* 使用webView访问网络
* @param html 直接用webView载入的html, 如果html为空直接访问url
* @param url html内如果有相对路径的资源不传入url访问不了
* @param js 用来取返回值的js语句, 没有就返回整个源代码
* @param cacheFirst 优先使用缓存,为true能提高访问速度
* @param delayTime 延迟执行js的时间
* @return 返回js获取的内容
java.webView(html: String?, url: String?, js: String?, cacheFirst: Boolean = false): String?

* 使用webView获取跳转url
java.webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean = false, delayTime: Long = 0): String?

* 使用webView获取资源url
java.webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean = false, delayTime: Long = 0): String?

* 使用内置浏览器打开链接，可用于获取验证码 手动验证网站防爬
* @param url 要打开的链接
* @param title 浏览器的标题
* @param html 本地html代码
java.startBrowser(url: String, title: String, html: String? = null)

* 使用内置浏览器打开链接，并等待网页结果 .body()获取网页内容
* @param refetchAfterSuccess 为false时获取最终展示界面的源码
java.startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean = false, html: String? = null): StrResponse
```
* 调试
```js
java.log(msg)
java.logType(var)
```
* 获取用户输入的验证码
```js
java.getVerificationCode(imageUrl)
```
* 弹窗提示
```js
java.longToast(msg: Any?)
java.toast(msg: Any?)
```
* 获取用户阅读配置
```js
java.getReadBookConfig(): String
java.getReadBookConfigMap(): Map<String, Any>
```
* 获取用户主题配置
```js
java.getThemeConfig(): String
java.getThemeConfigMap(): Map<String, Any?>
```
* 获取用户主题模式
```js
* @return 0 跟随系统，1 亮色主题，2 暗色主题，3 墨水屏
fun getThemeMode(): String
```
* 从网络(由java.cacheFile实现)、本地读取JavaScript文件，导入上下文请手动`eval(String(...))`
```js
java.importScript(url)
//相对路径支持android/data/{package}/cache
java.importScript(relativePath)
java.importScript(absolutePath)
```
* 缓存网络文件
```js
获取
java.cacheFile(url)
java.cacheFile(url,saveTime)
执行内容
eval(String(java.cacheFile(url)))
使缓存失效
cache.delete(java.md5Encode16(url))
```
* 获取网络压缩文件里面指定路径的数据 *可替换Zip Rar 7Z
```js
java.get*StringContent(url: String, path: String): String

java.get*StringContent(url: String, path: String, charsetName: String): String

java.get*ByteArrayContent(url: String, path: String): ByteArray?

```
* URI编码
```js
java.encodeURI(str: String, enc: String = "UTF-8")
```
* base64
> flags参数可省略，默认Base64.NO_WRAP，查看[flags参数说明](https://blog.csdn.net/zcmain/article/details/97051870)　
```js
java.base64Decode(str: String)
java.base64Decode(str: String, charset: String)
java.base64DecodeToByteArray(str: String, flags: Int)
java.base64Encode(str: String, flags: Int)
```
* ByteArray
```js
Str转Bytes
java.strToBytes(str: String)
java.strToBytes(str: String, charset: String)
Bytes转Str
java.bytesToStr(bytes: ByteArray)
java.bytesToStr(bytes: ByteArray, charset: String)
```
* Hex
```js
HexString 解码为字节数组
java.hexDecodeToByteArray(hex: String)
hexString 解码为utf8String
java.hexDecodeToString(hex: String)
utf8 编码为hexString
java.hexEncodeToString(utf8: String)
```
* 标识id
```js
java.randomUUID()
java.androidId()
```
* 繁简转换
```js
将文本转换为简体
java.t2s(text: String): String
将文本转换为繁体
java.s2t(text: String): String
```
* 时间格式化
```js
java.timeFormatUTC(time: Long, format: String, sh: Int): String?
java.timeFormat(time: Long): String
```
* html格式化
```js
java.htmlFormat(str: String): String
java.htmlFormat(str: String, redirectUrl: String): String
```
第二个参数用于按当前页面地址补全正文 HTML 中 `<img>` 的相对地址。
* 文件
>  所有对于文件的读写删操作都是相对路径,只能操作阅读缓存/android/data/{package}/cache/内的文件
```js
//文件下载 url用于生成文件名，返回文件路径
downloadFile(url: String): String
//文件解压,zipPath为压缩文件路径，返回解压路径
unArchiveFile(zipPath: String): String
unzipFile(zipPath: String): String
unrarFile(zipPath: String): String
un7zFile(zipPath: String): String
//文件夹内所有文件读取
getTxtInFolder(unzipPath: String): String
//读取文本文件
readTxtFile(path: String): String
//删除文件
deleteFile(path: String) 
```

### [js加解密类](https://github.com/LegadoTeam/legado/blob/master/app/src/main/java/io/legado/app/help/JsEncodeUtils.kt) 部分函数

> 提供在JavaScript环境中快捷调用crypto算法的函数，由[hutool-crypto](https://www.hutool.cn/docs/#/crypto/概述)实现  
> 由于兼容性问题，hutool-crypto当前版本为5.8.22  

> 注意：如果输入的参数不是Utf8String 可先调用`java.hexDecodeToByteArray java.base64DecodeToByteArray`转成ByteArray
* 对称加密
> 输入参数key iv 支持ByteArray|**Utf8String**
```js
// 创建Cipher
java.createSymmetricCrypto(transformation, key, iv)
```
>解密加密参数 data支持ByteArray|Base64String|HexString|InputStream
```js
//解密为ByteArray String
cipher.decrypt(data)
cipher.decryptStr(data)
//加密为ByteArray Base64字符 HEX字符
cipher.encrypt(data)
cipher.encryptBase64(data)
cipher.encryptHex(data)
```
* 非对称加密
> 输入参数 key支持ByteArray|**Utf8String**
```js
//创建cipher
java.createAsymmetricCrypto(transformation)
//设置密钥
.setPublicKey(key)
.setPrivateKey(key)

```
> 解密加密参数 data支持ByteArray|Base64String|HexString|InputStream  
```js
//解密为ByteArray String
cipher.decrypt(data,  usePublicKey: Boolean? = true)
cipher.decryptStr(data, usePublicKey: Boolean? = true)
//加密为ByteArray Base64字符 HEX字符
cipher.encrypt(data,  usePublicKey: Boolean? = true)
cipher.encryptBase64(data,  usePublicKey: Boolean? = true)
cipher.encryptHex(data,  usePublicKey: Boolean? = true)
```
* 签名
> 输入参数 key 支持ByteArray|**Utf8String**
```js
//创建Sign
java.createSign(algorithm)
//设置密钥
.setPublicKey(key)
.setPrivateKey(key)
```
> 签名参数 data支持ByteArray|inputStream|String
```js
//签名输出 ByteArray HexString
sign.sign(data)
sign.signHex(data)
```
* 摘要
```js
java.digestHex(data: String, algorithm: String,): String?

java.digestBase64Str(data: String, algorithm: String,): String?
```
* md5
```js
java.md5Encode(str: String)
java.md5Encode16(str: String)
```
* HMac
```js
java.HMacHex(data: String, algorithm: String, key: String): String

java.HMacBase64(data: String, algorithm: String, key: String): String
```

## book对象的可用属性
### 属性
> 使用方法: 在js中或{{}}中使用book.属性的方式即可获取.如在正文内容后加上 ##{{book.name+"正文卷"+title}} 可以净化 书名+正文卷+章节名称（如 我是大明星正文卷第二章我爸是豪门总裁） 这一类的字符.
```js
bookUrl // 详情页Url(本地书源存储完整文件路径)
tocUrl // 目录页Url (toc=table of Contents)
origin // 书源URL(默认BookType.local)
originName //书源名称 or 本地书籍文件名
name // 书籍名称(书源获取)
author // 作者名称(书源获取)
kind // 分类信息(书源获取)
customTag // 分类信息(用户修改)
coverUrl // 封面Url(书源获取)
customCoverUrl // 封面Url(用户修改)
intro // 简介内容(书源获取)
customIntro // 简介内容(用户修改)
charset // 自定义字符集名称(仅适用于本地书籍)
type // 0:text 1:audio
group // 自定义分组索引号
latestChapterTitle // 最新章节标题
latestChapterTime // 最新章节标题更新时间
lastCheckTime // 最近一次更新书籍信息的时间
lastCheckCount // 最近一次发现新章节的数量
totalChapterNum // 书籍目录总数
durChapterTitle // 当前章节名称
durChapterIndex // 当前章节索引
durChapterPos // 当前阅读的进度(首行字符的索引位置)
durChapterTime // 最近一次阅读书籍的时间(打开正文的时间)
canUpdate // 刷新书架时更新书籍信息
order // 手动排序
originOrder //书源排序
variable // 自定义书籍变量信息(用于书源规则检索书籍信息)
 ```
## book对象的部分可用函数
 * 自定义书籍变量存取
```js
book.putVariable(key: String, variable: String?)
book.getVariable(key: String): String?
```

## chapter对象的部分可用属性
> 使用方法: 在js中或{{}}中使用chapter.属性的方式即可获取.如在正文内容后加上 ##{{chapter.title+chapter.index}} 可以净化 章节标题+序号(如 第二章 天仙下凡2) 这一类的字符.
 ```js
 url // 章节地址
 title // 章节标题
 baseUrl //用来拼接相对url
 bookUrl // 书籍地址
 index // 章节序号
 resourceUrl // 音频真实URL
 tag //
 start // 章节起始位置
 end // 章节终止位置
 variable //变量
 ```
 ## chapter对象的部分可用函数
 * 自定义章节变量存取
```js
chapter.putVariable(key: String, variable: String?)
chapter.getVariable(key: String): String?
//在函数回调或登录界面等地方调用，chapter自身不会进行保存，需要调用chapter.update()
```
 * 章节信息存储
```js
 chapter.putLyric(value: String?) // 存储音频章节歌词
 chapter.putImgUrl(value: String?) // 存储章节图标链接，比如标题上的段评图标链接
 ```
 
## source对象的部分可用函数
* 获取书源url
```js
source.getKey()
```
* 书源变量存取
```js
source.putVariable(variable: String?)
source.getVariable()
```
* 自定义书源变量存取
```js
source.put(key: String, variable: String?)
source.get(key: String): String?
```
* 登录头操作
```js
获取登录头
source.getLoginHeader()
获取登录头某一键值
source.getLoginHeaderMap().get(key: String)
保存登录头
source.putLoginHeader(header: String)
清除登录头
source.removeLoginHeader()
```
* 用户登录信息操作
> 使用`登录UI`规则，并成功登录，阅读自动加密保存登录UI规则中除type为button的信息
```js
login函数获取登录信息
source.getLoginInfo()
login函数获取登录信息键值
source.getLoginInfoMap().get(key: String)
清除登录信息
source.removeLoginInfo()
login函数存放登录信息，  
在登录界面时请调用java.upLoginData
source.putLoginInfo()
```
* 书源缓存刷新
```js
刷新发现
source.refreshExplore()
刷新jslib
source.refreshJSLib()
```
## cookie对象的部分可用函数
```js
获取全部cookie
cookie.getCookie(url: String)
获取cookie某一键值
cookie.getKey(url: String, key: String)
设置cookie
cookie.setCookie(url: String, cookie: String)
替换cookie
cookie.replaceCookie(url: String, cookie: String)
删除cookie
cookie.removeCookie(url: String)
设置内置浏览器cookie
cookie.setWebCookie(url: String, cookie: String)
```

## cache对象的部分可用函数
> saveTime单位:秒，可省略  
> 保存至数据库和缓存文件(50M)，保存的内容较大时请使用`getFile putFile`
```js
保存,saveTime为0时无过期时间
cache.put(key: String, value: String, saveTime: Int = 0)
读取数据库,onlyDisk为true时只从磁盘读取
cache.get(key: String, onlyDisk: Boolean = false): String?
删除
cache.delete(key: String)
缓存文件内容
cache.putFile(key: String, value: String, saveTime: Int)
读取文件内容
cache.getFile(key: String): String?
保存到内存
cache.putMemory(key: String, value: Any)
读取内存
cache.getFromMemory(key: String): Any?
删除内存
cache.deleteMemory(key: String)
```

## 跳转外部链接/应用函数
```js
// 跳转外部链接，传入http链接或者scheme跳转到浏览器或其他应用
// 指定mimeType，可以跳转指定类型应用，例如（video/*）
java.openUrl(url: String, mimeType: String = null)
```
## 视频播放器函数
```js
* @param url 视频播放链接
* @param title 视频的标题
* @param isFloat 是否悬浮窗打开
java.openVideoPlayer(url: String, title: String, isFloat: Boolean = false)
```

<!-- js-source-guide:start -->
## JavaScript 单文件书源

JavaScript 单文件书源使用一个完整的 `.js` 文件描述书源。它不使用 `ruleSearch`、
`ruleBookInfo`、`ruleToc` 等声明式规则，而是由固定名称的函数返回搜索、详情、目录和正文数据。

### 文件结构

新脚本顶层必须声明一个名为 `config` 的配置对象和 `search` 函数。文本、音频、图片与视频源
还必须声明 `getChapters`、`getContent`；文件源（`bookSourceType: 3`）必须声明
`getBookInfo` 并返回 `downloadUrls`，可以省略目录与正文函数。`explore` 和 `login` 按需声明。

保存或导入时，应用会先在不含 `java`、`source`、`sourceApi` 等运行时绑定的安全作用域中执行脚本，
提取 `config` 并检查函数。网络请求、数据库访问和其他运行时代码必须写在函数内部，
不要在顶层直接调用。

<!-- js-source-example:start -->
```js
var config = {
    bookSourceUrl: "https://example.com",
    bookSourceName: "示例 JS 书源",
    bookSourceType: 0,
    bookSourceGroup: "",
    bookSourceComment: "JavaScript 单文件书源示例",
    loginUi: [
        { name: "账号", type: "text" },
        { name: "密码", type: "password" }
    ],
    exploreUrl: [
        { title: "分类", url: "https://example.com/list" }
    ],
    lastUpdateTime: 0
};

function login() {
    var loginInfo = JSON.parse(source.getLoginInfo() || "{}");
    // 发起登录请求，失败时可 throw "错误信息"。
}

function search(key, page) {
    var html = java.ajax(config.bookSourceUrl + "/search?q=" + encodeURIComponent(key) + "&p=" + page);
    return [];
}

function explore(url, page) {
    var html = java.ajax(url + "?page=" + page);
    return [];
}

function getBookInfo(book) {
    return {
        intro: "",
        tocUrl: book.bookUrl
    };
}

function getChapters(book) {
    return [];
}

function getContent(chapter, book, nextChapterUrl) {
    var html = java.ajax(chapter.url);
    return java.htmlFormat(String(html || ""), chapter.url);
}
```
<!-- js-source-example:end -->

`bookSourceUrl` 和 `bookSourceName` 不能为空。配置中的 `mainJs`、`ruleSearch`、
`ruleExplore`、`ruleBookInfo`、`ruleToc`、`ruleContent` 和 `ruleReview` 会被移除，
不要在单文件书源中配置这些字段。

### config、source 与 sourceApi

- `config` 是脚本声明的普通配置对象，例如读取 `config.bookSourceUrl`。
- `source` 是数据库中的运行时书源对象，用于读取登录信息、登录请求头和持久化书源变量。
- `sourceApi` 是 `source` 的兼容别名，供旧版脚本继续使用。

新脚本不要再声明同名 `source` 配置对象，否则会覆盖运行时 `source` 绑定。旧版脚本的
`var source = {...}` 仍可导入、保存和运行，并可继续通过 `sourceApi.getLoginInfo()` 等方法
访问书源实体。脚本同时声明完整的 `config` 与旧版 `source` 配置时，导入以 `config` 为准；
无关或未定义的 `config` 不影响旧版 `source` 导入。

### config 常用字段

|字段|说明|
|---|---|
|`bookSourceUrl`|必填，书源唯一标识|
|`bookSourceName`|必填，书源名称|
|`bookSourceType`|`0` 文本、`1` 音频、`2` 图片、`3` 下载站、`4` 视频|
|`bookSourceGroup`|书源分组|
|`bookSourceComment`|书源说明|
|`lastUpdateTime`|更新时间戳，发布新版本时应增大|
|`header`|请求头 JSON 字符串|
|`enabledCookieJar`|是否自动保存请求 Cookie|
|`concurrentRate`|并发限制|
|`jsLib`|公共 JavaScript 库文本|
|`exploreUrl`|发现分类，与 `explore` 函数配对|
|`loginUrl`|WebView 登录地址|
|`loginUi`|表单登录配置，与 `login` 函数配对|

`bookSourceType` 的 `0` 到 `4` 是书源类型，不等于返回书籍对象中的 `type`。
搜索结果和详情返回值里的 `type` 使用 `BookType` 位值：视频 `4`、文本 `8`、
音频 `32`、图片 `64`、下载服务 `128`，也可以组合合法位值。

### 函数契约

|函数|要求|返回值|
|---|---|---|
|`search(key, page)`|必选，页码从 `1` 开始|书籍数组|
|`explore(url, page)`|`exploreUrl` 非空时必选|与搜索相同的书籍数组|
|`getBookInfo(book)`|文件源必选，其他类型可选|详情字段对象|
|`getChapters(book)`|非文件源必选|非空章节数组|
|`getContent(chapter, book, nextChapterUrl)`|非文件源必选|非空正文字符串|
|`login()`|`loginUi` 非空时必选|返回值不限，失败时可 `throw`|

返回值可以直接返回原生对象或数组，也可以返回 `JSON.stringify(...)` 生成的字符串。
原生对象或数组会由引擎自动转换为 JSON，字符串则直接交给解析器。

#### search 与 explore

每条结果必须包含非空 `name` 和 `bookUrl`，缺少这些字段的条目会被跳过。常用可选字段有
`author`、`kind`、`coverUrl`、`intro`、`wordCount`、`latestChapterTitle`、`tocUrl` 和
`type`。`origin`、`originName`、`originOrder` 由应用写入，脚本返回的同名值不会生效。
`bookUrl` 不会自动补全相对地址，建议直接返回绝对地址。

`exploreUrl` 可直接写数组，也可使用书源原有的文本格式。数组中的每项必须具有非空
`title`；空数组会被视为未配置发现，不要求实现 `explore`。

#### getBookInfo

非文件源未声明 `getBookInfo`，或者函数返回 `null`、`undefined` 时，会继续使用搜索阶段的字段。
文件源必须声明该函数并返回非空 `downloadUrls`。
允许覆盖 `name`、`author`、`intro`、`coverUrl`、`kind`、`wordCount`、
`latestChapterTitle`、`tocUrl`、`variable`、`type` 和 `downloadUrls`，其他字段会被忽略。
部分调用场景不允许通过详情重新命名书籍，此时 `name` 不会覆盖。非文件源的 `tocUrl`
最终仍为空时会使用 `bookUrl`。`downloadUrls` 必须是字符串数组，相对地址会按
`book.bookUrl` 补全；文件源详情页会据此显示下载列表并将选中的文件导入为本地书。

`variable` 支持普通对象或 JSON 字符串，内容应为字符串键值：

```js
function getBookInfo(book) {
    return {
        tocUrl: book.bookUrl + "/chapters",
        variable: { token: "abc", categoryId: "12" }
    };
}
```

#### getChapters

每个章节必须包含非空 `title` 和 `url`，无效条目会被跳过；最终没有有效章节时视为目录失败。
相对章节地址会以 `book.tocUrl` 为基准补全。常用可选字段有 `isVolume`、`isVip`、
`isPay`、`resourceUrl`、`tag` 和 `wordCount`。

卷名行推荐设置 `isVolume: true`，并令 `url` 与 `title` 完全相同。应用不会为这种行补全
URL，打开时直接使用 `tag` 作为内容，不调用 `getContent`。

#### getContent

当前正文函数包含三个参数：

```js
function getContent(chapter, book, nextChapterUrl) {
    var html = java.ajax(chapter.url);
    return java.htmlFormat(String(html || ""), chapter.url);
}
```

`nextChapterUrl` 是下一章地址，末章可能为 `null`。返回值必须是非空字符串。
应用不会自动把任意网页 HTML 转换成正文，需要时应由脚本提取正文节点，或显式调用
`java.htmlFormat`；第二个参数用于按当前页面地址补全正文中的相对图片链接。

### 登录与发现

- 只设置 `loginUrl` 时使用 WebView 登录，不要求实现 `login`。
- 设置非空 `loginUi` 时必须实现顶层 `login` 函数。
- 设置非空 `exploreUrl` 时必须实现顶层 `explore` 函数。
- `loginUi` 和 `exploreUrl` 的空数组会被视为未配置，不要求对应函数。

`loginUi` 可直接写数组，也可写 JSON 字符串；数组中的每项必须具有非空 `name`。
登录函数内可通过 `source.getLoginInfo()` 读取用户填写的数据，并使用
`source.putLoginHeader(...)` 保存后续请求需要的登录头。旧版脚本可继续使用
`sourceApi.getLoginInfo()` 和 `sourceApi.putLoginHeader(...)`。

### 段评

段评由两个成对的顶层函数提供。只有同时声明 `getReviewSummary` 和 `getReviewDetail` 才会启用，缺少任意一个
函数时导入/保存会提示配对错误。章节加载后先调用统计函数，点击正文段评图标时再调用详情函数。

```js
function getReviewSummary(chapter, book) {
    var json = JSON.parse(java.ajax(config.bookSourceUrl + "/review/summary?url=" + chapter.url));
    return json.map(function (item) {
        return {
            paraIndex: item.paraIndex,
            count: item.count,
            paraData: item.paraData
        };
    });
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    var json = JSON.parse(java.ajax(
        config.bookSourceUrl + "/review/detail?para=" + paraIndex + "&data=" + paraData + "&page=" + page
    ));
    return {
        items: json.items.map(function (item) {
            return {
                id: item.id,
                name: item.name,
                avatar: item.avatar,
                badge: item.badge,
                content: item.content,
                replies: item.replies || []
            };
        }),
        nextPageUrl: json.hasNext ? "more" : null
    };
}
```

- `getReviewSummary(chapter, book)` 返回数组，每项包含 `paraIndex`（正文段落序号，`-1` 表示章节标题）、`count`（评论数）和可选的
  `paraData`。`count` 小于等于 0 的条目不会显示图标；缺少 `paraData` 时默认使用段落序号字符串。
- `getReviewDetail(chapter, book, paraIndex, paraData, page)` 返回 `{items, nextPageUrl}`。每项的 `content` 必填，
  可选 `id`、`name`、`avatar`、`badge` 和递归 `replies`；缺少内容的条目会被忽略，递归回复会在界面中按顺序展示。
- `nextPageUrl` 只是是否继续请求的信号，不会作为 URL 使用。返回任意非空值表示还有下一页，返回 `null` 或省略表示结束；
  下一次调用会把 `page` 加一。
- 段评函数异常会记录到日志，详情加载错误同时显示在弹窗中；返回空数组表示没有内容。

### 运行环境与并发

函数运行时可使用 `java`、`source`、`sourceApi`、`baseUrl`、`cookie`、`cache` 和当前函数参数。
每次调用都会建立新的脚本作用域并重新执行主脚本，编译缓存不会保留顶层变量值。

#### Java String 包装边界

`key`、`baseUrl` 等直接绑定的字符串是 JS 原生字符串。从 Java 对象成员或 Java 方法取得的
字符串，例如 `book.bookUrl`、`chapter.title`、`chapter.url`、Jsoup 的 `.text()`/`.attr()`
以及 `java.ajax()` 返回值，则可能保留为 Java `String` 包装对象。两者显示和拼接结果相同，
但类型、真假值、严格相等和同名方法分派不同。

以下示例假设 `chapter.title` 为 `"第1章"`、`chapter.url` 为 `"https://a/b/"`、
`chapter.tag` 为 Java 空字符串：

|表达式|当前行为|
|---|---|
|`typeof chapter.title`|`"object"`|
|`typeof chapter.title.length`|`"function"`；Java 长度应调用 `chapter.title.length()`|
|`chapter.tag ? "T" : "F"`|`"T"`；包装后的 Java 空字符串仍是真值|
|`chapter.title === "第1章"`|`false`；使用宽松相等 `==` 才按文本相等|
|`chapter.url.replace(/b/, "X")`|可能因 Java `replace` 重载无法唯一选择而抛错|
|`chapter.url.split("/").length`|`4`；调用 Java `split(regex)`，会丢弃尾部空串|
|`chapter.url.split("/", -1).length`|`5`；Java 双参数重载可保留尾部空串|

需要使用 JS 的正则 `replace`、`split`、`.length` 属性、空串真假值或严格相等时，先用
`String(...)` 归一化：

```js
var url = String(chapter.url || "");
var title = String(chapter.title || "");
var tag = String(chapter.tag || "");

url.replace(/b/, "X");       // https://a/X/
url.split("/").length;       // 5，使用 JS split 语义
title.length;                 // 3，使用 JS length 属性
tag ? "T" : "F";             // F
title === "第1章";           // true
```

若明确需要 Java 语义，可以直接调用 `length()`、`indexOf(...)`、`split(regex, limit)` 等
Java 方法。不要依赖某个方法名恰好回落到 `String.prototype`；跨 Java/JS 边界后先归一化最稳妥。

- 不要依赖顶层可变变量在函数或请求之间传递状态。
- 不要假设搜索、详情、目录和正文一定按固定顺序执行。
- 同一个书源可能同时执行多个请求。
- 持久状态使用 `cache.put/get`、`source.put/get` 或
  `source.putVariable/getVariable`；旧版脚本中的 `sourceApi` 调用保持兼容。

脚本由 Rhino 执行。为保持兼容，优先使用模板中的 `function` 和 `var` 写法，
不要依赖 `async/await`、Promise、`import`、`export` 等浏览器或模块运行时能力。

### 导入、导出与分享

- 书源管理菜单的“新建 JS 书源”会打开内置模板。
- 本地 `.js`、`.txt` 文件、内容为脚本的在线地址和直接粘贴的脚本文本均可导入。
- 导入时以 `bookSourceUrl` 匹配已有书源，并使用 `lastUpdateTime` 判断是否为更新。
- 只选择一个 JavaScript 书源导出或分享时，生成以书源名称命名的 `.js` 原文。
- 多选或混合选择 JavaScript 与声明式书源时，仍导出 JSON 书源容器。
<!-- js-source-guide:end -->
