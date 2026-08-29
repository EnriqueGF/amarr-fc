package amarr.torrent

import amarr.MagnetLink
import amarr.category.CategoryStore
import amarr.category.PackDownload
import amarr.category.PackMember
import amarr.security.QbitAuth
import amarr.torrent.model.Category
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jamule.AmuleClient
import jamule.model.AmuleTransferringFile
import jamule.model.DownloadCommand
import jamule.model.FileStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class TorrentApiTest : StringSpec({
    val amuleClient = mockk<AmuleClient>()
    val categoryStore = MemoryCategoryStore()
    val testMagnetHash = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    val testMagnetLink = MagnetLink.forAmarr(testMagnetHash, "test", 1)
    val finishedPath = "/finished"

    beforeAny {
        clearAllMocks()
        categoryStore.reset()
    }

    "should get preferences" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            client.get("/api/v2/app/preferences").apply {
                this.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "should get api version" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            client.get("/api/v2/app/webapiVersion").apply {
                this.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "should allow login" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            client.submitForm(formParameters = Parameters.build {
                append("username", "test")
                append("password", "test")
            }, url = "/api/v2/auth/login").apply {
                this.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "should require a valid qBittorrent session when authentication is enabled" {
        testApplication {
            val auth = QbitAuth("sonarr", "private-password")
            application {
                torrentApi(amuleClient, categoryStore, finishedPath, auth)
                configureForTest()
            }

            client.get("/api/v2/app/preferences").status shouldBe HttpStatusCode.Forbidden
            client.submitForm(
                formParameters = Parameters.build {
                    append("username", "sonarr")
                    append("password", "wrong")
                },
                url = "/api/v2/auth/login",
            ).status shouldBe HttpStatusCode.Forbidden

            val login = client.submitForm(
                formParameters = Parameters.build {
                    append("username", "sonarr")
                    append("password", "private-password")
                },
                url = "/api/v2/auth/login",
            )
            login.status shouldBe HttpStatusCode.OK
            client.get("/api/v2/app/preferences") {
                header(HttpHeaders.Cookie, "SID=${auth.sessionId}")
            }.status shouldBe HttpStatusCode.OK
        }
    }

    "should add torrent" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            val urls = listOf(testMagnetLink.toString())
            val ed2k = testMagnetLink.toEd2kLink()
            every { amuleClient.downloadEd2kLink(ed2k) } returns Result.success(Unit)
            client.submitForm(formParameters = Parameters.build {
                appendAll("urls", urls)
                append("category", "test")
                append("paused", "test")
            }, url = "/api/v2/torrents/add").apply {
                this.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "should expand a virtual season pack into real amule downloads" {
        val first = MagnetLink.forAmarr(ByteArray(16) { 1 }, "Show S01E01.mkv", 100)
        val second = MagnetLink.forAmarr(ByteArray(16) { 2 }, "Show S01E02.mkv", 200)
        val pack = MagnetLink.forAmarrPack("Show S01 PACK aMule", listOf(first, second))
        every { amuleClient.downloadEd2kLink(first.toEd2kLink()) } returns Result.success(Unit)
        every { amuleClient.downloadEd2kLink(second.toEd2kLink()) } returns Result.success(Unit)

        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            client.submitForm(formParameters = Parameters.build {
                append("urls", pack.toString())
                append("category", "sonarr")
            }, url = "/api/v2/torrents/add").status shouldBe HttpStatusCode.OK
        }

        verify(exactly = 1) { amuleClient.downloadEd2kLink(first.toEd2kLink()) }
        verify(exactly = 1) { amuleClient.downloadEd2kLink(second.toEd2kLink()) }
        categoryStore.getPack(pack.amuleHexHash()) shouldBe PackDownload(
            pack.amuleHexHash(),
            pack.name,
            "sonarr",
            listOf(
                PackMember(first.amuleHexHash(), first.name, first.size),
                PackMember(second.amuleHexHash(), second.name, second.size),
            ),
        )
    }

    "should expose virtual pack files and aggregate progress" {
        val first = MagnetLink.forAmarr(ByteArray(16) { 3 }, "Show S01E01.mkv", 100)
        val second = MagnetLink.forAmarr(ByteArray(16) { 4 }, "Show S01E02.mkv", 300)
        val packLink = MagnetLink.forAmarrPack("Show S01 PACK aMule", listOf(first, second))
        categoryStore.storePack(
            PackDownload(
                packLink.amuleHexHash(), packLink.name, "sonarr",
                listOf(
                    PackMember(first.amuleHexHash(), first.name, first.size),
                    PackMember(second.amuleHexHash(), second.name, second.size),
                ),
            )
        )
        every { amuleClient.getDownloadQueue() } returns Result.success(
            listOf(
                MockTransferringFile(
                    fileHashHexString = first.amuleHexHash(), fileName = first.name,
                    sizeFull = 100, sizeDone = 100, speed = 0, sourceXferCount = 0,
                ),
                MockTransferringFile(
                    fileHashHexString = second.amuleHexHash(), fileName = second.name,
                    sizeFull = 300, sizeDone = 100, speed = 50, sourceXferCount = 2,
                    fileStatus = FileStatus.READY,
                ),
            )
        )
        every { amuleClient.getSharedFiles() } returns Result.success(emptyList())

        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            val info = client.get("/api/v2/torrents/info?category=sonarr")
            val torrent = Json.parseToJsonElement(info.bodyAsText()).jsonArray.single().jsonObject
            torrent["hash"]!!.jsonPrimitive.content shouldBe packLink.amuleHexHash()
            torrent["progress"]!!.jsonPrimitive.content shouldBe "0.5"
            torrent["dlspeed"]!!.jsonPrimitive.content shouldBe "50"
            torrent["num_seeds"]!!.jsonPrimitive.content shouldBe "2"

            val files = client.get("/api/v2/torrents/files?hash=${packLink.amuleHexHash()}")
            Json.parseToJsonElement(files.bodyAsText()).jsonArray.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            } shouldBe listOf(first.name, second.name)
        }
    }

    "should materialize a completed pack as a season directory" {
        val localFinished = Files.createTempDirectory("amarr-pack-complete")
        val firstPath = Files.writeString(localFinished.resolve("Show S01E01.mkv"), "episode one")
        val secondPath = Files.writeString(localFinished.resolve("Show S01E02.mkv"), "episode two")
        val first = MagnetLink.forAmarr(ByteArray(16) { 5 }, firstPath.fileName.toString(), Files.size(firstPath))
        val second = MagnetLink.forAmarr(ByteArray(16) { 6 }, secondPath.fileName.toString(), Files.size(secondPath))
        val packLink = MagnetLink.forAmarrPack("Show S01 PACK aMule", listOf(first, second))
        categoryStore.storePack(
            PackDownload(
                packLink.amuleHexHash(), packLink.name, "sonarr",
                listOf(
                    PackMember(first.amuleHexHash(), first.name, first.size),
                    PackMember(second.amuleHexHash(), second.name, second.size),
                ),
            )
        )
        every { amuleClient.getDownloadQueue() } returns Result.success(emptyList())
        every { amuleClient.getSharedFiles() } returns Result.success(
            listOf(
                MockTransferringFile(
                    fileHashHexString = first.amuleHexHash(), fileName = first.name,
                    // aMule's shared-files EC response may omit the full path.
                    filePath = null, sizeFull = first.size,
                ),
                MockTransferringFile(
                    fileHashHexString = second.amuleHexHash(), fileName = second.name,
                    filePath = secondPath.toString(), sizeFull = second.size,
                ),
            )
        )

        testApplication {
            application {
                torrentApi(
                    amuleClient, categoryStore, finishedPath,
                    localFinishedPath = localFinished.toString(),
                )
                configureForTest()
            }
            val response = client.get("/api/v2/torrents/info?category=sonarr")
            val torrent = Json.parseToJsonElement(response.bodyAsText()).jsonArray.single().jsonObject
            torrent["progress"]!!.jsonPrimitive.content shouldBe "1.0"
            torrent["state"]!!.jsonPrimitive.content shouldBe "uploading"
            torrent["content_path"]!!.jsonPrimitive.content shouldBe
                "/finished/.amarr-packs/${packLink.amuleHexHash()}"
        }

        val packDirectory = localFinished.resolve(".amarr-packs").resolve(packLink.amuleHexHash())
        Files.isSameFile(firstPath, packDirectory.resolve(first.name)) shouldBe true
        Files.isSameFile(secondPath, packDirectory.resolve(second.name)) shouldBe true
    }

    "should get categories" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            client.get("/api/v2/torrents/categories").apply {
                this.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "should create category" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            client.submitForm(formParameters = Parameters.build {
                append("category", "test")
                append("savePath", "test")
            }, url = "/api/v2/torrents/createCategory").apply {
                this.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "should delete torrent when downloading" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            categoryStore.store("test", testMagnetLink.amuleHexHash())
            every {
                amuleClient.sendDownloadCommand(testMagnetHash, DownloadCommand.DELETE)
            } returns Result.success(Unit)
            every {
                amuleClient.getDownloadQueue()
            } returns Result.success(
                listOf(
                    MockTransferringFile(
                        fileHashHexString = testMagnetLink.amuleHexHash(),
                        fileName = testMagnetLink.name,
                        sizeFull = testMagnetLink.size,
                    )
                )
            )
            client.submitForm(formParameters = Parameters.build {
                append("hashes", testMagnetLink.amuleHexHash())
                append("deleteFiles", "true")
            }, url = "/api/v2/torrents/delete").apply {
                this.status shouldBe HttpStatusCode.OK
            }
            verify { amuleClient.sendDownloadCommand(testMagnetHash, DownloadCommand.DELETE) }
            categoryStore.getCategory(testMagnetLink.amuleHexHash()) shouldBe null
        }
    }

    "should delete file when not downloading" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            categoryStore.store("test", testMagnetLink.amuleHexHash())
            every {
                amuleClient.sendDownloadCommand(testMagnetHash, DownloadCommand.DELETE)
            } returns Result.success(Unit)
            val randomTemporaryFile = Files.createTempFile("test", "test")
            every { amuleClient.getSharedFiles() } returns Result.success(
                listOf(
                    MockTransferringFile(
                        fileHashHexString = testMagnetLink.amuleHexHash(),
                        fileName = testMagnetLink.name,
                        sizeFull = testMagnetLink.size,
                        filePath = randomTemporaryFile.toAbsolutePath().toString()
                    )
                )
            )
            every { amuleClient.getDownloadQueue() } returns Result.success(emptyList())
            client.submitForm(formParameters = Parameters.build {
                append("hashes", testMagnetLink.amuleHexHash())
                append("deleteFiles", "true")
            }, url = "/api/v2/torrents/delete").apply {
                this.status shouldBe HttpStatusCode.OK
            }
            verify(exactly = 0) { amuleClient.sendDownloadCommand(testMagnetHash, DownloadCommand.DELETE) }
            categoryStore.getCategory(testMagnetLink.amuleHexHash()) shouldBe null
            Files.exists(randomTemporaryFile) shouldBe false
        }
    }

    "should get files" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            categoryStore.store("test", testMagnetLink.amuleHexHash())
            amuleClient.addToDownloadQueue(testMagnetLink)
            every { amuleClient.getSharedFiles() } returns Result.success(emptyList())
            client.get {
                url("/api/v2/torrents/files")
                parameter("hash", testMagnetLink.amuleHexHash())
            }.apply {
                this.status shouldBe HttpStatusCode.OK
            }
        }
    }

    "should get info" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            categoryStore.store("test", testMagnetLink.amuleHexHash())
            amuleClient.addToDownloadQueue(testMagnetLink)
            every { amuleClient.getSharedFiles() } returns Result.success(emptyList())
            client.get {
                url("/api/v2/torrents/info")
            }.apply {
                this.status shouldBe HttpStatusCode.OK
                val torrent = Json.parseToJsonElement(bodyAsText()).jsonArray.single().jsonObject
                torrent["ratio"]!!.jsonPrimitive.content shouldBe "1.0"
                torrent["seeding_time"]!!.jsonPrimitive.content shouldBe "1"
            }
        }
    }

    "should synchronize amule progress speed and sources" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            categoryStore.store("radarr", testMagnetLink.amuleHexHash())
            every { amuleClient.getDownloadQueue() } returns Result.success(
                listOf(
                    MockTransferringFile(
                        fileHashHexString = testMagnetLink.amuleHexHash(),
                        fileName = "Jumanji.mkv",
                        sizeFull = 1_000,
                        sizeDone = 250,
                        speed = 50,
                        sourceXferCount = 3,
                        fileStatus = FileStatus.READY,
                    )
                )
            )
            every { amuleClient.getSharedFiles() } returns Result.success(emptyList())

            val response = client.get("/api/v2/torrents/info?category=radarr")
            val torrent = Json.parseToJsonElement(response.bodyAsText()).jsonArray.single().jsonObject

            response.status shouldBe HttpStatusCode.OK
            torrent["progress"]!!.jsonPrimitive.content shouldBe "0.25"
            torrent["downloaded"]!!.jsonPrimitive.content shouldBe "250"
            torrent["dlspeed"]!!.jsonPrimitive.content shouldBe "50"
            torrent["num_seeds"]!!.jsonPrimitive.content shouldBe "3"
            torrent["content_path"]!!.jsonPrimitive.content shouldBe "/finished/Jumanji.mkv"
            torrent["download_path"]!!.jsonPrimitive.content shouldBe "/finished"
        }
    }

    "should retry a transient amule progress read once" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            every { amuleClient.getDownloadQueue() } returnsMany listOf(
                Result.failure(IllegalStateException("Authentication failed")),
                Result.success(emptyList()),
            )
            every { amuleClient.getSharedFiles() } returns Result.success(emptyList())

            client.get("/api/v2/torrents/info").status shouldBe HttpStatusCode.OK

            verify(exactly = 2) { amuleClient.getDownloadQueue() }
        }
    }

    "should get properties" {
        testApplication {
            application {
                torrentApi(amuleClient, categoryStore, finishedPath)
                configureForTest()
            }
            categoryStore.store("test", testMagnetLink.amuleHexHash())
            amuleClient.addToDownloadQueue(testMagnetLink)
            every { amuleClient.getSharedFiles() } returns Result.success(emptyList())
            client.get {
                url("/api/v2/torrents/properties")
                parameter("hash", testMagnetLink.amuleHexHash())
            }.apply {
                this.status shouldBe HttpStatusCode.OK
                val properties = Json.parseToJsonElement(bodyAsText()).jsonObject
                properties["seeding_time"]!!.jsonPrimitive.content shouldBe "1"
            }
        }
    }
})

