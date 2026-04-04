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
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.ai.sessions.AiTargetManager
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.toolcalling.CommandToolsSupport
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolParameter
import me.wanttobee.openblock.interfaces.chat.ChatModeManager
import me.wanttobee.openblock.util.MinecraftTextFormatter
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.commands.SharedSuggestionProvider
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

	fun attachOpenBlockCommands(root: LiteralArgumentBuilder<CommandSourceStack>) {
		root.then(
			MinecraftCommands.literal("ping")
				.executes { context ->
					runPing(context.source)
					1
				}
		)
		root.then(buildModelBranch())
		root.then(buildToolsBranch())
		root.then(buildCommandsBranch())
		root.then(buildSessionsBranch())
		root.then(buildChatModeBranch())
		root.then(buildSandboxBranch())
		root.then(
			MinecraftCommands.literal("clear")
				.executes { context ->
					clearSession(context.source)
					1
				}
		)
	}

	fun sendPrompt(server: MinecraftServer, playerId: UUID, message: String) {
		sendToCurrentTarget(server, playerId, message)
	}

	private fun registerAiChatCommand(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("ai")
				.requires(Commands::isOpPlayer)
				.executes { context ->
					showCurrentTarget(context.source)
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

	private fun buildModelBranch(): LiteralArgumentBuilder<CommandSourceStack> {
		return MinecraftCommands.literal("model")
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
						val playerId = requirePlayerId(context.source) ?: return@executes 0
						selectTarget(
							context.source,
							playerId,
							StringArgumentType.getString(context, "provider"),
							null,
						)
						1
					}
					.then(
						MinecraftCommands.argument("model", StringArgumentType.word())
							.suggests(::suggestProviderModels)
							.executes { context ->
								val playerId = requirePlayerId(context.source) ?: return@executes 0
								selectTarget(
									context.source,
									playerId,
									StringArgumentType.getString(context, "provider"),
									StringArgumentType.getString(context, "model"),
								)
								1
							}
							.then(
								MinecraftCommands.argument("reasoning", StringArgumentType.word())
									.suggests(::suggestReasoningValues)
									.executes { context ->
										val playerId = requirePlayerId(context.source) ?: return@executes 0
										selectTarget(
											context.source,
											playerId,
											StringArgumentType.getString(context, "provider"),
											StringArgumentType.getString(context, "model"),
											StringArgumentType.getString(context, "reasoning"),
										)
										1
									}
							)
					)
			)
	}

	private fun buildToolsBranch(): LiteralArgumentBuilder<CommandSourceStack> {
		return MinecraftCommands.literal("tools")
			.executes { context ->
				showTools(context.source)
				1
			}
			.then(
				MinecraftCommands.argument("name", StringArgumentType.word())
					.suggests(::suggestToolNames)
					.executes { context ->
						showTool(context.source, StringArgumentType.getString(context, "name"))
						1
					}
					.then(
						MinecraftCommands.argument("state", StringArgumentType.word())
							.suggests(::suggestToolStates)
							.executes { context ->
								val playerId = requirePlayerId(context.source) ?: return@executes 0
								setToolState(
									context.source,
									playerId,
									StringArgumentType.getString(context, "name"),
									StringArgumentType.getString(context, "state"),
								)
								1
							}
					)
			)
	}

	private fun buildCommandsBranch(): LiteralArgumentBuilder<CommandSourceStack> {
		return MinecraftCommands.literal("commandtools")
			.executes { context ->
				showAllowedCommands(context.source)
				1
			}
			.then(
				MinecraftCommands.argument("name", StringArgumentType.word())
					.suggests(::suggestAllowedCommandNames)
					.executes { context ->
						showAllowedCommand(context.source, StringArgumentType.getString(context, "name"))
						1
					}
					.then(
						MinecraftCommands.argument("state", StringArgumentType.word())
							.suggests(::suggestToolStates)
							.executes { context ->
								setAllowedCommandState(
									context.source,
									StringArgumentType.getString(context, "name"),
									StringArgumentType.getString(context, "state"),
								)
								1
							}
					)
			)
	}

	private fun buildSessionsBranch(): LiteralArgumentBuilder<CommandSourceStack> {
		return MinecraftCommands.literal("sessions")
			.executes { context ->
				showSessions(context.source, resolveSessionOwner(context))
				1
			}
			.then(
				MinecraftCommands.literal("list")
					.executes { context ->
						showSessions(context.source, resolveSessionOwner(context))
						1
					}
					.then(
						MinecraftCommands.argument("player", StringArgumentType.word())
							.suggests(::suggestPlayerNames)
							.executes { context ->
								showSessions(context.source, resolveSessionOwner(context))
								1
							}
					)
			)
			.then(
				MinecraftCommands.literal("select")
					.then(
						MinecraftCommands.argument("session_id", StringArgumentType.word())
							.suggests(::suggestSessionIdsForCurrentPlayer)
							.executes { context ->
								selectSession(
									context.source,
									resolveSessionOwner(context),
									StringArgumentType.getString(context, "session_id"),
								)
								1
							}
					)
					.then(
						MinecraftCommands.argument("player", StringArgumentType.word())
							.suggests(::suggestPlayerNames)
							.then(
								MinecraftCommands.argument("session_id", StringArgumentType.word())
									.suggests(::suggestSessionIdsForNamedPlayer)
									.executes { context ->
										selectSession(
											context.source,
											resolveSessionOwner(context),
											StringArgumentType.getString(context, "session_id"),
										)
										1
									}
							)
					)
			)
			.then(
				MinecraftCommands.literal("clear")
					.executes { context ->
						clearSessionFor(context.source, resolveSessionOwner(context))
						1
					}
					.then(
						MinecraftCommands.argument("player", StringArgumentType.word())
							.suggests(::suggestPlayerNames)
							.executes { context ->
								clearSessionFor(context.source, resolveSessionOwner(context))
								1
							}
					)
			)
	}

	private fun buildChatModeBranch(): LiteralArgumentBuilder<CommandSourceStack> {
		return MinecraftCommands.literal("chatmode")
			.executes { context ->
				showChatMode(context.source)
				1
			}
			.then(
				MinecraftCommands.argument("state", StringArgumentType.word())
					.suggests(::suggestToolStates)
					.executes { context ->
						setChatMode(
							context.source,
							StringArgumentType.getString(context, "state"),
						)
						1
					}
			)
	}

	private fun buildSandboxBranch(): LiteralArgumentBuilder<CommandSourceStack> {
		return MinecraftCommands.literal("sandbox")
			.executes { context ->
				showSandbox(context.source)
				1
			}
			.then(
				MinecraftCommands.literal("at")
					.then(
						MinecraftCommands.argument("pos1", BlockPosArgument.blockPos())
							.then(
								MinecraftCommands.argument("pos2", BlockPosArgument.blockPos())
									.executes { context ->
										val playerId = requirePlayerId(context.source) ?: return@executes 0
										setSandbox(
											context.source,
											playerId,
											BlockPosArgument.getBlockPos(context, "pos1"),
											BlockPosArgument.getBlockPos(context, "pos2"),
										)
										1
									}
							)
					)
			)
			.then(
				MinecraftCommands.literal("clear")
					.executes { context ->
						clearSandbox(context.source)
						1
					}
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

	private fun runPing(source: CommandSourceStack) {
		executor.submit {
			val statuses = AiService.pingProviders()
			source.server.execute {
				for ((index, result) in statuses.withIndex()) {
					val provider = result.getOrNull() ?: Providers.all[index]
					source.sendSuccess({ formatProviderStatus(provider, result.exceptionOrNull()) }, false)
				}
			}
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

	private fun selectTarget(
		source: CommandSourceStack,
		playerId: UUID,
		providerName: String,
		modelName: String?,
		reasoningValue: String? = null,
	) {
		val target = AiService.selectTarget(playerId, providerName, modelName, reasoningValue).getOrElse { error ->
			source.sendFailure(
				Component.literal(error.message ?: "Unable to select that AI model.")
					.withStyle(ChatFormatting.RED)
			)
			return
		}

		source.sendSuccess(
			{
				Component.literal("Model set to ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(target.model.displayName).withStyle(target.provider.chatColor))
					.append(formatReasoningSuffix(target))
			},
			false,
		)
	}

	private fun showCurrentTarget(source: CommandSourceStack) {
		val playerId = requirePlayerId(source) ?: return
		val target = AiService.currentTarget(playerId).getOrElse {
			source.sendSuccess({ Component.literal("No AI model selected.").withStyle(ChatFormatting.YELLOW) }, false)
			return
		}

		source.sendSuccess(
			{
				Component.literal("Current AI model: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(target.provider.displayName).withStyle(target.provider.chatColor))
					.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(target.model.displayName).withStyle(ChatFormatting.WHITE))
					.append(formatReasoningSuffix(target))
			},
			false,
		)
	}

	private fun clearSession(source: CommandSourceStack) {
		val playerId = requirePlayerId(source) ?: return
		clearSessionFor(source, playerId)
	}

	private fun clearSessionFor(source: CommandSourceStack, playerId: UUID?) {
		if (playerId == null) {
			return
		}

		val cleared = AiService.clearSession(playerId)
		source.sendSuccess(
			{
				Component.literal(
					if (cleared) "AI session unselected."
					else "No AI session to clear."
				).withStyle(ChatFormatting.YELLOW)
			},
			false,
		)
	}

	private fun showSandbox(source: CommandSourceStack) {
		val playerId = requirePlayerId(source) ?: return
		val sandbox = AiService.currentSandbox(playerId).getOrElse {
			source.sendSuccess({ Component.literal("No sandbox defined.").withStyle(ChatFormatting.YELLOW) }, false)
			return
		}

		source.sendSuccess(
			{
				Component.literal("Sandbox: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(sandbox.boundary.description()).withStyle(ChatFormatting.WHITE))
			},
			false,
		)
	}

	private fun setSandbox(
		source: CommandSourceStack,
		playerId: UUID,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	) {
		val sandbox = AiService.setSandbox(
			playerId = playerId,
			dimension = source.level.dimension(),
			firstCorner = firstCorner,
			secondCorner = secondCorner,
		)
		val min = sandbox.minCorner()
		val max = sandbox.maxCorner()

		source.sendSuccess(
			{
				Component.literal("Sandbox set: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal("[${min.x}, ${min.y}, ${min.z}] -> [${max.x}, ${max.y}, ${max.z}]").withStyle(ChatFormatting.WHITE))
			},
			false,
		)
	}

	private fun clearSandbox(source: CommandSourceStack) {
		val playerId = requirePlayerId(source) ?: return
		val cleared = AiService.clearSandbox(playerId).getOrNull()
		source.sendSuccess(
			{
				Component.literal(
					if (cleared != null) "Sandbox cleared."
					else "No sandbox defined."
				).withStyle(ChatFormatting.YELLOW)
			},
			false,
		)
	}

	private fun showTools(source: CommandSourceStack) {
		val playerId = requirePlayerId(source) ?: return
		source.sendSuccess({ Component.literal("AI tools:").withStyle(ChatFormatting.YELLOW) }, false)

		for (tool in AiService.allTools()) {
			val enabled = AiService.isToolEnabled(playerId, tool.name)
			source.sendSuccess(
				{
					Component.literal("${tool.name}: ").withStyle(ChatFormatting.GRAY)
						.append(
							Component.literal(if (enabled) "on" else "off")
								.withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)
						)
				},
				false,
			)
		}
	}

	private fun showTool(source: CommandSourceStack, toolName: String) {
		val playerId = requirePlayerId(source) ?: return
		val tool = AiService.allTools().firstOrNull { it.name.equals(toolName, ignoreCase = true) }
		if (tool == null) {
			source.sendFailure(Component.literal("Unknown tool: $toolName").withStyle(ChatFormatting.RED))
			return
		}

		val enabled = AiService.isToolEnabled(playerId, tool.name)
		source.sendSuccess(
			{
				Component.literal("${tool.name}: ").withStyle(ChatFormatting.YELLOW)
					.append(
						Component.literal(if (enabled) "on" else "off")
							.withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)
					)
					.append(Component.literal(" - ${tool.description}").withStyle(ChatFormatting.WHITE))
			},
			false,
		)
	}

	private fun setToolState(source: CommandSourceStack, playerId: UUID, toolName: String, state: String) {
		val enabled = parseOnOff(source, state) ?: return
		if (!AiService.setToolEnabled(playerId, toolName, enabled)) {
			source.sendFailure(Component.literal("Unknown tool: $toolName").withStyle(ChatFormatting.RED))
			return
		}

		source.sendSuccess(
			{
				Component.literal("${toolName}: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
			},
			false,
		)
	}

	private fun showAllowedCommands(source: CommandSourceStack) {
		source.sendSuccess({ Component.literal("Allowed command tools:").withStyle(ChatFormatting.YELLOW) }, false)
		for (entry in CommandToolsSupport.commandEntries()) {
			source.sendSuccess(
				{
					Component.literal("${entry.name}: ").withStyle(ChatFormatting.GRAY)
						.append(
							Component.literal(if (entry.allowed) "on" else "off")
								.withStyle(if (entry.allowed) ChatFormatting.GREEN else ChatFormatting.RED)
						)
				},
				false,
			)
		}
	}

	private fun showAllowedCommand(source: CommandSourceStack, commandName: String) {
		val entry = CommandToolsSupport.commandEntries().firstOrNull { it.name.equals(commandName, ignoreCase = true) }
		if (entry == null) {
			source.sendFailure(Component.literal("Unknown command: $commandName").withStyle(ChatFormatting.RED))
			return
		}

		source.sendSuccess(
			{
				Component.literal("${entry.name}: ").withStyle(ChatFormatting.YELLOW)
					.append(
						Component.literal(if (entry.allowed) "on" else "off")
							.withStyle(if (entry.allowed) ChatFormatting.GREEN else ChatFormatting.RED)
					)
					.append(Component.literal(" / default ").withStyle(ChatFormatting.DARK_GRAY))
					.append(
						Component.literal(if (entry.defaultAllowed) "on" else "off")
							.withStyle(if (entry.defaultAllowed) ChatFormatting.GREEN else ChatFormatting.RED)
					)
			},
			false,
		)
	}

	private fun setAllowedCommandState(source: CommandSourceStack, commandName: String, state: String) {
		val enabled = parseOnOff(source, state) ?: return
		if (!CommandToolsSupport.setAllowed(commandName, enabled)) {
			source.sendFailure(Component.literal("Unknown command: $commandName").withStyle(ChatFormatting.RED))
			return
		}

		source.sendSuccess(
			{
				Component.literal("${commandName}: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
			},
			false,
		)
	}

	private fun showSessions(source: CommandSourceStack, playerId: UUID?) {
		if (playerId == null) {
			return
		}

		val sessions = AiService.allSessions(playerId)
		val selectedSessionId = AiService.currentSessionId(playerId).getOrNull()
		if (sessions.isEmpty()) {
			source.sendSuccess({ Component.literal("No sessions found.").withStyle(ChatFormatting.YELLOW) }, false)
			return
		}

		source.sendSuccess({ Component.literal("Sessions:").withStyle(ChatFormatting.YELLOW) }, false)
		for (session in sessions) {
			val selected = session.id == selectedSessionId
			source.sendSuccess(
				{
						Component.literal(if (selected) "* " else "- ").withStyle(if (selected) ChatFormatting.GREEN else ChatFormatting.DARK_GRAY)
							.append(Component.literal(session.id.toString()).withStyle(ChatFormatting.WHITE))
							.append(Component.literal(" (${session.userMessageCount} user messages)").withStyle(ChatFormatting.GRAY))
					},
					false,
				)
			}
	}

	private fun selectSession(source: CommandSourceStack, playerId: UUID?, sessionIdText: String) {
		if (playerId == null) {
			return
		}

		val sessionId = parseUuid(source, sessionIdText, "session id") ?: return
		AiService.selectSession(playerId, sessionId).getOrElse { error ->
			source.sendFailure(
				Component.literal(error.message ?: "Unable to select session: $sessionIdText")
					.withStyle(ChatFormatting.RED)
			)
			return
		}

		source.sendSuccess(
			{ Component.literal("Session selected: $sessionId").withStyle(ChatFormatting.YELLOW) },
			false,
		)
	}

	private fun showChatMode(source: CommandSourceStack) {
		val playerId = requirePlayerId(source) ?: return
		val enabled = ChatModeManager.isEnabled(playerId)
		source.sendSuccess(
			{
				Component.literal("Chat mode: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
			},
			false,
		)
	}

	private fun setChatMode(source: CommandSourceStack, state: String) {
		val playerId = requirePlayerId(source) ?: return
		val enabled = parseOnOff(source, state) ?: return
		ChatModeManager.setEnabled(playerId, enabled)
		source.sendSuccess(
			{
				Component.literal("Chat mode: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
			},
			false,
		)
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

	private fun formatProviderStatus(
		provider: me.wanttobee.openblock.ai.providers.AiProvider,
		exception: Throwable?,
	): Component {
		return Component.literal("${provider.displayName}: ").withStyle(provider.chatColor)
			.append(
				Component.literal(exception?.message ?: "ready")
					.withStyle(if (exception == null) ChatFormatting.WHITE else ChatFormatting.RED)
			)
	}

	private fun formatSessionMessage(target: AiTargetManager.AiTarget, message: SessionMessage): Component {
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

	private fun suggestProviders(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		return SharedSuggestionProvider.suggest(Providers.providerNames(), builder)
	}

	private fun suggestProviderModels(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val providerName = StringArgumentType.getString(context, "provider")
		for (model in Providers.modelList(providerName).getOrElse { emptyList() }) {
			builder.suggest(model.displaySlug, LiteralMessage(model.displayName))
		}
		return builder.buildFuture()
	}

	private fun suggestReasoningValues(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val providerName = StringArgumentType.getString(context, "provider")
		val modelName = StringArgumentType.getString(context, "model")
		for (suggestion in Providers.reasoningSuggestions(providerName, modelName).getOrElse { emptyList() }) {
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
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		return SharedSuggestionProvider.suggest(AiService.allTools().map(AiTool::name), builder)
	}

	private fun suggestToolStates(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		return SharedSuggestionProvider.suggest(listOf("on", "off"), builder)
	}

	private fun suggestAllowedCommandNames(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		return SharedSuggestionProvider.suggest(CommandToolsSupport.commandEntries().map(CommandToolsSupport.CommandEntry::name), builder)
	}

	private fun suggestPlayerNames(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val names = context.source.server.playerList.players.map { it.scoreboardName }
		return SharedSuggestionProvider.suggest(names, builder)
	}

	private fun suggestSessionIdsForCurrentPlayer(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val playerId = context.source.player?.uuid ?: return builder.buildFuture()
		return suggestSessionIds(builder, playerId)
	}

	private fun suggestSessionIdsForNamedPlayer(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val playerId = resolveNamedPlayerId(context.source, StringArgumentType.getString(context, "player")) ?: return builder.buildFuture()
		return suggestSessionIds(builder, playerId)
	}

	private fun suggestSessionIds(builder: SuggestionsBuilder, playerId: UUID): CompletableFuture<Suggestions> {
		return SharedSuggestionProvider.suggest(AiService.allSessions(playerId).map { it.id.toString() }, builder)
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

	private fun formatReasoningSuffix(target: AiTargetManager.AiTarget): Component {
		val reasoningDescription = target.provider.describeReasoning(target.model).getOrNull() ?: return Component.empty()
		return Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal(reasoningDescription).withStyle(ChatFormatting.GRAY))
	}

	private fun parseOnOff(source: CommandSourceStack, state: String): Boolean? {
		return when (state.lowercase()) {
			"on" -> true
			"off" -> false
			else -> {
				source.sendFailure(Component.literal("State must be on or off.").withStyle(ChatFormatting.RED))
				null
			}
		}
	}

	private fun requirePlayerId(source: CommandSourceStack): UUID? {
		return source.player?.uuid ?: run {
			source.sendFailure(Component.literal("This command requires a player.").withStyle(ChatFormatting.RED))
			null
		}
	}

	private fun resolveSessionOwner(context: CommandContext<CommandSourceStack>): UUID? {
		val specifiedPlayer = getOptionalWord(context, "player")
		return if (specifiedPlayer != null) {
			resolveNamedPlayerId(context.source, specifiedPlayer)
		} else {
			requirePlayerId(context.source)
		}
	}

	private fun resolveNamedPlayerId(source: CommandSourceStack, playerName: String): UUID? {
		val player = source.server.playerList.getPlayerByName(playerName)
		if (player == null) {
			source.sendFailure(Component.literal("Player is not online: $playerName").withStyle(ChatFormatting.RED))
			return null
		}
		return player.uuid
	}

	private fun parseUuid(source: CommandSourceStack, rawValue: String, label: String): UUID? {
		return try {
			UUID.fromString(rawValue)
		} catch (_: IllegalArgumentException) {
			source.sendFailure(Component.literal("Invalid $label: $rawValue").withStyle(ChatFormatting.RED))
			null
		}
	}

	private fun getOptionalWord(context: CommandContext<CommandSourceStack>, name: String): String? {
		return runCatching { StringArgumentType.getString(context, name) }.getOrNull()
	}
}
