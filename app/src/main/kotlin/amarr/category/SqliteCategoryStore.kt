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
                CREATE TABLE IF NOT EXISTS categories(
                    name TEXT PRIMARY KEY,
                    save_path TEXT NOT NULL
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
    override fun close() = connection.close()
}