private fun AmuleClient.addToDownloadQueue(magnetLink: MagnetLink) {
    every { this@addToDownloadQueue.getDownloadQueue() } returns Result.success(
        listOf(
            MockTransferringFile(
                fileHashHexString = magnetLink.amuleHexHash(),
                fileName = magnetLink.name,
                sizeFull = magnetLink.size,
            )
        )
    )
}

private fun Application.configureForTest() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        })
    }
}

private class MemoryCategoryStore() : CategoryStore {

    private val categories = mutableSetOf<Category>()
    private val hashes = mutableMapOf<String, String>()
    private val packs = mutableMapOf<String, PackDownload>()

    fun reset() {
        categories.clear()
        hashes.clear()
        packs.clear()
    }

    override fun store(category: String, hash: String) {
        hashes[hash] = category
    }

    override fun getCategory(hash: String): String? {
        return hashes[hash]
    }

    override fun delete(hash: String) {
        hashes.remove(hash)
    }

    override fun addCategory(category: Category) {
        categories.add(category)
    }

    override fun getCategories(): Set<Category> {
        return categories
    }

    override fun storePack(pack: PackDownload) {
        packs[pack.hash.lowercase()] = pack
    }

    override fun getPack(hash: String): PackDownload? = packs[hash.take(32).lowercase()]

