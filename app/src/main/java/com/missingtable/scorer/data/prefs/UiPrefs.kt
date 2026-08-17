package com.missingtable.scorer.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
}
