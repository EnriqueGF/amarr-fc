package amarr.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import jamule.AmuleClient
import java.nio.file.Files
import java.nio.file.Path

fun Application.healthApi(amuleClient: AmuleClient, configPath: String) {
    routing {
        get("/health/live") {
            call.respond(mapOf("status" to "ok"))
        }
        get("/health/ready") {
            val configReady = runCatching {
                val path = Path.of(configPath)
                Files.createDirectories(path)
                Files.isWritable(path)
            }.getOrDefault(false)
            val amuleReady = runCatching { amuleClient.getDownloadQueue().isSuccess }.getOrDefault(false)
            val ready = configReady && amuleReady
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf(
                    "status" to if (ready) "ready" else "unavailable",
                    "amule" to if (amuleReady) "connected" else "disconnected",
                    "storage" to if (configReady) "writable" else "unwritable",
                ),
            )
        }
    }
}
