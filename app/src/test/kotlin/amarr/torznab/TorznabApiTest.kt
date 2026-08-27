package amarr.torznab

import amarr.torznab.indexer.AmuleIndexer
import amarr.torznab.indexer.ddunlimitednet.DdunlimitednetIndexer
import amarr.torznab.model.Caps
import amarr.torznab.model.Feed
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class TorznabApiTest : StringSpec({
    val amuleIndexer = mockk<AmuleIndexer>()
    val ddunlimitednetIndexer = mockk<DdunlimitednetIndexer>()

    beforeAny {
        clearAllMocks()
    }

    "should throw exception when missing action" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            client.get("/api").status shouldBe HttpStatusCode.InternalServerError
        }
    }

    "should throw exception on unknown action" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            client.get("/api?t=unknown").status shouldBe HttpStatusCode.InternalServerError
        }
    }

    "should get capabilities from amule indexer when called on /api" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            coEvery { amuleIndexer.capabilities() } returns Caps()
            client.get("/api?t=caps")
            coVerify { amuleIndexer.capabilities() }
        }
    }

    "should pass query, offset and limits to amule indexer when called on /api" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            coEvery {
                amuleIndexer.search(
                    "test",
                    0,
                    100,
                    listOf()
                )
            } returns emptyFeed()
            client.get("/api?t=search&q=test&offset=0&limit=100")
            coVerify { amuleIndexer.search("test", 0, 100, listOf()) }
        }
    }

    "should preserve plain search for tv queries without season and episode" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            coEvery { amuleIndexer.searchTv("show", null, null, 0, 100, listOf()) } returns emptyFeed()
            client.get("/api?t=tvsearch&q=show&offset=0&limit=100")
            coVerify { amuleIndexer.searchTv("show", null, null, 0, 100, listOf()) }
        }
    }

    "should use a safe probe for Torznab validation searches" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            coEvery { amuleIndexer.search("S01E01", 0, 100, listOf(5030)) } returns emptyFeed()
            client.get("/api?t=search&cat=5030")
            coVerify { amuleIndexer.search("S01E01", 0, 100, listOf(5030)) }
        }
    }

    "should preserve Sonarr season and episode semantics for the amule indexer" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            coEvery { amuleIndexer.searchTv(any(), any(), any(), 0, 100, listOf()) } returns emptyFeed()
            client.get("/api?t=tvsearch&q=show&season=1&ep=2&offset=0&limit=100")
            coVerify { amuleIndexer.searchTv("show", 1, 2, 0, 100, listOf()) }
        }
    }

    "should preserve a season search without inventing an episode" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer)
            }
            coEvery { amuleIndexer.searchTv(any(), any(), any(), 0, 100, listOf(5030)) } returns emptyFeed()
            client.get("/api?t=tvsearch&q=show&season=1&cat=5030&offset=0&limit=100")
            coVerify { amuleIndexer.searchTv("show", 1, null, 0, 100, listOf(5030)) }
        }
    }

    "should reject a missing Torznab API key" {
        testApplication {
            application {
                torznabApi(amuleIndexer, ddunlimitednetIndexer, "private-key")
            }
            client.get("/indexer/amule/api?t=caps").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})

private fun emptyFeed() = Feed(
    channel = Feed.Channel(
        response = Feed.Channel.Response(offset = 0, total = 0),
        item = emptyList()
    )
)
