package com.example.are_we_there_yet

/** Delivers trip changes from [TripService] to the Flutter event channel, on the main thread. */
object TripEvents {
    @Volatile var listener: ((TripSnapshot) -> Unit)? = null
    @Volatile var last: TripSnapshot = TripSnapshot.IDLE
        private set

    fun publish(snapshot: TripSnapshot) {
        last = snapshot
        listener?.invoke(snapshot)
    }
}
