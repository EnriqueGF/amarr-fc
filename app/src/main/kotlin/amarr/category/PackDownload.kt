package amarr.category

data class PackDownload(
    val hash: String,
    val name: String,
    val category: String,
    val members: List<PackMember>,
)

data class PackMember(
    val hash: String,
    val name: String,
    val size: Long,
)
