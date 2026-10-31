package com.mobileai.framework

import com.mobileai.framework.agent.ActionType
import com.mobileai.framework.agent.DeviceActionParser
import org.junit.Assert.*
import org.junit.Test

class DeviceActionParserTest {

    @Test
    fun testParseSimpleClickAction() {
        val json = """{"action": "CLICK", "x": 540, "y": 960, "thought": "Tap the search bar"}"""
        val action = DeviceActionParser.parse(json)

        assertEquals(ActionType.CLICK, action.type)
        assertEquals(540, action.x)
        assertEquals(960, action.y)
        assertEquals("Tap the search bar", action.thought)
    }

    @Test
    fun testParseTypeActionWithTarget() {
        val json = """{"action": "TYPE", "text": "Quantum entanglement", "target": "search_input"}"""
        val action = DeviceActionParser.parse(json)

        assertEquals(ActionType.TYPE, action.type)
        assertEquals("Quantum entanglement", action.text)
        assertEquals("search_input", action.targetElementId)
    }

    @Test
    fun testParseMarkdownEnclosedJson() {
        val output = """
            Here is my planned action:
            ```json
            {
              "action": "SCROLL",
              "direction": "DOWN",
              "thought": "Scroll to reveal more search results"
            }
            ```
        """.trimIndent()

        val action = DeviceActionParser.parse(output)
        assertEquals(ActionType.SCROLL, action.type)
        assertEquals("DOWN", action.scrollDirection)
        assertEquals("Scroll to reveal more search results", action.thought)
    }

    @Test
    fun testParseDeepSeekReasoningWithThinkTags() {
        val output = """
            <think>
            The user wants to open Settings. Looking at the screen elements, I see an element 'Settings' at center (200, 450).
            I should click on it.
            </think>
            ```json
            {
              "action": "CLICK",
              "x": 200,
              "y": 450,
              "thought": "Click Settings app icon"
            }
            ```
        """.trimIndent()

        val action = DeviceActionParser.parse(output)
        assertEquals(ActionType.CLICK, action.type)
        assertEquals(200, action.x)
        assertEquals(450, action.y)
        assertEquals("Click Settings app icon", action.thought)
    }

    @Test
    fun testParseFinishAction() {
        val json = """{"action": "FINISH", "summary": "Successfully set alarm for 7:00 AM", "thought": "Alarm is enabled"}"""
        val action = DeviceActionParser.parse(json)

        assertEquals(ActionType.FINISH, action.type)
        assertEquals("Successfully set alarm for 7:00 AM", action.summary)
    }

    @Test
    fun testParseGlobalKeyPress() {
        val json = """{"action": "PRESS_KEY", "key": "HOME"}"""
        val action = DeviceActionParser.parse(json)

        assertEquals(ActionType.PRESS_KEY, action.type)
        assertEquals("HOME", action.keyName)
    }

    @Test
    fun testParseFallbackOnMalformedInput() {
        val malformed = "I will finish this task now because everything is done!"
        val action = DeviceActionParser.parse(malformed)

        assertEquals(ActionType.FINISH, action.type)
    }
}

