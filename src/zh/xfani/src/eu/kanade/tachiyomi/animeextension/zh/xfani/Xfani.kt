package eu.kanade.tachiyomi.animeextension.zh.xfani

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class Xfani :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {
    override val baseUrl = "https://next.xifanacg.com"
    override val lang = "zh"
    override val name = "稀饭动漫"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val selectedSourceCode
        get() = SOURCE_CODES.getOrElse(preferences.getString(PREF_KEY_VIDEO_SOURCE, "0")?.toIntOrNull() ?: 0) { SOURCE_CODES[0] }

    private val apiHeaders
        get() = headers.newBuilder()
            .set("apikey", API_KEY)
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

    private fun apiRequest(path: String, body: String): Request = POST(
        "$API_URL/$path",
        apiHeaders,
        body.toRequestBody("application/json; charset=utf-8".toMediaType()),
    )

    override fun popularAnimeRequest(page: Int): Request = searchAnimeRequest(page, "", AnimeFilterList(SortFilter().apply { state = 1 }))
    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent?page=$page", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val items = XfaniParser.recent(response.asJsoup())
        return AnimesPage(XfaniParser.recentPage(items, page, PAGE_SIZE).map { it.toAnime() }, page.toLong() * PAGE_SIZE < items.size)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = buildJsonObject {
            put("search_term", query)
            put("page_number", page)
            put("items_per_page", PAGE_SIZE)
            put("sort_by", filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: "release_date")
            put("sort_order", "desc")
            filters.forEach { filter ->
                when (filter) {
                    is TypeFilter -> filter.selected.toIntOrNull()?.let { put("filter_type_id", it) }
                    is ClassFilter -> if (filter.selected.isNotEmpty()) put("filter_meta_tags", JsonArray(listOf(JsonPrimitive(filter.selected))))
                    is VersionFilter -> if (filter.selected.isNotEmpty()) put("filter_format", filter.selected)
                    is YearFilter -> filter.selected.toIntOrNull()?.let { put("filter_release_year", it) }
                    else -> Unit
                }
            }
        }
        return apiRequest("rest/v1/rpc/search_animes", body.toString())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val items = XfaniParser.catalogue(response.body.string())
        // The POST response does not carry the request's page number.
        val requestBody = okio.Buffer().also { response.request.body!!.writeTo(it) }.readUtf8()
        return AnimesPage(items.map { it.toAnime() }, XfaniParser.hasNextPage(items, requestBody, PAGE_SIZE))
    }

    private fun AnimeInfo.toAnime(): SAnime = SAnime.create().apply {
        url = "/anime/$id"
        title = this@toAnime.title
        thumbnail_url = coverUrl
        description = this@toAnime.description
        author = director
        artist = actors?.joinToString(", ")
        genre = metaTags?.joinToString(", ")
        status = if (isFinished) SAnime.COMPLETED else SAnime.ONGOING
    }

    private fun animePath(anime: SAnime): String {
        require(Regex("^/anime/\\d+$").matches(anime.url)) { "旧番剧链接已失效，请在新站重新搜索番剧。" }
        return anime.url
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + animePath(anime), headers)
    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)
    override fun animeDetailsParse(response: Response): SAnime = XfaniParser.detail(response.asJsoup()).anime.toAnime()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val detail = XfaniParser.detail(response.asJsoup())
        return XfaniParser.episodes(detail.sources, selectedSourceCode).map { episode ->
            SEpisode.create().apply {
                url = XfaniParser.episodePath(detail.anime.id, episode, detail.sources, selectedSourceCode)
                name = XfaniParser.episodeName(episode)
                episode_number = episode.number
            }
        }.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        require(Regex("^/anime/\\d+/play/\\d+(?:\\?.*)?$").matches(episode.url)) { "旧播放链接已失效，请刷新番剧的剧集列表后重试。" }
        return GET(baseUrl + episode.url, headers)
    }

    private fun resolvePlayback(episodeId: Int, sourceId: Int): PlaybackCandidate {
        val body = buildJsonObject {
            put("action", "fallback")
            put("episode_id", episodeId)
            put("source_id", sourceId)
        }
        client.newCall(apiRequest("functions/v1/issue-web-playback", body.toString())).execute().use { response ->
            check(response.isSuccessful) { "播放解析失败：HTTP ${response.code}，请稍后重试或切换线路。" }
            return XfaniParser.playback(response.body.string(), sourceId)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val play = XfaniParser.playPage(response.asJsoup())
        val sources = play.sources.filter { source -> source.episodes.any { it.id == play.episodeId } }
        check(sources.isNotEmpty()) { "暂无可用播放线路。" }
        // Resolve only when selected by the user; one unavailable line must not hide the others.
        return sources.sortedByDescending { it.code == play.pageSourceCode }.map { source ->
            Video("$baseUrl/anime/${play.animeId}/play/${play.episodeId}?source=${source.code}", source.name, videoUrl = null, headers = headers)
        }
    }

    override fun videoUrlParse(response: Response): String {
        val play = XfaniParser.playPage(response.asJsoup())
        val code = response.request.url.queryParameter("source") ?: play.pageSourceCode
        val source = play.sources.firstOrNull { it.code == code && it.episodes.any { episode -> episode.id == play.episodeId } }
            ?: error("当前线路没有这一集，请切换线路。")
        return resolvePlayback(play.episodeId, source.id).url
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter(), ClassFilter(), VersionFilter(), YearFilter(), SortFilter())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_KEY_VIDEO_SOURCE
                title = "请设置首选视频源线路"
                entries = arrayOf("主线-1", "主线-2", "备用-1")
                entryValues = arrayOf("0", "1", "2")
                setDefaultValue("0")
                summary = "%s"
            },
        )
    }

    companion object {
        private const val API_URL = "https://api.xifanacg.com"

        // Public browser publishable key from the site's Supabase client, not a user credential.
        private const val API_KEY = "sb_publishable_OBIVAWACIX6lPXrO98_z24_HcsmalkA"
        private const val PAGE_SIZE = 24
        private const val PREF_KEY_VIDEO_SOURCE = "PREF_KEY_VIDEO_SOURCE"
        private val SOURCE_CODES = listOf("xfxf1", "AL", "CS")
    }
}
