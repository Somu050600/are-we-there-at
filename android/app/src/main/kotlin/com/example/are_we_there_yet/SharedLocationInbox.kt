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
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                Intent.ACTION_VIEW -> intent.data?.toString()
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // Some apps put the place name in the subject and only the link in the text.
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
                ?.takeIf { intent.action == Intent.ACTION_SEND && it.isNotEmpty() && !raw.contains(it) }
            return IncomingShare(if (subject == null) raw else "$subject\n$raw", raw)
        }
    }
}
