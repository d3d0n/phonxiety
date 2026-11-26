package com.phonxiety.bridge.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Value equality is what lets a view model compare the text it publishes without a Context. */
class UiTextTest {
    @Test
    fun resComparesByIdAndArgs() {
        assertEquals(UiText.Res(1, listOf("a", 2)), UiText.Res(1, listOf("a", 2)))
        assertNotEquals(UiText.Res(1, listOf("a")), UiText.Res(1, listOf("b")))
        assertNotEquals(UiText.Res(1, listOf("a", 2)), UiText.Res(1, listOf(2, "a")))
        assertNotEquals(UiText.Res(1), UiText.Res(2))
    }

    @Test
    fun pluralComparesByQuantityAndArgs() {
        assertEquals(UiText.Plural(1, 2, listOf(2)), UiText.Plural(1, 2, listOf(2)))
        assertNotEquals(UiText.Plural(1, 2), UiText.Plural(1, 5))
        assertNotEquals(UiText.Plural(1, 2, listOf("a")), UiText.Plural(1, 2, listOf("b")))
    }

    @Test
    fun literalComparesByText() {
        assertEquals(UiText.Literal("daemon timed out"), UiText.Literal("daemon timed out"))
        assertNotEquals(UiText.Literal("a"), UiText.Literal("b"))
    }

    @Test
    fun equalityKeepsExtraArgsOrder() {
        val first = UiText.Res(7, listOf("name", 3, true))
        val second = UiText.Res(7, listOf("name", 3, true))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
