package com.missingtable.scorer.domain

/**
 * Player position taxonomy — Kotlin port of the web app's
 * `frontend/src/constants/positions.js` / `backend/constants/positions.py`
 * (SB-284). A player's positions are an ORDERED list of specific codes; the
 * first entry is their primary position. Each specific code belongs to exactly
 * one broad group (GK/DEF/MID/FWD), used for lineup-slot fit filtering.
 *
 * KEEP IN SYNC with the web taxonomy.
 */
object Positions {

    /** Broad groups, each mapping to its canonical specific codes. */
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "GK" to listOf("GK"),
        "DEF" to listOf("CB", "LB", "RB", "LWB", "RWB"),
        "MID" to listOf("CDM", "CM", "CAM", "LM", "RM"),
        "FWD" to listOf("LW", "RW", "ST", "CF"),
    )

    /** Ordering used for grouped display (bench / grid sections). */
    val GROUP_ORDER: List<String> = listOf("GK", "DEF", "MID", "FWD")

    val GROUP_NAMES: Map<String, String> = mapOf(
        "GK" to "Goalkeeper",
        "DEF" to "Defender",
        "MID" to "Midfielder",
        "FWD" to "Forward",
    )

    /** Old side-specific player codes that may still exist in stale data. */
    val LEGACY_MAP: Map<String, String> = mapOf(
        "LCB" to "CB",
        "RCB" to "CB",
        "LCM" to "CM",
        "RCM" to "CM",
    )

    private val CODE_TO_GROUP: Map<String, String> =
        GROUPS.entries.flatMap { (group, codes) -> codes.map { it to group } }.toMap()

    /**
     * Formation SLOT code -> group. Formation slots keep side-specific codes
     * (LCB, LS, RAM, ...); this maps every slot code used in
     * [Formations.presets] to the group used for player fit filtering.
     */
    val SLOT_TO_GROUP: Map<String, String> = mapOf(
        "GK" to "GK",
        // defense
        "CB" to "DEF", "LCB" to "DEF", "RCB" to "DEF",
        "LB" to "DEF", "RB" to "DEF", "LWB" to "DEF", "RWB" to "DEF",
        // midfield
        "CDM" to "MID", "LCDM" to "MID", "RCDM" to "MID",
        "LDM" to "MID", "RDM" to "MID",
        "CM" to "MID", "LCM" to "MID", "RCM" to "MID",
        "CAM" to "MID", "LAM" to "MID", "RAM" to "MID",
        "LM" to "MID", "RM" to "MID",
        // attack
        "LW" to "FWD", "RW" to "FWD", "ST" to "FWD",
        "LST" to "FWD", "RST" to "FWD",
        "LS" to "FWD", "RS" to "FWD", "CF" to "FWD",
    )

    /** Broad group (GK/DEF/MID/FWD) for a specific position code, or null. */
    fun groupForPosition(code: String?): String? {
        if (code == null) return null
        val canonical = LEGACY_MAP[code] ?: code
        return CODE_TO_GROUP[canonical]
    }

    /**
     * Normalize a raw positions list into canonical codes: remap legacy codes
     * and dedupe preserving order (first occurrence wins).
     */
    fun parse(positions: List<String>?): List<String> {
        if (positions.isNullOrEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (raw in positions) {
            seen.add(LEGACY_MAP[raw] ?: raw)
        }
        return seen.toList()
    }

    /** Primary position = first entry of the ordered list, or null. */
    fun primary(positions: List<String>?): String? = parse(positions).firstOrNull()

    /**
     * Fit score of a player's positions for a slot group:
     * 2 = primary is in the group, 1 = a secondary position is in the group,
     * 0 = no match / no positions set.
     */
    fun fitScore(positions: List<String>?, group: String?): Int {
        if (group == null) return 0
        val codes = parse(positions)
        if (codes.isEmpty()) return 0
        if (groupForPosition(codes.first()) == group) return 2
        if (codes.any { groupForPosition(it) == group }) return 1
        return 0
    }
}
