package amarr.torrent

import amarr.category.CategoryStore
import amarr.security.QbitAuth
import amarr.torrent.model.Category
import amarr.torrent.model.Preferences
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jamule.AmuleClient

fun Application.torrentApi(
    amuleClient: AmuleClient,
    categoryStore: CategoryStore,
    finishedPath: String,
    auth: QbitAuth = QbitAuth.disabled(),
) {
    val service = TorrentService(amuleClient, categoryStore, finishedPath, log)
    routing {
        get("/api/v2/app/webapiVersion") {
            if (!call.requireQbitAuth(auth)) return@get
            call.respondText("2.8.19") // Emulating qBittorrent API version 2.8.19
        }
        post("/api/v2/auth/login") {
            val params = call.receiveParameters()
            val username = params["username"]
            val password = params["password"]
            if (auth.enabled && !auth.validCredentials(username, password)) {
                call.respondText("Fails.", status = HttpStatusCode.Forbidden)
                return@post
            }
            if (auth.enabled) {
                call.response.cookies.append(
                    Cookie(
                        name = "SID",
                        value = auth.sessionId,
                        path = "/",
                        httpOnly = true,
                        extensions = mapOf("SameSite" to "Lax"),
                    )
                )
            }
            call.respondText("Ok.")
        }
        get("/api/v2/app/preferences") {
            if (!call.requireQbitAuth(auth)) return@get
            call.respond(Preferences(save_path = finishedPath))
        }
        post("/api/v2/torrents/add") {
            if (!call.requireQbitAuth(auth)) return@post
            val params = call.receiveParameters()
            val urls = params["urls"]?.split("\n")?.filterNot { it.isBlank() }
            val category = params["category"]
            val paused = params["paused"]
            call.application.log.debug(
                "Received add torrent request with urls: {}, category: {}, paused: {}",
                urls,
                category,
                paused
            )
            service.addTorrent(urls, category, paused)
            call.respondText("Ok.")
        }
        post("/api/v2/torrents/createCategory") {
            if (!call.requireQbitAuth(auth)) return@post
            val params = call.receiveParameters()
            val category = Category(params["category"]!!, params["savePath"] ?: "")
            call.application.log.debug("Received create category request with category: {}", category)
            service.addCategory(category)
            call.respondText("Ok.")
        }
        get("/api/v2/torrents/categories") {
            if (!call.requireQbitAuth(auth)) return@get
            call.respond(service.getCategories())
        }
        get("/api/v2/torrents/info") {
            if (!call.requireQbitAuth(auth)) return@get
            val category = call.request.queryParameters["category"]
            call.respond(service.getTorrentInfo(category))
        }
        post("/api/v2/torrents/delete") {
            if (!call.requireQbitAuth(auth)) return@post
            val params = call.receiveParameters()
            val hashes = params["hashes"]!!.split("|")
            val deleteFiles = params["deleteFiles"]
            call.application.log.debug(
                "Received delete torrent request with hashes: {}, deleteFiles: {}",
                hashes,
                deleteFiles
            )
            if (hashes.size == 1 && hashes[0] == "all")
                service.deleteAllTorrents(deleteFiles)
            else service.deleteTorrent(hashes, deleteFiles)
            call.respondText("Ok.")
        }
        get("/api/v2/torrents/files") {
            if (!call.requireQbitAuth(auth)) return@get
            val hash = call.request.queryParameters["hash"]!!
            call.application.log.debug("Received get files request with hash: {}", hash)
            val response = listOf(service.getFile(hash))
            call.respond(response)
        }
        get("/api/v2/torrents/properties") {
            if (!call.requireQbitAuth(auth)) return@get
            val hash = call.request.queryParameters["hash"]!!
            call.application.log.debug("Received get properties request with hash: {}", hash)
            val response = service.getTorrentProperties(hash)
            call.respond(response)
        }
    }
}

private suspend fun ApplicationCall.requireQbitAuth(auth: QbitAuth): Boolean {
    if (auth.validSession(request.cookies["SID"])) return true
    respondText("Forbidden", status = HttpStatusCode.Forbidden)
    return false
}
