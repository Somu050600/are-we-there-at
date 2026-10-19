package com.example.are_we_there_yet

class SerialNativeOperations {
    private data class Entry(
        val operation: ((() -> Unit) -> Unit),
        val onCancelled: () -> Unit,
        val onFailure: (Throwable) -> Unit,
    )
    private val waiting = ArrayDeque<Entry>()
    private var active: Entry? = null
    private var cancelled = false

    @Synchronized fun submit(
        onCancelled: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        operation: ((() -> Unit) -> Unit),
    ) {
        if (cancelled) return onCancelled()
        waiting.addLast(Entry(operation, onCancelled, onFailure))
        startNext()
    }

    @Synchronized fun cancelAll() {
        if (cancelled) return
        cancelled = true
        val entries = listOfNotNull(active) + waiting.toList()
        active = null
        waiting.clear()
        entries.forEach { runCatching(it.onCancelled) }
    }

    @Synchronized fun isIdle() = active == null && waiting.isEmpty()

    @Synchronized private fun startNext() {
        if (cancelled || active != null) return
        val next = waiting.removeFirstOrNull() ?: return
        active = next
        var finished = false
        try {
            next.operation {
                synchronized(this) {
                    if (finished) return@synchronized
                    finished = true
                    if (active === next) active = null
                    startNext()
                }
            }
        } catch (failure: Throwable) {
            if (!finished) {
                finished = true
                if (active === next) active = null
                runCatching { next.onFailure(failure) }
                startNext()
            }
        }
    }
}

object NativeOperationQueue {
    val shared = SerialNativeOperations()
}
