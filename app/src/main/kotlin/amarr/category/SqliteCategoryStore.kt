package amarr.category

import amarr.torrent.model.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Transactional category/download ownership state used by Sonarr. */
class SqliteCategoryStore(storePath: String) : CategoryStore, AutoCloseable {
    private val connection: Connection

    init {
        val directory = Path.of(storePath)
        Files.createDirectories(directory)

        // Docker commonly mounts /tmp with noexec. sqlite-jdbc extracts its native
        // library before loading it, so keep that extraction beside the database
        // on the persistent, executable config volume instead.
        val nativeDirectory = directory.resolve("native")
        Files.createDirectories(nativeDirectory)
        System.setProperty("org.sqlite.tmpdir", nativeDirectory.toString())
        System.setProperty("jansi.tmpdir", nativeDirectory.toString())

        connection = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("amarr-fc.sqlite3")}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=FULL")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS downloads(
                    hash TEXT PRIMARY KEY,
                    category TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS packs(
                    hash TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS pack_members(
                    pack_hash TEXT NOT NULL REFERENCES packs(hash) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    member_hash TEXT NOT NULL,
                    name TEXT NOT NULL,
                    size INTEGER NOT NULL,
                    PRIMARY KEY(pack_hash, member_hash)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    name TEXT PRIMARY KEY,
                    save_path TEXT NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS download_observations(
                    hash TEXT PRIMARY KEY,
                    first_seen_at INTEGER NOT NULL,
                    last_activity_at INTEGER NOT NULL,
                    bytes INTEGER NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS replacement_attempts(
                    logical_key TEXT NOT NULL,
                    hash TEXT NOT NULL,
                    attempted_at INTEGER NOT NULL,
                    PRIMARY KEY(logical_key, hash)
                )
                """.trimIndent()
            )
        }
    }

    @Synchronized
    override fun store(category: String, hash: String) {
        require(category.isNotBlank()) { "category cannot be blank" }
        require(hash.matches(Regex("[0-9a-fA-F]{32}"))) { "invalid eD2k hash" }
        connection.prepareStatement(
            """
            INSERT INTO downloads(hash, category, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(hash) DO UPDATE SET category=excluded.category, updated_at=excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, hash.lowercase())
            statement.setString(2, category)
            statement.setLong(3, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    @Synchronized
    override fun getCategory(hash: String): String? = connection.prepareStatement(
        "SELECT category FROM downloads WHERE hash=?"
    ).use { statement ->
        statement.setString(1, hash.lowercase())
        statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
    }

    @Synchronized
    override fun delete(hash: String) {
        connection.prepareStatement("DELETE FROM downloads WHERE hash=?").use { statement ->
            statement.setString(1, hash.lowercase())
            statement.executeUpdate()
        }
    }

    @Synchronized
    override fun addCategory(category: Category) {
        require(category.name.isNotBlank()) { "category cannot be blank" }
        connection.prepareStatement(
            """
            INSERT INTO categories(name, save_path) VALUES (?, ?)
            ON CONFLICT(name) DO UPDATE SET save_path=excluded.save_path
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, category.name)
            statement.setString(2, category.savePath)
            statement.executeUpdate()
        }
    }

    @Synchronized
    override fun getCategories(): Set<Category> = connection.prepareStatement(
        "SELECT name, save_path FROM categories ORDER BY name"
    ).use { statement ->
        statement.executeQuery().use { result ->
            buildSet {
                while (result.next()) add(Category(result.getString(1), result.getString(2)))
            }
        }
    }

    @Synchronized
    override fun storePack(pack: PackDownload) {
        require(pack.hash.matches(Regex("[0-9a-fA-F]{32}"))) { "invalid pack hash" }
        require(pack.name.isNotBlank()) { "pack name cannot be blank" }
        require(pack.category.isNotBlank()) { "pack category cannot be blank" }
        require(pack.members.isNotEmpty()) { "pack must contain members" }
        require(pack.members.map { it.hash.lowercase() }.distinct().size == pack.members.size) {
            "pack members must be unique"
        }
        require(pack.members.all { it.name.isNotBlank() && it.size > 0 }) { "invalid pack member" }
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                INSERT INTO packs(hash, name, category, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(hash) DO UPDATE SET
                    name=excluded.name,
                    category=excluded.category,
                    updated_at=excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, pack.hash.lowercase())
                statement.setString(2, pack.name)
                statement.setString(3, pack.category)
                statement.setLong(4, System.currentTimeMillis())
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM pack_members WHERE pack_hash=?").use { statement ->
                statement.setString(1, pack.hash.lowercase())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO pack_members(pack_hash, position, member_hash, name, size) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                pack.members.forEachIndexed { index, member ->
                    require(member.hash.matches(Regex("[0-9a-fA-F]{32}"))) { "invalid member hash" }
                    statement.setString(1, pack.hash.lowercase())
                    statement.setInt(2, index)
                    statement.setString(3, member.hash.lowercase())
                    statement.setString(4, member.name)
                    statement.setLong(5, member.size)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    @Synchronized
    override fun getPack(hash: String): PackDownload? = connection.prepareStatement(
        "SELECT hash, name, category FROM packs WHERE hash=?"
    ).use { statement ->
        statement.setString(1, hash.take(32).lowercase())
        statement.executeQuery().use { result ->
            if (!result.next()) return@use null
            PackDownload(
                hash = result.getString("hash"),
                name = result.getString("name"),
                category = result.getString("category"),
                members = getPackMembers(result.getString("hash")),
            )
        }
    }

    @Synchronized
    override fun getPacks(category: String?): List<PackDownload> {
        val sql = if (category == null) {
            "SELECT hash, name, category FROM packs ORDER BY updated_at"
        } else {
            "SELECT hash, name, category FROM packs WHERE category=? ORDER BY updated_at"
        }
        return connection.prepareStatement(sql).use { statement ->
            if (category != null) statement.setString(1, category)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val hash = result.getString("hash")
                        add(
                            PackDownload(
                                hash = hash,
                                name = result.getString("name"),
                                category = result.getString("category"),
                                members = getPackMembers(hash),
                            )
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    override fun deletePack(hash: String) {
        connection.prepareStatement("DELETE FROM packs WHERE hash=?").use { statement ->
            statement.setString(1, hash.take(32).lowercase())
            statement.executeUpdate()
        }
    }

    @Synchronized
    override fun observeDownload(
        hash: String,
        bytes: Long,
        active: Boolean,
        now: Long,
    ): DownloadObservation {
        val normalizedHash = hash.lowercase()
        val existing = connection.prepareStatement(
            "SELECT first_seen_at, last_activity_at, bytes FROM download_observations WHERE hash=?"
        ).use { statement ->
            statement.setString(1, normalizedHash)
            statement.executeQuery().use { result ->
                if (result.next()) DownloadObservation(
                    result.getLong("first_seen_at"),
                    result.getLong("last_activity_at"),
                    result.getLong("bytes"),
                ) else null
            }
        }
        val firstSeen = existing?.firstSeenAt ?: connection.prepareStatement(
            "SELECT updated_at FROM downloads WHERE hash=?"
        ).use { statement ->
            statement.setString(1, normalizedHash)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else now }
        }
        val lastActivity = when {
            existing == null -> firstSeen
            active || bytes > existing.bytes -> now
            else -> existing.lastActivityAt
        }
        connection.prepareStatement(
            """
            INSERT INTO download_observations(hash, first_seen_at, last_activity_at, bytes)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(hash) DO UPDATE SET
                last_activity_at=excluded.last_activity_at,
                bytes=excluded.bytes
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, normalizedHash)
            statement.setLong(2, firstSeen)
            statement.setLong(3, lastActivity)
            statement.setLong(4, bytes)
            statement.executeUpdate()
        }
        return DownloadObservation(firstSeen, lastActivity, bytes)
    }

    @Synchronized
    override fun attemptedHashes(logicalKey: String): Set<String> = connection.prepareStatement(
        "SELECT hash FROM replacement_attempts WHERE logical_key=?"
    ).use { statement ->
        statement.setString(1, logicalKey)
        statement.executeQuery().use { result ->
            buildSet { while (result.next()) add(result.getString(1)) }
        }
    }

    @Synchronized
    override fun markAttempt(logicalKey: String, hash: String, now: Long) {
        connection.prepareStatement(
            "INSERT OR IGNORE INTO replacement_attempts(logical_key, hash, attempted_at) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setString(1, logicalKey)
            statement.setString(2, hash.lowercase())
            statement.setLong(3, now)
            statement.executeUpdate()
        }
    }

    @Synchronized
    override fun replacePackMember(
        packHash: String,
        oldHash: String,
        replacement: PackMember,
    ): Boolean {
        require(replacement.hash.matches(Regex("[0-9a-fA-F]{32}"))) { "invalid replacement hash" }
        require(replacement.name.isNotBlank() && replacement.size > 0) { "invalid replacement member" }
        val normalizedPack = packHash.take(32).lowercase()
        val normalizedOld = oldHash.lowercase()
        val normalizedNew = replacement.hash.lowercase()
        val category = getPack(normalizedPack)?.category ?: return false
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val updated = connection.prepareStatement(
                """
                UPDATE pack_members SET member_hash=?, name=?, size=?
                WHERE pack_hash=? AND member_hash=?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, normalizedNew)
                statement.setString(2, replacement.name)
                statement.setLong(3, replacement.size)
                statement.setString(4, normalizedPack)
                statement.setString(5, normalizedOld)
                statement.executeUpdate()
            }
            if (updated != 1) {
                connection.rollback()
                return false
            }
            connection.prepareStatement(
                """
                INSERT INTO downloads(hash, category, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(hash) DO UPDATE SET category=excluded.category, updated_at=excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, normalizedNew)
                statement.setString(2, category)
                statement.setLong(3, System.currentTimeMillis())
                statement.executeUpdate()
            }
            val oldStillReferenced = connection.prepareStatement(
                "SELECT 1 FROM pack_members WHERE member_hash=? LIMIT 1"
            ).use { statement ->
                statement.setString(1, normalizedOld)
                statement.executeQuery().use { it.next() }
            }
            if (!oldStillReferenced) {
                connection.prepareStatement("DELETE FROM downloads WHERE hash=?").use { statement ->
                    statement.setString(1, normalizedOld)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM download_observations WHERE hash=?").use { statement ->
                    statement.setString(1, normalizedOld)
                    statement.executeUpdate()
                }
            }
            connection.commit()
            return true
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun getPackMembers(hash: String): List<PackMember> = connection.prepareStatement(
        "SELECT member_hash, name, size FROM pack_members WHERE pack_hash=? ORDER BY position"
    ).use { statement ->
        statement.setString(1, hash.lowercase())
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(
                        PackMember(
                            hash = result.getString("member_hash"),
                            name = result.getString("name"),
                            size = result.getLong("size"),
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    override fun close() = connection.close()
}
