package eu.kanade.tachiyomi.animeextension.zh.xfani

import keiyoushi.utils.extractNextJs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

object XfaniParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun catalogue(body: String): List<AnimeInfo> = json.decodeFromString(body)

    fun recent(document: Document): List<AnimeInfo> {
        val board = document.extractNextJs<RecentInfo>()
            ?: error("最近更新加载失败，请稍后重试。")
        // Use the site's wider 14-day window, preserving its newest-episode ordering.
        return board.windows.maxByOrNull { it.days }?.items.orEmpty()
            .distinctBy { it.id }
            .map { AnimeInfo(it.id, it.title, coverUrl = it.coverUrl, isFinished = it.isFinished) }
    }

    fun recentPage(items: List<AnimeInfo>, page: Int, pageSize: Int): List<AnimeInfo> = items.drop((page - 1) * pageSize).take(pageSize)

    fun hasNextPage(items: List<AnimeInfo>, requestBody: String, pageSize: Int): Boolean {
        val page = json.parseToJsonElement(requestBody).jsonObject.getValue("page_number").jsonPrimitive.int
        return items.isNotEmpty() && page.toLong() * pageSize < items.first().totalCount
    }

    fun detail(document: Document): DetailInfo = document.extractNextJs<DetailInfo>()
        ?: error("番剧数据加载失败，请在 WebView 中完成验证后重试。")

    fun playPage(document: Document): PlayInfo = document.extractNextJs<PlayInfo>()
        ?: error("播放页数据加载失败，请在 WebView 中完成验证后重试。")

    fun episodes(sources: List<SourceInfo>, preferredCode: String): List<EpisodeInfo> {
        val preferred = sources.firstOrNull { it.code == preferredCode }
        // Include specials and episodes exclusive to another line, without duplicating episode IDs.
        return (listOfNotNull(preferred) + sources.filter { it != preferred })
            .flatMap { it.episodes }.distinctBy { it.id }
            .sortedWith(compareBy<EpisodeInfo> { it.kind != "main" }.thenBy { it.kind }.thenBy { it.number })
    }

    fun episodePath(animeId: Int, episode: EpisodeInfo, sources: List<SourceInfo>, preferredCode: String): String {
        val available = sources.filter { source -> source.episodes.any { it.id == episode.id } }
        val source = available.firstOrNull { it.code == preferredCode } ?: available.firstOrNull()
            ?: error("暂无可用播放线路。")
        return "/anime/$animeId/play/${episode.id}?source=${source.code}"
    }

    fun episodeName(episode: EpisodeInfo): String {
        val number = if (episode.number % 1f == 0f) episode.number.toInt().toString() else episode.number.toString()
        val label = if (episode.kind == "main") "第${number}集" else "${episode.kind.uppercase()} $number"
        return episode.title?.takeIf { it.isNotBlank() }?.let { "$label $it" } ?: label
    }

    fun playback(body: String, sourceId: Int): PlaybackCandidate {
        val playback = json.decodeFromString<PlaybackInfo>(body)
        check(playback.ok) { "播放解析失败：${playback.error ?: "unknown"}，请切换线路或稍后重试。" }
        val candidate = playback.candidates.firstOrNull { it.sourceId == sourceId }
            ?: error("当前线路没有可用视频，请切换线路。")
        check(candidate.url.startsWith("https://") || candidate.url.startsWith("http://")) { "无效的视频链接。" }
        return candidate
    }
}
