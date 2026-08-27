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
}
