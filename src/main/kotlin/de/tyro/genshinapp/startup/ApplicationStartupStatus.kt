package de.tyro.genshinapp.startup

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

data class StartupTaskStatus(
    val task: String,
    val detail: String? = null,
)

/**
 * Process-local progress channel for work that runs before Spring has finished starting.
 * It has no Spring dependency so the desktop window can subscribe before the context exists.
 */
object ApplicationStartupStatus {
    private val current = AtomicReference(
        StartupTaskStatus("Preparing application…"),
    )
    private val listeners = CopyOnWriteArrayList<(StartupTaskStatus) -> Unit>()

    fun report(task: String, detail: String? = null) {
        val status = StartupTaskStatus(
            task = task.trim().takeIf(String::isNotEmpty) ?: return,
            detail = detail?.trim()?.takeIf(String::isNotEmpty),
        )
        current.set(status)
        listeners.forEach { listener -> runCatching { listener(status) } }
    }

    fun subscribe(listener: (StartupTaskStatus) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(current.get())
        return AutoCloseable { listeners.remove(listener) }
    }
}
