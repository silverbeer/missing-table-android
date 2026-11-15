package com.missingtable.scorer.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The account copy of the Matches filters, in the shared v1 format (SB-1717).
 *
 * Spec: missing-table `docs/03-architecture/filter-memory-format.md`. The
 * server merges `filters` key by key, so this app sends only the fields that
 * changed on this device and never echoes what it read from /me — that copy
 * may be stale, and would overwrite a newer value set on the laptop.
 *
 * Android writes four of the Matches fields: `ageGroupId`, `competition`,
 * `conferenceIds`, `programLeagueId`. Season is not persisted here (the tab
 * always opens on now), so `seasonId` is neither read nor written.
 */
object FilterMemory {

    const val VERSION = 1
    const val VIEW_MATCHES = "matches"

    /**
     * What this device holds for Matches.
     *
     * @param competition null = never chose (the dynamic default applies);
     *   otherwise `"all"`, `"qualifying"` or a match-type id as a string.
     */
    data class Local(
        val ageGroupId: Int? = null,
        val competition: String? = null,
        val conferenceIds: Set<Int> = emptySet(),
        val programLeagueId: Int? = null,
    )

    /** A field the account copy has, possibly set to null ("all"). */
    data class Present<T>(val value: T)

    /**
     * The account copy. A null field is one no client has set — keep yours;
     * [cleared] means the viewer cleared every filter (Profile default saved).
     */
    data class Remote(
        val savedAt: Long,
        val cleared: Boolean = false,
        val ageGroupId: Present<Int?>? = null,
        val competition: String? = null,
        val conferenceIds: Set<Int>? = null,
        val programLeagueId: Present<Int?>? = null,
        val panelOpen: Boolean? = null,
    )

    /** UiPrefs' match-type pair → the v1 `competition`. */
    fun competitionOf(savedType: String?, chosen: Boolean): String? = when {
        !chosen -> null
        savedType == null -> Competitions.ALL
        isCompetitionKey(savedType) -> savedType
        // A label saved before chips were keyed by id ("League"): not a v1
        // value, so it stays on this device rather than being sent.
        else -> null
    }

    /** The v1 `competition` → UiPrefs' match type (null = All). */
    fun savedTypeOf(competition: String): String? =
        competition.takeUnless { it == Competitions.ALL }

    fun isCompetitionKey(value: String): Boolean =
        value == Competitions.ALL || value == Competitions.QUALIFYING || value.toIntOrNull() != null

    /** The fields that differ between [before] and [after], as v1 JSON. */
    fun changes(before: Local, after: Local): Map<String, JsonElement> = buildMap {
        if (before.ageGroupId != after.ageGroupId) put("ageGroupId", JsonPrimitive(after.ageGroupId))
        if (before.competition != after.competition && after.competition != null) {
            put("competition", JsonPrimitive(after.competition))
        }
        if (before.conferenceIds != after.conferenceIds) {
            put("conferenceIds", JsonArray(after.conferenceIds.sorted().map { JsonPrimitive(it) }))
        }
        if (before.programLeagueId != after.programLeagueId) {
            put("programLeagueId", JsonPrimitive(after.programLeagueId))
        }
    }

    /** The PUT body: only [changes] and/or [panelOpen], stamped [savedAt]. */
    fun body(changes: Map<String, JsonElement>, panelOpen: Boolean?, savedAt: Long): JsonObject =
        JsonObject(
            buildMap {
                if (changes.isNotEmpty()) {
                    put(
                        "filters",
                        JsonObject(
                            changes + mapOf(
                                "v" to JsonPrimitive(VERSION),
                                "savedAt" to JsonPrimitive(savedAt),
                            ),
                        ),
                    )
                }
                if (panelOpen != null) put("panel_open", JsonPrimitive(panelOpen))
                put("saved_at", JsonPrimitive(savedAt))
            },
        )

    /**
     * Read one view's entry from /me's `filter_memory`, accepting the web's
     * pre-v1 names (`divisionIds`, `matchTypeId`) where the v1 field is absent.
     * Null when there is nothing usable — the normal state for most accounts.
     */
    fun parse(entry: JsonElement?): Remote? {
        val obj = entry as? JsonObject ?: return null
        val panelOpen = (obj["panel_open"] as? JsonPrimitive)?.booleanOrNull
        val filters = obj["filters"]

        if (filters is JsonNull) {
            val at = (obj["saved_at"] as? JsonPrimitive)?.longOrNull ?: return null
            return Remote(savedAt = at, cleared = true, panelOpen = panelOpen)
        }
        val f = filters as? JsonObject
        val savedAt = (f?.get("savedAt") as? JsonPrimitive)?.longOrNull
            ?: (obj["saved_at"] as? JsonPrimitive)?.longOrNull
            ?: 0L
        if (f == null) return if (panelOpen != null) Remote(savedAt = 0L, panelOpen = panelOpen) else null

        return Remote(
            savedAt = savedAt,
            ageGroupId = nullableInt(f, "ageGroupId"),
            competition = competition(f),
            conferenceIds = intSet(f["conferenceIds"]) ?: intSet(f["divisionIds"]),
            programLeagueId = nullableInt(f, "programLeagueId"),
            panelOpen = panelOpen,
        )
    }

    /** The account copy is newer than this device's. Ties stay local. */
    fun shouldAdopt(localSavedAt: Long, remote: Remote): Boolean = remote.savedAt > localSavedAt

    /** [local] with every field [remote] has applied over it. */
    fun merge(local: Local, remote: Remote): Local {
        if (remote.cleared) return Local()
        return Local(
            ageGroupId = if (remote.ageGroupId != null) remote.ageGroupId.value else local.ageGroupId,
            competition = remote.competition ?: local.competition,
            conferenceIds = remote.conferenceIds ?: local.conferenceIds,
            programLeagueId = if (remote.programLeagueId != null) remote.programLeagueId.value else local.programLeagueId,
        )
    }

    private fun nullableInt(f: JsonObject, key: String): Present<Int?>? {
        if (!f.containsKey(key)) return null
        return when (val v = f[key]) {
            is JsonNull -> Present(null)
            is JsonPrimitive -> v.intOrNull?.let { Present(it) }
            else -> null
        }
    }

    private fun intSet(e: JsonElement?): Set<Int>? =
        (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet()

    private fun competition(f: JsonObject): String? {
        (f["competition"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { isCompetitionKey(it) }
            ?.let { return it }
        // Pre-v1 web: matchTypeId is an id, "qualifying", or null for All.
        if (!f.containsKey("matchTypeId")) return null
        return when (val legacy = f["matchTypeId"]) {
            is JsonNull -> Competitions.ALL
            is JsonPrimitive -> legacy.contentOrNull?.takeIf { isCompetitionKey(it) }
            else -> null
        }
    }
}
