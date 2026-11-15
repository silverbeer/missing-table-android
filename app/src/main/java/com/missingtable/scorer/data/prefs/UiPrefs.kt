package com.missingtable.scorer.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.missingtable.scorer.domain.FilterMemory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * UI choices that should survive an app restart (SB-642).
 *
 * Deliberately a separate DataStore from `auth`: this is preference, not
 * identity, and it must not be wiped on logout — a scorer who signs out and
 * back in at a pitch should not have to re-pick their age group.
 */
private val Context.uiDataStore by preferencesDataStore(name = "ui_prefs")

class UiPrefs(private val context: Context) : FilterPrefs {

    private val matchesAgeGroupKey = intPreferencesKey("matches_age_group_id")

    /** Age-group filter on the Matches tab; null means "All". */
    val matchesAgeGroup: Flow<Int?> =
        context.uiDataStore.data.map { it[matchesAgeGroupKey] }

    suspend fun setMatchesAgeGroup(ageGroupId: Int?) {
        context.uiDataStore.edit { prefs ->
            if (ageGroupId == null) prefs.remove(matchesAgeGroupKey)
            else prefs[matchesAgeGroupKey] = ageGroupId
        }
    }

    private val matchesTypeKey = stringPreferencesKey("matches_match_type")
    private val matchesTypeChosenKey = booleanPreferencesKey("matches_match_type_chosen")

    /** Match-type filter on the Matches tab; null means "All" (SB-681). */
    val matchesType: Flow<String?> = context.uiDataStore.data.map { it[matchesTypeKey] }

    /**
     * Whether the user has ever picked a type. Distinguishes "chose All" from
     * "never chose", which the dynamic default needs to tell apart.
     */
    val matchesTypeChosen: Flow<Boolean> =
        context.uiDataStore.data.map { it[matchesTypeChosenKey] ?: false }

    suspend fun setMatchesType(typeName: String?) {
        context.uiDataStore.edit { prefs ->
            if (typeName == null) prefs.remove(matchesTypeKey) else prefs[matchesTypeKey] = typeName
            prefs[matchesTypeChosenKey] = true
        }
    }

    // Leaders keeps its own keys: it is a different question ("who is top
    // scorer in League?") from the one Matches asks, and sharing a filter
    // between the two tabs would move one when the viewer changed the other.
    private val leadersAgeGroupKey = intPreferencesKey("leaders_age_group_id")
    private val leadersMatchTypeKey = intPreferencesKey("leaders_match_type_id")

    /** Age-group filter on Leaders; null means every age group. */
    val leadersAgeGroup: Flow<Int?> = context.uiDataStore.data.map { it[leadersAgeGroupKey] }

    suspend fun setLeadersAgeGroup(ageGroupId: Int?) {
        context.uiDataStore.edit { prefs ->
            if (ageGroupId == null) prefs.remove(leadersAgeGroupKey)
            else prefs[leadersAgeGroupKey] = ageGroupId
        }
    }

    /** Competition filter on Leaders; null means "never chose", which resolves to League. */
    val leadersMatchType: Flow<Int?> = context.uiDataStore.data.map { it[leadersMatchTypeKey] }

    suspend fun setLeadersMatchType(matchTypeId: Int?) {
        context.uiDataStore.edit { prefs ->
            if (matchTypeId == null) prefs.remove(leadersMatchTypeKey)
            else prefs[leadersMatchTypeKey] = matchTypeId
        }
    }

    private val matchesConferencesKey = stringPreferencesKey("matches_conference_ids")

    /**
     * Conference filter on the Matches tab — multi-select, so a set (SB-1110).
     * Empty means "all conferences", which is the absence of a filter rather
     * than a value of its own.
     *
     * Stored as a comma-joined string: DataStore does have a string-set key,
     * but it gives no ordering guarantee and these are ints, so parsing one
     * field beats hiding the conversion behind a type that fits worse.
     */
    val matchesConferences: Flow<Set<Int>> = context.uiDataStore.data.map { prefs ->
        prefs[matchesConferencesKey]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()
    }

    suspend fun setMatchesConferences(ids: Set<Int>) {
        context.uiDataStore.edit { prefs ->
            if (ids.isEmpty()) prefs.remove(matchesConferencesKey)
            else prefs[matchesConferencesKey] = ids.sorted().joinToString(",")
        }
    }

