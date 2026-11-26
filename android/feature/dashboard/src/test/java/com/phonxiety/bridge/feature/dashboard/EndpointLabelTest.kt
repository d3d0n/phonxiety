package com.phonxiety.bridge.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The dashboard's short endpoint form: enough to tell two keys apart, never a whole row of them. */
class EndpointLabelTest {
    @Test
    fun aKeyKeepsItsFirstEightAndLastFourCharacters() {
        val id = "864b90b7d1f2a5c6e93b47f80a1c2d3e4f5061728394a5b6c7d8e9f0a1b2cc62"
        assertEquals("864b90b7…cc62", shortEndpoint(id))
    }

    @Test
    fun somethingAlreadyShortIsLeftWhole() {
        assertEquals("lane-socket", shortEndpoint("lane-socket"))
        assertEquals("", shortEndpoint(""))
    }
}
