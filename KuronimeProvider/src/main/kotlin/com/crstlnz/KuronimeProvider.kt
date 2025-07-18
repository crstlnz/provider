package com.crstlnz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.util.ArrayList

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.biz"
    private var animekuUrl = "https://animeku.org"
    override var name = "Kuronime+"
    override val hasQuickSearch = true
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )

    companion object {
        const val KEY = "3&!Z0M,VIZ;dZW=="
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Episodes",
        "$mainUrl/popular-anime/page/" to "Popular Anime",
        "$mainUrl/movies/page/" to "Movies",
//        "$mainUrl/genres/donghua/page/" to "Donghua",
//        "$mainUrl/live-action/page/" to "Live Action",
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val req = app.get(request.data + page)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = document.select("article").map {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> Regex("nonton-(.+)-episode").find(
                    title
                )?.groupValues?.get(1).toString()

                (title.contains("-movie")) -> Regex("nonton-(.+)-movie").find(title)?.groupValues?.get(
                    1
                ).toString()

                else -> title
            }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select(".bsuxtt, .tt > h4").text().trim()
        val posterUrl = fixUrlNull(
            this.selectFirst("div.view,div.bt")?.nextElementSibling()?.select("img")
                ?.attr("data-src")
        )
        val epNum = this.select(".ep").text().replace(Regex("\\D"), "").trim().toIntOrNull()
        val tvType = getType(this.selectFirst(".bt > span")?.text().toString())
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = app.get(mainUrl).url
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ajaxy_sf", "sf_value" to query, "search" to "false"
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<Search>()?.anime?.firstOrNull()?.all?.mapNotNull {
            newAnimeSearchResponse(
                it.postTitle ?: "", it.postLink ?: return@mapNotNull null, TvType.Anime
            ) {
                this.posterUrl = it.postImage
                addSub(it.postLatest?.toIntOrNull())
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title")?.text().toString().trim()
        val poster = document.selectFirst("div.l[itemprop=image] > img")?.attr("data-src")
        val tags = document.select(".infodetail > ul > li:nth-child(2) > a").map { it.text() }
        val type = getType(
            document.selectFirst(".infodetail > ul > li:nth-child(7)")?.ownText()?.removePrefix(":")
                ?.lowercase()?.trim() ?: "tv"
        )

        val trailer = document.selectFirst("div.tply iframe")?.attr("data-src")
        val year = Regex("\\d, (\\d*)").find(
            document.select(".infodetail > ul > li:nth-child(5)").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.selectFirst(".infodetail > ul > li:nth-child(3)")!!.ownText()
                .replace(Regex("\\W"), "")
        )
        val description = document.select("span.const > p").text()

        val episodes = document.select("div.bixbox.bxcl > ul > li").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val episode =
                Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)?.toIntOrNull()
            Episode(link, episode = episode)
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            addTrailer(trailer)
            this.tags = tags
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    fun extractEncodedString(input: String): String? {
        // Pattern to match variable declaration with Base64-like string
        // Looking for: var [any_name] = "[base64_string]";
        val pattern = Regex("""var\s+\w+\s*=\s*"([A-Za-z0-9+/=]{100,})";""")
        return try {
            val match = pattern.find(input)
            match?.groups?.get(1)?.value
        } catch (e: Exception) {
            null
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val scriptData =
            document.selectFirst("div#content script:containsData(is_singular)")?.data() ?: ""
        val id = extractEncodedString(scriptData)
            ?: throw ErrorLoadingException("No id found")
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val jsonBody = "{\"id\":\"${id}\"}"
        val body = jsonBody.toRequestBody(mediaType)
        val servers = app.post(
            "$animekuUrl/api/v9/sources",
            requestBody = body,
            referer = "$mainUrl/",
            headers = mapOf("Content-Type" to "application/json; charset=utf-8")
        ).parsedSafe<Servers>()
        argamaps({
            val decrypt = AesHelper.cryptoAESHandler(
                base64Decode(servers?.src ?: return@argamaps),
                KEY.toByteArray(),
                false,
                "AES/CBC/NoPadding"
            )
            val source = tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\", "")
            M3u8Helper.generateM3u8(
                this.name,
                source ?: return@argamaps,
                "$animekuUrl/",
                headers = mapOf("Origin" to animekuUrl)
            ).forEach(callback)
        }, {
            val decrypt = AesHelper.cryptoAESHandler(
                base64Decode(servers?.mirror ?: return@argamaps),
                KEY.toByteArray(),
                false,
                "AES/CBC/NoPadding"
            )
            tryParseJson<Mirrors>(decrypt)?.embed?.map { embed ->
                embed.value.apmap {
                    loadFixedExtractor(
                        it.value,
                        embed.key.removePrefix("v"),
                        "$mainUrl/",
                        subtitleCallback,
                        callback
                    )
                }
            }

        })

        return true
    }

    private fun String.toJsonFormat(): String {
        return if (this.startsWith("\"")) this.substringAfter("\"").substringBeforeLast("\"")
            .replace("\\\"", "\"") else this
    }

    private suspend fun loadFixedExtractor(
        url: String? = null,
        quality: String? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url ?: return, referer, subtitleCallback) { link ->
            callback.invoke(
                link
//                ExtractorLink(
//                    link.name,
//                    link.name,
//                    link.url,
//                    link.referer,
//                    getQualityFromName(quality),
//                    link.type,
//                    link.headers,
//                    link.extractorData
//                )
            )
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    data class Mirrors(
        @JsonProperty("embed") val embed: Map<String, Map<String, String>> = emptyMap(),
    )

    data class Sources(
        @JsonProperty("src") var src: String? = null,
    )

    data class Servers(
        @JsonProperty("src") var src: String? = null,
        @JsonProperty("mirror") var mirror: String? = null,
    )

    data class All(
        @JsonProperty("post_image") var postImage: String? = null,
        @JsonProperty("post_image_html") var postImageHtml: String? = null,
        @JsonProperty("ID") var ID: Int? = null,
        @JsonProperty("post_title") var postTitle: String? = null,
        @JsonProperty("post_genres") var postGenres: String? = null,
        @JsonProperty("post_type") var postType: String? = null,
        @JsonProperty("post_latest") var postLatest: String? = null,
        @JsonProperty("post_sub") var postSub: String? = null,
        @JsonProperty("post_link") var postLink: String? = null
    )

    data class Anime(
        @JsonProperty("all") var all: ArrayList<All> = arrayListOf(),
    )

    data class Search(
        @JsonProperty("anime") var anime: ArrayList<Anime> = arrayListOf()
    )

}

fun <R> argamaps(
    vararg transforms: suspend () -> R,
) = runBlocking {
    transforms.map {
        async {
            try {
                it.invoke()
            } catch (e: Exception) {
                logError(e)
            }
        }
    }.map { it.await() }
}