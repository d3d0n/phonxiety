package com.phonxiety.bridge.net

import com.phonxiety.bridge.model.LinkDownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A transport's own close text as the engine sees it: a text that spells a
 * known reason keeps that reason, an empty text is [LinkDownReason.CLOSED] as
 * before, and anything else becomes [LinkDownReason.OTHER] with the original
 * words kept as detail.
 */
class NativeCloseTest {
    @Test
    fun aKnownSpellingKeepsItsReason() {
        for (reason in LinkDownReason.entries.filter { it != LinkDownReason.OTHER }) {
            val (typed, detail) = fromNativeClose(reason.wire)
            assertEquals(reason, typed)
            assertNull("no detail for a named reason", detail)
        }
    }

    @Test
    fun anEmptyTextIsClosed() {
        val (typed, detail) = fromNativeClose("")
        assertEquals(LinkDownReason.CLOSED, typed)
        assertNull(detail)
    }

    @Test
    fun anUnknownTextKeepsItsWordsAsDetail() {
        val (typed, detail) = fromNativeClose("timed out by peer")
        assertEquals(LinkDownReason.OTHER, typed)
        assertEquals("timed out by peer", detail)
    }

    /**
     * An unnamed close is still known by its own words, as it was before the
     * reasons were typed; a named reason is its wire spelling whatever detail
     * travels with it.
     */
    @Test
    fun theLegacyTextOfAnUnnamedCloseIsItsOwnWords() {
        assertEquals("timed out by peer", LinkDownReason.OTHER.text("timed out by peer"))
        assertEquals("other", LinkDownReason.OTHER.text(null))
        assertEquals("closed", LinkDownReason.CLOSED.text("ignored"))
    }
}
