package amarr

import java.nio.file.Files
import java.nio.file.Path

data class AppConfig(
    val port: Int,
    val configPath: String,
    val finishedPath: String,
    val amuleHost: String,
    val amulePort: Int,
    val amulePassword: String,
    val apiKey: String,
    val qbitUsername: String,
    val qbitPassword: String,
    val searchCacheSeconds: Long,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            val port = optional(env, "AMARR_PORT", "8080").toIntOrNull()
                ?: error("AMARR_PORT must be a valid port number")
            require(port in 1..65535) { "AMARR_PORT must be between 1 and 65535" }
            val amulePort = required(env, "AMULE_PORT").toIntOrNull()
                ?: error("AMULE_PORT must be a valid port number")
            require(amulePort in 1..65535) { "AMULE_PORT must be between 1 and 65535" }
            val cacheSeconds = optional(env, "AMARR_SEARCH_CACHE_SECONDS", "900").toLongOrNull()
                ?: error("AMARR_SEARCH_CACHE_SECONDS must be a number")
            require(cacheSeconds >= 0) { "AMARR_SEARCH_CACHE_SECONDS cannot be negative" }

            return AppConfig(
                port = port,
                configPath = optional(env, "AMARR_CONFIG_PATH", "/config"),
                finishedPath = optional(env, "AMULE_FINISHED_PATH", "/data/amuleCompleted"),
                amuleHost = required(env, "AMULE_HOST"),
                amulePort = amulePort,
                amulePassword = secret(env, "AMULE_PASSWORD"),
                apiKey = secret(env, "AMARR_API_KEY"),
                qbitUsername = optional(env, "AMARR_QBIT_USERNAME", "sonarr"),
                qbitPassword = secret(env, "AMARR_QBIT_PASSWORD"),
                searchCacheSeconds = cacheSeconds,
            )
        }

        private fun required(env: Map<String, String>, name: String): String =
            env[name]?.takeIf { it.isNotBlank() } ?: error("$name is not set")

        private fun optional(env: Map<String, String>, name: String, default: String): String =
            env[name]?.takeIf { it.isNotBlank() } ?: default

        private fun secret(env: Map<String, String>, name: String): String {
            env["${name}_FILE"]?.takeIf { it.isNotBlank() }?.let { file ->
                return Files.readString(Path.of(file)).trim().takeIf { it.isNotBlank() }
                    ?: error("$name secret file is empty")
            }
            return required(env, name)
        }
    }
}
