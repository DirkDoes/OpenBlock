package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object InvokeToolCommands {
	private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
	private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
		Thread(runnable, "openblock-invoke-tool").apply {
			isDaemon = true
		}
	}

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		val root = MinecraftCommands.literal("ob-invoke-tool")
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

	private fun runManualTool(source: CommandSourceStack, tool: AiTool, arguments: Map<String, String>) {
		if (tool.runsAsyncWhenInvokedManually) {
			runManualToolAsync(source, tool, arguments)
			return
		}

		sendToolResult(source, tool, AiService.executeTool(source.player?.uuid, tool.name, arguments))
	}

	private fun runManualToolAsync(source: CommandSourceStack, tool: AiTool, arguments: Map<String, String>) {
		source.sendSuccess(
			{ Component.literal("${tool.name}: running...").withStyle(ChatFormatting.GRAY) },
			false,
		)
		val server = source.server
		val playerId = source.player?.uuid
		executor.submit {
			val result = AiService.executeTool(playerId, tool.name, arguments)
			server.execute {
				sendToolResult(source, tool, result)
			}
		}
	}

	private fun sendToolResult(source: CommandSourceStack, tool: AiTool, result: Result<me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution>) {
		val execution = result.getOrElse { error ->
			source.sendFailure(Component.literal(error.message ?: "Unknown tool: ${tool.name}").withStyle(ChatFormatting.RED))
			return
		}

		val color = if (execution.isError) ChatFormatting.RED else ChatFormatting.WHITE
		source.sendSuccess({ Component.literal("${tool.name}:").withStyle(ChatFormatting.YELLOW) }, false)
		for (line in gson.toJson(execution.asResponseMap()).lines()) {
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
