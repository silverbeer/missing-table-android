package com.missingtable.scorer.data.push

import com.missingtable.scorer.data.api.MtApi
import com.missingtable.scorer.data.api.TeamFollow
import com.missingtable.scorer.data.api.TeamFollowRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * One follow: a team, and the age group (squad) it is for. A null
 * [ageGroupId] follows every age group of the team, which is what a follow
 * meant before squads existed (SB-1608) and is still what a web follow of a
 * whole club team makes (SB-1645).
 */
data class FollowKey(val teamId: Int, val ageGroupId: Int? = null) {
    /**
     * Whether unfollowing this key removes [other]: a whole-team unfollow
     * takes every follow of the team, a squad unfollow only that squad.
     */
    fun removes(other: FollowKey): Boolean =
        other.teamId == teamId && (ageGroupId == null || ageGroupId == other.ageGroupId)
}

/**
 * Whether the user hears about [teamId] at [ageGroupId]. A whole-team follow
 * covers every age group. With no age group known (null), any follow of the
 * team counts, since there is no squad to tell them apart by.
 */
fun Set<FollowKey>.covers(teamId: Int, ageGroupId: Int?): Boolean = any {
    it.teamId == teamId && (it.ageGroupId == null || ageGroupId == null || it.ageGroupId == ageGroupId)
}

/**
 * What tapping Follow / Following for [teamId] at [ageGroupId] changes.
 *
 * Follow makes a squad follow when the age group is known, else a whole-team
 * one. Unfollow removes the squad follow; but when the user is covered by a
 * whole-team follow (or there is no age group to narrow it to), the button
 * cannot take back one squad, so it removes every follow of the team.
 */
fun Set<FollowKey>.toggleTarget(teamId: Int, ageGroupId: Int?): FollowKey =
    if (ageGroupId == null || FollowKey(teamId, null) in this) FollowKey(teamId, null)
    else FollowKey(teamId, ageGroupId)

val TeamFollow.key: FollowKey get() = FollowKey(teamId, ageGroupId)

/**
 * The teams this user follows (SB-1464), shared by every Follow button so a
 * follow on Match Detail shows on the team screen and in Profile at once.
 * Follows are per squad, [FollowKey] (SB-1645).
 *
 * [keys] is the server's set with every in-flight toggle laid over it, so a
 * refresh landing mid-toggle cannot undo what the user just tapped; each
 * override drops when its own request settles, rolled back on failure.
 * [ids] is the teams with any follow. [follows] carries the server's embed
 * (club, division, age group) for display.
 *
 * [clear] starts a new generation: a response for the previous user that
 * arrives after logout is dropped rather than restoring their follows.
 */
class TeamFollows(private val api: MtApi) {

    private val lock = Any()
    private var serverKeys: Set<FollowKey> = emptySet()
    private var serverFollows: List<TeamFollow> = emptyList()
    private val pending = mutableMapOf<FollowKey, Boolean>()
    private var generation = 0

    private val _keys = MutableStateFlow<Set<FollowKey>>(emptySet())
    val keys: StateFlow<Set<FollowKey>> = _keys.asStateFlow()

    private val _ids = MutableStateFlow<Set<Int>>(emptySet())
    val ids: StateFlow<Set<Int>> = _ids.asStateFlow()

    private val _follows = MutableStateFlow<List<TeamFollow>>(emptyList())
    val follows: StateFlow<List<TeamFollow>> = _follows.asStateFlow()

    /** False until a refresh for this session has succeeded; buttons wait on it. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val loadMutex = Mutex()

    /** Caller holds [lock]. */
    private fun removedByPending(k: FollowKey): Boolean =
        pending.any { (p, follow) -> !follow && p.removes(k) }

    /** Caller holds [lock]. */
    private fun publish() {
        val keys = serverKeys.filterNot(::removedByPending).toSet() + pending.filterValues { it }.keys
        _keys.value = keys
        _ids.value = keys.mapTo(linkedSetOf()) { it.teamId }
        _follows.value = serverFollows.filterNot { removedByPending(it.key) }
    }

    suspend fun refresh(): Boolean {
        val gen = synchronized(lock) { generation }
        val resp = runCatching { api.teamFollows() }.getOrNull() ?: return false
        synchronized(lock) {
            if (gen != generation) return false
            serverFollows = resp.follows
            serverKeys = resp.follows.mapTo(linkedSetOf()) { it.key }
            _loaded.value = true
            publish()
        }
        return true
    }

    /** [refresh] once if this session has not loaded yet; concurrent callers share it. */
    suspend fun ensureLoaded() {
        if (_loaded.value) return
        loadMutex.withLock { if (!_loaded.value) refresh() }
    }

    /**
     * Follow or unfollow [teamId] — one squad when [ageGroupId] is set, the
     * whole team when null. Returns false (that change rolled back) on failure.
     */
    suspend fun setFollowing(teamId: Int, follow: Boolean, ageGroupId: Int? = null): Boolean {
        val key = FollowKey(teamId, ageGroupId)
        val gen = synchronized(lock) {
            pending[key] = follow
            publish()
            generation
        }
        val ok = runCatching {
            if (follow) {
                api.followTeam(TeamFollowRequest(teamId, ageGroupId))
            } else {
                val resp = api.unfollowTeam(teamId, ageGroupId)
                if (!resp.isSuccessful) throw HttpException(resp)
            }
        }.isSuccess
        synchronized(lock) {
            if (gen != generation) return false
            // Only this request's override: a later tap on the same key keeps its own.
            if (pending[key] == follow) pending.remove(key)
            if (ok) {
                if (follow) {
                    serverKeys = serverKeys + key
                } else {
                    serverKeys = serverKeys.filterNot { key.removes(it) }.toSet()
                    serverFollows = serverFollows.filterNot { key.removes(it.key) }
                }
            }
            publish()
        }
        // The embed for a new follow only comes from the server.
        if (ok && follow) refresh()
        return ok
    }

    fun clear() {
        synchronized(lock) {
            generation++
            serverKeys = emptySet()
            serverFollows = emptyList()
            pending.clear()
            _loaded.value = false
            publish()
        }
    }
}
