package com.abdo.ps4monitor

import kotlin.math.min

/**
 * What kind of game it is, as visual tags. They decide the MOTIFS and the component language (a football game gets pitch lines and slanted
 * cards, a samurai game gets brush and paper, a pixel game gets blocks), while the artwork keeps deciding the colours and the mood.
 * Sources: keywords in the title (offline) and the genre sentence of an encyclopedia entry (online, optional). Pure Kotlin.
 */
enum class Tag { SPORTS, RACING, FIGHTING, SHOOTER, HORROR, FANTASY, DARK_FANTASY, SAMURAI, SCIFI, CYBERPUNK, SPACE, PIXEL, ANIME, ADVENTURE, STRATEGY, SURVIVAL, FAMILY, NAUTICAL, SOULSLIKE, MYTHIC, ACTION }

object GenreClassifier {
    private class Rule(val tag: Tag, val weight: Float, val words: List<String>)
    private fun r(tag: Tag, weight: Float, vararg w: String) = Rule(tag, weight, w.toList())

    /** Whole-word / phrase hints in a game TITLE (works offline). Deliberately about genres and settings, never about one specific game. */
    private val titleRules = listOf(
        r(Tag.SPORTS, 0.9f, "fc", "fifa", "nba", "nfl", "nhl", "mlb", "madden", "pes", "efootball", "pro evolution soccer", "football", "soccer", "basketball", "baseball", "tennis", "golf", "ufc", "wwe", "wrestling", "cricket", "rugby", "skate", "snowboard", "olympic"),
        r(Tag.RACING, 0.9f, "racing", "race", "rally", "forza", "gran turismo", "need for speed", "burnout", "grid", "dirt", "f1", "motogp", "drift", "kart", "motorsport", "wrc", "drive", "driver"),
        r(Tag.FIGHTING, 0.9f, "tekken", "mortal kombat", "street fighter", "fighter", "fighterz", "guilty gear", "soulcalibur", "blazblue", "injustice", "brawl", "kombat", "fighting"),
        r(Tag.SHOOTER, 0.8f, "call of duty", "battlefield", "doom", "halo", "shooter", "warfare", "sniper", "gun", "wolfenstein", "fps", "arena", "strike", "ops"),
        r(Tag.HORROR, 0.9f, "resident evil", "silent hill", "horror", "outlast", "evil within", "dead space", "alien isolation", "fatal frame", "nightmare", "haunted", "dying light"),
        r(Tag.SAMURAI, 0.8f, "samurai", "ninja", "shinobi", "nioh", "onimusha", "katana", "shogun", "sekiro", "bushido", "yakuza", "like a dragon"),
        r(Tag.FANTASY, 0.7f, "fantasy", "dragon", "witcher", "elder scrolls", "skyrim", "dungeon", "kingdom", "quest", "sword", "magic", "legend", "mage", "wizard"),
        r(Tag.SOULSLIKE, 0.9f, "dark souls", "demon's souls", "bloodborne", "souls", "lies of p", "lords of the fallen", "code vein"),
        r(Tag.CYBERPUNK, 0.9f, "cyberpunk", "neon", "deus ex", "ghostrunner", "watch dogs"),
        r(Tag.SCIFI, 0.7f, "mass effect", "mech", "robot", "android", "alien", "future", "titanfall", "destiny", "matrix", "cyber"),
        r(Tag.SPACE, 0.8f, "space", "galaxy", "star wars", "starfield", "no man's sky", "astro", "cosmos", "orbit"),
        r(Tag.PIXEL, 0.8f, "pixel", "8-bit", "16-bit", "retro", "arcade", "minecraft", "terraria", "stardew"),
        r(Tag.ANIME, 0.7f, "naruto", "dragon ball", "one piece", "anime", "manga", "persona", "tales of", "final fantasy", "kingdom hearts", "jump force"),
        r(Tag.STRATEGY, 0.8f, "civilization", "strategy", "tactics", "xcom", "total war", "age of", "command", "tycoon", "city", "builder"),
        r(Tag.SURVIVAL, 0.8f, "survival", "last of us", "zombie", "dead", "apocalypse", "wasteland", "fallout", "days gone", "rust", "the forest"),
        r(Tag.FAMILY, 0.8f, "lego", "mario", "kirby", "party", "kids", "farm", "animal crossing", "sackboy", "rayman", "crash", "spyro", "sonic", "pals"),
        r(Tag.NAUTICAL, 0.8f, "pirate", "pirates", "sea of", "naval", "ocean", "fishing", "ship", "black flag"),
    )

