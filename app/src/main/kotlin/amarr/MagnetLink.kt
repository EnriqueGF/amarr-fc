package amarr

import com.google.common.io.BaseEncoding.base32
import io.ktor.http.*
import java.security.MessageDigest

data class MagnetLink(
    private val hash: ByteArray,
    val name: String,
    val size: Long,
    val trackers: List<String>,
) {
    fun toEd2kLink(): String {
        return "ed2k://|file|${name.encodeURLParameter()}|$size|${amuleHexHash()}|/"
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun amuleHexHash(): String {
        // unpad the hash to ensure a size of 128 bits, then encode it as hex
        return hash.copyOf(16).toHexString()
    }

    fun isAmarr(): Boolean {
        return trackers.contains(AMARR_TRACKER)
    }

    fun packMembers(): List<MagnetLink> = trackers
        .filter { it.startsWith("ed2k://|file|") }
        .map(::fromEd2k)

    fun isPack(): Boolean = packMembers().isNotEmpty()

    override fun toString(): String {
        // pad the hash to ensure a size of 160 bits
        val hash = hash.copyOf(20)
        val base32Hash = base32().encode(hash)
        return "magnet:" +
                "?xt=urn:btih:$base32Hash" +
                "&dn=${name.encodeURLParameter()}" +
                "&xl=$size" +
                "&tr=${trackers.joinToString("&tr=") { it.encodeURLParameter() }}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MagnetLink

        if (!hash.contentEquals(other.hash)) return false
        if (name != other.name) return false
        if (size != other.size) return false
        if (trackers != other.trackers) return false

        return true
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + trackers.hashCode()
        return result
    }

    companion object {
        fun forAmarr(hash: ByteArray, name: String, size: Long) = MagnetLink(
            hash = hash.copyOf(16),
            name = name,
            size = size,
            trackers = listOf(AMARR_TRACKER)
        )

        fun forAmarrPack(name: String, members: List<MagnetLink>): MagnetLink {
            require(members.size >= 2) { "a virtual pack needs at least two files" }
            val distinctMembers = members.distinctBy { it.amuleHexHash() }.sortedBy { it.amuleHexHash() }
            require(distinctMembers.size == members.size) { "a virtual pack cannot contain duplicate files" }
            val digest = MessageDigest.getInstance("SHA-256")
            distinctMembers.forEach { digest.update(it.amuleHexHash().toByteArray(Charsets.US_ASCII)) }
            return MagnetLink(
                hash = digest.digest().copyOf(16),
                name = name,
                size = distinctMembers.sumOf { it.size },
                trackers = listOf(AMARR_TRACKER) + distinctMembers.map { it.toEd2kLink() },
            )
        }

        fun fromString(magnet: String): MagnetLink = magnet
            .substringAfter("magnet:?")
            .split("&")
            .filter { it.matches(Regex(".+=.+")) }
            .map { val els = it.split("="); els[0] to els[1] }
            .let { params ->
                // aMule/eD2k hashes are 128-bit. Magnet links carry the value in a
                // padded 160-bit BTIH field for qBittorrent compatibility.
                val hash = base32()
                    .decode(params.first { it.first == "xt" }.second.substringAfter("urn:btih:"))
                    .copyOf(16)
                MagnetLink(
                    hash = hash,
                    name = params.first { it.first == "dn" }.second.decodeURLPart(),
                    size = params.first { it.first == "xl" }.second.toLong(),
                    trackers = params.filter { it.first == "tr" }.map { it.second.decodeURLPart() }
                )
            }

        @OptIn(ExperimentalStdlibApi::class)
        fun fromEd2k(ed2k: String): MagnetLink = ed2k
            .substringAfter("ed2k://|file|")
            .substringBefore("|/")
            .split("|")
            .let { els ->
                MagnetLink(
                    hash = els[2].hexToByteArray(),
                    name = els[0].decodeURLPart(),
                    size = els[1].toLong(),
                    trackers = listOf(AMARR_TRACKER)
                )
            }

        const val AMARR_TRACKER = "http://amarr-reserved"
    }
}
