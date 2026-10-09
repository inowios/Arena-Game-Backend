package com.matchdayclub.domain.rules

/**
 * Cosmetics (spec §6: sold for gold, never power). DECOR items are looks of the DECOR building; KIT, FRAME and
 * CELEBRATION are equipped on the club (kit in matches, frame around the emblem everywhere, celebration after goals).
 */
enum class CosmeticKind { DECOR, KIT, FRAME, CELEBRATION }

/** Where an item comes from: free default, the gold shop, a season-pass level, or an achievement tier. */
enum class CosmeticSource { DEFAULT, SHOP, PASS, ACHIEVEMENT }

data class Cosmetic(
    val id: String,
    val kind: CosmeticKind,
    val source: CosmeticSource,
    val gold: Int = 0,
    val rarity: Rarity = Rarity.COMMON,
    /** For PASS: "premium:10" / "free:30"; for ACHIEVEMENT: "<achievement>:<tier>". */
    val unlock: String? = null,
) {
    enum class Rarity { COMMON, RARE, EPIC, LEGENDARY }
    val purchasable get() = source == CosmeticSource.SHOP
}

object Cosmetics {
    /** Gold granted instead of a pass/achievement item the club already owns (seasons repeat the pass track). */
    const val DUPLICATE_GOLD = 25

    val ALL: List<Cosmetic> = listOf(
        // decor looks
        Cosmetic("decor_tree", CosmeticKind.DECOR, CosmeticSource.DEFAULT),
        Cosmetic("decor_flowerbed", CosmeticKind.DECOR, CosmeticSource.SHOP, 25),
        Cosmetic("decor_bench", CosmeticKind.DECOR, CosmeticSource.SHOP, 25),
        Cosmetic("decor_hedge", CosmeticKind.DECOR, CosmeticSource.SHOP, 30),
        Cosmetic("decor_lamp", CosmeticKind.DECOR, CosmeticSource.SHOP, 35),
        Cosmetic("decor_flagpole", CosmeticKind.DECOR, CosmeticSource.SHOP, 50, Cosmetic.Rarity.RARE),
        Cosmetic("decor_fountain", CosmeticKind.DECOR, CosmeticSource.SHOP, 120, Cosmetic.Rarity.EPIC),
        Cosmetic("decor_banner", CosmeticKind.DECOR, CosmeticSource.PASS, rarity = Cosmetic.Rarity.RARE, unlock = "free:30"),
        Cosmetic("decor_statue", CosmeticKind.DECOR, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "premium:15"),
        Cosmetic("decor_trophy", CosmeticKind.DECOR, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.LEGENDARY, unlock = "champion:2"),
        // kits
        Cosmetic("kit_plain", CosmeticKind.KIT, CosmeticSource.DEFAULT),
        Cosmetic("kit_stripes", CosmeticKind.KIT, CosmeticSource.SHOP, 60),
        Cosmetic("kit_hoops", CosmeticKind.KIT, CosmeticSource.SHOP, 60),
        Cosmetic("kit_halves", CosmeticKind.KIT, CosmeticSource.SHOP, 80, Cosmetic.Rarity.RARE),
        Cosmetic("kit_sash", CosmeticKind.KIT, CosmeticSource.SHOP, 80, Cosmetic.Rarity.RARE),
        Cosmetic("kit_gold_trim", CosmeticKind.KIT, CosmeticSource.SHOP, 150, Cosmetic.Rarity.EPIC),
        Cosmetic("kit_pinstripe", CosmeticKind.KIT, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "premium:20"),
        Cosmetic("kit_retro", CosmeticKind.KIT, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.RARE, unlock = "promoted:1"),
        // emblem frames (no frame = nothing equipped)
        Cosmetic("frame_silver", CosmeticKind.FRAME, CosmeticSource.SHOP, 40),
        Cosmetic("frame_stars", CosmeticKind.FRAME, CosmeticSource.SHOP, 90, Cosmetic.Rarity.RARE),
        Cosmetic("frame_flame", CosmeticKind.FRAME, CosmeticSource.SHOP, 150, Cosmetic.Rarity.EPIC),
        Cosmetic("frame_gold", CosmeticKind.FRAME, CosmeticSource.PASS, rarity = Cosmetic.Rarity.EPIC, unlock = "premium:10"),
        Cosmetic("frame_laurel", CosmeticKind.FRAME, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.LEGENDARY, unlock = "champion:1"),
        Cosmetic("frame_legend", CosmeticKind.FRAME, CosmeticSource.ACHIEVEMENT, rarity = Cosmetic.Rarity.LEGENDARY, unlock = "climber:6"),
        // goal celebrations
        Cosmetic("cel_fist", CosmeticKind.CELEBRATION, CosmeticSource.DEFAULT),
        Cosmetic("cel_slide", CosmeticKind.CELEBRATION, CosmeticSource.SHOP, 50),
        Cosmetic("cel_aeroplane", CosmeticKind.CELEBRATION, CosmeticSource.SHOP, 70, Cosmetic.Rarity.RARE),
        Cosmetic("cel_backflip", CosmeticKind.CELEBRATION, CosmeticSource.SHOP, 120, Cosmetic.Rarity.EPIC),
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
