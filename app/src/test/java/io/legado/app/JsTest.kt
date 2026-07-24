package io.legado.app

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BookChapter
import org.intellij.lang.annotations.Language
import org.junit.Assert
import org.junit.Test

class JsTest {

    @Language("js")
    private val printJs = """
        function print(str, newline) {
            if (typeof(str) == 'undefined') {
                str = 'undefined';
            } else if (str == null) {
                str = 'null';
            } 
            java.lang.System.out.print(String(str));
            if (newline) java.lang.System.out.print("\n");
        }
        function println(str) { 
            print(str, true);
        }
    """.trimIndent()

    @Test
    fun testMap() {
        val map = hashMapOf("id" to "3242532321")
        val bindings = ScriptBindings()
        bindings["result"] = map
        @Language("js")
        val jsMap = "$=result;id=$.id;id"
        val result = RhinoScriptEngine.eval(jsMap, bindings)
        Assert.assertEquals("3242532321", result)
        @Language("js")
        val jsMap1 = """result.get("id")"""
        val result1 = RhinoScriptEngine.eval(jsMap1, bindings)
        Assert.assertEquals("3242532321", result1)
    }

    @Test
    fun testFor() {
        val scope = RhinoScriptEngine.run {
            val scope = getRuntimeScope(ScriptBindings())
            eval(printJs, scope)
            scope
        }

        @Language("js")
        val jsFor = """
            let result = 0
            let a=[1,2,3]
            let l=a.length
            for (let i = 0;i<l;i++){
            	result = result + a[i]
                println(i)
            }
            for (let o of a){
            	result = result + o
                println(o)
            }
            for (let o in a){
            	result = result + o
                println(o)
            }
            result
        """.trimIndent()
        val result = RhinoScriptEngine.eval(jsFor, scope)
        Assert.assertEquals("12012", result)
    }

    @Test
    fun testReturnNull() {
        val result = RhinoScriptEngine.eval("null")
        Assert.assertEquals(null, result)
    }

    @Test
    fun testReplace() {
        @Language("js")
        val js = """
            s=result.match(/(.{1,6}?)(第.*)/);
            n=s[2].length-parseInt(6-s[1].length);
            s[2].substr(0,n);
        """.trimIndent()
        val x = RhinoScriptEngine.run {
            val bindings = ScriptBindings()
            bindings["result"] = "筳彩涫第七百一十四章 人头树鮺舦綸"
            eval(js, bindings)
        }
        Assert.assertEquals(x, "第七百一十四章 人头树")
    }


    @Test
    fun chapterText() {
        val chapter = BookChapter(title = "xxxyyy")
        val bindings = ScriptBindings()
        bindings["chapter"] = chapter
        @Language("js")
        val js = "chapter.title"
        val result = RhinoScriptEngine.eval(js, bindings)
        Assert.assertEquals(result, "xxxyyy")
    }

    @Test
    fun javaListForEach() {
        val list = arrayListOf(1, 2, 3)
        val bindings = ScriptBindings()
        bindings["list"] = list
        @Language("js")
        val js = """
            var result = 0
            list.forEach(item => {result = result + item})
            result
        """.trimIndent()
        val result = RhinoScriptEngine.eval(js, bindings)
        Assert.assertEquals(result, 6.0)
    }

    class ElementsProvider {
        private val document = org.jsoup.Jsoup.parse(
            """<div id="video-artist-name"><a href="/artist/1">n</a></div>"""
        )

        fun getElements(rule: String): List<Any> = document.select("$rule a")
    }

    @Test
    fun javaListSubclassMethods() {
        val provider = ElementsProvider()
        val bindings = ScriptBindings()
        bindings["java"] = provider
        bindings["result"] = provider.getElements("#video-artist-name")

        val viaMethod = RhinoScriptEngine.eval(
            "java.getElements('#video-artist-name').attr('href')", bindings
        )
        Assert.assertEquals("/artist/1", viaMethod)
        val viaBinding = RhinoScriptEngine.eval("result.attr('href')", bindings)
        Assert.assertEquals("/artist/1", viaBinding)
    }