    override fun getPacks(category: String?): List<PackDownload> = packs.values
        .filter { category == null || it.category == category }

    override fun deletePack(hash: String) {
        packs.remove(hash.take(32).lowercase())
    }

}

private data class MockTransferringFile(
    override val fileHashHexString: String? = null,
    override val partMetID: Short? = 0,
    override val sizeXfer: Long? = 0,
    override val sizeDone: Long? = 0,
    override val fileStatus: FileStatus = FileStatus.UNKNOWN,
    override val stopped: Boolean = false,
    override val sourceCount: Short = 0,
    override val sourceNotCurrCount: Short = 0,
    override val sourceXferCount: Short = 0,
    override val sourceCountA4AF: Short = 0,
    override val speed: Long? = 0,
    override val downPrio: Byte = 0,
    override val fileCat: Long = 0,
    override val lastSeenComplete: Long = 0,
    override val lastDateChanged: Long = 0,
    override val downloadActiveTime: Int = 0,
    override val availablePartCount: Short = 0,
    override val a4AFAuto: Boolean = false,
    override val hashingProgress: Boolean = false,
    override val getLostDueToCorruption: Long = 0,
    override val getGainDueToCompression: Long = 0,
    override val totalPacketsSavedDueToICH: Int = 0,
    override val fileName: String? = null,
    override val filePath: String? = null,
    override val sizeFull: Long? = 0,
    override val fileEd2kLink: String? = null,
    override val upPrio: Byte = 0,
    override val getRequests: Short = 0,
    override val getAllRequests: Int = 0,
    override val getAccepts: Short = 0,
    override val getAllAccepts: Int = 0,
    override val getXferred: Long = 0,
    override val getAllXferred: Long = 0,
    override val getCompleteSourcesLow: Short = 0,
    override val getCompleteSourcesHigh: Short = 0,
    override val getCompleteSources: Short = 0,
    override val getOnQueue: Short = 0,
    override val getComment: String? = null,
    override val getRating: Byte? = 0,
) : AmuleTransferringFile
