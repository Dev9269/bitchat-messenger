package com.bitchat.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineExpiryTest {

    private val now = 1_800_000_000_000L

    @Test
    fun exactlyAtTtl_isExpired() {
        assertTrue(isMailboxExpired(now - MAILBOX_TTL_MS, now))
    }

    @Test
    fun olderThanTtl_isExpired() {
        assertTrue(isMailboxExpired(now - MAILBOX_TTL_MS - 1_000L, now))
    }

    @Test
    fun newerThanTtl_isNotExpired() {
        assertFalse(isMailboxExpired(now - MAILBOX_TTL_MS + 1_000L, now))
    }

    @Test
    fun freshEnvelope_isNotExpired() {
        assertFalse(isMailboxExpired(now, now))
    }

    @Test
    fun missingTimestamp_isNeverExpired() {
        assertFalse(isMailboxExpired(0L, now))
        assertFalse(isMailboxExpired(-5L, now))
    }

    @Test
    fun futureTimestamp_isNotExpired() {
        assertFalse(isMailboxExpired(now + 60_000L, now))
    }

    @Test
    fun customTtlIsRespected() {
        assertTrue(isMailboxExpired(now - 60_000L, now, ttlMs = 60_000L))
        assertFalse(isMailboxExpired(now - 59_000L, now, ttlMs = 60_000L))
    }
}