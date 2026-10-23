package com.mominulmega

import android.content.Context
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.treeToValue
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe

/**
 * Community repository aggregator inspired by the public MegaRepo concept.
 *
 * Reads the community repository database and registers repositories that are
 * not already installed. It does not install content-provider plugins itself.
 */
@CloudstreamPlugin
class MominulMegaPlugin : Plugin() {
    override fun load(context: Context) {
        ioSafe {
            runCatching { getRepositories() }
                .getOrDefault(emptyList())
                .distinct()
                .forEach { url ->
                    runCatching { addRepositoryIfMissing(url) }
                }
        }
    }

    private suspend fun addRepositoryIfMissing(url: String) {
        if (url.isBlank()) return

        val current = RepositoryManager.getRepositories()
        if (current.any { it.url.trimEnd('/') == url.trimEnd('/') }) return

        val repository = RepositoryManager.parseRepository(url)
        val name = repository?.name?.takeIf { it.isNotBlank() } ?: hostLabel(url)
        val iconUrl = repository?.iconUrl

        runCatching {
            RepositoryManager.addRepository(RepositoryData(iconUrl, name, url))
        }.recoverCatching {
            RepositoryManager.addRepository(RepositoryData(name, url))
        }
    }

    private fun hostLabel(url: String): String =
        runCatching {
            android.net.Uri.parse(url).host?.removePrefix("www.") ?: "Community Repository"
        }.getOrDefault("Community Repository")

    private suspend fun getRepositories(): List<String> {
        data class VerifiedRepo(
            val url: String? = null,
            val verified: Boolean? = null
        )

        val sourceUrl =
            "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
        val response = app.get(sourceUrl).text
        val tree = mapper.readTree(response)

        return tree.mapNotNull { item ->
            when (item) {
                is TextNode -> runCatching { mapper.treeToValue<String>(item) }.getOrNull()
                is ObjectNode -> runCatching {
                    mapper.treeToValue<VerifiedRepo>(item).url
                }.getOrNull()
                else -> null
            }
        }.filter { it.startsWith("https://") || it.startsWith("http://") }
    }
}
