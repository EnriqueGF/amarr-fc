package amarr

import amarr.category.SqliteCategoryStore
import amarr.health.healthApi
import amarr.security.QbitAuth
import amarr.torrent.torrentApi
import amarr.torznab.indexer.AmuleIndexer
import amarr.torznab.indexer.ddunlimitednet.DdunlimitednetClient
import amarr.torznab.indexer.ddunlimitednet.DdunlimitednetIndexer
import amarr.torznab.torznabApi
import io.ktor.client.engine.cio.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import jamule.AmuleClient
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.Logger
import org.slf4j.event.Level

fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(
        Netty, port = config.port
    ) {
        app(config)
    }.start(wait = true)
}

@VisibleForTesting
internal fun Application.app(config: AppConfig = AppConfig.fromEnvironment()) {
    setLogLevel(log, optionalEnv("AMARR_LOG_LEVEL", "INFO"))
    val amuleClient = buildClient(log, config)
    val amuleIndexer = AmuleIndexer(amuleClient, log, config.searchCacheSeconds)
    val ddunlimitednetClient = DdunlimitednetClient(
        CIO.create(),
        System.getenv("DDUNLIMITEDNET_USERNAME"),
        System.getenv("DDUNLIMITEDNET_PASSWORD"),
        log
    )
    val ddunlimitednetIndexer = DdunlimitednetIndexer(ddunlimitednetClient, log)
    val categoryStore = SqliteCategoryStore(config.configPath)

    install(CallLogging) {
        level = Level.DEBUG
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        })
    }
    torznabApi(amuleIndexer, ddunlimitednetIndexer, config.apiKey)
    torrentApi(
        amuleClient,
        categoryStore,
        config.finishedPath,
        QbitAuth(config.qbitUsername, config.qbitPassword),
    )
    healthApi(amuleClient, config.configPath)
}

@VisibleForTesting
internal fun amarrPort(env: Map<String, String> = System.getenv()): Int {
    val port = optionalEnv(env, "AMARR_PORT", "8080").toIntOrNull()
        ?: throw Exception("AMARR_PORT must be a valid port number")
    if (port !in 1..65535) {
        throw Exception("AMARR_PORT must be between 1 and 65535")
    }
    return port
}

private fun setLogLevel(logger: Logger, logLevel: String) {
    val logBackLogger = logger as ch.qos.logback.classic.Logger
    when (logLevel) {
        "DEBUG" -> logBackLogger.level = ch.qos.logback.classic.Level.DEBUG
        "INFO" -> logBackLogger.level = ch.qos.logback.classic.Level.INFO
        "WARN" -> logBackLogger.level = ch.qos.logback.classic.Level.WARN
        "ERROR" -> logBackLogger.level = ch.qos.logback.classic.Level.ERROR
        else -> throw Exception("Unknown log level: $logLevel")
    }
}

fun buildClient(logger: Logger, config: AppConfig): AmuleClient =
    AmuleClient(
        config.amuleHost,
        config.amulePort,
        config.amulePassword,
        logger = logger
    )

private fun optionalEnv(name: String, default: String): String = optionalEnv(System.getenv(), name, default)

private fun optionalEnv(env: Map<String, String>, name: String, default: String): String = env[name] ?: default
