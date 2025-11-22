# 订阅源规则帮助

* [阅读3.0(Legado)规则说明](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [书源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/source.html)
* [订阅源帮助文档](https://mgz0227.github.io/The-tutorial-of-Legado/Rule/rss.html)
* 辅助键盘❓中可插入URL参数模板,打开帮助,js教程,正则教程,选择文件
* 规则标志, {{......}}内使用规则必须有明显的规则标志,没有规则标志当作js执行
```
@@ 默认规则,直接写时可以省略@@
@XPath: xpath规则,直接写时以//开头可省略@XPath
@Json: json规则,直接写时以$.开头可省略@Json
: regex规则,不可省略,只可以用在书籍列表和目录列表
```
* jsLib、并发率、CookieJar、登录UI、请求头代理、封面解密等重复说明见书源帮助

* 订阅源类型
> 网页：用内置浏览器加载正文内容  
> 图片：链接规则或正文规则得到图片链接，点击文章就会显示链接对应的图片  
> 视频：链接规则或正文规则得到视频链接，点击文章就会使用内置视频播放器进行播放

* 预加载
> 启用后会提前预加载相邻分类内容，瀑布流样式时会提前加载下一页内容

* 网页JS
> `window.close()` 关闭浏览器界面  
> `screen.orientation.lock()` 全屏后可控制屏幕方向  

* 内置浏览器额外支持的函数
> 打开对应界面  
```js
window.openUi("sort", {"分类":"https://example.com"}); //函数打开分类界面
window.openUi("sort", {"分类1":"https://example1.com","分类2":"https://example2.com"}); //函数打开多个分类界面
window.openUi("rss", {"正文标题":"https://example.com"}); //函数打开正文界面
```

> 异步执行阅读函数代码字符串，并返回字符串结果  
```js
window.run("java.toast('执行成功');'成功'")
.then(r=>alert(r))
.catch(e=>alert("执行出错:"+e));
```

> 异步函数，函数参数和返回结果类型均为字符串    
```js
window.ajaxAwait(url, callTimeout) //用java.ajax异步访问
window.connectAwait(url, header, callTimeout) //用java.connect异步访问，返回序列化后的响应
window.getAwait(url, header, callTimeout) //返回响应体
window.headAwait(url, header, callTimeout) //序列化后的响应头
window.postAwait(url, body, header, callTimeout) //返回响应体
window.webViewAwait(html, url, js) //用Java.webView异步访问
window.decryptStrAwait(transformation, key, iv, data)
//同java.createSymmetricCrypto(transformation, key, iv).decryptStr(data)
window.encryptBase64Await(transformation, key, iv, data)
//同java.createSymmetricCrypto(transformation, key, iv).encryptBase64(data)
window.encryptHexAwait(transformation, key, iv, data)
//同java.createSymmetricCrypto(transformation, key, iv).encryptHex(data)
window.createSignHexAwait(algorithm, publicKey, privateKey, data)
//同java.createSign(algorithm).setPublicKey(publicKey).setPrivateKey(privateKey).signHex(data)
window.downloadFileAwait(url) //同java.downloadFile(url)
window.readTxtFileAwait(path) //同java.readTxtFile(url)
window.importScriptAwait(url) //同java.importScript(url)
window.getStringAwait(ruleStr, mContent) //同java.getString(ruleStr, mContent)
```
> 同步调用  

支持直接调用java、source和cache对象上的函数。  
参数和返回结果不是字符串、数字、布尔、空的函数不支持。  
再次打开浏览器startBrowser等相关函数不支持。
```js
//部分示例参考
java.put("cs","v");
java.toast("cs"):
java.ajax("https://example.com");
java.get("https://example.com","{}");
java.base64Encode("cs");
java.aesDecodeToString("str","key","tran","iv");
source.login();
source.getLoginInfo();
cache.putMemory("cs","v");
```

