package amarr

import amarr.category.SqliteCategoryStore
import amarr.health.healthApi
import amarr.security.QbitAuth
import amarr.torrent.torrentApi
import amarr.torrent.StalledDownloadMonitor
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
    val amuleClient = buildClient(config)
    val amuleMutex = Mutex()
    val amuleIndexer = AmuleIndexer(amuleClient, log, config.searchCacheSeconds, amuleMutex)
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
        amuleMutex,
    )
    healthApi(amuleClient, config.configPath, amuleMutex)

    val replacementJob = if (config.stalledReplacementEnabled) launch {
        val monitor = StalledDownloadMonitor(
            amuleClient = amuleClient,
            indexer = amuleIndexer,
            categoryStore = categoryStore,
            amuleMutex = amuleMutex,
            log = log,
            stallMillis = config.stalledMinutes * 60_000L,
            maxReplacementsPerRun = config.maxReplacementsPerRun,
        )
        delay(30_000L)
        while (isActive) {
            runCatching { monitor.runOnce() }
                .onSuccess { count ->
                    if (count > 0) log.info("Replaced {} stalled aMule pack member(s)", count)
                }
                .onFailure { error -> log.error("Stalled download monitor failed", error) }
            delay(config.replacementIntervalMinutes * 60_000L)
        }
    } else null
    monitor.subscribe(ApplicationStopping) {
        replacementJob?.cancel()
        categoryStore.close()
    }
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

fun buildClient(config: AppConfig): AmuleClient =
    AmuleClient(
        config.amuleHost,
        config.amulePort,
        config.amulePassword,
        // jaMule logs every raw result and labels its normal search-window
        // expiry as an error. Keep that internal trace disabled and emit
        // concise, actionable search metrics from AmuleIndexer instead.
        logger = LoggerFactory.getLogger("jamule.client")
    )

private fun optionalEnv(name: String, default: String): String = optionalEnv(System.getenv(), name, default)

private fun optionalEnv(env: Map<String, String>, name: String, default: String): String = env[name] ?: default
