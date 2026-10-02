package eu.kanade.tachiyomi.animeextension.zh.xfani

import org.junit.Assert.assertEquals
import org.junit.Test
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import uy.kohesive.injekt.api.addSingleton

class XfaniParserTest {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun setUp() {
            uy.kohesive.injekt.Injekt.addSingleton(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        }
    }
    private fun fixture(name: String): String = javaClass.getResource("/$name")!!.readText()

    @Test
    fun latestReadsEpisodeUpdatesAndPaginatesWithoutRepeats() {
        val items = XfaniParser.recent(Jsoup.parse(fixture("recent.html")))
        assertEquals(60, items.size)
        assertEquals(3506, items.first().id)
        assertEquals("黑化吧！圣女大人 Season2", items.first().title)
        val pages = (1..3).flatMap { XfaniParser.recentPage(items, it, 24) }
        assertEquals(items, pages)
        assertTrue(XfaniParser.recentPage(items, 4, 24).isEmpty())
    }

    @Test
    fun filtersUseSupportedSortAndDropdownYears() {
        assertEquals("形式", VersionFilter().name)
        assertEquals("release_date", SortFilter().selected)
        val year = YearFilter()
        assertEquals("", year.selected)
        year.state = 1
        assertEquals("2026", year.selected)
        year.state = 20
        assertEquals("2007", year.selected)
    }

    @Test
    fun episodeLinkFallsBackToALineThatActuallyHasTheEpisode() {
        val episode = EpisodeInfo(42, number = 1f)
        val sources = listOf(SourceInfo(4, "xfxf1", "主线", emptyList()), SourceInfo(1, "AL", "备用", listOf(episode)))
        assertEquals("/anime/3394/play/42?source=AL", XfaniParser.episodePath(3394, episode, sources, "xfxf1"))
    }

    @Test
    fun mergesSpecialsAndLineExclusiveEpisodesById() {
        val episode = EpisodeInfo(1, number = 1f)
        val special = EpisodeInfo(2, kind = "sp", number = 1f)
        val sources = listOf(SourceInfo(4, "xfxf1", "主线", listOf(episode)), SourceInfo(1, "AL", "备用", listOf(episode, special)))
        assertEquals(listOf(episode, special), XfaniParser.episodes(sources, "xfxf1"))
        assertEquals("SP 1", XfaniParser.episodeName(special))
    }

    @Test
    fun readsLiveNextDetailAndAllEpisodeSources() {
        val detail = XfaniParser.detail(Jsoup.parse(fixture("detail.html")))
        assertEquals(3394, detail.anime.id)
        assertEquals("少女怪兽焦糖味", detail.anime.title)
        assertEquals(3, detail.sources.size)
        assertEquals(12, XfaniParser.episodes(detail.sources, "AL").size)
        assertEquals(121274, XfaniParser.episodes(detail.sources, "AL").first().id)
    }

    @Test
    fun readsLiveNextPlayer() {
        val play = XfaniParser.playPage(Jsoup.parse(fixture("play.html")))
        assertEquals(121274, play.episodeId)
        assertEquals("xfxf1", play.pageSourceCode)
    }

    @Test
    fun selectsOnlyTheRequestedPlaybackLine() {
        val candidate = XfaniParser.playback(fixture("playback.json"), 4)
        assertTrue(candidate.url.endsWith("01f.mp4"))
        assertEquals(4, candidate.sourceId)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsPlaybackErrors() {
        XfaniParser.playback("""{"ok":false,"error":"forbidden"}""", 4)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsWrongPlaybackLine() {
        XfaniParser.playback(fixture("playback.json"), 1)
    }


    @Test
    fun paginationUsesPageAndTotalRatherThanFullPageHeuristic() {
        val items = listOf(AnimeInfo(1, "A", totalCount = 25))
        assertTrue(XfaniParser.hasNextPage(items, """{"page_number":1}""", 24))
        assertEquals(false, XfaniParser.hasNextPage(items, """{"page_number":2}""", 24))
    }

    @Test
    fun parsesNextCatalogueWithoutShiftingIds() {
        val items = XfaniParser.catalogue("""[{"id":3394,"title":"少女怪兽焦糖味","cover_url":null,"actors":null,"meta_tags":["恋爱"],"total_count":1}]""")
        assertEquals(3394, items.single().id)
        assertEquals("少女怪兽焦糖味", items.single().title)
    }
}
