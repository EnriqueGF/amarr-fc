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
    private data class TvCriteria(val season: Int, val episode: Int?)

    private val searchMutex = Mutex()
    private val cacheTtlNanos = cacheSeconds * 1_000_000_000L
    private val cache = ConcurrentHashMap<String, CachedSearch>()

    override suspend fun search(query: String, offset: Int, limit: Int, cat: List<Int>): Feed {
        return search(query, offset, limit, cat, null)
    }

    override suspend fun searchTv(
        query: String,
        season: Int?,
        episode: Int?,
        offset: Int,
        limit: Int,
        cat: List<Int>,
    ): Feed {
        if (season == null) return search(query, offset, limit, cat)
        return search(query, offset, limit, cat, TvCriteria(season, episode))
    }

    private suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        cat: List<Int>,
        tvCriteria: TvCriteria?,
    ): Feed {
        log.debug("Starting search for query: {}, offset: {}, limit: {}", query, offset, limit)
        if (query.isBlank()) {
            log.debug("Empty query, returning empty response")
            return EMPTY_QUERY_RESPONSE
        }
        val cleanQuery = normalizeSearchQuery(query)
        val rankedFiles = searchFiles(cleanQuery)
            .asSequence()
            .filter(::isRelevantVideoResult)
            .distinctBy { file -> file.hash.joinToString("") { "%02x".format(it) } }
            .sortedByDescending { score(it, cleanQuery, tvCriteria) }
            .toList()
        val semanticMatches = tvCriteria?.let { criteria ->
            rankedFiles.filter { tvMatchStrength(it.fileName, criteria) > 0 }
        }.orEmpty()
        // Kad searches are deliberately broad so one network request covers S01E02,
        // 1x02, T01E02 and season packs. If any semantic matches exist, keep only
        // those; otherwise preserve the broad results as a compatibility fallback.
        val files = if (semanticMatches.isNotEmpty()) semanticMatches else rankedFiles
        return buildFeed(files, offset, limit, resultCategory(cat))
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

    private fun score(file: SearchFile, query: String, tvCriteria: TvCriteria?): Long {
        val normalizedName = normalizeSearchQuery(file.fileName).lowercase()
        val matchedTokens = query.lowercase().split(' ')
            .filter { it.length >= 2 }
            .count { normalizedName.contains(it) }
        return (tvCriteria?.let { tvMatchStrength(file.fileName, it) } ?: 0) * 100_000_000L +
            matchedTokens * 1_000_000L +
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

    private fun tvMatchStrength(fileName: String, criteria: TvCriteria): Long {
        val name = Normalizer.normalize(fileName, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
        val season = criteria.season.toString()
        val separator = "[ ._\\-]*"
        val startBoundary = "(?<![a-z0-9])"
        val endBoundary = "(?![0-9])"

        criteria.episode?.let { episode ->
            val episodeText = episode.toString()
            val paddedEpisode = episodeText.padStart(2, '0')
            val exactEpisodePatterns = listOf(
                "$startBoundary(?:s|t)0*$season${separator}e0*$episodeText$endBoundary",
                "(?<![0-9])0*$season${separator}x${separator}0*$episodeText$endBoundary",
                "$startBoundary(?:cap(?:itulo)?|episodio|episode)$separator" +
                    Regex.escape(season + paddedEpisode) + endBoundary,
            )
            if (exactEpisodePatterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(name) }) {
                return 3L
            }
            return 0L
        }

        val episodeInSeasonPatterns = listOf(
            "$startBoundary(?:s|t)0*$season${separator}e[0-9]+$endBoundary",
            "(?<![0-9])0*$season${separator}x${separator}[0-9]+$endBoundary",
            "$startBoundary(?:cap(?:itulo)?|episodio|episode)$separator" +
                Regex.escape(season) + "[0-9]{2}$endBoundary",
        )
        if (episodeInSeasonPatterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(name) }) {
            return 3L
        }
        val seasonPackPatterns = listOf(
            "$startBoundary(?:s|t)0*$season$endBoundary",
            "$startBoundary(?:temporada|season)$separator(?:0*$season|${ordinalWord(criteria.season)})$endBoundary",
        )
        return if (seasonPackPatterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(name) }) 2L else 0L
    }

    private fun ordinalWord(season: Int): String = when (season) {
        1 -> "primera|first"
        2 -> "segunda|second"
        3 -> "tercera|third"
        4 -> "cuarta|fourth"
        5 -> "quinta|fifth"
        else -> "0*$season"
    }

    private fun resultCategory(categories: List<Int>): String =
        categories.firstOrNull { it in 2000..2999 || it in 5000..5999 }?.toString() ?: TV_CATEGORY

    private fun buildFeed(items: List<SearchFile>, offset: Int, limit: Int, category: String) = Feed(
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
                            Item.TorznabAttribute("category", category),
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
        private const val TV_CATEGORY = "5030"

        private val EMPTY_QUERY_RESPONSE = Feed(
            channel = Feed.Channel(
                response = Feed.Channel.Response(offset = 0, total = 0),
                item = emptyList(),
            )
        )
    }

}
