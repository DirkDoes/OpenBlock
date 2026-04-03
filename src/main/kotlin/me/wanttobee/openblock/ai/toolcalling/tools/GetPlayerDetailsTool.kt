package me.wanttobee.openblock.ai.toolcalling.tools

import me.wanttobee.openblock.ai.context.PlayerContextCapturer
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import net.minecraft.world.item.Items
import java.util.UUID

object GetPlayerDetailsTool : AiTool {
	private val playerUuidParameter = AiToolParameter(
		name = "player_uuid",
		description = "The UUID of the player to inspect.",
		type = AiToolParameter.ParameterType.UUID,
	)

	override val name = "get_player_details"
	override val description =
		"Returns detailed live state for one player: hands, armor, inventory, effects, stack counts, durability, and enchantments."
	override val enabledByDefault = true
	override val parameters = listOf(playerUuidParameter)
	override val menuIcon = Items.PLAYER_HEAD

	override fun suggestions(
		playerId: UUID?,
		parameterIndex: Int,
		arguments: Map<String, String>,
	): Result<List<AiToolSuggestion>> {
		if (parameterIndex != 0) {
			return Result.success(emptyList())
		}
		return PlayerContextCapturer.onlinePlayerSuggestions()
	}

	override fun execute(playerId: UUID?, arguments: ToolArguments): Result<AiToolExecution> {
		val targetId = arguments.get<UUID>(playerUuidParameter.name).getOrElse { return Result.failure(it) }
		val details = PlayerContextCapturer.playerDetails(targetId).getOrElse { error ->
			return Result.success(AiToolExecution(
				payload = mapOf("message" to (error.message ?: "Player is not online: $targetId")),
				isError = true,
			))
		}
		return Result.success(AiToolExecution(payload = details))
	}
}
