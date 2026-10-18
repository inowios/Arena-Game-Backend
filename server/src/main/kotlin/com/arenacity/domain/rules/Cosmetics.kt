package com.arenacity.domain.rules

/**
 * Cosmetics (spec §6: looks, never power). DECOR items are looks of the DECOR building; KIT, FRAME and CELEBRATION are
 * equipped on the club (kit in matches, frame around the emblem everywhere, celebration after goals). The gold shop for
 * looks is gone; they come from the season pass, achievements, the roads, events and mail.
 */
enum class CosmeticKind { DECOR, KIT, FRAME, CELEBRATION }

/**
 * Where an item comes from: free default, a season-pass level, an achievement tier or the trophy road. SHOP marks the
 * looks the removed gold shop sold (and that clubs may still own); they are now reward items, e.g. on the roads.
 */
enum class CosmeticSource { DEFAULT, SHOP, PASS, ACHIEVEMENT, ROAD }

data class Cosmetic(
    val id: String,
    val kind: CosmeticKind,
    val source: CosmeticSource,
    val rarity: Rarity = Rarity.COMMON,
    /** For PASS: "premium:10" / "free:30"; for ACHIEVEMENT: "<achievement>:<tier>". */
    val unlock: String? = null,
) {
    enum class Rarity { COMMON, RARE, EPIC, LEGENDARY }
}

object Cosmetics {
    /** Gold granted instead of a pass/achievement item the club already owns (seasons repeat the pass track). */
    const val DUPLICATE_GOLD = 25

    val ALL: List<Cosmetic> = listOf(
        // decor looks: the town decorations went with the city builder (17 October 2026); nothing hands them out any more
        Cosmetic("decor_tree", CosmeticKind.DECOR, CosmeticSource.DEFAULT),
        Cosmetic("decor_flowerbed", CosmeticKind.DECOR, CosmeticSource.SHOP),
        Cosmetic("decor_bench", CosmeticKind.DECOR, CosmeticSource.SHOP),
        Cosmetic("decor_hedge", CosmeticKind.DECOR, CosmeticSource.SHOP),
        Cosmetic("decor_lamp", CosmeticKind.DECOR, CosmeticSource.SHOP),
        Cosmetic("decor_flagpole", CosmeticKind.DECOR, CosmeticSource.SHOP, Cosmetic.Rarity.RARE),
        Cosmetic("decor_fountain", CosmeticKind.DECOR, CosmeticSource.SHOP, Cosmetic.Rarity.EPIC),
        Cosmetic("decor_banner", CosmeticKind.DECOR, CosmeticSource.SHOP, rarity = Cosmetic.Rarity.RARE),
        Cosmetic("decor_statue", CosmeticKind.DECOR, CosmeticSource.SHOP, rarity = Cosmetic.Rarity.EPIC),
        Cosmetic("decor_trophy", CosmeticKind.DECOR, CosmeticSource.SHOP, rarity = Cosmetic.Rarity.LEGENDARY),
        // season themes: one decoration per time of year, on the pass while that theme runs (see SeasonThemes)
        Cosmetic("decor_pumpkins", CosmeticKind.DECOR, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "theme:autumn"),
        Cosmetic("decor_snow_tree", CosmeticKind.DECOR, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "theme:winter"),
        Cosmetic("decor_blossom", CosmeticKind.DECOR, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "theme:spring"),
        Cosmetic("decor_beach", CosmeticKind.DECOR, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "theme:summer"),
        // the trophy road: the city of the future, one look per arena climbed
        Cosmetic("decor_led_pole", CosmeticKind.DECOR, CosmeticSource.SHOP, rarity = Cosmetic.Rarity.RARE),
        Cosmetic("decor_holo_ball", CosmeticKind.DECOR, CosmeticSource.SHOP, rarity = Cosmetic.Rarity.EPIC),
        Cosmetic("decor_drone_pad", CosmeticKind.DECOR, CosmeticSource.SHOP, rarity = Cosmetic.Rarity.LEGENDARY),
        // kits
        Cosmetic("kit_plain", CosmeticKind.KIT, CosmeticSource.DEFAULT),
        Cosmetic("kit_stripes", CosmeticKind.KIT, CosmeticSource.SHOP),
        Cosmetic("kit_hoops", CosmeticKind.KIT, CosmeticSource.SHOP),
        Cosmetic("kit_halves", CosmeticKind.KIT, CosmeticSource.ROAD, rarity = Cosmetic.Rarity.RARE, unlock = "trophy:2200"),
        Cosmetic("kit_sash", CosmeticKind.KIT, CosmeticSource.SHOP, Cosmetic.Rarity.RARE),
        Cosmetic("kit_gold_trim", CosmeticKind.KIT, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "premium:15"),
        Cosmetic("kit_pinstripe", CosmeticKind.KIT, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "premium:20"),
        Cosmetic("kit_retro", CosmeticKind.KIT, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.RARE, unlock = "promoted:1"),
        // emblem frames (no frame = nothing equipped)
        Cosmetic("frame_silver", CosmeticKind.FRAME, CosmeticSource.SHOP),
        Cosmetic("frame_stars", CosmeticKind.FRAME, CosmeticSource.SHOP, Cosmetic.Rarity.RARE),
        Cosmetic("frame_flame", CosmeticKind.FRAME, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "free:30"),
        Cosmetic("frame_gold", CosmeticKind.FRAME, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "premium:10"),
        Cosmetic("frame_laurel", CosmeticKind.FRAME, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.LEGENDARY, unlock = "champion:1"),
        Cosmetic("frame_legend", CosmeticKind.FRAME, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.LEGENDARY, unlock = "climber:6"),
        Cosmetic("frame_neon", CosmeticKind.FRAME, CosmeticSource.ROAD, rarity = Cosmetic.Rarity.RARE, unlock = "trophy:1000"),
        Cosmetic("frame_aurora", CosmeticKind.FRAME, CosmeticSource.ROAD, rarity = Cosmetic.Rarity.LEGENDARY, unlock = "trophy:8000"),
        // goal celebrations
        Cosmetic("cel_fist", CosmeticKind.CELEBRATION, CosmeticSource.DEFAULT),
        Cosmetic("cel_slide", CosmeticKind.CELEBRATION, CosmeticSource.SHOP),
        Cosmetic("cel_aeroplane", CosmeticKind.CELEBRATION, CosmeticSource.ROAD, rarity = Cosmetic.Rarity.RARE, unlock = "trophy:5500"),
        Cosmetic("cel_backflip", CosmeticKind.CELEBRATION, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.EPIC, unlock = "champion:2"),
        Cosmetic("cel_dance", CosmeticKind.CELEBRATION, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "premium:25"),
        Cosmetic("cel_bow", CosmeticKind.CELEBRATION, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.RARE, unlock = "social:2"),
    )

    private val byId = ALL.associateBy { it.id }
    fun find(id: String): Cosmetic? = byId[id]
    fun defaults(): List<Cosmetic> = ALL.filter { it.source == CosmeticSource.DEFAULT }
    fun isDefault(id: String) = byId[id]?.source == CosmeticSource.DEFAULT

    val DEFAULT_DECOR = "decor_tree"

    /** Bots wear a kit picked from the free/shop kits by their seed, so league tables and matches look varied. */
    private val BOT_KITS = listOf("kit_plain", "kit_stripes", "kit_hoops", "kit_halves", "kit_sash")
    fun botKit(seed: Long): String = BOT_KITS[Math.floorMod(seed, BOT_KITS.size.toLong()).toInt()]
}
