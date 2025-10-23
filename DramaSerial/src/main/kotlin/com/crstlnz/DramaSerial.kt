package com.crstlnz

//import com.crstlnz.utils.getLastNumber
//import com.crstlnz.utils.loadAnimeSail
//import com.crstlnz.utils.splitTitles
//import com.crstlnz.utils.toAbsoluteURL
import com.crstlnz.models.CaptionData
import com.crstlnz.models.DetailMovie
import com.crstlnz.models.EpisodeData
import com.crstlnz.models.HomeData
import com.crstlnz.models.HomeSearch
import com.crstlnz.models.SearchAPI
import com.crstlnz.utils.ArmoBiz
import com.crstlnz.utils.getLastNumber
import com.crstlnz.utils.splitTitles
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrackerType
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.RequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


class DramaSerial : MainAPI() {
    override var mainUrl = "https://tv4.dramaserial.id"
    override var name = "DramaSerial"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.OVA,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    init {
        val headers = mutableMapOf<String, String>()
        headers["Cookie"] = "_as_ipin_ct=ID;"
        app.defaultHeaders = headers
    }

    override val mainPage = mainPageOf(
        "/Genre/ongoing" to "Sedang Tayang",
        "/Genre/drama-serial-korea" to "Drama Serial Korea",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.post(
            "$mainUrl/${request.data}/page/${page}",
        ).document

        return newHomePageResponse(request.name, document.toSearchResponse() ?: listOf())
    }

    private fun Document.toSearchResponse(): List<SearchResponse> {
        val list = select("#main article")
        return list.map {
            newMovieSearchResponse(
                name = it.select(".entry-title").text().replace("Nonton", "")
                    .replace("Sub Indo", "").trim(),
                url = it.select(".entry-title a").attr("href"),
                TvType.AsianDrama
            ) {
                posterUrl = it.select(".content-thumbnail img").attr("src")
            }
        }
    }

    private fun Document.toDetailMovie(): DetailMovie {
        val nuxtDataJson = selectFirst("#__NUXT_DATA__")?.html()
        val objectMapper = ObjectMapper()
        val nuxtData = objectMapper.readTree(nuxtDataJson);
        return objectMapper.convertValue(nuxtData.extractNuxtData(), DetailMovie::class.java)
    }

    private fun JsonNode.extractNuxtData(): JsonNode {
        return get(5).getData(this).get("\$sresData")
    }

    private fun JsonNode.getData(dataNuxt: JsonNode): JsonNode {
        val mapper = ObjectMapper()
        return when {
            isObject -> {
                val newNode = mapper.createObjectNode()
                fields().forEach { (key, value) ->
                    newNode.set<JsonNode>(key, dataNuxt.get(value.asInt()).getData(dataNuxt))
                }
                newNode
            }

            isArray -> {
                val newArray = map { dataNuxt.get(it.asInt()).getData(dataNuxt) }
                jacksonObjectMapper().valueToTree(newArray)
            }

            else -> this
        }
    }

    private fun getTvType(str: String?, isMovie: Boolean): TvType {
        if (str == null) return TvType.Movie
        if (str.lowercase().contains("anime")) {
            if (!isMovie) {
                return TvType.Anime
            } else {
                return TvType.AnimeMovie
            }
        }
        if (!isMovie) return TvType.TvSeries
        return TvType.Movie
    }

    private fun Document.isWatchPage(): Boolean {
        return selectFirst(".singlelink .lcp_catlist") == null
    }

