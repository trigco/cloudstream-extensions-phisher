package com.trigco.xflixbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
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
        "$mainUrl/" to "Latest",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/genre/bangla/" to "Bangla",
        "$mainUrl/genre/hollywood/" to "Hollywood",
        "$mainUrl/genre/bollywood/" to "Bollywood",
        "$mainUrl/genre/k-drama/" to "K-Drama",
        "$mainUrl/genre/web-series/" to "Web Series",
        "$mainUrl/genre/tv-show/" to "TV Shows",
        "$mainUrl/genre/bangla-dubbed/" to "Bangla Dubbed",
        "$mainUrl/genre/hindi-dubbed/" to "Hindi Dubbed",
        "$mainUrl/genre/action-adventure/" to "Action & Adventure",
        "$mainUrl/genre/thriller/" to "Thriller",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/animation/" to "Animation"
    )

    private fun Element.toItem(): SearchResponse? {
        val anchor = selectFirst("h1 a, h2 a, h3 a, .entry-title a, .post-title a, a[href*='/movies/'], a[href*='/movie/'], a[href*='/tv-show/'], a[href*='/tv-shows/'], a[href*='/series/'], a[href*='/episode/']")
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
        val type = if (
            href.contains("tv-show", true) || href.contains("series", true) ||
            text().contains("season", true)
        ) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun parseItems(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val cards = document.select(
            "article, .item, .movie-item, .film-item, .post, .ml-item, .video-item, .grid-item, .entry, .movie-card, .movie, .poster, .thumb, .flw-item, .film"
        )
            .mapNotNull { it.toItem() }
        if (cards.isNotEmpty()) return cards.distinctBy { it.url }

        val contentLinks = document.select(
            "a[href*='/movies/'], a[href*='/movie/'], a[href*='/tv-shows/'], a[href*='/tv-show/'], a[href*='/series/'], a[href*='/episode/']"
        )
        val linkedItems = contentLinks.mapNotNull { link ->
            val card = link.closest(
                "article, .item, .movie-item, .film-item, .post, .ml-item, .video-item, .grid-item, .entry, .movie-card, .movie, .poster, .thumb, .flw-item, .film"
            ) ?: link.parent() ?: link
            card.toItem()
        }.distinctBy { it.url }
        if (linkedItems.isNotEmpty()) return linkedItems

        return document.select("a[href]").mapNotNull { link ->
            if (link.text().isBlank() || link.selectFirst("img") == null) null
            else (link.parent() ?: link).toItem()
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) base else "$base/page/$page/"
        val response = app.get(
            url,
            referer = "$mainUrl/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
            )
        )
        val results = parseItems(response.document)
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = results, isHorizontalImages = false),
            hasNext = results.isNotEmpty() && page < 100
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val response = app.get(
            "$mainUrl/?s=$encoded",
            referer = "$mainUrl/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
            )
        )
        return parseItems(response.document)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(
            fixUrl(url),
            referer = "$mainUrl/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8"
            )
        )
        val document = response.document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim().ifBlank { return null }

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".poster img, .movie-poster img, article img, .entry-content img")?.let {
                it.absUrl("src").ifBlank { it.absUrl("data-src") }
            }

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .entry-content, .story, .plot")?.text()?.trim()
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title + " " + document.text().take(1500))?.value?.toIntOrNull()

        val episodeAnchors = document.select("a[href*='episode'], a[href*='/ep-'], .episodes a, .episode-list a")
        val isSeries = document.select("a[href*='season'], .episodes, .seasons, .episode-list").isNotEmpty() ||
            title.contains("season", true) || title.contains("series", true)

        if (isSeries && episodeAnchors.isNotEmpty()) {
            val episodes = episodeAnchors.mapNotNull { episodeLink ->
                val href = episodeLink.absUrl("href").ifBlank { fixUrl(episodeLink.attr("href")) }
                val label = episodeLink.text().trim().ifBlank { return@mapNotNull null }
                if (!href.startsWith("http")) return@mapNotNull null
                newEpisode(href) {
                    this.name = label
                    this.episode = Regex("(?:episode|ep)[\\s-]*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(fixUrl(data), referer = "$mainUrl/").document
        var delegated = false

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

            try {
                loadExtractor(candidate, data, subtitleCallback, callback)
                delegated = true
            } catch (_: Exception) {
                // An unsupported player is skipped; continue checking other public embeds.
            }
        }
        return delegated
    }
}
