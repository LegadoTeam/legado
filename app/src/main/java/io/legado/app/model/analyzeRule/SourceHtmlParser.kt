package io.legado.app.model.analyzeRule

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag
import org.jsoup.parser.TagSet

internal fun parseSourceHtml(html: String): Document {
    // Source rules written for older jsoup rely on HTML <a/> closing the tag.
    // Keep CSS and XPath on the same DOM without changing textNodes semantics.
    val tags = TagSet.Html().onNewTag { tag ->
        if (tag.namespace() == Parser.NamespaceHtml) tag.set(Tag.SelfClose)
    }
    return Jsoup.parse(html, Parser.htmlParser().tagSet(tags))
}
