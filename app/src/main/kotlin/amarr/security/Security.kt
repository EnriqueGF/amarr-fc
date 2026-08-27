package amarr.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class QbitAuth(
    val username: String,
    private val password: String,
    val enabled: Boolean = true,
) {
    val sessionId: String = sha256("amarr-fc:$username:$password")

    fun validCredentials(candidateUsername: String?, candidatePassword: String?): Boolean =
        enabled && secureEquals(username, candidateUsername) && secureEquals(password, candidatePassword)

    fun validSession(candidate: String?): Boolean = !enabled || secureEquals(sessionId, candidate)

    companion object {
        fun disabled() = QbitAuth("", "", enabled = false)
    }
}

fun secureEquals(expected: String, candidate: String?): Boolean {
    if (candidate == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        candidate.toByteArray(StandardCharsets.UTF_8),
    )
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
