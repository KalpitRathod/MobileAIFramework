package com.mobileai.framework.agent.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parameter definition for a tool function.
 */
data class ToolParameter(
    val type: String,
    val description: String,
    val required: Boolean = true
)

/**
 * Tool capability provided by an on-device or cloud skill.
 */
data class SkillTool(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>,
    val isLocalEdgeOnly: Boolean = true
) {
    /**
     * Converts to standard Gemini / OpenAI JSON Function Declaration schema.
     */
    fun toGeminiToolDeclaration(): JSONObject {
        val properties = JSONObject()
        val requiredList = JSONArray()

        for ((pName, pDef) in parameters) {
            properties.put(pName, JSONObject().apply {
                put("type", pDef.type)
                put("description", pDef.description)
            })
            if (pDef.required) {
                requiredList.put(pName)
            }
        }

        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", properties)
                put("required", requiredList)
            })
        }
    }
}

/**
 * Encapsulates a modular capability module following the progressive-disclosure pattern.
 */
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val tools: List<SkillTool>,
    val triggerKeywords: List<String>
)
