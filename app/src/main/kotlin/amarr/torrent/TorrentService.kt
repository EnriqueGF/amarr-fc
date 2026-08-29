package amarr.torrent

import amarr.MagnetLink
import amarr.category.CategoryStore
import amarr.category.PackDownload
import amarr.category.PackMember
import amarr.torrent.model.*
import io.ktor.server.plugins.*
import io.ktor.util.logging.*
import jamule.AmuleClient
import jamule.model.AmuleTransferringFile
import jamule.model.DownloadCommand
import jamule.model.FileStatus
import kotlin.io.path.Path
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TorrentService(
    private val amuleClient: AmuleClient,
    private val categoryStore: CategoryStore,
    private val finishedPath: String,
    private val log: Logger,
    private val amuleMutex: Mutex = Mutex(),
    private val localFinishedPath: String = "/incoming",
) {

    suspend fun getTorrentInfo(category: String?): List<TorrentInfo> {
        val (downloadingFiles, sharedFiles) = readAmule("refresh download progress") {
            amuleClient.getDownloadQueue().getOrThrow() to
                amuleClient.getSharedFiles().getOrThrow()
        }
        val downloadingFilesHashSet = downloadingFiles.mapNotNull { it.fileHashHexString?.lowercase() }.toHashSet()
        val downloadingByHash = downloadingFiles.associateBy { it.fileHashHexString?.lowercase() }
        val completedFiles = sharedFiles.filterNot {
            it.fileHashHexString?.lowercase() in downloadingFilesHashSet
        }
        val completedByHash = completedFiles.associateBy { it.fileHashHexString?.lowercase() }
        val packs = categoryStore.getPacks(category)
        val packMemberHashes = packs.flatMapTo(hashSetOf()) { pack ->
            pack.members.map { it.hash.lowercase() }
        }

        val packInfo = packs.map { pack ->
            val activeMembers = pack.members.mapNotNull { downloadingByHash[it.hash.lowercase()] }
            val allComplete = pack.members.all { completedByHash.containsKey(it.hash.lowercase()) }
            val materialized = allComplete && runCatching {
                materializePack(
                    pack,
                    completedByHash.mapValues { (_, file) ->
                        CompletedFile(file.filePath, file.fileName)
                    },
                )
            }.onFailure { error ->
                log.error("Could not materialize virtual pack {}: {}", pack.name, error.message)
            }.getOrDefault(false)
            val totalSize = pack.members.sumOf { it.size }
            val downloaded = pack.members.sumOf { member ->
                when {
                    completedByHash.containsKey(member.hash.lowercase()) -> member.size
                    else -> minOf(
                        downloadingByHash[member.hash.lowercase()]?.sizeDone ?: 0L,
                        member.size,
                    )
                }
            }
            val speed = activeMembers.sumOf { it.speed ?: 0L }
            TorrentInfo(
                hash = pack.hash,
                name = pack.name,
                size = totalSize,
                total_size = totalSize,
                save_path = finishedPath,
                downloaded = downloaded,
                progress = if (totalSize == 0L) 0.0 else downloaded.toDouble() / totalSize.toDouble(),
                priority = activeMembers.maxOfOrNull { it.downPrio.toInt() } ?: 0,
                state = packState(activeMembers, allComplete, materialized),
                category = pack.category,
                dlspeed = speed,
                num_seeds = activeMembers.sumOf { it.sourceXferCount.toInt() },
                eta = computeEta(speed, totalSize, downloaded),
                content_path = publicPackPath(pack.hash).toString(),
                download_path = finishedPath,
            )
        }

        val allFiles = (completedFiles + downloadingFiles)
            .filter { file ->
                val hash = file.fileHashHexString ?: return@filter false
                if (hash.lowercase() in packMemberHashes) return@filter false
                val storedCategory = categoryStore.getCategory(hash) ?: return@filter false
                category == null || storedCategory == category
            }

        val individualInfo = allFiles
            .map { dl ->
                if (dl is AmuleTransferringFile)
                    TorrentInfo(
                        hash = dl.fileHashHexString!!,
                        name = dl.fileName!!,
                        size = dl.sizeFull!!,
                        total_size = dl.sizeFull!!,
                        save_path = finishedPath,
                        downloaded = dl.sizeDone!!,
                        progress = dl.sizeDone!!.toDouble() / dl.sizeFull!!.toDouble(),
                        priority = dl.downPrio.toInt(),
                        state = if (dl.sourceXferCount > 0) TorrentState.downloading
                        else when (dl.fileStatus) {
                            FileStatus.READY -> TorrentState.metaDL
                            FileStatus.ERROR -> TorrentState.error
                            FileStatus.COMPLETING -> TorrentState.checkingDL
                            FileStatus.COMPLETE -> TorrentState.uploading
                            FileStatus.PAUSED -> TorrentState.pausedDL
                            FileStatus.ALLOCATING -> TorrentState.allocating
                            FileStatus.INSUFFICIENT -> TorrentState.error
                                .also { log.error("Insufficient disk space") }

                            else -> TorrentState.unknown
                        },
                        category = categoryStore.getCategory(dl.fileHashHexString!!),
                        dlspeed = dl.speed!!,
                        num_seeds = dl.sourceXferCount.toInt(),
                        eta = computeEta(dl.speed!!, dl.sizeFull!!, dl.sizeDone!!),
                        content_path = Path(finishedPath, dl.fileName!!).toString(),
                        download_path = finishedPath,
                    )
                else
                // File is already fully downloaded
                    TorrentInfo(
                        hash = dl.fileHashHexString!!,
                        name = dl.fileName!!,
                        size = dl.sizeFull!!,
                        total_size = dl.sizeFull!!,
                        save_path = finishedPath,
                        dlspeed = 0,
                        downloaded = dl.sizeFull!!,
                        progress = 1.0,
                        priority = 0,
                        state = TorrentState.uploading,
                        category = categoryStore.getCategory(dl.fileHashHexString!!),
                        eta = 0,
                        num_seeds = 0, // Irrelevant
                        content_path = Path(finishedPath, dl.fileName!!).toString(),
                        download_path = finishedPath,
                    )
            }
        return packInfo + individualInfo
    }

    private fun packState(
        activeMembers: List<AmuleTransferringFile>,
        allComplete: Boolean,
        materialized: Boolean,
    ): TorrentState = when {
        allComplete && materialized -> TorrentState.uploading
        // aMule can report a shared file before its final path is visible in
        // the bind mount. This is a transient completed/checking condition,
        // not a qBittorrent download error.
        allComplete -> TorrentState.checkingUP
        activeMembers.any { it.fileStatus == FileStatus.ERROR || it.fileStatus == FileStatus.INSUFFICIENT } ->
            TorrentState.error
        activeMembers.any { it.fileStatus == FileStatus.COMPLETING } -> TorrentState.checkingDL
        activeMembers.any { it.sourceXferCount > 0 } -> TorrentState.downloading
        activeMembers.any { it.fileStatus == FileStatus.PAUSED } -> TorrentState.pausedDL
        activeMembers.isNotEmpty() -> TorrentState.metaDL
        else -> TorrentState.metaDL
    }

    private fun materializePack(
        pack: PackDownload,
        completedFiles: Map<String?, CompletedFile>,
    ): Boolean {
        val localRoot = Path(localFinishedPath).toAbsolutePath().normalize()
        val packDirectory = localPackPath(pack.hash)
        Files.createDirectories(packDirectory)
        return pack.members.all { member ->
            val completed = completedFiles[member.hash.lowercase()] ?: return@all false
            val source = resolveCompletedSource(localRoot, completed, member)
                ?: return@all false.also {
                    log.warn(
                        "Completed pack member is not visible locally: hash={}, path={}, name={}",
                        member.hash, completed.path, completed.name,
                    )
                }
            val target = packDirectory.resolve(Path(member.name).fileName.toString()).normalize()
            if (!target.startsWith(packDirectory)) return@all false
            if (Files.exists(target)) {
                Files.isSameFile(source, target)
            } else {
                Files.createLink(target, source)
                true
            }
        }
    }

    private fun resolveCompletedSource(
        localRoot: java.nio.file.Path,
        completed: CompletedFile,
        member: PackMember,
    ): java.nio.file.Path? {
        val reported = completed.path?.takeIf { it.isNotBlank() }?.let(::Path)
        val candidates = listOfNotNull(
            reported,
            reported?.fileName?.let(localRoot::resolve),
            completed.name?.takeIf { it.isNotBlank() }?.let(::Path)?.fileName?.let(localRoot::resolve),
            Path(member.name).fileName?.let(localRoot::resolve),
        )
        return candidates.asSequence()
            .map { it.toAbsolutePath().normalize() }
            .filter { it.startsWith(localRoot) }
            .firstOrNull(Files::isRegularFile)
    }

    private fun localPackPath(hash: String) =
        Path(localFinishedPath, PACK_DIRECTORY, hash.take(32).lowercase()).toAbsolutePath().normalize()

    private fun publicPackPath(hash: String) = Path(finishedPath, PACK_DIRECTORY, hash.take(32).lowercase())

    private fun computeEta(speed: Long, sizeFull: Long, sizeDone: Long): Int {
        val remainingBytes = sizeFull - sizeDone
        return if (speed == 0L) 8640000 else Math.min((remainingBytes / speed).toInt(), 8640000)
    }

    fun getCategories(): Map<String, Category> = categoryStore
        .getCategories()
        .associateBy { it.name }

    fun addCategory(category: Category) = categoryStore.addCategory(category)

    suspend fun addTorrent(urls: List<String>?, category: String?, paused: String?) {
        if (urls == null) {
            log.error("No urls provided")
            throw nonAmarrLink("No urls provided")
        }
        urls.forEach { url ->
            val magnetLink = try {
                MagnetLink.fromString(url)
            } catch (e: Exception) {
                throw nonAmarrLink(url)
            }
            if (!magnetLink.isAmarr()) {
                throw nonAmarrLink(url)
            }
            val members = magnetLink.packMembers()
            if (members.isNotEmpty()) {
                val packCategory = category?.takeIf { it.isNotBlank() }
                    ?: throw BadRequestException("A category is required for virtual packs")
                val pack = PackDownload(
                    hash = magnetLink.amuleHexHash(),
                    name = magnetLink.name,
                    category = packCategory,
                    members = members.map { PackMember(it.amuleHexHash(), it.name, it.size) },
                )
                categoryStore.storePack(pack)
                categoryStore.store(packCategory, pack.hash)
                members.forEach { categoryStore.store(packCategory, it.amuleHexHash()) }
                withAmule {
                    members.forEach { member ->
                        amuleClient.downloadEd2kLink(member.toEd2kLink()).getOrThrow()
                    }
                }
            } else {
                withAmule { amuleClient.downloadEd2kLink(magnetLink.toEd2kLink()).getOrThrow() }
                if (category != null) {
                    categoryStore.store(category, magnetLink.amuleHexHash())
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun deleteTorrent(hashes: List<String>, deleteFiles: String?) {
        withAmule {
            val downloadingFiles = amuleClient.getDownloadQueue().getOrThrow()
            hashes.forEach { rawHash ->
                val hash = rawHash.take(32).lowercase()
                val pack = categoryStore.getPack(hash)
                if (pack != null) {
                    pack.members.forEach { member ->
                        val memberHash = member.hash.lowercase()
                        if (downloadingFiles.any { it.fileHashHexString?.lowercase() == memberHash }) {
                            amuleClient.sendDownloadCommand(memberHash.hexToByteArray(), DownloadCommand.DELETE)
                                .getOrThrow()
                        } else if (deleteFiles == "true") {
                            deleteSharedFileByHash(memberHash)
                        }
                    }
                    deletePackLinks(pack)
                    categoryStore.deletePack(hash)
                    categoryStore.delete(hash)
                    val remainingMembers = categoryStore.getPacks().flatMapTo(hashSetOf()) { remainingPack ->
                        remainingPack.members.map { it.hash.lowercase() }
                    }
                    pack.members
                        .map { it.hash.lowercase() }
                        .filterNot { it in remainingMembers }
                        .forEach(categoryStore::delete)
                } else if (downloadingFiles.any { it.fileHashHexString?.lowercase() == hash }) {
                    amuleClient.sendDownloadCommand(hash.hexToByteArray(), DownloadCommand.DELETE).getOrThrow()
                } else if (deleteFiles == "true") {
                    deleteSharedFileByHash(hash)
                } else {
                    log.error("File with hash $hash not found in downloading files")
                }
                if (pack == null) categoryStore.delete(hash)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun deleteAllTorrents(deleteFiles: String?) {
        val trackedHashes = readAmule("list tracked downloads") {
            (amuleClient.getDownloadQueue().getOrThrow() +
                amuleClient.getSharedFiles().getOrThrow())
                .mapNotNull { it.fileHashHexString }
                .filter { categoryStore.getCategory(it) != null }
                .plus(categoryStore.getPacks().map { it.hash })
                .distinct()
        }
        deleteTorrent(trackedHashes, deleteFiles)
    }

    suspend fun getFiles(hash: String): List<TorrentFile> {
        val normalizedHash = hash.take(32).lowercase()
        return categoryStore.getPack(normalizedHash)?.members
            ?.map { TorrentFile(name = it.name) }
            ?: listOf(
                getTorrentInfo(null)
                    .first { it.hash.take(32).lowercase() == normalizedHash }
                    .let { TorrentFile(name = it.name) }
            )
    }

    suspend fun getTorrentProperties(hash: String): TorrentProperties = getTorrentInfo(null)
        .first { it.hash.take(32).equals(hash.take(32), ignoreCase = true) }
        .let {
            TorrentProperties(
                hash = it.hash,
                save_path = it.save_path,
                seeding_time = it.seeding_time.toLong(),
            )
        }

    private fun deleteSharedFileByHash(hash: String) = amuleClient
        .getSharedFiles()
        .getOrThrow()
        .firstOrNull { it.fileHashHexString?.equals(hash, ignoreCase = true) == true }
        ?.filePath
        ?.let { Path(it).toFile().delete() }
        ?: log.error("File with hash $hash not found in shared files")

    private fun deletePackLinks(pack: PackDownload) {
        val directory = localPackPath(pack.hash)
        pack.members.forEach { member ->
            val target = directory.resolve(Path(member.name).fileName.toString()).normalize()
            if (target.startsWith(directory)) Files.deleteIfExists(target)
        }
        Files.deleteIfExists(directory)
    }

    private fun nonAmarrLink(url: String): Exception {
        log.error(
            "The provided link does not appear to be an Amarr link: {}. " +
                    "Have you configured Radarr/Sonarr's download client priority correctly? See README.md", url
        )
        return NotFoundException("The provided link does not appear to be an Amarr link: $url")
    }

    private suspend fun <T> withAmule(block: () -> T): T =
        amuleMutex.withLock { withContext(Dispatchers.IO) { block() } }

    private suspend fun <T> readAmule(operation: String, block: () -> T): T = withAmule {
        runCatching(block).getOrElse { firstError ->
            log.warn("aMule EC failed while trying to {}; retrying once: {}", operation, firstError.message)
            block()
        }
    }

    private companion object {
        const val PACK_DIRECTORY = ".amarr-packs"
    }

    private data class CompletedFile(val path: String?, val name: String?)

}