    private val matchesProgramKey = intPreferencesKey("matches_program_league_id")

    /** Program filter on the Matches tab — a top-level league id; null means both (SB-1680). */
    val matchesProgram: Flow<Int?> = context.uiDataStore.data.map { it[matchesProgramKey] }

    suspend fun setMatchesProgram(leagueId: Int?) {
        context.uiDataStore.edit { prefs ->
            if (leagueId == null) prefs.remove(matchesProgramKey) else prefs[matchesProgramKey] = leagueId
        }
    }

    private val matchesFiltersOpenKey = booleanPreferencesKey("matches_filters_open")

    /** Whether the Matches filter panel is open; open until first closed (SB-1680). */
    val matchesFiltersOpen: Flow<Boolean> =
        context.uiDataStore.data.map { it[matchesFiltersOpenKey] ?: true }

    suspend fun setMatchesFiltersOpen(open: Boolean) {
        context.uiDataStore.edit { it[matchesFiltersOpenKey] = open }
    }

    // === Account sync (SB-1717) ===

    private val matchesFiltersSavedAtKey = longPreferencesKey("matches_filters_saved_at")

    /** When the Matches filters last changed here or were adopted from the account; 0 = never. */
    override suspend fun matchesSavedAt(): Long =
        context.uiDataStore.data.map { it[matchesFiltersSavedAtKey] ?: 0L }.first()

    override suspend fun setMatchesSavedAt(at: Long) {
        context.uiDataStore.edit { it[matchesFiltersSavedAtKey] = at }
    }

    /** The four Matches filters the account copy shares, in its terms. */
    override val matchesLocal: Flow<FilterMemory.Local> = context.uiDataStore.data
        .map { prefs ->
            FilterMemory.Local(
                ageGroupId = prefs[matchesAgeGroupKey],
                competition = FilterMemory.competitionOf(
                    prefs[matchesTypeKey],
                    prefs[matchesTypeChosenKey] ?: false,
                ),
                conferenceIds = prefs[matchesConferencesKey]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
                    .orEmpty(),
                programLeagueId = prefs[matchesProgramKey],
            )
        }
        .distinctUntilChanged()

    /**
     * Adopt the account copy in ONE edit, so the screen sees one change and
     * the sync sees one state — never a half-applied mix it would send back.
     */
    override suspend fun applyMatches(local: FilterMemory.Local, savedAt: Long) {
        context.uiDataStore.edit { prefs ->
            if (local.ageGroupId == null) prefs.remove(matchesAgeGroupKey)
            else prefs[matchesAgeGroupKey] = local.ageGroupId
            if (local.competition == null) {
                prefs.remove(matchesTypeKey)
                prefs.remove(matchesTypeChosenKey)
            } else {
                val type = FilterMemory.savedTypeOf(local.competition)
                if (type == null) prefs.remove(matchesTypeKey) else prefs[matchesTypeKey] = type
                prefs[matchesTypeChosenKey] = true
            }
            if (local.conferenceIds.isEmpty()) prefs.remove(matchesConferencesKey)
            else prefs[matchesConferencesKey] = local.conferenceIds.sorted().joinToString(",")
            if (local.programLeagueId == null) prefs.remove(matchesProgramKey)
            else prefs[matchesProgramKey] = local.programLeagueId
            prefs[matchesFiltersSavedAtKey] = savedAt
        }
    }

    /** The panel choice made on this device; null until it is first toggled. */
    override val matchesPanelChoice: Flow<Boolean?> =
        context.uiDataStore.data.map { it[matchesFiltersOpenKey] }.distinctUntilChanged()

    override suspend fun adoptMatchesPanel(open: Boolean) = setMatchesFiltersOpen(open)
}

/** What [com.missingtable.scorer.data.sync.FilterMemorySync] needs from UiPrefs — a fake in tests. */
interface FilterPrefs {
    val matchesLocal: Flow<FilterMemory.Local>
    val matchesPanelChoice: Flow<Boolean?>
    suspend fun matchesSavedAt(): Long
    suspend fun setMatchesSavedAt(at: Long)
    suspend fun applyMatches(local: FilterMemory.Local, savedAt: Long)
    suspend fun adoptMatchesPanel(open: Boolean)
}
