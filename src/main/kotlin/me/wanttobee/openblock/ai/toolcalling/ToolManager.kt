package me.wanttobee.openblock.ai.toolcalling

import me.wanttobee.openblock.ai.sessions.AiSessionManager
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolInvocation
import me.wanttobee.openblock.ai.toolcalling.tools.ExecuteCommandTool
import me.wanttobee.openblock.ai.toolcalling.tools.FillBlocksTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetBlockDetailsTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetBlocksTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetCommandDocumentationTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetOnlinePlayersTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetPlayerDetailsTool
import me.wanttobee.openblock.ai.toolcalling.tools.InteractBlockTool
import me.wanttobee.openblock.ai.toolcalling.tools.PlaceBlockTool
import me.wanttobee.openblock.ai.toolcalling.tools.SandboxInteractionTool
import java.util.UUID

object ToolManager {
	private val tools = listOf(
		GetOnlinePlayersTool,
		GetPlayerDetailsTool,

		GetCommandDocumentationTool,
		ExecuteCommandTool,

		GetBlockDetailsTool,
		GetBlocksTool,
		InteractBlockTool,
		SandboxInteractionTool,
		PlaceBlockTool,
		FillBlocksTool,
	)

	fun allTools(): List<AiTool> = tools

	fun toolNames(): List<String> = tools.map(AiTool::name)

	fun defaultEnabledToolNames(): Set<String> {
		return tools.filter(AiTool::enabledByDefault).mapTo(linkedSetOf(), AiTool::name)
	}

	fun getTool(name: String): AiTool? {
		if (name.equals("get_block_area", ignoreCase = true)) {
			return GetBlocksTool
		}
		return tools.firstOrNull { it.name.equals(name, ignoreCase = true) }
	}

	fun isEnabled(playerId: UUID?, name: String): Boolean {
		val tool = getTool(name) ?: return false
		if (playerId == null) {
			return tool.enabledByDefault
		}

		val session = AiSessionManager.getSession(playerId).getOrNull() ?: return tool.enabledByDefault
		return tool.name in session.enabledToolNames()
	}

	fun enabledTools(session: Session): List<AiTool> {
		val enabledNames = session.enabledToolNames()
		return tools.filter { it.name in enabledNames }
	}

	fun setEnabled(playerId: UUID?, name: String, enabled: Boolean): Boolean {
		val tool = getTool(name) ?: return false
		val scopedPlayerId = playerId ?: return false
		val session = AiSessionManager.getSession(scopedPlayerId).getOrNull() ?: return false
		session.updateToolState(tool.name, enabled)
		return true
	}

	fun invoke(boundedPlayerId: UUID?, name: String, arguments: Map<String, String>): Result<AiToolInvocation> {
		val tool = getTool(name)
			?: return Result.failure(NoSuchElementException("Unknown tool: $name"))
		return tool.invoke(boundedPlayerId, arguments)
	}

	fun execute(boundedPlayerId: UUID?, name: String, arguments: Map<String, String>): Result<AiToolExecution> {
		return invoke(boundedPlayerId, name, arguments).map(AiToolInvocation::execution)
	}
}
