package amarr.torznab

import amarr.security.secureEquals
import amarr.torznab.indexer.AmuleIndexer
import amarr.torznab.indexer.Indexer
import amarr.torznab.indexer.ThrottledException
import amarr.torznab.indexer.UnauthorizedException
import amarr.torznab.indexer.ddunlimitednet.DdunlimitednetIndexer
import amarr.torznab.model.Feed
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML


fun Application.torznabApi(
    amuleIndexer: AmuleIndexer,
    ddunlimitednetIndexer: DdunlimitednetIndexer,
    apiKey: String? = null,
) {
    routing {
        // Kept for legacy reasons
        get("/api") {
            call.handleRequests(amuleIndexer, apiKey)
        }
        get("/indexer/amule/api") {
            call.handleRequests(amuleIndexer, apiKey)
        }
        get("indexer/ddunlimitednet/api") {
            call.handleRequests(ddunlimitednetIndexer, apiKey)
        }
    }
}

private suspend fun ApplicationCall.handleRequests(indexer: Indexer, apiKey: String?) {
    if (apiKey != null && !secureEquals(apiKey, request.queryParameters["apikey"])) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return
    }
    application.log.debug("Handling torznab request")
    val xmlFormat = XML {
        xmlDeclMode = XmlDeclMode.Charset
        xmlVersion = XmlVersion.XML10
    } // This API uses XML instead of JSON
    request.queryParameters["t"]?.let {
        when (it) {
            "caps" -> {
                application.log.debug("Handling caps request")
                respondText(xmlFormat.encodeToString(indexer.capabilities()), contentType = ContentType.Application.Xml)
            }

            "tvsearch" -> performSearch(indexer, xmlFormat, SearchMode.Tv)
            "movie" -> performSearch(indexer, xmlFormat, SearchMode.Default)
            "search" -> performSearch(indexer, xmlFormat)

            else -> throw IllegalArgumentException("Unknown action: $it")
        }
    } ?: throw IllegalArgumentException("Missing action")
}

private suspend fun ApplicationCall.performSearch(indexer: Indexer, xmlFormat: XML) {
    performSearch(indexer, xmlFormat, SearchMode.Default)
}

private suspend fun ApplicationCall.performSearch(indexer: Indexer, xmlFormat: XML, mode: SearchMode) {
    val query = request.queryParameters["q"].orEmpty()
    val offset = request.queryParameters["offset"]?.toIntOrNull() ?: 0
    val limit = request.queryParameters["limit"]?.toIntOrNull() ?: 100
    val cat = request.queryParameters["cat"]?.split(",")?.map { cat -> cat.toInt() } ?: emptyList()
    val season = request.queryParameters["season"]?.toIntOrNull()
    val episode = (request.queryParameters["ep"] ?: request.queryParameters["episode"])?.toIntOrNull()
    val effectiveQuery = if (query.isBlank()) EMPTY_QUERY_PROBE else query
    application.log.debug("Handling search request: {}, {}, {}, {}", effectiveQuery, offset, limit, cat)
    try {
        val feed = if (mode == SearchMode.Tv && query.isNotBlank()) {
            indexer.searchTv(effectiveQuery, season, episode, offset, limit, cat)
        } else {
            indexer.search(effectiveQuery, offset, limit, cat)
        }
        respondText(
            xmlFormat.encodeToString(feed),
            contentType = ContentType.Application.Xml
        )
    } catch (e: ThrottledException) {
        application.log.warn("Throttled, returning 403")
        respondText("You are being throttled. Retry in a few minutes.", status = HttpStatusCode.Forbidden)
    } catch (e: UnauthorizedException) {
        application.log.warn("Unauthorized, returning 401")
        respondText("Unauthorized, check your credentials.", status = HttpStatusCode.Unauthorized)
    }
}

private enum class SearchMode {
    Default,
    Tv
}

private const val EMPTY_QUERY_PROBE = "S01E01"