    /** Hints in an encyclopedia sentence about the game ("... is a football simulation video game ..."). The first sentence counts double. */
    private val textRules = listOf(
        r(Tag.SPORTS, 0.8f, "football", "soccer", "basketball", "baseball", "ice hockey", "sports game", "sports video game", "tennis", "golf", "wrestling", "rugby", "cricket"),
        r(Tag.RACING, 0.9f, "racing", "driving simulation", "rally", "motorsport", "kart"),
        r(Tag.FIGHTING, 0.9f, "fighting game", "fighting video game", "versus fighting"),
        r(Tag.ACTION, 0.8f, "hack and slash", "hack-and-slash", "beat 'em up", "brawler", "character action"),
        r(Tag.SOULSLIKE, 0.9f, "soulslike", "souls-like", "fromsoftware", "dark souls", "bloodborne", "sekiro"),
        r(Tag.MYTHIC, 0.8f, "norse", "mythology", "mythological", "viking", "vikings", "valhalla", "olympus", "pantheon", "egyptian"),
        r(Tag.SHOOTER, 0.8f, "first-person shooter", "third-person shooter", "shooter", "military", "battle royale", "tactical shooter"),
        r(Tag.HORROR, 0.9f, "horror", "survival horror", "psychological horror"),
        r(Tag.SAMURAI, 0.9f, "samurai", "feudal japan", "ninja", "edo period", "sengoku", "shogun", "mongol invasion"),
        r(Tag.FANTASY, 0.7f, "fantasy", "role-playing", "dragons", "medieval", "swords", "magic", "action rpg"),
        r(Tag.DARK_FANTASY, 0.9f, "metroidvania", "soulslike", "souls-like", "dark fantasy", "insect", "bug"),
        r(Tag.CYBERPUNK, 0.9f, "cyberpunk", "dystopian", "neo-noir"),
        r(Tag.SCIFI, 0.8f, "science fiction", "science-fiction", "sci-fi", "futuristic", "alien", "mecha", "robots"),
        r(Tag.SPACE, 0.8f, "space", "galaxy", "interstellar", "planets", "spaceship"),
        r(Tag.PIXEL, 0.8f, "pixel art", "retro", "8-bit", "16-bit", "side-scrolling", "2d platform"),
        r(Tag.ANIME, 0.8f, "anime", "manga", "jrpg", "japanese role-playing"),
        r(Tag.ADVENTURE, 0.6f, "action-adventure", "open world", "open-world", "exploration", "adventure game"),
        r(Tag.STRATEGY, 0.9f, "strategy", "real-time strategy", "turn-based tactics", "city-building", "4x", "management"),
        r(Tag.SURVIVAL, 0.8f, "survival", "post-apocalyptic", "zombie", "crafting"),
        r(Tag.FAMILY, 0.7f, "family", "party game", "platform game", "platformer", "children", "kids", "cartoon"),
        r(Tag.NAUTICAL, 0.8f, "pirate", "naval", "sailing", "ocean", "fishing"),
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun hasWord(hay: String, w: String) = Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(w) + "([^\\p{L}\\p{N}]|$)").containsMatchIn(hay)

