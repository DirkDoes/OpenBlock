package me.wanttobee.openblock.ai.providers.codex

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter

object CodexProtocol {
	fun responseTool(tool: AiTool): JsonObject {
		return JsonObject().apply {
			addProperty("type", "function")
			addProperty("name", tool.name)
			addProperty("description", tool.description)
			addProperty("strict", true)
			add("parameters", JsonObject().apply {
				addProperty("type", "object")
				add("properties", JsonObject().apply {
					tool.parameters.forEach { parameter ->
						add(parameter.name, JsonObject().apply {
							if (parameter.required) {
								addProperty("type", "string")
							} else {
								add("type", JsonArray().apply {
									add("string")
									add("null")
								})
							}
							addProperty("description", parameter.description)
						})
					}
				})
				add("required", JsonArray().apply {
					tool.parameters.map(AiToolParameter::name).forEach(::add)
				})
				addProperty("additionalProperties", false)
			})
		}
	}

	fun arguments(value: JsonElement): Map<String, String> {
		if (!value.isJsonObject) return emptyMap()
		return value.asJsonObject.entrySet().associate { (name, argument) ->
			name to argumentString(argument)
		}
	}

	private fun argumentString(value: JsonElement): String {
		return when {
			value is JsonNull -> ""
			value is JsonPrimitive && value.isString -> value.asString
			value is JsonPrimitive -> value.asString
			else -> value.toString()
		}
	}
}
