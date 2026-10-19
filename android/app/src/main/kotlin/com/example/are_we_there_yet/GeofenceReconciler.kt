package com.example.are_we_there_yet

class GeofenceReconciler(private val store: AlarmStateStore, private val gateway: GeofenceGateway) {
    fun add(alarm: AlarmRecord, callback: (Result<Unit>) -> Unit) {
        val sink = ResultSink(callback)
        guard(sink) {
            val active = store.snapshot().active
            if (active.none { it.id == alarm.id } && active.size >= NativeArguments.MAX_GEOFENCES) {
                sink.failure(IllegalStateException("Android supports at most 100 active geofences"))
                return@guard
            }
            gateway.add(listOf(alarm), true) { addResult ->
                guard(sink) {
                    val addFailure = addResult.exceptionOrNull()
                    if (addFailure != null) {
                        sink.failure(addFailure)
                        return@guard
                    }
                    try {
                        store.saveActive(alarm)
                        sink.success()
                    } catch (storeFailure: Throwable) {
                        removeUnmanaged(setOf(alarm.id), storeFailure, sink)
                    }
                }
            }
        }
    }

    fun remove(id: String, callback: (Result<Unit>) -> Unit) {
        val sink = ResultSink(callback)
        guard(sink) {
            store.beginRemoval(id)
            gateway.remove(setOf(id)) { removeResult ->
                guard(sink) {
                    val removeFailure = removeResult.exceptionOrNull()
                    if (removeFailure != null) {
                        sink.failure(removeFailure)
                        return@guard
                    }
                    store.finishRemoval(id)
                    sink.success()
                }
            }
        }
    }

    fun sync(arguments: SyncArguments, callback: (Result<Unit>) -> Unit) {
        val sink = ResultSink(callback)
        guard(sink) {
            val snapshot = store.snapshot()
            val desiredIds = arguments.alarms.mapTo(hashSetOf()) { it.id }
            val removals = snapshot.pendingRemovals + arguments.inactiveIds +
                snapshot.active.map { it.id }.filterNot { it in desiredIds }
            removals.forEach(store::beginRemoval)
            gateway.remove(removals) { removeResult ->
                guard(sink) {
                    val removeFailure = removeResult.exceptionOrNull()
                    if (removeFailure != null) {
                        sink.failure(removeFailure)
                        return@guard
                    }
                    removals.forEach(store::finishRemoval)
                    gateway.add(arguments.alarms, false) { addResult ->
                        guard(sink) {
                            val addFailure = addResult.exceptionOrNull()
                            if (addFailure != null) {
                                sink.failure(addFailure)
                                return@guard
                            }
                            try {
                                store.replaceActive(arguments.alarms)
                                sink.success()
                            } catch (storeFailure: Throwable) {
                                removeUnmanaged(desiredIds, storeFailure, sink)
                            }
                        }
                    }
                }
            }
        }
    }

    fun restore(callback: (Result<Unit>) -> Unit) {
        val sink = ResultSink(callback)
        guard(sink) {
            val snapshot = store.snapshot()
            gateway.remove(snapshot.pendingRemovals) { removeResult ->
                guard(sink) {
                    var earlierFailure = removeResult.exceptionOrNull()
                    if (earlierFailure == null) {
                        try {
                            snapshot.pendingRemovals.forEach(store::finishRemoval)
                        } catch (storeFailure: Throwable) {
                            earlierFailure = storeFailure
                        }
                    }
                    gateway.add(snapshot.active, false) { addResult ->
                        guard(sink) {
                            val failure = earlierFailure ?: addResult.exceptionOrNull()
                            if (failure == null) sink.success() else sink.failure(failure)
                        }
                    }
                }
            }
        }
    }

    private fun removeUnmanaged(ids: Set<String>, cause: Throwable, sink: ResultSink) {
        guard(sink) {
            gateway.remove(ids) { cleanupResult ->
                cleanupResult.exceptionOrNull()?.let(cause::addSuppressed)
                sink.failure(cause)
            }
        }
    }

    private inline fun guard(sink: ResultSink, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            sink.failure(failure)
        }
    }

    private class ResultSink(private val callback: (Result<Unit>) -> Unit) {
        private var complete = false

        @Synchronized fun success() = settle(Result.success(Unit))
        @Synchronized fun failure(error: Throwable) = settle(Result.failure(error))

        private fun settle(result: Result<Unit>) {
            if (complete) return
            complete = true
            callback(result)
        }
    }
}
