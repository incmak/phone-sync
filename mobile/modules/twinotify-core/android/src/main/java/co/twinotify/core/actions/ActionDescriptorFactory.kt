package co.twinotify.core.actions

import co.twinotify.core.listener.NotifActionJson
import java.util.UUID

data class ActionCandidate<T>(
    val title: String,
    val semantic: Int,
    val reply: Boolean,
    val replyLabel: String?,
    val handle: T,
)

data class PreparedActionGeneration<T>(
    val descriptors: List<NotifActionJson>,
    val generation: ActionGeneration<T>,
)

class ActionDescriptorFactory(
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun <T> prepare(
        sourceKey: String,
        packageName: String,
        sequence: Long,
        candidates: List<ActionCandidate<T>>,
    ): PreparedActionGeneration<T> {
        require(sequence >= 1)
        require(candidates.size <= 3)
        val handles = LinkedHashMap<String, T>(candidates.size)
        val descriptors = candidates.map { candidate ->
            val actionId = newId().toString()
            handles[actionId] = candidate.handle
            NotifActionJson(
                action_id = actionId,
                title = candidate.title,
                semantic = candidate.semantic,
                reply = candidate.reply,
                reply_label = candidate.replyLabel,
            )
        }
        return PreparedActionGeneration(
            descriptors = descriptors,
            generation = ActionGeneration(
                sequence = sequence,
                sourceKey = sourceKey,
                packageName = packageName,
                handlesByActionId = handles.toMap(),
            ),
        )
    }
}
