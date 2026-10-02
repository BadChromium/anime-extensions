package eu.kanade.tachiyomi.animeextension.zh.xfani

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeInfo(
    val id: Int,
    val title: String,
    val aliases: List<String>? = null,
    @SerialName("title_original") val titleOriginal: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    val director: String? = null,
    val actors: List<String>? = null,
    @SerialName("meta_tags") val metaTags: List<String>? = null,
    @SerialName("is_finished") val isFinished: Boolean = false,
    @SerialName("total_count") val totalCount: Int = 0,
)

@Serializable
data class DetailInfo(
    val anime: AnimeInfo,
    val sources: List<SourceInfo>,
)

@Serializable
data class RecentInfo(val windows: List<RecentWindow>)

@Serializable
data class RecentWindow(val days: Int, val items: List<RecentAnime>)

@Serializable
data class RecentAnime(
    val id: Int,
    val title: String,
    val coverUrl: String? = null,
    val isFinished: Boolean = false,
)

@Serializable
data class PlayInfo(
    val episodeId: Int,
    val animeId: Int,
    val sources: List<SourceInfo>,
    val pageSourceCode: String,
)

@Serializable
data class SourceInfo(
    val id: Int,
    val code: String,
    val name: String,
    val episodes: List<EpisodeInfo>,
)

@Serializable
data class EpisodeInfo(
    val id: Int,
    val kind: String = "main",
    val title: String? = null,
    @SerialName("episode_number") val number: Float,
)

@Serializable
data class PlaybackInfo(
    val ok: Boolean,
    val error: String? = null,
    val url: String? = null,
    val candidates: List<PlaybackCandidate> = emptyList(),
)

@Serializable
data class PlaybackCandidate(
    @SerialName("source_id") val sourceId: Int,
    @SerialName("source_name") val sourceName: String,
    val url: String,
    val quality: String? = null,
)
