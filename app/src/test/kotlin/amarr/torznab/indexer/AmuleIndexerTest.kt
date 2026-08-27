package amarr.torznab.indexer

import amarr.MagnetLink
import amarr.torznab.model.Feed.Channel.Item.TorznabAttribute
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jamule.AmuleClient
import jamule.request.SearchType
import jamule.response.SearchResultsResponse
import jamule.response.SearchResultsResponse.SearchFile
import org.slf4j.LoggerFactory

class AmuleIndexerTest : StringSpec({
    val mockClient = mockk<AmuleClient>()
    val logger = LoggerFactory.getLogger(AmuleIndexerTest::class.java)

    "should advertise the TV category in capabilities" {
        val indexer = AmuleIndexer(mockClient, logger)
        val capabilities = indexer.capabilities()
        val tv = capabilities.categories.category.single { it.id == 5000 }
        tv.name shouldBe "TV"
        tv.subcat.single().id shouldBe 5030
        capabilities.categories.category.single { it.id == 2000 }.name shouldBe "Movies"
        capabilities.searching.movieSearch.available shouldBe "yes"
    }

    "when empty queried should not launch a global Kad search" {
        val indexer = AmuleIndexer(mockClient, logger)
        val results = indexer.search("", 0, 1000, listOf())
        results.channel.response.total shouldBe 0
        results.channel.response.offset shouldBe 0
        results.channel.item.size shouldBe 0
        verify { mockClient wasNot Called }
    }

    "when queried calls amule client" {
        val searchFile = SearchFile(
            fileName = "test.mkv",
            hash = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            sizeFull = 1000,
            completeSourceCount = 1,
            sourceCount = 2,
            downloadStatus = SearchResultsResponse.SearchFileDownloadStatus.NEW,
        )
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(listOf(searchFile)))
        val indexer = AmuleIndexer(mockClient, logger)
        val result = indexer.search("test", 0, 1000, listOf())
        verify { mockClient.searchSync("test", SearchType.GLOBAL) }
        result.channel.response.total shouldBe 1
        result.channel.response.offset shouldBe 0
        result.channel.item.size shouldBe 1
        val item = result.channel.item[0]
        item.title shouldBe "test.mkv"
        item.enclosure.url shouldBe MagnetLink.forAmarr(searchFile.hash, "test.mkv", searchFile.sizeFull).toString()
        item.enclosure.length shouldBe 1000
        item.attributes.size shouldBe 4
        item.attributes shouldContain TorznabAttribute("category", "5030")
        item.attributes shouldContain TorznabAttribute("size", "1000")
        item.attributes shouldContain TorznabAttribute("seeders", "1")
        item.attributes shouldContain TorznabAttribute("peers", "2")
    }

    "should filter noisy non-video search results" {
        val videoFile = SearchFile(
            fileName = "matrix.mkv",
            hash = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            sizeFull = 1000,
            completeSourceCount = 1,
            sourceCount = 2,
            downloadStatus = SearchResultsResponse.SearchFileDownloadStatus.NEW,
        )
        val nfoFile = videoFile.copy(fileName = "matrix.nfo")
        val zipFile = videoFile.copy(fileName = "matrix.zip")
        val mp3File = videoFile.copy(fileName = "matrix.mp3")
        every { mockClient.searchSync(any(), any()) } returns Result.success(
            SearchResultsResponse(listOf(videoFile, nfoFile, zipFile, mp3File))
        )

        val indexer = AmuleIndexer(mockClient, logger)
        val result = indexer.search("matrix", 0, 1000, listOf())

        result.channel.response.total shouldBe 1
        result.channel.item.map { it.title } shouldBe listOf("matrix.mkv")
    }

    "should normalize query accents and punctuation before searching amule" {
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(emptyList()))

        val indexer = AmuleIndexer(mockClient, logger)
        indexer.search("C'est un complot", 0, 1000, listOf())

        verify { mockClient.searchSync("C est un complot", SearchType.GLOBAL) }
    }

    "should cache identical searches to protect the aMule global search slot" {
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(emptyList()))
        val indexer = AmuleIndexer(mockClient, logger, cacheSeconds = 60)

        indexer.search("The Show S01E02", 0, 100, emptyList())
        indexer.search("The Show S01E02", 0, 100, emptyList())

        verify(exactly = 1) { mockClient.searchSync("The Show S01E02", SearchType.GLOBAL) }
    }

    "should fall back to Kad when no eD2k server is connected" {
        every { mockClient.searchSync("fallback", SearchType.GLOBAL) } returns Result.failure(
            IllegalStateException("eD2k unavailable")
        )
        every { mockClient.searchSync("fallback", SearchType.KAD) } returns Result.success(
            SearchResultsResponse(emptyList())
        )

        val indexer = AmuleIndexer(mockClient, logger)
        indexer.search("fallback", 0, 100, emptyList())

        verify(exactly = 1) { mockClient.searchSync("fallback", SearchType.GLOBAL) }
        verify(exactly = 1) { mockClient.searchSync("fallback", SearchType.KAD) }
    }

    "should find typical season naming styles with one broad amule search" {
        val files = listOf(
            searchFile("Muertos SL S01E02.mkv", 1),
            searchFile("Muertos.SL.1x06.1080p.mkv", 2),
            searchFile("Muertos SL T01E08 Castellano.mkv", 3),
            searchFile("Muertos SL Capitulo 103.mkv", 4),
            searchFile("Muertos SL Temporada Primera.mkv", 5),
            searchFile("Muertos SL S02E01.mkv", 6),
            searchFile("Unrelated movie.mkv", 7),
        )
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(files))

        val result = AmuleIndexer(mockClient, logger).searchTv("Muertos SL", 1, null, 0, 100, emptyList())

        verify(exactly = 1) { mockClient.searchSync("Muertos SL", SearchType.GLOBAL) }
        result.channel.item.map { it.title }.shouldContainExactlyInAnyOrder(
            "Muertos SL S01E02.mkv",
            "Muertos.SL.1x06.1080p.mkv",
            "Muertos SL T01E08 Castellano.mkv",
            "Muertos SL Capitulo 103.mkv",
            "Muertos SL Temporada Primera.mkv",
        )
    }

    "should select an exact episode across alternate naming styles" {
        val files = listOf(
            searchFile("Death Inc S01E03.mkv", 1),
            searchFile("Muertos SL 1x03 Castellano.mkv", 2),
            searchFile("Muertos SL T01E03 1080p.mkv", 3),
            searchFile("Muertos SL Capitulo 103.mkv", 4),
            searchFile("Muertos SL S01E04.mkv", 5),
        )
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(files))

        val result = AmuleIndexer(mockClient, logger).searchTv("Muertos SL", 1, 3, 0, 100, emptyList())

        result.channel.item.map { it.title }.shouldContainExactlyInAnyOrder(
            "Muertos SL 1x03 Castellano.mkv",
            "Muertos SL T01E03 1080p.mkv",
            "Muertos SL Capitulo 103.mkv",
        )
    }

    "should reject Kad false positives that only contain part of the title" {
        val files = listOf(
            searchFile("Dr Death 1x03 The Incident.mkv", 1),
            searchFile("Love and Death S01E03.mkv", 2),
        )
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(files))

        val result = AmuleIndexer(mockClient, logger).searchTv("Death Inc", 1, null, 0, 100, emptyList())

        result.channel.item shouldBe emptyList()
    }

    "should reject broad results when filenames have no recognizable season marker" {
        val file = searchFile("Show complete collection.mkv", 1)
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(listOf(file)))

        val result = AmuleIndexer(mockClient, logger).searchTv("Show", 1, null, 0, 100, emptyList())

        result.channel.item shouldBe emptyList()
    }

    "should advertise a contiguous season as a downloadable virtual pack" {
        val files = listOf(
            searchFile("Dragon Ball Super S01E01 1080p.mkv", 1),
            searchFile("Dragon Ball Super 1x02 1080p.mkv", 2),
            searchFile("Dragon Ball Super T01E03 1080p.mkv", 3),
            searchFile("Dragon Ball Super S02E01 1080p.mkv", 4),
        )
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(files))

        val result = AmuleIndexer(mockClient, logger).searchTv("Dragon Ball Super", 1, null, 0, 100, emptyList())
        val packItem = result.channel.item.first()
        val pack = MagnetLink.fromString(packItem.enclosure.url)

        packItem.title shouldBe "Dragon Ball Super S01 PACK aMule"
        pack.isPack() shouldBe true
        pack.packMembers().map { it.name }.toSet() shouldBe setOf(
            "Dragon Ball Super S01E01 1080p.mkv",
            "Dragon Ball Super 1x02 1080p.mkv",
            "Dragon Ball Super T01E03 1080p.mkv",
        )
        result.channel.response.total shouldBe 4
    }

    "should return a movie category requested by Radarr" {
        val file = searchFile("Movie 2026.mkv", 1)
        every { mockClient.searchSync(any(), any()) } returns Result.success(SearchResultsResponse(listOf(file)))

        val result = AmuleIndexer(mockClient, logger).search("Movie 2026", 0, 100, listOf(2000))

        result.channel.item.single().attributes shouldContain TorznabAttribute("category", "2000")
    }

})

private fun searchFile(name: String, id: Int) = SearchFile(
    fileName = name,
    hash = ByteArray(16) { index -> (index + id).toByte() },
    sizeFull = 1_000_000_000,
    completeSourceCount = 1,
    sourceCount = 2,
    downloadStatus = SearchResultsResponse.SearchFileDownloadStatus.NEW,
)
