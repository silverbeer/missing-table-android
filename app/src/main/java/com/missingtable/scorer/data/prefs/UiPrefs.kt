package com.missingtable.scorer.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * UI choices that should survive an app restart (SB-642).
 *
 * Deliberately a separate DataStore from `auth`: this is preference, not
 * identity, and it must not be wiped on logout — a scorer who signs out and
 * back in at a pitch should not have to re-pick their age group.
 */
private val Context.uiDataStore by preferencesDataStore(name = "ui_prefs")

class UiPrefs(private val context: Context) {

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
}
