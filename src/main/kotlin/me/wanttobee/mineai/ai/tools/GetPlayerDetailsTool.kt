package me.wanttobee.mineai.ai.tools

import me.wanttobee.mineai.ai.context.PlayerInspectionFormatter
import java.util.UUID

object GetPlayerDetailsTool : AiTool {
	private val playerUuidParameter = AiTool.Parameter(
		name = "player_uuid",
		description = "The UUID of the player to inspect.",
		type = AiTool.Type.UUID,
	)

	override val name = "get_player_details"
	override val description =
		"Returns detailed live state for one player: hands, armor, inventory, effects, stack counts, durability, and enchantments."
	override val enabledByDefault = true
	override val parameters = listOf(playerUuidParameter)

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): List<AiTool.Suggestion> {
		if (parameterIndex != 0) {
			return emptyList()
		}
		return PlayerInspectionFormatter.onlinePlayerSuggestions()
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): AiTool.ExecutionResult {
		val targetId = arguments.get<UUID>(playerUuidParameter.name)
		val details = PlayerInspectionFormatter.playerDetails(targetId)
			?: return AiTool.ExecutionResult(
				payload = mapOf("message" to "Player is not online: $targetId"),
				isError = true,
			)

		return AiTool.ExecutionResult(payload = details)
	}
}
