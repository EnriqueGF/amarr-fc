package amarr.torznab.indexer

import amarr.MagnetLink
import amarr.torznab.model.Caps
import amarr.torznab.model.Feed
import amarr.torznab.model.Feed.Channel.Item
import io.ktor.util.logging.*
import jamule.AmuleClient
import jamule.request.SearchType
import jamule.response.SearchResultsResponse.SearchFile
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AmuleIndexer(
    private val amuleClient: AmuleClient,
    private val log: Logger,
    cacheSeconds: Long = 900,
) : Indexer {
    private data class CachedSearch(val createdAtNanos: Long, val files: List<SearchFile>)

    private val searchMutex = Mutex()
    private val cacheTtlNanos = cacheSeconds * 1_000_000_000L
    private val cache = ConcurrentHashMap<String, CachedSearch>()

    override suspend fun search(query: String, offset: Int, limit: Int, cat: List<Int>): Feed {
        log.debug("Starting search for query: {}, offset: {}, limit: {}", query, offset, limit)
        if (query.isBlank()) {
            log.debug("Empty query, returning empty response")
            return EMPTY_QUERY_RESPONSE
        }
        val cleanQuery = normalizeSearchQuery(query)
        val files = searchFiles(cleanQuery)
            .asSequence()
            .filter(::isRelevantVideoResult)
            .distinctBy { file -> file.hash.joinToString("") { "%02x".format(it) } }
            .sortedByDescending { score(it, cleanQuery) }
            .toList()
        return buildFeed(files, offset, limit)
    }

    override suspend fun capabilities(): Caps = Caps()

    private fun isRelevantVideoResult(file: SearchFile): Boolean {
        val extension = file.fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension in EXCLUDED_EXTENSIONS) {
            return false
        }
        val looksLikeVideo = extension in VIDEO_EXTENSIONS ||
            (extension.isBlank() && file.sizeFull >= MIN_VIDEO_SIZE_BYTES)
        return looksLikeVideo && file.sourceCount > 0
    }

    private suspend fun searchFiles(cleanQuery: String): List<SearchFile> {
        val key = cleanQuery.lowercase()
        cached(key)?.let { return it }
        return searchMutex.withLock {
            cached(key)?.let { return@withLock it }
            log.info("Running serialized aMule search for: {}", cleanQuery)
            val files = withContext(Dispatchers.IO) {
                // jaMule may either throw directly or return Result.failure,
                // depending on whether the EC server rejects the request.
                val global = runCatching {
                    amuleClient.searchSync(cleanQuery, SearchType.GLOBAL).getOrThrow()
                }
                global.fold(
                    onSuccess = { it.files },
                    onFailure = { globalError ->
                        log.warn(
                            "Global eD2k search unavailable; retrying over Kad: {}",
                            globalError.message,
                        )
                        amuleClient.searchSync(cleanQuery, SearchType.KAD).getOrThrow().files
                    },
                )
            }
            log.info("aMule search completed for '{}': {} raw candidate(s)", cleanQuery, files.size)
            cache[key] = CachedSearch(System.nanoTime(), files)
            files
        }
    }

    private fun cached(key: String): List<SearchFile>? {
        if (cacheTtlNanos == 0L) return null
        val cached = cache[key] ?: return null
        if (System.nanoTime() - cached.createdAtNanos <= cacheTtlNanos) return cached.files
        cache.remove(key, cached)
        return null
    }

    private fun score(file: SearchFile, query: String): Long {
        val normalizedName = normalizeSearchQuery(file.fileName).lowercase()
        val matchedTokens = query.lowercase().split(' ')
            .filter { it.length >= 2 }
            .count { normalizedName.contains(it) }
        return matchedTokens * 1_000_000L +
            file.completeSourceCount * 10_000L +
            file.sourceCount * 100L +
            minOf(file.sizeFull / (100L * 1024L * 1024L), 99L)
    }

    private fun normalizeSearchQuery(query: String): String =
        Normalizer.normalize(query, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^\\w\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun buildFeed(items: List<SearchFile>, offset: Int, limit: Int) = Feed(
        channel = Feed.Channel(
            response = Feed.Channel.Response(
                offset = offset,
                total = items.size
            ),
            item = items
                .drop(offset)
                .take(limit)
                .map { result ->
                    Item(
                        title = result.fileName,
                        enclosure = Item.Enclosure(
                            url = MagnetLink.forAmarr(result.hash, result.fileName, result.sizeFull).toString(),
                            length = result.sizeFull
                        ),
                        attributes = listOf(
                            Item.TorznabAttribute("category", "5030"),
                            Item.TorznabAttribute("seeders", result.completeSourceCount.toString()),
                            Item.TorznabAttribute("peers", result.sourceCount.toString()),
                            Item.TorznabAttribute("size", result.sizeFull.toString())
                        )
                    )
                }
        )
    )

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "avi",
            "m2ts",
            "m4v",
            "mkv",
            "mov",
            "mp4",
            "mpeg",
            "mpg",
            "ts",
            "webm",
            "wmv"
        )
        private val EXCLUDED_EXTENSIONS = setOf(
            "ass",
            "cue",
            "gif",
            "jpg",
            "jpeg",
            "m3u",
            "mp3",
            "nfo",
            "png",
            "rar",
            "srt",
            "sub",
            "txt",
            "zip"
        )
        private const val MIN_VIDEO_SIZE_BYTES = 50L * 1024L * 1024L

        private val EMPTY_QUERY_RESPONSE = Feed(
            channel = Feed.Channel(
                response = Feed.Channel.Response(offset = 0, total = 0),
                item = emptyList(),
            )
        )
    }

}
