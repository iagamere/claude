package com.abdo.ps4monitor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for phase 3: screens must take their corners from the design (dnaShape / dnaCard / DnaSurface), never from a hard-coded radius,
 * otherwise a theme could not change the look of that screen. A deliberate exception is marked `fixed-shape` on the same line.
 */
class PageThemingTest {
    private fun dir(): File = listOf("src/main/java/com/abdo/ps4monitor", "app/src/main/java/com/abdo/ps4monitor").map { File(it) }.first { it.isDirectory }
    private val pages = listOf("HomeUi", "DownloadsUi", "FilesUi", "BrowserUi", "CapturesUi", "PkgUi", "Ui", "Ps4InfoUi", "ServerUi", "StorageUi", "SettingsUi", "GamesUi", "TrophiesUi", "ServerPicker", "Gallery")

    @Test fun screensHaveNoHardCodedCornerRadii() {
        val bad = ArrayList<String>()
        for (p in pages) {
            val f = File(dir(), "$p.kt"); if (!f.exists()) continue
            f.readLines().forEachIndexed { i, l -> if (l.contains("RoundedCornerShape(") && !l.trimStart().startsWith("import") && !l.contains("fixed-shape")) bad.add("$p.kt:${i + 1}") }
        }
        assertTrue("hard-coded corners: $bad", bad.isEmpty())
    }
    @Test fun sharedComponentsAreDefinedOnceAndUsedByTheHomeScreen() {
        val kit = File(dir(), "DnaKit.kt").readText(); val home = File(dir(), "HomeUi.kt").readText()
        for (n in listOf("fun ActionTile", "fun StatusPill", "fun PrimaryAction", "fun SecondaryAction")) {
            assertTrue("$n once in the kit", Regex(Regex.escape(n)).findAll(kit).count() == 1); assertTrue("$n not redefined in HomeUi", !home.contains(n))
        }
        assertTrue(home.contains("dnaCard("))
    }
    @Test fun everyDesignLayerValueIsHandledByTheKit() {
        // the Kotlin compiler checks `when` exhaustiveness; this guards the other half: every motif / rule / texture the generator can choose is drawn somewhere
        val kit = File(dir(), "DnaKit.kt").readText()
        for (k in OrnamentKind.values()) assertTrue("ornament $k not drawn", kit.contains("OrnamentKind.${k.name} ->"))
        for (k in RuleKind.values()) assertTrue("rule $k not drawn", kit.contains("RuleKind.${k.name} ->"))
        for (k in TextureKind.values().filter { it != TextureKind.NONE }) assertTrue("texture $k not drawn", kit.contains("TextureKind.${k.name} ->"))
        for (k in ProgressKind.values()) assertTrue("progress $k not drawn", kit.contains("ProgressKind.${k.name} ->"))
        for (k in BorderKind.values()) assertTrue("border $k not drawn", kit.contains("BorderKind.${k.name} ->"))
    }
}
