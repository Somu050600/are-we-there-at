package com.example.are_we_there_yet

import android.content.Intent

data class IncomingShare(val text: String, val sharedLink: String)

class SharedLocationInbox {
    private val queue = ArrayDeque<IncomingShare>()
    @Synchronized fun add(share: IncomingShare) = queue.addLast(share)
    @Synchronized fun take(): IncomingShare? = queue.removeFirstOrNull()

    companion object {
        fun fromIntent(intent: Intent?): IncomingShare? {
            intent ?: return null
            val raw = when (intent.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.data?.toString()
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return IncomingShare(raw, raw)
        }
    }
}
