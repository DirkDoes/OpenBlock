package me.wanttobee.mineai.ai.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ToolManager {
	private val toolOverrides = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Boolean>>()

	private val tools = listOf(
		GetOnlinePlayersTool,
		GetPlayerDetailsTool,
	)

	fun allTools(): List<AiTool> = tools

	fun toolNames(): List<String> = tools.map(AiTool::name)

	fun getTool(name: String): AiTool? = tools.firstOrNull { it.name.equals(name, ignoreCase = true) }

	fun isEnabled(playerId: UUID?, name: String): Boolean {
		val tool = getTool(name) ?: return false
		val scopedPlayerId = playerId ?: return tool.enabledByDefault
		return toolOverrides[scopedPlayerId]?.get(tool.name) ?: tool.enabledByDefault
	}

	fun enabledTools(playerId: UUID?): List<AiTool> {
		return tools.filter { isEnabled(playerId, it.name) }
	}

	fun setEnabled(playerId: UUID?, name: String, enabled: Boolean): Boolean {
		val tool = getTool(name) ?: return false
		val scopedPlayerId = playerId ?: return false
		toolOverrides.computeIfAbsent(scopedPlayerId) { ConcurrentHashMap() }[tool.name] = enabled
		return true
	}

	fun execute(playerId: UUID?, name: String, arguments: Map<String, String>): AiTool.ExecutionResult? {
		val tool = getTool(name) ?: return null
		return tool.invoke(playerId, arguments)
	}
}