    @Test
    fun analyzeRuleGetElementsKeepsCollectionMethods() {
        val analyzeRule = io.legado.app.model.analyzeRule.AnalyzeRule()
        analyzeRule.setContent(
            """<div id="video-artist-name"><a href="/artist/1">n</a></div>"""
        )
        val bindings = ScriptBindings()
        bindings["java"] = analyzeRule
        val result = RhinoScriptEngine.eval(
            "java.getElements('#video-artist-name a').attr('href')", bindings
        )
        Assert.assertEquals("/artist/1", result)
    }

    @Test
    fun javaStringInteropBoundary() {
        val chapter = BookChapter(
            title = "第1章",
            url = "https://a/b/",
            tag = "",
        )
        val bindings = ScriptBindings()
        bindings["chapter"] = chapter
        bindings["key"] = "native"
        bindings["baseUrl"] = "https://base.example"
        fun evaluate(js: String) = RhinoScriptEngine.eval(js, bindings)

        Assert.assertEquals("string", evaluate("typeof key"))
        Assert.assertEquals("string", evaluate("typeof baseUrl"))
        Assert.assertEquals("object", evaluate("typeof chapter.title"))
        Assert.assertEquals("object", evaluate("typeof chapter.title.substring(0, 1)"))
        Assert.assertEquals("function", evaluate("typeof chapter.title.length"))
        Assert.assertEquals("3", evaluate("'' + chapter.title.length()"))
        Assert.assertEquals("3", evaluate("'' + String(chapter.title).length"))

        Assert.assertEquals("T", evaluate("chapter.tag ? 'T' : 'F'"))
        Assert.assertEquals("F", evaluate("String(chapter.tag) ? 'T' : 'F'"))
        Assert.assertEquals(
            "false:true:true",
            evaluate(
                "(chapter.title === '第1章') + ':' + " +
                    "(chapter.title == '第1章') + ':' + " +
                    "(String(chapter.title) === '第1章')"
            ),
        )

        val ambiguousReplace = runCatching {
            evaluate("chapter.url.replace(/b/, 'X')")
        }
        Assert.assertTrue("Java replace overload should reject a JS RegExp", ambiguousReplace.isFailure)
        Assert.assertEquals(
            "https://a/X/",
            evaluate("String(chapter.url).replace(/b/, 'X')"),
        )

        Assert.assertEquals("4", evaluate("'' + chapter.url.split('/').length"))
        Assert.assertEquals("5", evaluate("'' + chapter.url.split('/', -1).length"))
        Assert.assertEquals("5", evaluate("'' + String(chapter.url).split('/').length"))
        Assert.assertEquals("string", evaluate("typeof String(chapter.url)"))
    }

    @Test
    fun indirectEvalDynamicRealm() {
        val bindings = ScriptBindings()
        bindings["cache"] = "EXEC_ENV"
        Assert.assertEquals("EXEC_ENV", RhinoScriptEngine.eval("(0, eval)('cache')", bindings))
        Assert.assertEquals("EXEC_ENV", RhinoScriptEngine.eval("new Function('return cache')()", bindings))
        Assert.assertEquals("EXEC_ENV-L", RhinoScriptEngine.eval(
            "function f() { var loc = '-L'; return eval('cache + loc') }; f()", bindings
        ))

        val shared = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        RhinoScriptEngine.eval("function libEval(code) { return this.eval(code) }", shared)
        val chained = ScriptBindings()
        chained["cache"] = "EXEC_ENV2"
        chained.chainTo(shared)
        Assert.assertEquals("EXEC_ENV2", RhinoScriptEngine.eval("libEval('cache')", chained))
    }

    @Test
    fun typeofString() {
        val bindings = ScriptBindings()
        @Language("js")
        val js = """
            s = "" + String()
            typeof s
        """.trimIndent()
        val result = RhinoScriptEngine.eval(js, bindings)
        Assert.assertEquals(result, "string")
    }

}
