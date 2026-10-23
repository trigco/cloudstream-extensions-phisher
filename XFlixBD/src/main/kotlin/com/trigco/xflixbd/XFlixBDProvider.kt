package com.trigco.xflixbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

@CloudstreamPlugin
class XFlixBDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(XFlixBD())
    }
}

class XFlixBD : MainAPI() {
    override var mainUrl = "https://xflixbd.org"
    override var name = "XFlixBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Latest",
        "genre/bangla" to "Bangla",
        "genre/hollywood" to "Hollywood",
        "genre/bollywood" to "Bollywood",
        "genre/k-drama" to "K-Drama",
        "genre/bangla-dubbed" to "Bangla Dubbed",
        "genre/hindi-dubbed" to "Hindi Dubbed"
    )

    private fun Element.toItem(): SearchResponse? {
        val anchor = selectFirst("h2 a, h3 a, .entry-title a, .post-title a, a[href*='/movie/'], a[href*='/tv-show/'], a[href*='/episode/']")
            ?: selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }
        if (!href.startsWith("http") || href == mainUrl || href == "$mainUrl/") return null

        val image = selectFirst("img")
        val title = anchor.text().ifBlank { image?.attr("alt").orEmpty() }
            .replace(Regex("\\s+"), " ").trim()
        if (title.isBlank()) return null

        val poster = image?.let {
            listOf("data-src", "data-lazy-src", "src", "srcset")
                .map { key -> it.attr(key).substringBefore(" ") }
                .firstOrNull { value -> value.startsWith("http") || value.startsWith("/") }
                ?.let { value -> fixUrl(value) }
        }

        val year = Regex("\\b(19|20)\\d{2}\\b").find(title + " " + text())?.value?.toIntOrNull()
        val type = if (href.contains("tv-show", true) || href.contains("series", true) || text().contains("season", true)) {
            TvType.TvSeries
        } else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun parseItems(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val cards = document.select(
            "article, .item, .movie-item, .film-item, .post, .ml-item, .video-item, .grid-item, .entry"
        ).mapNotNull { it.toItem() }
        if (cards.isNotEmpty()) return cards.distinctBy { it.url }

        return document.select("a[href]").mapNotNull { link ->
            val title = link.text().replace(Regex("\\s+"), " ").trim()
            if (title.isBlank() || link.selectFirst("img") == null) null
            else (link.parent() ?: link).toItem()
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = when {
            path.isBlank() && page <= 1 -> "$mainUrl/"
            page <= 1 -> "$mainUrl/$path/"
            else -> "$mainUrl/$path/page/$page/"
        }
        val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
        val results = parseItems(response.document)
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty() && page < 100)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/?s=$encoded&paged=$page"
        val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
        return parseItems(response.document).toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(fixUrl(url), headers = mapOf("Referer" to "$mainUrl/"))
        val document = response.document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim().ifBlank { "XFlixBD title" }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".poster img, .movie-poster img, article img, .entry-content img")?.let {
                it.absUrl("src").ifBlank { it.absUrl("data-src") }
            }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .entry-content, .story, .plot")?.text()?.trim()

        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(title + " " + document.text().take(1500))?.value?.toIntOrNull()
        val isSeries = document.select("a[href*='season'], a[href*='episode'], .episodes, .seasons").isNotEmpty() ||
            title.contains("season", true) || title.contains("episode", true)

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(fixUrl(data), headers = mapOf("Referer" to "$mainUrl/")).document
        var found = false

        document.select("iframe[src], iframe[data-src], source[src], a[href]").forEach { element ->
            val attribute = when {
                element.hasAttr("src") -> "src"
                element.hasAttr("data-src") -> "data-src"
                else -> "href"
            }
            val raw = element.attr(attribute).trim()
            if (raw.isBlank()) return@forEach
            val candidate = element.absUrl(attribute).ifBlank { fixUrl(raw) }
            if (!candidate.startsWith("http")) return@forEach

            val host = runCatching { java.net.URI(candidate).host.orEmpty().lowercase() }.getOrDefault("")
            val ownHost = runCatching { java.net.URI(mainUrl).host.orEmpty().lowercase() }.getOrDefault("")
            if (host == ownHost || host.endsWith(".$ownHost")) return@forEach
            if (element.tagName() == "a" && !element.text().contains(
                    Regex("watch|play|stream|server|download", RegexOption.IGNORE_CASE)
                )) return@forEach

            runCatching {
                loadExtractor(candidate, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }
}
