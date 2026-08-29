package co.twinotify.core.actions

import android.app.Notification
import java.util.concurrent.ConcurrentHashMap

data class ActionGeneration<T>(
    val sequence: Long,
    val sourceKey: String,
    val packageName: String,
    val handlesByActionId: Map<String, T>,
)

sealed interface ActionLookup {
    data object MissingGeneration : ActionLookup
    data object StaleGeneration : ActionLookup
    data object MissingAction : ActionLookup
    data class Found<T>(val generation: ActionGeneration<T>, val handle: T) : ActionLookup
}

class ActionRegistry<T> {
    private val generations = ConcurrentHashMap<String, ActionGeneration<T>>()

    fun install(canonId: String, generation: ActionGeneration<T>) {
        require(canonId.isNotEmpty())
        require(generation.sequence >= 1)
        generations[canonId] = generation.copy(handlesByActionId = generation.handlesByActionId.toMap())
    }

    fun lookup(canonId: String, sequence: Long, actionId: String): ActionLookup {
        val generation = generations[canonId] ?: return ActionLookup.MissingGeneration
        if (generation.sequence != sequence) return ActionLookup.StaleGeneration
        val handle = generation.handlesByActionId[actionId] ?: return ActionLookup.MissingAction
        return ActionLookup.Found(generation, handle)
    }

    fun purge(canonId: String) {
        generations.remove(canonId)
    }

    fun clear() {
        generations.clear()
    }
}

object ProcessNotificationActionRegistry {
    val registry = ActionRegistry<Notification.Action>()
}
