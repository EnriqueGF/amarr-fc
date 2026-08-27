package amarr.torznab.indexer

import amarr.MagnetLink
import amarr.torznab.model.Feed.Channel.Item.TorznabAttribute
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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
        capabilities.categories.category.size shouldBe 1
        capabilities.categories.category[0].name shouldBe "TV"
        capabilities.categories.category[0].id shouldBe 5000
        capabilities.categories.category[0].subcat.single().id shouldBe 5030
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

})
