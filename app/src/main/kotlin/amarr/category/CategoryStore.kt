package amarr.category

import amarr.torrent.model.Category

interface CategoryStore {
    fun store(category: String, hash: String)
    fun getCategory(hash: String): String?
    fun delete(hash: String)
    fun addCategory(category: Category)
    fun getCategories(): Set<Category>
    fun storePack(pack: PackDownload) = Unit
    fun getPack(hash: String): PackDownload? = null
    fun getPacks(category: String? = null): List<PackDownload> = emptyList()
    fun deletePack(hash: String) = Unit
    fun observeDownload(hash: String, bytes: Long, active: Boolean, now: Long): DownloadObservation? = null
    fun attemptedHashes(logicalKey: String): Set<String> = emptySet()
    fun markAttempt(logicalKey: String, hash: String, now: Long) = Unit
    fun replacePackMember(packHash: String, oldHash: String, replacement: PackMember): Boolean = false
}

data class DownloadObservation(
    val firstSeenAt: Long,
    val lastActivityAt: Long,
    val bytes: Long,
)
