package me.wanttobee.openblock.interfaces.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.sandbox.SandboxManager
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands as MinecraftCommands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

object SandboxCommands {
	fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			MinecraftCommands.literal("ob-sandbox")
				.requires(Commands::isAdminSource)
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
											val playerId = Commands.requirePlayerId(context.source) ?: return@executes 0
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
				.then(buildRendererBranch())
				.then(buildExclusionBranch())
				.then(buildInteractionBranch())
				.then(
					MinecraftCommands.literal("clear")
						.executes { context ->
							clearSandbox(context.source)
							1
						}
				)
		)
	}

	private fun buildRendererBranch() = MinecraftCommands.literal("renderer")
		.executes { context ->
			showRendererMode(context.source)
			1
		}
		.then(
			MinecraftCommands.argument("mode", StringArgumentType.word())
				.suggests(::suggestRendererModes)
				.executes { context ->
					val playerId = Commands.requirePlayerId(context.source) ?: return@executes 0
					setRendererMode(
						context.source,
						playerId,
						StringArgumentType.getString(context, "mode"),
					)
					1
				}
		)

	private fun buildExclusionBranch() = MinecraftCommands.literal("exclusion")
		.then(
			MinecraftCommands.literal("add")
				.then(
					MinecraftCommands.argument("name", StringArgumentType.word())
						.then(
							MinecraftCommands.argument("position", BlockPosArgument.blockPos())
								.executes { context ->
									val playerId = Commands.requirePlayerId(context.source) ?: return@executes 0
									addSandboxExclusion(
										context.source,
										playerId,
										StringArgumentType.getString(context, "name"),
										BlockPosArgument.getBlockPos(context, "position"),
									)
									1
								}
						)
				)
		)
		.then(
			MinecraftCommands.literal("remove")
				.then(
					MinecraftCommands.argument("name", StringArgumentType.word())
						.suggests(::suggestSandboxExclusionNames)
						.executes { context ->
							removeSandboxExclusion(
								context.source,
								StringArgumentType.getString(context, "name"),
							)
							1
						}
				)
		)
		.then(
			MinecraftCommands.literal("clear")
				.executes { context ->
					clearSandboxExclusions(context.source)
					1
				}
		)

	private fun buildInteractionBranch() = MinecraftCommands.literal("interaction")
		.then(
			MinecraftCommands.literal("add")
				.then(
					MinecraftCommands.argument("name", StringArgumentType.word())
						.then(
							MinecraftCommands.argument("position", BlockPosArgument.blockPos())
								.executes { context ->
									val playerId = Commands.requirePlayerId(context.source) ?: return@executes 0
									addSandboxInteraction(
										context.source,
										playerId,
										StringArgumentType.getString(context, "name"),
										BlockPosArgument.getBlockPos(context, "position"),
									)
									1
								}
						)
				)
		)
		.then(
			MinecraftCommands.literal("remove")
				.then(
					MinecraftCommands.argument("name", StringArgumentType.word())
						.suggests(::suggestSandboxInteractionNames)
						.executes { context ->
							removeSandboxInteraction(
								context.source,
								StringArgumentType.getString(context, "name"),
							)
							1
						}
				)
		)
		.then(
			MinecraftCommands.literal("clear")
				.executes { context ->
					clearSandboxInteractions(context.source)
					1
				}
		)

	private fun showSandbox(source: CommandSourceStack) {
		val playerId = Commands.requirePlayerId(source) ?: return
		val sandbox = AiService.currentSandbox(playerId).getOrElse {
			val message = it.message ?: "No sandbox defined."
			if (message == "No active sandbox.") {
				source.sendSuccess({ Component.literal("No sandbox defined.").withStyle(ChatFormatting.YELLOW) }, false)
			} else {
				source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED))
			}
			return
		}

		source.sendSuccess(
			{
				Component.literal("Sandbox: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(sandbox.boundary.description()).withStyle(ChatFormatting.WHITE))
			},
			false,
		)
		source.sendSuccess(
			{
				Component.literal("Exclusions: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(sandbox.exclusionDescription()).withStyle(ChatFormatting.WHITE))
			},
			false,
		)
		source.sendSuccess(
			{
				Component.literal("Interactions: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(sandbox.interactionDescription()).withStyle(ChatFormatting.WHITE))
			},
			false,
		)
		source.sendSuccess(
			{
				Component.literal("Renderer: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(SandboxManager.rendererMode(playerId).commandName).withStyle(ChatFormatting.WHITE))
			},
			false,
		)
	}

	private fun showRendererMode(source: CommandSourceStack) {
		val playerId = Commands.requirePlayerId(source) ?: return
		source.sendSuccess(
			{
				Component.literal("Sandbox renderer: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(SandboxManager.rendererMode(playerId).commandName).withStyle(ChatFormatting.WHITE))
			},
			false,
		)
	}

	private fun setRendererMode(source: CommandSourceStack, playerId: UUID, rawMode: String) {
		val mode = SandboxManager.RendererMode.fromCommandName(rawMode)
		if (mode == null) {
			source.sendFailure(Component.literal("Unknown sandbox renderer: $rawMode").withStyle(ChatFormatting.RED))
			return
		}

		SandboxManager.setRendererMode(playerId, mode)
		source.sendSuccess(
			{
				Component.literal("Sandbox renderer: ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(mode.commandName).withStyle(ChatFormatting.WHITE))
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
		AiService.setSandbox(
			playerId = playerId,
			dimension = source.level.dimension(),
			firstCorner = firstCorner,
			secondCorner = secondCorner,
		).onSuccess { sandbox ->
			val min = sandbox.minCorner()
			val max = sandbox.maxCorner()

			source.sendSuccess(
				{
					Component.literal("Sandbox set: ").withStyle(ChatFormatting.YELLOW)
						.append(Component.literal("[${min.x}, ${min.y}, ${min.z}] -> [${max.x}, ${max.y}, ${max.z}]").withStyle(ChatFormatting.WHITE))
				},
				false,
			)
		}.onFailure { error ->
			source.sendFailure(Component.literal(error.message ?: "Unable to set sandbox.").withStyle(ChatFormatting.RED))
		}
	}

	private fun clearSandbox(source: CommandSourceStack) {
		val playerId = Commands.requirePlayerId(source) ?: return
		AiService.clearSandbox(playerId)
			.onSuccess {
				source.sendSuccess(
					{ Component.literal("Sandbox cleared.").withStyle(ChatFormatting.YELLOW) },
					false,
				)
			}
			.onFailure { error ->
				source.sendFailure(Component.literal(error.message ?: "Unable to clear sandbox.").withStyle(ChatFormatting.RED))
			}
	}

	private fun addSandboxExclusion(source: CommandSourceStack, playerId: UUID, name: String, position: BlockPos) {
		AiService.addSandboxExclusion(playerId, source.level.dimension(), name, position)
			.onSuccess { sandbox ->
				source.sendSuccess(
					{
						Component.literal("Sandbox exclusion added: ").withStyle(ChatFormatting.YELLOW)
							.append(Component.literal("$name=[${position.x}, ${position.y}, ${position.z}]").withStyle(ChatFormatting.WHITE))
							.append(Component.literal(" / total ${sandbox.exclusions.size}").withStyle(ChatFormatting.DARK_GRAY))
					},
					false,
				)
			}
			.onFailure { error ->
				source.sendFailure(Component.literal(error.message ?: "Unable to add sandbox exclusion.").withStyle(ChatFormatting.RED))
			}
	}

	private fun removeSandboxExclusion(source: CommandSourceStack, name: String) {
		val playerId = Commands.requirePlayerId(source) ?: return
		AiService.removeSandboxExclusion(playerId, name)
			.onSuccess {
				source.sendSuccess(
					{ Component.literal("Sandbox exclusion removed: $name").withStyle(ChatFormatting.YELLOW) },
					false,
				)
			}
			.onFailure { error ->
				source.sendFailure(Component.literal(error.message ?: "Unable to remove sandbox exclusion.").withStyle(ChatFormatting.RED))
			}
	}

	private fun clearSandboxExclusions(source: CommandSourceStack) {
		val playerId = Commands.requirePlayerId(source) ?: return
		AiService.clearSandboxExclusions(playerId)
			.onSuccess {
				source.sendSuccess(
					{ Component.literal("Sandbox exclusions cleared.").withStyle(ChatFormatting.YELLOW) },
					false,
				)
			}
			.onFailure { error ->
				source.sendFailure(Component.literal(error.message ?: "Unable to clear sandbox exclusions.").withStyle(ChatFormatting.RED))
			}
	}

	private fun addSandboxInteraction(source: CommandSourceStack, playerId: UUID, name: String, position: BlockPos) {
		AiService.addSandboxInteraction(playerId, source.level.dimension(), name, position)
			.onSuccess { sandbox ->
				source.sendSuccess(
					{
						Component.literal("Sandbox interaction added: ").withStyle(ChatFormatting.YELLOW)
							.append(Component.literal("$name=[${position.x}, ${position.y}, ${position.z}]").withStyle(ChatFormatting.WHITE))
							.append(Component.literal(" / total ${sandbox.interactions.size}").withStyle(ChatFormatting.DARK_GRAY))
					},
					false,
				)
			}
			.onFailure { error ->
				source.sendFailure(Component.literal(error.message ?: "Unable to add sandbox interaction.").withStyle(ChatFormatting.RED))
			}
	}

	private fun removeSandboxInteraction(source: CommandSourceStack, name: String) {
		val playerId = Commands.requirePlayerId(source) ?: return
		AiService.removeSandboxInteraction(playerId, name)
			.onSuccess {
				source.sendSuccess(
					{ Component.literal("Sandbox interaction removed: $name").withStyle(ChatFormatting.YELLOW) },
					false,
				)
			}
			.onFailure { error ->
				source.sendFailure(Component.literal(error.message ?: "Unable to remove sandbox interaction.").withStyle(ChatFormatting.RED))
			}
	}

	private fun clearSandboxInteractions(source: CommandSourceStack) {
		val playerId = Commands.requirePlayerId(source) ?: return
		AiService.clearSandboxInteractions(playerId)
			.onSuccess {
				source.sendSuccess(
					{ Component.literal("Sandbox interactions cleared.").withStyle(ChatFormatting.YELLOW) },
					false,
				)
			}
			.onFailure { error ->
				source.sendFailure(Component.literal(error.message ?: "Unable to clear sandbox interactions.").withStyle(ChatFormatting.RED))
			}
	}

	private fun suggestSandboxExclusionNames(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val playerId = context.source.player?.uuid ?: return builder.buildFuture()
		return SharedSuggestionProvider.suggest(
			AiService.sandboxExclusionNames(playerId).getOrElse { emptyList() },
			builder,
		)
	}

	private fun suggestSandboxInteractionNames(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		val playerId = context.source.player?.uuid ?: return builder.buildFuture()
		return SharedSuggestionProvider.suggest(
			AiService.sandboxInteractionNames(playerId).getOrElse { emptyList() },
			builder,
		)
	}

	private fun suggestRendererModes(
		context: CommandContext<CommandSourceStack>,
		builder: SuggestionsBuilder,
	): CompletableFuture<Suggestions> {
		return SharedSuggestionProvider.suggest(
			SandboxManager.RendererMode.entries.map(SandboxManager.RendererMode::commandName),
			builder,
		)
	}
}
