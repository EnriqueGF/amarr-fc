package amarr.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SecurityTest : StringSpec({
    "should only authorize the configured qBittorrent credentials and SID" {
        val auth = QbitAuth("sonarr", "secret")
        auth.validCredentials("sonarr", "secret") shouldBe true
        auth.validCredentials("sonarr", "wrong") shouldBe false
        auth.validSession(auth.sessionId) shouldBe true
        auth.validSession("wrong") shouldBe false
    }
})