    private fun Element.getElementsBefore(cssQuery: String?): Elements {
        // Find the target element using the CSS query
        val targetElement = selectFirst(cssQuery!!)
            ?: return Elements() // Return an empty Elements if target not found

        // Get all siblings of the target element's parent
        val siblings = targetElement.parent()?.children()

        // Create a new Elements object to hold the result
        val beforeElements = Elements()

        // Loop through siblings until we reach the target element
        if (siblings != null) {
            for (sibling in siblings) {
                if (sibling === targetElement) {
                    break // Stop when we find the target element
                }
                beforeElements.add(sibling)
            }
        }

        return beforeElements
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query}&post_type%5B%5D=post&post_type%5B%5D=tv").document
        return document.toSearchResponse()
    }

    override suspend fun load(url: String): AnimeLoadResponse {
        val data = app.get(url).document

        val type = if (data.selectFirst(".page-links") != null) TvType.TvSeries else TvType.Movie
        val year = data.select(".gmr-movie-innermeta")[1].select(".gmr-movie-genre a").text().trim()
            .toInt()


        val episodes = mutableListOf<Episode>()
        val seasons = mutableListOf<SeasonData>()
        episodes.add(
            newEpisode(url, {
                this.episode = 1
            })
        )

        val episodesEl = data.select(".page-links a")
        for (episode in episodesEl) {
            episodes.add(
                newEpisode(episode.attr("href"), {
                    this.episode = episode.select(".page-link-number").text().toInt()
                })
            )
        }
        val img = (data.selectFirst(".gmr-movie-data img")?.attr("src") ?: "").replace("-60x90", "")
        return newAnimeLoadResponse(
            data.selectFirst(".entry-title")?.text()?.replace("Nonton", "")?.replace("Sub Indo", "")
                ?.trim() ?: "",
            url,
            type
        ) {
            score = Score.from(data.selectFirst(".gmr-rating-content [itemprop='ratingValue']")?.text()?.trim()
                ?.replace(",", ".")?.toFloat()?.toInt(), 10)
            backgroundPosterUrl = img
            posterUrl = img
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            plot = ""
            tags = data.select(".gmr-movie-genre a").map { it.text() }
        }
    }

    fun String.getYear(): Int? {
        val format = SimpleDateFormat("yyyy-MM-dd")
        val date = format.parse(this)
        val year = date?.year?.plus(1900)
        return year
    }

    private fun Document.getTableContent(title: String): String? {
        return select(".entry-content table tr")
            .find {
                it.selectFirst("th")?.ownText()?.trim()?.lowercase() == title.lowercase()
            }
            ?.selectFirst("td")?.text()?.trim()
    }

    private fun getStatus(status: String?): ShowStatus {
        if (status != null) {
            if (status.contains("Ongoing", true)) {
                return ShowStatus.Ongoing
            }
        }
        return ShowStatus.Completed
    }

    fun String.ensureHttp(): String {
        return if (startsWith("http://") || startsWith("https://")) {
            this
        } else {
            "https://$this"
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val juraganLink = doc.selectFirst(".pull-right a.tombol")?.attr("href") ?: return false

        val document = app.get(juraganLink, referer = data).document
        val dlLinks = document.select(".main-menu-container li a").map { it.attr("href") }
        for (link in dlLinks) {
            if (link !== juraganLink) {
                if (link.contains("armob.biz.id")) {
                    ArmoBiz().getUrl(
                        link.replace("\n", ""),
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                } else {
                    loadExtractor(
                        link,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
        return true
    }

    fun String.extractSubjectId(): String? {
        val regex = "subjectId=(\\d+)".toRegex()
        val matchResult = regex.find(this)
        return matchResult?.groups?.get(1)?.value
    }

    fun String.getStreamType(): ExtractorLinkType {
        if (this == "MP4") return ExtractorLinkType.VIDEO
        return ExtractorLinkType.M3U8
    }

//    private suspend fun customLoadExtractor(
//        name: String,
//        url: String,
//        referer: String? = null,
//        subtitleCallback: (SubtitleFile) -> Unit,
//        callback: (ExtractorLink) -> Unit
//    ): Boolean {
//        if (url.contains("dood")) return true
//        if (url.contains("154.26.137.28")) {
//            AnimeSailEmbed().getUrl(
//                url,
//                name,
//                subtitleCallback = subtitleCallback,
//            ) { link ->
//                callback.invoke(
//                    ExtractorLink(
//                        AnimeSailEmbed.getSource(link.name) ?: link.name,
//                        name,
//                        link.url,
//                        link.referer,
//                        getQualityFromName(name),
//                        link.type,
//                        link.headers,
//                        link.extractorData
//                    )
//                )
//            }
//        } else {
//            loadExtractor(
//                url,
//                referer,
//                subtitleCallback
//            ) { link ->
//                callback.invoke(
//                    ExtractorLink(
//                        link.name,
//                        name,
//                        link.url,
//                        link.referer,
//                        getQualityFromName(name),
//                        link.type,
//                        link.headers,
//                        link.extractorData
//                    )
//                )
//            }
//        }
//        return true
//    }

    data class Streamsb(
        @JsonProperty("link") val link: String?,
    )

    data class Server(
        @JsonProperty("streamsb") val streamsb: Streamsb?,
    )

    data class Sources(
        @JsonProperty("server") val server: Server?,
    )

    data class Responses(
        @JsonProperty("data") val data: ArrayList<Anime>? = arrayListOf(),
    )

    data class Anime(
        @JsonProperty("post_title") val postTitle: String?,
        @JsonProperty("post_name") val postName: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("total_episode") val totalEpisode: String?,
        @JsonProperty("salt") val salt: String?,
    )

}