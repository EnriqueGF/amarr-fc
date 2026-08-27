package amarr.category

import amarr.torrent.model.Category
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SqliteCategoryStoreTest : StringSpec({
    "should persist download ownership and categories across restarts" {
        val directory = Files.createTempDirectory("amarr-fc-state")
        SqliteCategoryStore(directory.toString()).use { store ->
            Files.isDirectory(directory.resolve("native")) shouldBe true
            System.getProperty("org.sqlite.tmpdir") shouldBe directory.resolve("native").toString()
            System.getProperty("jansi.tmpdir") shouldBe directory.resolve("native").toString()
            store.addCategory(Category("sonarr", "/data/amule/complete"))
            store.store("sonarr", "00112233445566778899aabbccddeeff")
        }

        SqliteCategoryStore(directory.toString()).use { restored ->
            restored.getCategory("00112233445566778899AABBCCDDEEFF") shouldBe "sonarr"
            restored.getCategories() shouldBe setOf(Category("sonarr", "/data/amule/complete"))
        }
    }

    "should persist and delete virtual packs transactionally" {
        val directory = Files.createTempDirectory("amarr-fc-packs")
        val pack = PackDownload(
            hash = "ffeeddccbbaa99887766554433221100",
            name = "Dragon Ball Super S01 PACK aMule",
            category = "sonarr",
            members = listOf(
                PackMember("00112233445566778899aabbccddeeff", "Dragon Ball Super S01E01.mkv", 100),
                PackMember("112233445566778899aabbccddeeff00", "Dragon Ball Super S01E02.mkv", 200),
            ),
        )
        SqliteCategoryStore(directory.toString()).use { it.storePack(pack) }

        SqliteCategoryStore(directory.toString()).use { restored ->
            restored.getPack(pack.hash) shouldBe pack
            restored.getPacks("sonarr") shouldBe listOf(pack)
            restored.deletePack(pack.hash)
            restored.getPack(pack.hash) shouldBe null
        }
    }
})
