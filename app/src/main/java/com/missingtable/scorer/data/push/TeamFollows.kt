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
 * The teams this user follows (SB-1464), shared by every Follow button so a
 * follow on Match Detail shows on the team screen and in Profile at once.
 *
 * [ids] is the server's set with every in-flight toggle laid over it, so a
 * refresh landing mid-toggle cannot undo what the user just tapped; each
 * override drops when its own request settles, rolled back on failure.
 * [follows] carries the server's embed (club, division) for display.
 *
 * [clear] starts a new generation: a response for the previous user that
 * arrives after logout is dropped rather than restoring their follows.
 */
class TeamFollows(private val api: MtApi) {

    private val lock = Any()
    private var serverIds: Set<Int> = emptySet()
    private var serverFollows: List<TeamFollow> = emptyList()
    private val pending = mutableMapOf<Int, Boolean>()
    private var generation = 0

    private val _ids = MutableStateFlow<Set<Int>>(emptySet())
    val ids: StateFlow<Set<Int>> = _ids.asStateFlow()

    private val _follows = MutableStateFlow<List<TeamFollow>>(emptyList())
    val follows: StateFlow<List<TeamFollow>> = _follows.asStateFlow()

    /** False until a refresh for this session has succeeded; buttons wait on it. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val loadMutex = Mutex()

    /** Caller holds [lock]. */
    private fun publish() {
        _ids.value = (serverIds + pending.filterValues { it }.keys) - pending.filterValues { !it }.keys
        _follows.value = serverFollows.filter { pending[it.teamId] != false }
    }

    suspend fun refresh(): Boolean {
        val gen = synchronized(lock) { generation }
        val resp = runCatching { api.teamFollows() }.getOrNull() ?: return false
        synchronized(lock) {
            if (gen != generation) return false
            serverFollows = resp.follows
            serverIds = resp.follows.map { it.teamId }.toSet()
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

    /** Follow or unfollow; returns false (that team rolled back) on failure. */
    suspend fun setFollowing(teamId: Int, follow: Boolean): Boolean {
        val gen = synchronized(lock) {
            pending[teamId] = follow
            publish()
            generation
        }
        val ok = runCatching {
            if (follow) {
                api.followTeam(TeamFollowRequest(teamId))
            } else {
                val resp = api.unfollowTeam(teamId)
                if (!resp.isSuccessful) throw HttpException(resp)
            }
        }.isSuccess
        synchronized(lock) {
            if (gen != generation) return false
            // Only this request's override: a later tap on the same team keeps its own.
            if (pending[teamId] == follow) pending.remove(teamId)
            if (ok) {
                serverIds = if (follow) serverIds + teamId else serverIds - teamId
                if (!follow) serverFollows = serverFollows.filterNot { it.teamId == teamId }
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
            serverIds = emptySet()
            serverFollows = emptyList()
            pending.clear()
            _loaded.value = false
            publish()
        }
    }
}
