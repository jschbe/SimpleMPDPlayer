package com.github.jschbe.simplempdplayer.audio

import com.github.jschbe.simplempdplayer.mpd.ConnectionStatus
import com.github.jschbe.simplempdplayer.mpd.MpdRepository
import com.github.jschbe.simplempdplayer.mpd.PlayerAction
import kotlinx.coroutines.Job

/** Main-thread asynchronous transport, bound to one manually enabled receiver generation. */
class MpdMediaTransport(
    private val repository: MpdRepository,
    private val stream: LocalStreamController,
    private val request: StreamRequest,
) {
    private fun authorized() = stream.request.value?.generation == request.generation &&
        stream.request.value?.server == request.server && repository.activeServer == request.server &&
        repository.state.value.status == ConnectionStatus.CONNECTED && !repository.state.value.waitingForNetwork

    val available: Boolean get() = authorized() && !repository.state.value.commandPending

    /** Uses the same serialized operations and confirmed readback as the app's buttons. */
    fun submit(action: PlayerAction): Job? {
        if (action !in setOf(PlayerAction.Play, PlayerAction.Pause, PlayerAction.PlayPause,
                PlayerAction.Next, PlayerAction.Previous)) return null
        return repository.perform(action, ::authorized)
    }
}
