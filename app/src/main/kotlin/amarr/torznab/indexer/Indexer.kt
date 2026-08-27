package amarr.torznab.indexer

import amarr.torznab.model.Caps
import amarr.torznab.model.Feed

interface Indexer {

    /**
     * Given a paginated query, returns a [Feed] with the results.
     */
    suspend fun search(query: String, offset: Int, limit: Int, cat: List<Int>): Feed

    /**
     * Searches TV releases while preserving the season/episode information sent by Sonarr.
     * Indexers that cannot rank TV naming conventions can keep the traditional SxxExx query.
     */
    suspend fun searchTv(
        query: String,
        season: Int?,
        episode: Int?,
        offset: Int,
        limit: Int,
        cat: List<Int>,
    ): Feed {
        val preciseQuery = if (season != null && episode != null) {
            "$query S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        } else {
            query
        }
        return search(preciseQuery, offset, limit, cat)
    }

    /**
     * Returns the capabilities of this indexer.
     */
    suspend fun capabilities(): Caps

}