    /** "EA SPORTS™ FC 24 Deluxe Edition" -> "EA SPORTS FC 24" */
    fun cleanTitle(t: String): String = t.replace(Regex("[™®©]"), " ").replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("(?i)\\b(director'?s cut|digital deluxe|deluxe|ultimate|gold|complete|standard|premium|game of the year|goty)\\b( edition)?"), " ")
        .replace(Regex("(?i)\\bedition\\b"), " ").replace(Regex("\\s+"), " ").trim()

    /** An online entry must really be about this game: most significant words of the title are in the page title, and the text talks about a game. */
    fun plausible(game: String, pageTitle: String, extract: String): Boolean {
        val toks = game.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
        if (toks.isEmpty()) return false
        val pt = pageTitle.lowercase()
        return toks.count { pt.contains(it) } * 2 >= toks.size && extract.contains("game", ignoreCase = true)
    }

    fun fromTitle(title: String): Map<Tag, Float> {
        val t = norm(title); if (t.isBlank()) return emptyMap()
        val out = HashMap<Tag, Float>()
        for (rule in titleRules) if (rule.words.any { hasWord(t, it) }) out[rule.tag] = rule.weight
        return out
    }

    fun fromText(text: String?): Map<Tag, Float> {
        if (text.isNullOrBlank()) return emptyMap()
        val all = norm(text.take(1200)); val first = norm(text.substringBefore('.').take(400))
        val out = HashMap<Tag, Float>()
        for (rule in textRules) {
            var hits = 0f
            for (w in rule.words) { if (hasWord(first, w)) hits += 2f else if (hasWord(all, w)) hits += 1f }
            if (hits > 0f) out[rule.tag] = min(1f, rule.weight * min(1f, 0.45f + hits * 0.3f))
        }
        return out
    }

    /** Several sources of the same game: the stronger evidence of each tag wins, a tag seen by both gets a boost; weak tags are dropped, at most four stay. */
    /** Wikidata genre labels ("action role-playing game", "hack and slash", "association football video game" ...) -> game types. */
    fun fromGenreLabels(labels: List<String>): Map<Tag, Float> {
        val table = listOf(
            "soulslike" to (Tag.SOULSLIKE to 0.95f), "hack and slash" to (Tag.ACTION to 0.85f), "hack-and-slash" to (Tag.ACTION to 0.85f), "beat 'em up" to (Tag.ACTION to 0.8f), "character action" to (Tag.ACTION to 0.85f),
            "metroidvania" to (Tag.DARK_FANTASY to 0.85f), "association football" to (Tag.SPORTS to 0.9f), "football video game" to (Tag.SPORTS to 0.9f), "sports video game" to (Tag.SPORTS to 0.9f),
            "basketball" to (Tag.SPORTS to 0.9f), "baseball" to (Tag.SPORTS to 0.9f), "ice hockey" to (Tag.SPORTS to 0.9f), "golf" to (Tag.SPORTS to 0.85f), "racing" to (Tag.RACING to 0.9f),
            "fighting game" to (Tag.FIGHTING to 0.9f), "first-person shooter" to (Tag.SHOOTER to 0.85f), "third-person shooter" to (Tag.SHOOTER to 0.85f), "shooter" to (Tag.SHOOTER to 0.8f), "battle royale" to (Tag.SHOOTER to 0.8f),
            "survival horror" to (Tag.HORROR to 0.9f), "horror" to (Tag.HORROR to 0.85f), "role-playing" to (Tag.FANTASY to 0.55f), "action-adventure" to (Tag.ADVENTURE to 0.55f), "open world" to (Tag.ADVENTURE to 0.6f),
            "real-time strategy" to (Tag.STRATEGY to 0.85f), "turn-based" to (Tag.STRATEGY to 0.8f), "strategy" to (Tag.STRATEGY to 0.8f), "city-building" to (Tag.STRATEGY to 0.8f),
            "platform" to (Tag.FAMILY to 0.6f), "survival" to (Tag.SURVIVAL to 0.7f), "sandbox" to (Tag.PIXEL to 0.4f), "rhythm" to (Tag.FAMILY to 0.4f))
        val out = HashMap<Tag, Float>()
        for (l in labels) { val s = l.lowercase(); for ((k, v) in table) if (s.contains(k)) out[v.first] = maxOf(out[v.first] ?: 0f, v.second) }
        return out
    }

    fun merge(vararg sources: Map<Tag, Float>): Map<Tag, Float> {
        val acc = HashMap<Tag, Float>(); val seen = HashMap<Tag, Int>()
        for (s in sources) for ((k, v) in s) { acc[k] = maxOf(acc[k] ?: 0f, v); seen[k] = (seen[k] ?: 0) + 1 }
        for ((k, n) in seen) if (n > 1) acc[k] = min(1f, (acc[k] ?: 0f) + 0.1f)
        return acc.filter { it.value >= 0.4f }.entries.sortedByDescending { it.value }.take(4).associate { it.key to it.value }
    }

    /** "SPORTS:0.9,HORROR:0.5" <-> map (storage). */
    fun encode(m: Map<Tag, Float>) = m.entries.joinToString(",") { "${it.key.name}:${"%.2f".format(java.util.Locale.US, it.value)}" }
    fun decode(s: String?): Map<Tag, Float> = if (s.isNullOrBlank()) emptyMap() else s.split(',').mapNotNull {
        val p = it.split(':'); val t = runCatching { enumValueOf<Tag>(p[0]) }.getOrNull() ?: return@mapNotNull null
        val v = p.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null; t to v.coerceIn(0f, 1f)
    }.toMap()

    /** Compact stable key for the cache: changes when the detected type changes. */
    fun key(m: Map<Tag, Float>): Long {
        var h = 1125899906842597L
        for (c in m.entries.sortedBy { it.key.name }.joinToString("|") { "${it.key.name}${(it.value * 10).toInt()}" }) h = 31 * h + c.code
        return h
    }
}
