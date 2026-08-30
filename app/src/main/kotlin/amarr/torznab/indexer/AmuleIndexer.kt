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

data class ReplacementCandidate(
    val hash: String,
    val name: String,
    val size: Long,
    val completeSources: Int,
    val sources: Int,
)

class AmuleIndexer(
    private val amuleClient: AmuleClient,
    private val log: Logger,
    cacheSeconds: Long = 900,
    private val amuleMutex: Mutex = Mutex(),
) : Indexer {
    private data class CachedSearch(val createdAtNanos: Long, val files: List<SearchFile>)
    private data class TvCriteria(val season: Int, val episode: Int?)

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
        val titleMatches = rankedFiles.filter { matchesTitle(it.fileName, cleanQuery) }
        val titleRelevantFiles = when {
            titleMatches.isNotEmpty() -> titleMatches
            tvCriteria != null -> emptyList()
            else -> rankedFiles
        }
        val semanticMatches = tvCriteria?.let { criteria ->
            titleRelevantFiles.filter { tvMatchStrength(it.fileName, criteria) > 0 }
        }.orEmpty()
        // Kad searches are deliberately broad so one network request covers S01E02,
        // 1x02, T01E02 and season packs. TV searches must only expose semantic
        // matches: returning another season is worse than returning no result.
        val files = if (tvCriteria != null) semanticMatches else titleRelevantFiles
        return buildFeed(files, offset, limit, resultCategory(cat), cleanQuery, tvCriteria)
    }

    override suspend fun capabilities(): Caps = Caps()

    /** Fresh, strict candidates used only to replace a stalled pack member. */
    suspend fun findSeasonReplacementCandidates(
        query: String,
        season: Int,
        excludedHashes: Set<String>,
    ): Map<Int, ReplacementCandidate> {
        val cleanQuery = normalizeSearchQuery(query)
        val criteria = TvCriteria(season, null)
        val excluded = excludedHashes.mapTo(hashSetOf()) { it.lowercase() }
        return searchFiles(cleanQuery, refresh = true)
            .asSequence()
            .filter(::isRelevantVideoResult)
            .filter { it.completeSourceCount > 0 }
            .filter { matchesOrderedTitle(it.fileName, cleanQuery) }
            .mapNotNull { file -> episodeNumber(file.fileName, season)?.let { it to file } }
            .filterNot { (_, file) -> hashHex(file.hash) in excluded }
            .sortedByDescending { (_, file) -> score(file, cleanQuery, criteria) }
            .distinctBy { (episode, _) -> episode }
            .associate { (episode, file) ->
                episode to ReplacementCandidate(
                    hash = hashHex(file.hash),
                    name = file.fileName,
                    size = file.sizeFull,
                    completeSources = file.completeSourceCount.toInt(),
                    sources = file.sourceCount.toInt(),
                )
            }
    }

    private fun isRelevantVideoResult(file: SearchFile): Boolean {
        val extension = file.fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension in EXCLUDED_EXTENSIONS) {
            return false
        }
        val looksLikeVideo = extension in VIDEO_EXTENSIONS ||
            (extension.isBlank() && file.sizeFull >= MIN_VIDEO_SIZE_BYTES)
        return looksLikeVideo && file.sourceCount > 0
    }

    private suspend fun searchFiles(cleanQuery: String, refresh: Boolean = false): List<SearchFile> {
        val key = cleanQuery.lowercase()
        if (!refresh) cached(key)?.let { return it }
        return amuleMutex.withLock {
            if (!refresh) cached(key)?.let { return@withLock it }
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

    private fun hashHex(hash: ByteArray): String = hash.joinToString("") { "%02x".format(it) }

    private fun score(file: SearchFile, query: String, tvCriteria: TvCriteria?): Long {
        val nameTokens = normalizedTokens(file.fileName)
        val matchedTokens = titleTokens(query).count { it in nameTokens }
        return (tvCriteria?.let { tvMatchStrength(file.fileName, it) } ?: 0) * 100_000_000L +
            matchedTokens * 1_000_000L +
            file.completeSourceCount * 10_000L +
            file.sourceCount * 100L +
            minOf(file.sizeFull / (100L * 1024L * 1024L), 99L)
    }

    private fun normalizeSearchQuery(query: String): String =
        Normalizer.normalize(query, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun matchesTitle(fileName: String, query: String): Boolean {
        val expected = titleTokens(query)
        if (expected.isEmpty()) return true
        val actual = normalizedTokens(fileName)
        return expected.all { it in actual }
    }

    private fun matchesOrderedTitle(fileName: String, query: String): Boolean {
        val expected = normalizeSearchQuery(query).lowercase()
        val actual = normalizeSearchQuery(fileName).lowercase()
        val phrase = expected.split(' ').filter { it.isNotBlank() }.joinToString("\\s+") { Regex.escape(it) }
        return expected.isNotBlank() && Regex(
            "(?<![\\p{L}\\p{N}])$phrase(?![\\p{L}\\p{N}])"
        ).containsMatchIn(actual)
    }

    private fun normalizedTokens(text: String): Set<String> =
        normalizeSearchQuery(text).lowercase().split(' ').filter { it.isNotBlank() }.toSet()

    private fun titleTokens(query: String): List<String> {
        val tokens = normalizedTokens(query).filter { it.length >= 2 }
        val significant = tokens.filterNot { it in TITLE_STOP_WORDS }
        return if (significant.isNotEmpty()) significant else tokens
    }

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
                "${startBoundary}0*$season${separator}x${separator}0*$episodeText$endBoundary",
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
            "${startBoundary}0*$season${separator}x${separator}[0-9]+$endBoundary",
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

    private fun buildFeed(
        items: List<SearchFile>,
        offset: Int,
        limit: Int,
        category: String,
        query: String,
        tvCriteria: TvCriteria?,
    ): Feed {
        val individualItems = items.map { result ->
            Item(
                title = result.fileName,
                enclosure = Item.Enclosure(
                    url = MagnetLink.forAmarr(result.hash, result.fileName, result.sizeFull).toString(),
                    length = result.sizeFull
                ),
                attributes = resultAttributes(
                    category,
                    result.completeSourceCount.toInt(),
                    result.sourceCount.toInt(),
                    result.sizeFull,
                ),
            )
        }
        val virtualPack = tvCriteria
            ?.takeIf { it.episode == null }
            ?.let { createVirtualPack(items, query, it.season, category) }
        val feedItems = listOfNotNull(virtualPack) + individualItems
        return Feed(
            channel = Feed.Channel(
                response = Feed.Channel.Response(offset = offset, total = feedItems.size),
                item = feedItems.drop(offset).take(limit),
            )
        )
    }

    private fun createVirtualPack(
        files: List<SearchFile>,
        query: String,
        season: Int,
        category: String,
    ): Item? {
        val selectedByEpisode = linkedMapOf<Int, SearchFile>()
        files.forEach { file ->
            episodeNumber(file.fileName, season)?.let { episode -> selectedByEpisode.putIfAbsent(episode, file) }
        }
        val episodes = selectedByEpisode.keys.sorted()
        if (episodes.size < 2) return null
        val selected = episodes.map { selectedByEpisode.getValue(it) }
        val resolution = dominantResolution(selected)
        val packName = "$query S${season.toString().padStart(2, '0')} PACK HDTV-${resolution}p aMule"
        val pack = MagnetLink.forAmarrPack(
            packName,
            selected.map { MagnetLink.forAmarr(it.hash, it.fileName, it.sizeFull) },
        )
        return Item(
            title = packName,
            enclosure = Item.Enclosure(url = pack.toString(), length = pack.size),
            attributes = resultAttributes(
                category = category,
                seeders = selected.minOf { it.completeSourceCount }.toInt(),
                peers = selected.minOf { it.sourceCount }.toInt(),
                size = pack.size,
            ),
        )
    }

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

    private fun dominantResolution(files: List<SearchFile>): Int {
        val resolutions = files.mapNotNull { file ->
            Regex("(?<![0-9])(2160|1080|720|576|480)p?(?![0-9])", RegexOption.IGNORE_CASE)
                .find(file.fileName)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        }
        return resolutions
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            ?.key
            ?: 1080
    }

    private fun resultAttributes(category: String, seeders: Int, peers: Int, size: Long) = listOf(
        Item.TorznabAttribute("category", category),
        Item.TorznabAttribute("seeders", seeders.toString()),
        Item.TorznabAttribute("peers", peers.toString()),
        Item.TorznabAttribute("size", size.toString()),
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
        private val TITLE_STOP_WORDS = setOf(
            "a", "al", "and", "de", "del", "el", "en", "la", "las", "los", "of", "the", "un", "una", "y"
        )

        private val EMPTY_QUERY_RESPONSE = Feed(
            channel = Feed.Channel(
                response = Feed.Channel.Response(offset = 0, total = 0),
                item = emptyList(),
            )
        )
    }

}
