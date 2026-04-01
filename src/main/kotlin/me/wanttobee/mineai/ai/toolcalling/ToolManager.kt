package me.wanttobee.mineai.ai.toolcalling

import me.wanttobee.mineai.ai.toolcalling.tools.ExecuteCommandTool
import me.wanttobee.mineai.ai.toolcalling.tools.FillBlocksTool
import me.wanttobee.mineai.ai.toolcalling.tools.GetBlockDetailsTool
import me.wanttobee.mineai.ai.toolcalling.tools.GetBlocksTool
import me.wanttobee.mineai.ai.toolcalling.tools.GetCommandDocumentationTool
import me.wanttobee.mineai.ai.toolcalling.tools.GetOnlinePlayersTool
import me.wanttobee.mineai.ai.toolcalling.tools.GetPlayerDetailsTool
import me.wanttobee.mineai.ai.toolcalling.tools.PlaceBlockTool
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ToolManager {
	private val toolOverrides = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Boolean>>()

	private val tools = listOf(
		GetOnlinePlayersTool,
		GetPlayerDetailsTool,
		GetBlockDetailsTool,
		GetBlocksTool,
		GetCommandDocumentationTool,
		PlaceBlockTool,
		FillBlocksTool,
		ExecuteCommandTool,
	)

	fun allTools(): List<AiTool> = tools

	fun toolNames(): List<String> = tools.map(AiTool::name)

	fun getTool(name: String): AiTool? {
		if (name.equals("get_block_area", ignoreCase = true)) {
			return GetBlocksTool
		}
		return tools.firstOrNull { it.name.equals(name, ignoreCase = true) }
	}

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

	fun invoke(playerId: UUID?, name: String, arguments: Map<String, String>): AiTool.InvocationResult? {
		val tool = getTool(name) ?: return null
		return tool.invoke(playerId, arguments)
	}

	fun execute(playerId: UUID?, name: String, arguments: Map<String, String>): AiTool.ExecutionResult? {
		return invoke(playerId, name, arguments)?.execution
	}
}
