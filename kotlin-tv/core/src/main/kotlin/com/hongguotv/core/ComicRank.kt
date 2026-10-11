// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/** Read the website's serialized data only; never execute its scripts. */
internal object ComicRank {
    fun parse(html: String, page: Int): CatalogPage = parse(html,page,"hot-comic-drama","comic","漫剧")

    fun parse(html: String,page: Int,slug: String,rankKey: String,label: String): CatalogPage {
        try {
            val document=Jsoup.parse(html)
            val updatedText=runCatching { ContentRepository.extractRouter(html).optJSONObject("loaderData")
                ?.optJSONObject("rank_$slug/page")?.str("updatedText") }.getOrNull().orEmpty()
            for(tag in document.select("script[data-fn-name][data-fn-args]")) {
                val name=tag.attr("data-fn-name")
                if(name!="r" && name!="mergeLoaderData") continue
                val args=JSONArray(tag.attr("data-fn-args"))
                if(args.optString(0)!="rank_$slug/page") continue
                val raw=if(name=="r" && args.optString(1)=="content") args.opt(2) else if(name=="mergeLoaderData") {
                    args.optJSONArray(1)?.objects()?.firstOrNull { it.str("key")=="content" && it.str("routerDataFnName")=="p" }
                        ?.optJSONArray("routerDataFnArgs")?.let { values ->
                            (0 until values.length()).asSequence().map { values.opt(it) }
                                .firstOrNull { value -> when(value) { is JSONObject -> value.has("rankList"); is String -> value.contains("rankList") && value.contains("pagination"); else -> false } }
                        }
                } else null
                val payload=when(raw) { is JSONObject -> raw; is String -> JSONObject(raw); else -> continue }
                if(!payload.optBoolean("isSuccess")) throw IOException("$label榜单暂不可用")
                val rows=payload.optJSONArray("rankList") ?: throw IOException("$label榜单格式已变化")
                val pagination=payload.optJSONObject("pagination") ?: throw IOException("$label分页信息缺失")
                val totalPages=pagination.optInt("totalPages",0)
                if(totalPages<1 || pagination.optInt("pageNum",page)!=page) throw IOException("$label分页信息异常")
                val positions=linkedMapOf<String,RankPosition>()
                val items=rows.objects().map { row ->
                    val count=row.optInt("episodeCount",row.optJSONArray("episodeVids")?.length() ?: 0)
                    val tags=row.optJSONArray("tags") ?: JSONArray()
                    val id=row.str("seriesId").ifEmpty { row.str("id") }
                    if(id !in positions) positions[id]=RankPosition(row.optInt("rank").takeIf { it>0 },row.str("heatText"))
                    Series(id,row.str("title"),cleanUrl(row.str("cover")),
                        row.str("description"),if(count>0) "全 $count 集" else row.str("heatText"),
                        (0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optString(it) })
                }.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
                return CatalogPage(items,items.isNotEmpty() && page<minOf(totalPages,100),
                    ComicRanking(positions.filterKeys { id -> items.any { it.id==id } },minOf(totalPages,100),updatedText))
            }
            return renderedPage(document,html,page,slug,rankKey,label)
        } catch(e: IOException) { throw e } catch(e: Exception) { throw IOException("$label榜单格式已变化",e) }
    }

    private fun renderedPage(document: Document, html: String, page: Int,slug: String,rankKey: String,label: String): CatalogPage {
        // Some responses contain rendered cards while the deferred JSON is still unresolved.
        val route=ContentRepository.extractRouter(html).optJSONObject("loaderData")
            ?.optJSONObject("rank_$slug/page") ?: throw IOException("页面没有$label榜单数据")
        if(route.str("rankKey")!=rankKey || route.optInt("pageNum")!=page) throw IOException("$label分页信息异常")
        val positions=linkedMapOf<String,RankPosition>()
        val items=document.select("article[aria-labelledby^=rank-title-]").mapNotNull { article ->
            val heading=article.attr("aria-labelledby")
            val id=heading.removePrefix("rank-title-")
            val title=article.getElementById(heading)?.text().orEmpty()
            if(!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            if(id !in positions) positions[id]=RankPosition(
                article.selectFirst("[class*=badge-number-]")?.text()?.toIntOrNull()?.takeIf { it>0 },
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty())
            Series(id,title,article.selectFirst("img[src]")?.attr("src").orEmpty(),
                article.selectFirst("p[class*=description-]")?.text().orEmpty(),
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty(),
                article.select("p[class*=categories-] > span").take(5).joinToString(" · ") { it.text() })
        }.distinctBy { it.id }
        if(items.isEmpty()) throw IOException("页面没有$label榜单数据")
        val paging=document.selectFirst("nav[aria-label=榜单分页]") ?: throw IOException("漫剧分页信息缺失")
        if(paging.selectFirst("[aria-current=page]")?.text()?.toIntOrNull()!=page) throw IOException("$label分页信息异常")
        val lastPage=maxOf(page,paging.select("a[href]").mapNotNull { it.attr("href").substringAfter("?page=","").substringBefore('&').toIntOrNull() }.maxOrNull() ?: page)
        return CatalogPage(items,page<minOf(lastPage,100),ComicRanking(positions,minOf(lastPage,100),route.str("updatedText")))
    }
}
