package com.crstlnz

//import com.crstlnz.utils.getLastNumber
//import com.crstlnz.utils.loadAnimeSail
//import com.crstlnz.utils.splitTitles
//import com.crstlnz.utils.toAbsoluteURL
import com.crstlnz.models.CaptionData
import com.crstlnz.models.DetailMovie
import com.crstlnz.models.EpisodeData
import com.crstlnz.models.HomeData
import com.crstlnz.models.SearchAPI
import com.crstlnz.utils.AnimeSailEmbed
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
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrackerType
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
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


class MovieBox : MainAPI() {
    override var mainUrl = "https://moviebox.ng"
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    init {
        val headers = mutableMapOf<String, String>()
        headers["Cookie"] = "_as_ipin_ct=ID;"
        app.defaultHeaders = headers
    }

    override val mainPage = mainPageOf(
        "/" to "Top",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = app.get(mainUrl).document.toHomeSearchResponse()
        return newHomePageResponse(request.name, home)
    }

    private fun Document.toHomeSearchResponse(): List<SearchResponse> {
        val nuxtDataJson = selectFirst("#__NUXT_DATA__")?.html()
        val objectMapper = ObjectMapper()
        val nuxtData = objectMapper.readTree(nuxtDataJson);
        val data = objectMapper.convertValue(nuxtData.extractNuxtData(), HomeData::class.java)
        return data.banner?.items?.map {
            newMovieSearchResponse(
                name = it?.title ?: "",
                url = it?.url?.ensureHttp() ?: "",
                type = TvType.Movie
            ) {
                posterUrl = it?.image?.url
            }
        } ?: listOf()
    }

    private fun Document.toDetailMovie(): DetailMovie {
        val nuxtDataJson = selectFirst("#__NUXT_DATA__")?.html()
        val objectMapper = ObjectMapper()
        val nuxtData = objectMapper.readTree(nuxtDataJson);
        return objectMapper.convertValue(nuxtData.extractNuxtData(), DetailMovie::class.java)
    }

    private fun JsonNode.extractNuxtData(): JsonNode {
        return get(7).getData(this)
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
        val data = app.post(
            "$mainUrl/wefeed-h5-bff/web/subject/search",
            data = mapOf(
                "keyword" to query,
                "page" to "1",
                "perPage" to "30"
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "content-type" to "application/json",
                "origin" to "https://moviebox.ng",
                "referer" to "https://moviebox.ng",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
                "x-client-info" to "{\"timezone\":\"Asia/Jakarta\"}"
            )
        ).parsed<SearchAPI>()
        return data.data?.items?.map {
            newMovieSearchResponse(
                name = it?.title ?: "",
                url = "$mainUrl/movies/${it?.detailPath}?id=${it?.subjectId}&scene=&type=/movie/detail",
                TvType.Movie
            ) {
                posterUrl = it?.cover?.url
            }
        } ?: listOf()
    }

    override suspend fun load(url: String): AnimeLoadResponse {
        val data = app.get(url).document.toDetailMovie()
        val isMovie =
            data.resource?.seasons == null || data.resource.seasons.find { it?.maxEp == 0 || it?.se == 0 } != null
        val type = getTvType(data.subject?.genre, isMovie)
        val year = data.subject?.releaseDate?.getYear()
        val tracker =
            APIHolder.getTracker(
                listOf(data.subject?.title ?: ""),
                TrackerType.getTypes(type),
                year,
                true
            )

        val episodes = mutableListOf<Episode>()
        val seasons = mutableListOf<SeasonData>()
        println("APAKAH MOVIE ${isMovie}")
        if (isMovie) {
            episodes.add(
                Episode(
                    "$mainUrl/wefeed-h5-bff/web/subject/play?subjectId=${data.subject?.subjectId}&se=0&ep=0",
                    episode = 1,
                    name = data.subject?.title
                )
            )
        } else {
            for (season in data.resource?.seasons ?: listOf()) {
                for (ep in 1..(season?.maxEp ?: 0)) {
                    seasons.add(SeasonData(season?.se ?: 0, "Season ${season?.se}"))
                    episodes.add(
                        Episode(
                            "$mainUrl/wefeed-h5-bff/web/subject/play?subjectId=${data.subject?.subjectId}&se=${season?.se}&ep=${ep}",
                            episode = ep,
                            season = season?.se
                        )
                    )
                }
            }
        }

        return newAnimeLoadResponse(
            data.subject?.title ?: "",
            url,
            type
        ) {
            backgroundPosterUrl = tracker?.cover
            posterUrl = tracker?.image ?: data.metadata?.image
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            addSeasonNames(seasons)
//            showStatus = status
            plot = data.subject?.description
//            this.tags = tags
//            addMalId(tracker?.malId)
//            addAniListId(tracker?.aniId?.toIntOrNull())
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
        val episodeData = app.get(data).parsed<EpisodeData>()
        for (stream in episodeData.data?.streams ?: listOf()) {
            callback(
                ExtractorLink(
                    source = "MovieBox",
                    name = stream?.resolutions ?: "",
                    referer = mainUrl,
                    url = (stream?.url ?: "").ensureHttp(),
                    type = stream?.format?.getStreamType() ?: ExtractorLinkType.VIDEO,
                    quality = getQualityFromName(stream?.resolutions ?: "")
                )
            )
        }
        try {
            val subtitleData =
                app.get("$mainUrl/wefeed-h5-bff/web/subject/caption?format=MP4&id=${episodeData.data?.streams?.first()?.id}&subjectId=${data.extractSubjectId()}")
                    .parsed<CaptionData>()
            for (caption in subtitleData.data?.captions ?: listOf()) {
                subtitleCallback(
                    SubtitleFile(
                        lang = caption?.lan ?: "",
                        url = (caption?.url ?: "").ensureHttp()
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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