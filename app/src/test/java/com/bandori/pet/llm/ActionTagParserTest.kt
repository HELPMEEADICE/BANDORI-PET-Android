package com.bandori.pet.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionTagParserTest {
    @Test
    fun stripsKnownTagSplitAcrossChunks() {
        val parser = ActionTagParser(setOf("smile", "yukina_default.exp"))
        assertEquals("你好", parser.consume("你好[sm"))
        assertEquals("你好，今天很开心", parser.consume("ile]，今天很开心"))
        val result = parser.finish()
        assertEquals("你好，今天很开心", result.text)
        assertEquals("smile", result.action)
    }

    @Test
    fun keepsUnknownTagsAndExecutesOnlyFirstKnownTag() {
        val parser = ActionTagParser(setOf("smile", "sad"))
        parser.consume("[unknown][sad]内容[smile]")
        val result = parser.finish()
        assertEquals("[unknown]内容", result.text)
        assertEquals("sad", result.action)
    }

    @Test
    fun noTagProducesNoAction() {
        val result = ActionTagParser(setOf("smile")).apply { consume("普通回答") }.finish()
        assertEquals("普通回答", result.text)
        assertNull(result.action)
    }
}
