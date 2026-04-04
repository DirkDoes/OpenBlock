package me.wanttobee.openblock.ai.toolcalling.base

import net.minecraft.world.item.Items
import net.minecraft.world.level.ItemLike
import java.util.UUID

interface AiTool {
	val name: String
	val description: String
	val enabledByDefault: Boolean
	val parameters: List<AiToolParameter>
	val menuIcon: ItemLike
		get() = Items.BARRIER
	val hasConfigurationMenu: Boolean
		get() = false

	fun invoke(boundedPlayerId: UUID?, rawArguments: Map<String, String>): Result<AiToolInvocation> {
		val validatedArguments = ToolArguments.validate(parameters, rawArguments)
			.getOrElse { return Result.failure(it) }
		val execution = execute(boundedPlayerId, validatedArguments).getOrElse { return Result.failure(it) }
		val conversationMessage = conversationMessage(boundedPlayerId, validatedArguments).getOrElse { return Result.failure(it) }
		return Result.success(
			AiToolInvocation(
				execution = execution,
				conversationMessage = conversationMessage,
			)
		)
	}

	fun execute(boundedPlayerId: UUID?, arguments: ToolArguments): Result<AiToolExecution>

	fun conversationMessage(playerId: UUID?, arguments: ToolArguments): Result<String?> {
		return Result.success(null)
	}

	fun suggestions(playerId: UUID?, parameterIndex: Int, arguments: Map<String, String>): Result<List<AiToolSuggestion>> {
		return Result.success(emptyList())
	}
}
