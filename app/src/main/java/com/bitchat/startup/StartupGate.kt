package com.bitchat.startup

/**
 * The single reason, if any, that Ghostwire cannot run against its own local state.
 *
 * Two things put the app here: an encrypted database that will not open, and an account
 * whose stored keys will not decrypt. In both cases the data is intact but unreadable, and
 * the old code "recovered" by discarding it. Both now refuse, record why, and let the UI
 * say so rather than quietly start over with an empty history and a new identity.
 */
object StartupGate {

    var blockedReason: String? = null
        private set

    /** Records the first cause; later failures do not overwrite it. */
    fun block(reason: String) {
        if (blockedReason == null) blockedReason = reason
    }

    fun clear() {
        blockedReason = null
    }
}
