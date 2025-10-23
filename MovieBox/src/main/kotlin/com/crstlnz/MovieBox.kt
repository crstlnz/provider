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
import com.lagradost.cloudstream3.utils.VideoDownloadHelper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.RequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


class MovieBox : MainAPI() {
    override var mainUrl = "https://moviebox.id"

    var videoRefererBaseUrl = "https://fmoviesunblocked.net"
    var videoPageUrl = mainUrl
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.OVA,
        TvType.TvSeries
    )

    init {
        val headers = mutableMapOf<String, String>()
        headers["Cookie"] = "_as_ipin_ct=ID;"
        app.defaultHeaders = headers
    }

    override val mainPage = mainPageOf(
        "872031290915189720" to "Top 20",
        "6001471894749331600" to "Ramadhan",
        "5848753831881965888" to "Horror Indonesia",
        "4380734070238626200" to "Hot K-Drama",
        "6528093688173053896" to "Film Indonesia Baru",
        "5283462032510044280" to "Sinetron Favorite\uD83D\uDC97",
        "movie_hottest" to "Film Paling Top",
        "997144265920760504" to "Film Populer",
        "animation_hottest" to "Top Anime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val res = app.post(
            "$mainUrl/wefeed-h5-bff/web/ranking-list/content",
            data = mapOf(
                "id" to request.data,
                "page" to page.toString(),
                "perPage" to "30"
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "content-type" to "application/json",
                "origin" to mainUrl,
                "referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
                "x-client-info" to "{\"timezone\":\"Asia/Jakarta\"}"
            )
        )

        println(res.text)
        val data = res.parsed<HomeSearch>()
        return newHomePageResponse(request.name, data.data?.subjectList?.map {
            newMovieSearchResponse(
                name = it?.title ?: "",
                url = "$mainUrl/movies/${it?.detailPath}?id=${it?.subjectId}&scene=&type=/movie/detail",
                TvType.Movie
            ) {
                posterUrl = it?.cover?.url
            }
        } ?: listOf())
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
        println("=====================");
        println(nuxtData.extractNuxtData());
        println("=====================")
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
                "origin" to mainUrl,
                "referer" to mainUrl,
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

    fun transformUrl(url: String): String {
        val base = url.replace("/detail/", "/spa/videoPlayPage/movies/")
        val queries = base.substringAfter("?", "")
            .split("&")
            .filter { it.startsWith("id=") || it.startsWith("type=") }
            .joinToString("&")
        return base.substringBefore("?") + "?" + queries
    }

    override suspend fun load(url: String): AnimeLoadResponse {
        val data = app.get(url).document.toDetailMovie()
        val referer = transformUrl(url)
        val isMovie =
            data.resource?.seasons == null || data.resource.seasons.find { it?.maxEp == 0 || it?.se == 0 } != null
        val type = getTvType(data.subject?.genre, isMovie)
        val year = data.subject?.releaseDate?.getYear()
        val tracker = if (data.subject?.countryName === "Japan")
            APIHolder.getTracker(
                listOf(data.subject?.title ?: ""),
                TrackerType.getTypes(type),
                year,
                true
            ) else null

        val episodes = mutableListOf<Episode>()
        val seasons = mutableListOf<SeasonData>()
        if (isMovie) {
            episodes.add(
                newEpisode(
                    "${videoPageUrl}/wefeed-h5-bff/web/subject/play?subjectId=${data.subject?.subjectId}&se=0&ep=0|${referer}",
                    {
                        episode = 1
                        name = data.subject?.title
                    })

            )
        } else {
            for (season in data.resource?.seasons ?: listOf()) {
                for (ep in 1..(season?.maxEp ?: 0)) {
                    seasons.add(SeasonData(season?.se ?: 0, "Season ${season?.se}"))
                    episodes.add(
                        newEpisode(
                            "$videoPageUrl/wefeed-h5-bff/web/subject/play?subjectId=${data.subject?.subjectId}&se=${season?.se}&ep=${ep}|${url}",
                            {
                                this.episode = ep
                                this.season = season?.se
                            })
                    )
                }
            }
        }

        return newAnimeLoadResponse(
            data.subject?.title ?: "",
            url,
            type
        ) {
            score = Score.from(((data.subject?.imdbRatingValue?.toFloatOrNull() ?: (0f * 10f))),10)
            backgroundPosterUrl = tracker?.cover
            posterUrl = tracker?.image ?: data.metadata?.image
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            addSeasonNames(seasons)
            plot = data.subject?.description
            println(data.subject?.genre?.split(",")?.map { it.trim() })
            tags = data.subject?.genre?.split(",")?.map { it.trim() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
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

    fun addUrlParams(baseUrl: String, params: Map<String, String>): String {
        if (params.isEmpty()) return baseUrl

        val separator = if (baseUrl.contains("?")) "&" else "?"
        val encodedParams = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

        return "$baseUrl$separator$encodedParams"
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val datas = data.split("|")
        val url = datas.first()
        val referer = datas.getOrNull(1) ?: ""
        println("Referer ${referer}")
        println("Url ${url}")
        val episodeData = app.get(
            url, headers = mapOf(
                "referer" to referer,
//            "X-Requested-With" to "XMLHttpRequest",
                "x-client-info" to "{\"timezone\":\"Asia/Jakarta\"}",
//            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
//            "Cookie" to "account=849960928765527600|0|H5|1746602024|"
            )
        ).parsed<EpisodeData>()
//        println("Episode Data ${episodeData}")

        for (stream in episodeData.data?.streams ?: listOf()) {
            val quality = getQualityFromName(stream?.resolutions ?: "")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = addUrlParams(
                        (stream?.url ?: "").ensureHttp(), mapOf(
                            "_q" to quality.toString()
                        )
                    ),
                    type = stream?.format?.getStreamType() ?: ExtractorLinkType.VIDEO,
                ) {
                    this.referer = referer.replace(mainUrl, videoRefererBaseUrl)
                    this.quality = quality
                }
            )
        }
        try {
            val subtitleData =
                app.get("$videoPageUrl/wefeed-h5-bff/web/subject/caption?format=MP4&id=${episodeData.data?.streams?.first()?.id}&subjectId=${data.extractSubjectId()}")
                    .parsed<CaptionData>()
            for (caption in subtitleData.data?.captions ?: listOf()) {
                val lang = caption?.lanName ?: caption?.lan ?: ""
                subtitleCallback(
                    SubtitleFile(
                        lang = if (lang.lowercase().trim() == "indonesia") "Indonesian" else lang,
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
//        if (this == "MP4") return ExtractorLinkType.VIDEO
//        return ExtractorLinkType.M3U8
        return ExtractorLinkType.VIDEO
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