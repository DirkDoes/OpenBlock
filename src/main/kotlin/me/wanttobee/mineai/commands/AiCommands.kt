package me.wanttobee.mineai.commands

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.wanttobee.mineai.ai.AiActionBarManager
import me.wanttobee.mineai.ai.AiService
import me.wanttobee.mineai.ai.Providers
import me.wanttobee.mineai.ai.sessions.AiTargetManager
import me.wanttobee.mineai.ai.sessions.Session
import me.wanttobee.mineai.ai.tools.AiTool
import me.wanttobee.mineai.util.MinecraftTextFormatter
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.Commands as MinecraftCommands
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
		Thread(runnable, "mineai-ai").apply {
			isDaemon = true
		}
	}

	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		registerAlias(dispatcher, "ai")
		registerAlias(dispatcher, "mineai")
		registerAiToolCommand(dispatcher)
	}

	private fun registerAlias(dispatcher: CommandDispatcher<CommandSourceStack>, alias: String) {
		dispatcher.register(
			MinecraftCommands.literal(alias)
				.requires(Commands::isOpPlayer)
				.executes { context ->
					showCurrentTarget(context.source)
					1
				}
				.then(
					MinecraftCommands.literal("ping")
						.executes { context ->
							val player = context.source.player ?: return@executes 0
							runPing(context.source.server, player.uuid)
							1
						}
				)
				.then(
					MinecraftCommands.literal("target")
						.executes { context ->
							showCurrentTarget(context.source)
							1
						}
						.then(
							MinecraftCommands.literal("get")
								.executes { context ->
									showCurrentTarget(context.source)
									1
								}
						)
						.then(
							MinecraftCommands.argument("provider", StringArgumentType.word())
								.suggests(::suggestProviders)
								.executes { context ->
									val player = context.source.player ?: return@executes 0
									selectTarget(
										context.source,
										player.uuid,
										StringArgumentType.getString(context, "provider"),
										null
									)
									1
								}
								.then(
									MinecraftCommands.argument("model", StringArgumentType.word())
										.suggests(::suggestProviderModels)
										.executes { context ->
											val player = context.source.player ?: return@executes 0
											selectTarget(
												context.source,
												player.uuid,
												StringArgumentType.getString(context, "provider"),
												StringArgumentType.getString(context, "model"),
											)
											1
										}
										.then(
											MinecraftCommands.argument("reasoning", StringArgumentType.word())
												.suggests(::suggestReasoningValues)
												.executes { context ->
													val player = context.source.player ?: return@executes 0
													selectTarget(
														context.source,
														player.uuid,
														StringArgumentType.getString(context, "provider"),
														StringArgumentType.getString(context, "model"),
														StringArgumentType.getString(context, "reasoning"),
													)
													1
												}
										)
								)
						)
				)
				.then(
					MinecraftCommands.literal("tools")
						.executes { context ->
							showTools(context.source)
							1
						}
						.then(
							MinecraftCommands.argument("name", StringArgumentType.word())
								.suggests(::suggestToolNames)
								.executes { context ->
									showTool(
										context.source,
										StringArgumentType.getString(context, "name"),
									)
									1
								}
								.then(
									MinecraftCommands.argument("state", StringArgumentType.word())
										.suggests(::suggestToolStates)
										.executes { context ->
											val player = context.source.player ?: return@executes 0
											setToolState(
												context.source,
												player.uuid,
												StringArgumentType.getString(context, "name"),
												StringArgumentType.getString(context, "state"),
											)
											1
										}
								)
						)
				)
				.then(
					MinecraftCommands.literal("send")
						.then(
							MinecraftCommands.argument("message", StringArgumentType.greedyString())
								.executes { context ->
									val player = context.source.player ?: return@executes 0
									sendToCurrentTarget(
										context.source.server,
										player.uuid,
										StringArgumentType.getString(context, "message")
									)
									1
								}
						)
				)
				.then(
					MinecraftCommands.literal("clear")
						.executes { context ->
							clearSession(context.source)
							1
						}
				)
		)
	}

	private fun runPing(server: MinecraftServer, playerId: UUID) {
		executor.submit {
			val statuses = AiService.pingProviders()

			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				for ((provider, exception) in statuses) {
					player.sendSystemMessage(formatProviderStatus(provider, exception))
				}
			}
		}
	}

	private fun sendToCurrentTarget(server: MinecraftServer, playerId: UUID, message: String) {
		val target = AiService.currentTarget(playerId)
		if (target == null) {
			server.execute {
				val player = server.playerList.getPlayer(playerId) ?: return@execute
				player.sendSystemMessage(
					Component.literal("No AI target selected. Use /ai target <provider> first.")
						.withStyle(ChatFormatting.RED)
				)
			}
			return
		}

		server.execute {
			val player = server.playerList.getPlayer(playerId) ?: return@execute
			player.sendSystemMessage(
				Component.literal(MESSAGE_DIVIDER).withStyle(ChatFormatting.DARK_GRAY)
			)
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
				}
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

	private fun selectTarget(
		source: CommandSourceStack,
		playerId: UUID,
		providerName: String,
		modelName: String?,
		reasoningValue: String? = null,
	) {
		if (Providers.getProviderByName(providerName) == null) {
			source.sendFailure(
				Component.literal("Unknown AI provider: $providerName")
					.withStyle(ChatFormatting.RED)
			)
			return
		}

		val target = AiService.selectTarget(playerId, providerName, modelName, reasoningValue)
		if (target == null) {
			source.sendFailure(
				Component.literal(
					if (reasoningValue.isNullOrBlank()) "Unable to select that AI target."
					else "That reasoning option is not supported for the selected model."
				)
					.withStyle(ChatFormatting.RED)
			)
			return
		}

		source.sendSuccess(
			{
				Component.literal("Target set to ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(target.model.displayName).withStyle(target.provider.chatColor))
					.append(formatReasoningSuffix(target))
			},
			false
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
		val argumentType = when {
			parameterIndex == tool.parameters.lastIndex && parameter.type == AiTool.Type.STRING ->
				StringArgumentType.greedyString()
			else -> StringArgumentType.word()
		}

		val argument = MinecraftCommands.argument(parameter.name, argumentType)
			.suggests { context, builder ->
				suggestToolArgument(tool, parameterIndex, context, builder)
			}

		if (parameterIndex == tool.parameters.lastIndex) {
			argument.executes { context ->
				runManualTool(context.source, tool, toolArguments(tool, context))
				1
			}
		}

		parent.then(argument)
		attachToolArguments(argument, tool, parameterIndex + 1)
	}

	private fun showCurrentTarget(source: CommandSourceStack) {
		val player = source.player ?: return
		val target = AiService.currentTarget(player.uuid)
		if (target == null) {
			source.sendSuccess(
				{ Component.literal("No AI target selected.").withStyle(ChatFormatting.YELLOW) },
				false
			)
			return
		}

		source.sendSuccess(
			{
				Component.literal("Current AI target: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(target.provider.displayName).withStyle(target.provider.chatColor))
					.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(target.model.displayName).withStyle(ChatFormatting.WHITE))
					.append(formatReasoningSuffix(target))
			},
			false
		)
	}

	private fun clearSession(source: CommandSourceStack) {
		val player = source.player ?: return
		val cleared = AiService.clearSession(player.uuid)

		source.sendSuccess(
			{
				Component.literal(
					if (cleared) "AI session cleared."
					else "No AI session to clear."
				).withStyle(ChatFormatting.YELLOW)
			},
			false
		)
	}

	private fun showTools(source: CommandSourceStack) {
		val player = source.player ?: return
		source.sendSuccess(
			{ Component.literal("AI tools:").withStyle(ChatFormatting.YELLOW) },
			false
		)

		for (tool in AiService.allTools()) {
			val enabled = AiService.isToolEnabled(player.uuid, tool.name)
			source.sendSuccess(
				{
					Component.literal("${tool.name}: ").withStyle(ChatFormatting.GRAY)
						.append(
							Component.literal(if (enabled) "on" else "off")
								.withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)
						)
				},
				false
			)
		}
	}

	private fun showTool(source: CommandSourceStack, toolName: String) {
		val player = source.player ?: return
		val tool = AiService.allTools().firstOrNull { it.name.equals(toolName, ignoreCase = true) }
		if (tool == null) {
			source.sendFailure(Component.literal("Unknown tool: $toolName").withStyle(ChatFormatting.RED))
			return
		}

		val enabled = AiService.isToolEnabled(player.uuid, tool.name)
		source.sendSuccess(
			{
				Component.literal("${tool.name}: ").withStyle(ChatFormatting.YELLOW)
					.append(
						Component.literal(if (enabled) "on" else "off")
							.withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)
					)
					.append(Component.literal(" - ${tool.description}").withStyle(ChatFormatting.WHITE))
			},
			false
		)
	}

	private fun setToolState(source: CommandSourceStack, playerId: UUID, toolName: String, state: String) {
		val enabled = when (state.lowercase()) {
			"on" -> true
			"off" -> false
			else -> {
				source.sendFailure(Component.literal("State must be on or off.").withStyle(ChatFormatting.RED))
				return
			}
		}

		if (!AiService.setToolEnabled(playerId, toolName, enabled)) {
			source.sendFailure(Component.literal("Unknown tool: $toolName").withStyle(ChatFormatting.RED))
			return
		}

		source.sendSuccess(
			{
				Component.literal("${toolName}: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
			},
			false
		)
	}

	private fun runManualTool(source: CommandSourceStack, tool: AiTool, arguments: Map<String, String>) {
		val playerId = source.player?.uuid
		val result = AiService.executeTool(playerId, tool.name, arguments)
		if (result == null) {
			source.sendFailure(Component.literal("Unknown tool: ${tool.name}").withStyle(ChatFormatting.RED))
			return
		}

		val color = if (result.isError) ChatFormatting.RED else ChatFormatting.WHITE
		source.sendSuccess(
			{ Component.literal("${tool.name}:").withStyle(ChatFormatting.YELLOW) },
			false
		)
		for (line in gson.toJson(result.asResponseMap()).lines()) {
			source.sendSuccess(
				{ Component.literal(line).withStyle(color) },
				false
			)
		}
	}

	private fun formatProviderStatus(
		provider: me.wanttobee.mineai.ai.providers.AiProvider,
		exception: Exception?,
	): Component {
		return Component.literal("${provider.displayName}: ").withStyle(provider.chatColor)
			.append(
				Component.literal(exception?.message ?: "ready")
					.withStyle(if (exception == null) ChatFormatting.WHITE else ChatFormatting.RED)
			)
	}

	private fun formatSessionMessage(target: AiTargetManager.AiTarget, message: Session.Message): Component {
		return when (message.type) {
			Session.Message.Type.ASSISTANT -> Component.literal("${target.model.displayName}: ")
				.withStyle(target.provider.chatColor)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			Session.Message.Type.TOOL -> Component.literal(message.content).withStyle(ChatFormatting.GRAY)
			Session.Message.Type.ERROR -> Component.literal("${target.model.displayName} error: ")
				.withStyle(ChatFormatting.RED)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
			Session.Message.Type.USER -> Component.literal("you: ").withStyle(ChatFormatting.GRAY)
				.append(MinecraftTextFormatter.format(message.content, Style.EMPTY.withColor(ChatFormatting.WHITE)))
		}
	}

	private fun suggestProviders(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		return SharedSuggestionProvider.suggest(Providers.providerNames(), builder)
	}

	private fun suggestProviderModels(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		val providerName = StringArgumentType.getString(context, "provider")
		for (model in Providers.modelList(providerName)) {
			builder.suggest(model.displaySlug, LiteralMessage(model.displayName))
		}
		return builder.buildFuture()
	}

	private fun suggestReasoningValues(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		val providerName = StringArgumentType.getString(context, "provider")
		val modelName = StringArgumentType.getString(context, "model")
		for (suggestion in Providers.reasoningSuggestions(providerName, modelName)) {
			val description = suggestion.description
			if (description == null) {
				builder.suggest(suggestion.value)
			} else {
				builder.suggest(suggestion.value, LiteralMessage(description))
			}
		}
		return builder.buildFuture()
	}

	private fun suggestToolNames(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		return SharedSuggestionProvider.suggest(AiService.allTools().map(AiTool::name), builder)
	}

	private fun suggestToolStates(
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		return SharedSuggestionProvider.suggest(listOf("on", "off"), builder)
	}

	private fun suggestToolArgument(
		tool: AiTool,
		parameterIndex: Int,
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
		val playerId = context.source.player?.uuid
		for (suggestion in tool.suggestions(playerId, parameterIndex, toolArguments(tool, context, parameterIndex))) {
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
		context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
		uptoExclusive: Int = tool.parameters.size,
	): Map<String, String> {
		return tool.parameters.take(uptoExclusive).associate { parameter ->
			parameter.name to StringArgumentType.getString(context, parameter.name)
		}
	}

	private fun formatReasoningSuffix(target: AiTargetManager.AiTarget): Component {
		val reasoningDescription = target.provider.describeReasoning(target.model) ?: return Component.empty()
		return Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal(reasoningDescription).withStyle(ChatFormatting.GRAY))
	}

}
