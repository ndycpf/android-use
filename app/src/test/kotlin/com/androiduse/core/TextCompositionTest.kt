package com.androiduse.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCompositionTest {

    @Test
    fun `replaces hint text instead of appending to placeholder`() {
        val result = TextComposition.compose(
            existingText = "メールを検索",
            isShowingHintText = true,
            newText = "invoice",
            clearFirst = false,
        )
        assertEquals("invoice", result)
    }

    @Test
    fun `appends to real existing text when clearFirst is false`() {
        val result = TextComposition.compose(
            existingText = "hello ",
            isShowingHintText = false,
            newText = "world",
            clearFirst = false,
        )
        assertEquals("hello world", result)
    }

    @Test
    fun `replaces real existing text when clearFirst is true`() {
        val result = TextComposition.compose(
            existingText = "old query",
            isShowingHintText = false,
            newText = "new query",
            clearFirst = true,
        )
        assertEquals("new query", result)
    }

    @Test
    fun `handles null or empty existing text cleanly`() {
        assertEquals("abc", TextComposition.compose(null, false, "abc", false))
        assertEquals("abc", TextComposition.compose("", false, "abc", false))
    }
}
