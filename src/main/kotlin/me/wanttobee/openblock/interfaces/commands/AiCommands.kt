package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.wanttobee.openblock.ai.AiActionBarManager
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.sessions.AiTargetManager
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.util.MinecraftTextFormatter
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AiCommands {
	private const val MESSAGE_DIVIDER = "------------------------------"
	private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
	private val executor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
		Thread(runnable, "openblock-ai").apply {
			isDaemon = true
		}
	}

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		registerAiChatCommand(dispatcher)
		registerAiToolCommand(dispatcher)
	}

	fun sendPrompt(server: MinecraftServer, playerId: UUID, message: String) {
		sendToCurrentTarget(server, playerId, message)
	}

	private fun registerAiChatCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("ai")
				.requires(Commands::isOpPlayer)
				.executes { context ->
					OpenBlockCommands.showCurrentTarget(context.source)
					1
				}
				.then(
					MinecraftCommands.argument("message", StringArgumentType.greedyString())
						.executes { context ->
							val player = context.source.player ?: return@executes 0
							sendPrompt(
								context.source.server,
								player.uuid,
								StringArgumentType.getString(context, "message"),
							)
							1
						}
				)
		)
	}

	private fun registerAiToolCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
		val root = MinecraftCommands.literal("aitool")
			.requires(Commands::isOpPlayer)

		for (tool in AiService.allTools()) {
			root.then(buildAiToolCommand(tool))
		}

		dispatcher.register(root)
	}

	private fun buildAiToolCommand(tool: AiTool): LiteralArgumentBuilder<CommandSourceStack> {
		val root = MinecraftCommands.literal(tool.name)
		if (tool.parameters.isEmpty()) {
			return root.executes { context ->
				runManualTool(context.source, tool, emptyMap())
				1
			}
		}

		attachToolExecution(root, tool, 0)
		attachToolArguments(root, tool, 0)
		return root
	}

	private fun attachToolArguments(
		parent: ArgumentBuilder<CommandSourceStack, *>,
		tool: AiTool,
		parameterIndex: Int,
	) {
		if (parameterIndex >= tool.parameters.size) {
			return
		}

		val parameter = tool.parameters[parameterIndex]
		val argument = when (parameter.manualInput) {
			AiToolParameter.ManualInput.BLOCK_POS -> MinecraftCommands.argument(parameter.name, BlockPosArgument.blockPos())
			AiToolParameter.ManualInput.WORD -> {
				val argumentType = when {
					parameterIndex == tool.parameters.lastIndex && parameter.type == AiToolParameter.ParameterType.STRING ->
						StringArgumentType.greedyString()
					else -> StringArgumentType.word()
				}
				MinecraftCommands.argument(parameter.name, argumentType)
					.suggests { context, builder ->
						suggestToolArgument(tool, parameterIndex, context, builder)
					}
			}
		}

		attachToolExecution(argument, tool, parameterIndex + 1)
		attachToolArguments(argument, tool, parameterIndex + 1)
		parent.then(argument)
	}

	private fun attachToolExecution(
		parent: ArgumentBuilder<CommandSourceStack, *>,
		tool: AiTool,
		providedParameterCount: Int,
	) {
		if (tool.parameters.drop(providedParameterCount).any(AiToolParameter::required)) {
			return
		}

		parent.executes { context ->
			runManualTool(context.source, tool, toolArguments(tool, context, providedParameterCount))
			1
		}
	}

	private fun sendToCurrentTarget(server: MinecraftServer, playerId: UUID, message: String) {
		val target = AiService.currentTarget(playerId).getOrElse {
			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				player.sendSystemMessage(
					Component.literal("No AI model selected. Use /openblock model <provider> first.")
						.withStyle(ChatFormatting.RED)
				)
			}
			return
		}

		server.execute {
			val player = server.playerList.getPlayer(playerId) ?: return@execute
			player.sendSystemMessage(Component.literal(MESSAGE_DIVIDER).withStyle(ChatFormatting.DARK_GRAY))
			player.sendSystemMessage(
				Component.literal("you: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(message).withStyle(ChatFormatting.WHITE))
			)
			AiActionBarManager.start(server, playerId, target, target.provider.startingAction(target.model))
		}

		executor.submit {
			val result = AiService.sendMessage(
				playerId = playerId,
				message = message,
				onActionChange = { action ->
					server.execute {
						AiActionBarManager.updateAction(server, playerId, action)
					}
				},
				onMessageAdded = { sessionMessage ->
					server.execute {
						val player = server.playerList.getPlayer(playerId) ?: return@execute
						player.sendSystemMessage(formatSessionMessage(target, sessionMessage))
					}
				},
			)

			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				AiActionBarManager.stop(server, playerId, 700L)
				val currentResult = result ?: return@execute
				for (sessionMessage in currentResult.second) {
					player.sendSystemMessage(formatSessionMessage(currentResult.first, sessionMessage))
				}
			}
		}
	}

	internal fun formatSessionMessage(target: AiTargetManager.AiTarget, message: SessionMessage): Component {
		return when (message.type) {
			SessionMessage.Type.ASSISTANT -> Component.literal("${target.model.displayName}: ")
				.withStyle(target.provider.chatColor)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			SessionMessage.Type.TOOL -> Component.literal(message.content).withStyle(ChatFormatting.GRAY)
			SessionMessage.Type.ERROR -> Component.literal("${target.model.displayName} error: ")
				.withStyle(ChatFormatting.RED)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			SessionMessage.Type.USER -> Component.literal("you: ").withStyle(ChatFormatting.GRAY)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
		}
	}

	private fun runManualTool(source: CommandSourceStack, tool: AiTool, arguments: Map<String, String>) {
		val playerId = source.player?.uuid
		val result = AiService.executeTool(playerId, tool.name, arguments).getOrElse { error ->
			source.sendFailure(Component.literal(error.message ?: "Unknown tool: ${tool.name}").withStyle(ChatFormatting.RED))
			return
		}

		val color = if (result.isError) ChatFormatting.RED else ChatFormatting.WHITE
		source.sendSuccess({ Component.literal("${tool.name}:").withStyle(ChatFormatting.YELLOW) }, false)
		for (line in gson.toJson(result.asResponseMap()).lines()) {
			source.sendSuccess({ Component.literal(line).withStyle(color) }, false)
		}
	}

	private fun suggestToolArgument(
		tool: AiTool,
		parameterIndex: Int,
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val playerId = context.source.player?.uuid
		for (suggestion in tool.suggestions(playerId, parameterIndex, toolArguments(tool, context, parameterIndex)).getOrElse { emptyList() }) {
			val description = suggestion.description
			if (description == null) {
				builder.suggest(suggestion.value)
			} else {
				builder.suggest(suggestion.value, LiteralMessage(description))
			}
		}
		return builder.buildFuture()
	}

	private fun toolArguments(
		tool: AiTool,
		context: CommandContext<CommandSourceStack>,
		uptoExclusive: Int = tool.parameters.size,
	): Map<String, String> {
		return tool.parameters.take(uptoExclusive).associate { parameter ->
			parameter.name to when (parameter.manualInput) {
				AiToolParameter.ManualInput.BLOCK_POS -> formatToolPosition(BlockPosArgument.getBlockPos(context, parameter.name))
				AiToolParameter.ManualInput.WORD -> StringArgumentType.getString(context, parameter.name)
			}
		}
	}

	private fun formatToolPosition(position: BlockPos): String {
		return "${position.x},${position.y},${position.z}"
	}
}
