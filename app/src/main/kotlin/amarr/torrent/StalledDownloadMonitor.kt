package amarr.torrent

import amarr.MagnetLink
import amarr.category.CategoryStore
import amarr.category.PackDownload
import amarr.category.PackMember
import amarr.torznab.indexer.AmuleIndexer
import jamule.AmuleClient
import jamule.model.AmuleTransferringFile
import jamule.model.DownloadCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.text.Normalizer

class StalledDownloadMonitor(
    private val amuleClient: AmuleClient,
    private val indexer: AmuleIndexer,
    private val categoryStore: CategoryStore,
    private val amuleMutex: Mutex,
    private val log: Logger,
    private val stallMillis: Long,
    private val maxReplacementsPerRun: Int,
) {
    private data class EligibleMember(
        val pack: PackDownload,
        val member: PackMember,
        val file: AmuleTransferringFile,
        val episode: Int,
        val lastActivityAt: Long,
    )

    suspend fun runOnce(now: Long = System.currentTimeMillis()): Int {
        val queue = amuleMutex.withLock {
            withContext(Dispatchers.IO) { amuleClient.getDownloadQueue().getOrThrow() }
        }
        val queueByHash = queue.associateBy { it.fileHashHexString?.lowercase() }
        val packs = categoryStore.getPacks()
        val references = packs.flatMap { pack -> pack.members.map { it.hash.lowercase() } }
            .groupingBy { it }
            .eachCount()
        val eligible = packs.flatMap { pack ->
            val identity = packIdentity(pack) ?: return@flatMap emptyList()
            pack.members.mapNotNull { member ->
                val hash = member.hash.lowercase()
                val file = queueByHash[hash] ?: return@mapNotNull null
                val bytes = file.sizeDone ?: 0L
                val active = file.sourceCount > 0 || file.sourceXferCount > 0 || (file.speed ?: 0L) > 0
                val observation = categoryStore.observeDownload(hash, bytes, active, now)
                    ?: return@mapNotNull null
                val episode = episodeNumber(member.name, identity.second) ?: return@mapNotNull null
                if (
                    bytes == 0L && !active &&
                    now - observation.lastActivityAt >= stallMillis &&
                    references[hash] == 1
                ) {
                    EligibleMember(pack, member, file, episode, observation.lastActivityAt)
                } else null
            }
        }
        if (eligible.isEmpty()) return 0

        // One fresh network search can provide alternatives for several dead
        // members of the same season without monopolising aMule's EC slot.
        val selectedPack = eligible.minBy { it.lastActivityAt }.pack
        val packIdentity = packIdentity(selectedPack) ?: return 0
        val selected = eligible.filter { it.pack.hash.equals(selectedPack.hash, ignoreCase = true) }
        val trackedHashes = packs.flatMapTo(hashSetOf()) { pack ->
            pack.members.map { it.hash.lowercase() }
        }
        val attempted = selected.flatMapTo(hashSetOf()) { member ->
            categoryStore.attemptedHashes(logicalKey(selectedPack, member.episode))
        }
        val candidates = indexer.findSeasonReplacementCandidates(
            query = packIdentity.first,
            season = packIdentity.second,
            excludedHashes = trackedHashes + attempted,
        )
        // Whether or not a replacement exists, defer this season so the next
        // cycle examines another pack instead of starving the queue behind it.
        selected.forEach { stalled ->
            categoryStore.observeDownload(stalled.member.hash, 0L, active = true, now = now)
        }

        var replaced = 0
        selected.sortedBy { it.lastActivityAt }.forEach { stalled ->
            if (replaced >= maxReplacementsPerRun) return@forEach
            val candidate = candidates[stalled.episode] ?: return@forEach
            val logicalKey = logicalKey(selectedPack, stalled.episode)
            val replacement = PackMember(candidate.hash, candidate.name, candidate.size)
            val added = runCatching {
                amuleMutex.withLock {
                    withContext(Dispatchers.IO) {
                        amuleClient.downloadEd2kLink(
                            MagnetLink.forAmarr(
                                candidate.hash.hexToByteArray(),
                                candidate.name,
                                candidate.size,
                            ).toEd2kLink()
                        ).getOrThrow()
                    }
                }
            }.onFailure { error ->
                log.warn(
                    "Could not queue replacement for {} S{}E{}: {}",
                    packIdentity.first, packIdentity.second, stalled.episode, error.message,
                )
            }.isSuccess
            if (!added) return@forEach

            val stored = runCatching {
                categoryStore.replacePackMember(selectedPack.hash, stalled.member.hash, replacement)
            }.getOrElse { error ->
                log.error("Could not persist replacement {}: {}", candidate.name, error.message)
                false
            }
            if (!stored) {
                deleteDownload(candidate.hash)
                return@forEach
            }

            categoryStore.markAttempt(logicalKey, stalled.member.hash, now)
            categoryStore.markAttempt(logicalKey, candidate.hash, now)
            deleteDownload(stalled.member.hash)
            replaced++
            log.info(
                "Replaced stalled {} S{}E{} with '{}' ({} complete source(s), {} total source(s))",
                packIdentity.first,
                packIdentity.second,
                stalled.episode,
                candidate.name,
                candidate.completeSources,
                candidate.sources,
            )
        }
        return replaced
    }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun deleteDownload(hash: String) {
        runCatching {
            amuleMutex.withLock {
                withContext(Dispatchers.IO) {
                    amuleClient.sendDownloadCommand(hash.hexToByteArray(), DownloadCommand.DELETE).getOrThrow()
                }
            }
        }.onFailure { error -> log.warn("Could not remove superseded aMule hash {}: {}", hash, error.message) }
    }

    private fun packIdentity(pack: PackDownload): Pair<String, Int>? {
        val match = PACK_NAME.matchEntire(pack.name.trim()) ?: return null
        val title = match.groupValues[1].trim()
        val season = match.groupValues[2].toIntOrNull() ?: return null
        return title.takeIf { it.isNotBlank() }?.let { it to season }
    }

    private fun logicalKey(pack: PackDownload, episode: Int) =
        "${pack.hash.lowercase()}:e${episode.toString().padStart(3, '0')}"

    private fun episodeNumber(fileName: String, season: Int): Int? {
        val name = Normalizer.normalize(fileName, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
        val separator = "[ ._\\-]*"
        val patterns = listOf(
            Regex("(?<![a-z0-9])(?:s|t)0*$season${separator}e0*([0-9]+)(?![0-9])"),
            Regex("(?<![a-z0-9])0*$season${separator}x${separator}0*([0-9]+)(?![0-9])"),
            Regex("(?<![a-z0-9])(?:cap(?:itulo)?|episodio|episode)$separator" + season + "([0-9]{2})(?![0-9])"),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(name)?.groupValues?.get(1)?.toIntOrNull()
        }?.takeIf { it in 1..999 }
    }

    private companion object {
        val PACK_NAME = Regex("^(.*?)\\s+S0*([0-9]+)\\s+PACK(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
    }
}
