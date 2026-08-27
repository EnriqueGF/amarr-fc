package amarr

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AppConfigTest : StringSpec({
    val required = mapOf(
        "AMULE_HOST" to "amule",
        "AMULE_PORT" to "4712",
        "AMULE_PASSWORD" to "ec-secret",
        "AMARR_API_KEY" to "api-secret",
        "AMARR_QBIT_PASSWORD" to "qbit-secret",
    )

    "should build production configuration with safe defaults" {
        val config = AppConfig.fromEnvironment(required)
        config.port shouldBe 8080
        config.finishedPath shouldBe "/data/amuleCompleted"
        config.qbitUsername shouldBe "sonarr"
        config.searchCacheSeconds shouldBe 900
    }

    "should require API and qBittorrent secrets" {
        shouldThrow<IllegalStateException> {
            AppConfig.fromEnvironment(required - "AMARR_API_KEY")
        }
    }
})
