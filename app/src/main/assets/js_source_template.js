/**
 * JavaScript 单文件书源模板。
 * search、getChapters、getContent 为必需函数，getBookInfo 和 explore 为可选函数。
 * getReviewSummary 与 getReviewDetail 成对声明即可启用段评。
 * config 保存脚本配置；source 是运行时书源实体，sourceApi 是兼容旧脚本的别名。
 * 可使用 java、source、sourceApi、cookie、cache、baseUrl 等现有书源脚本绑定。
 */

var config = {
    bookSourceUrl: "https://example.com",
    bookSourceName: "示例 JS 书源",
    bookSourceType: 0,
    bookSourceGroup: "",
    bookSourceComment: "",
    // 表单登录示例: [{ name: "账号", type: "text" }, { name: "密码", type: "password" }]
    loginUi: [],
    exploreUrl: [],
    lastUpdateTime: 0
};

var Jsoup = org.jsoup.Jsoup;

// config.loginUi 非空时必须提供。
function login() {
    var loginInfo = JSON.parse(source.getLoginInfo() || "{}");
    // 执行登录请求；失败时 throw "错误信息"。
}

function search(key, page) {
    var html = java.ajax(config.bookSourceUrl + "/search?q=" + encodeURIComponent(key) + "&p=" + page);
    var books = [];
    // books.push({ name: "书名", bookUrl: "https://example.com/book/1", author: "作者" });
    return books;
}

// config.exploreUrl 非空时必须提供。
function explore(url, page) {
    var html = java.ajax(url);
    return [];
}

// 可选。返回字段会合并到搜索结果，tocUrl 为空时默认使用 bookUrl。
function getBookInfo(book) {
    var html = java.ajax(book.bookUrl);
    return {
        intro: "",
        coverUrl: "",
        latestChapterTitle: "",
        tocUrl: book.bookUrl
    };
}

// title 和 url 为必填字段，数组顺序即目录顺序。
function getChapters(book) {
    var html = java.ajax(book.tocUrl);
    var chapters = [];
    // chapters.push({ title: "第 1 章", url: "https://example.com/read/1" });
    return chapters;
}

// 返回正文文本，空字符串视为失败。
function getContent(chapter, book, nextChapterUrl) {
    var html = java.ajax(chapter.url);
    return html;
}

/*
 * 段评统计与详情函数为可选功能。启用时请同时取消下面两个函数的注释。
 * getReviewSummary 返回 [{ paraIndex: 1, count: 5, paraData: "token" }]，paraIndex 为 -1 时表示章节标题；
 * getReviewDetail 返回 { items: [{ content: "评论内容", replies: [] }], nextPageUrl: null }。
 * nextPageUrl 只是“是否还有下一页”的信号，翻页时应用会递增 page 参数再次调用。
 *
function getReviewSummary(chapter, book) {
    var html = java.ajax(config.bookSourceUrl + "/review/summary?url=" + chapter.url);
    return [];
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    var html = java.ajax(config.bookSourceUrl + "/review/detail?para=" + paraIndex + "&page=" + page);
    return { items: [], nextPageUrl: null };
}
*/
