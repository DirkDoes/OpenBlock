package me.wanttobee.openblock.ai.toolcalling

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import java.util.UUID

object SandboxToolsSupport {
	fun manageTarget(
		playerId: UUID?,
		action: String,
		name: String,
		position: String?,
	): AiToolExecution {
		val scopedPlayerId = playerId ?: return failedExecution("Sandbox target management requires a bound player.")
		return when (action.trim().lowercase()) {
			"add" -> addTarget(scopedPlayerId, name, position)
			"remove" -> removeTarget(scopedPlayerId, name)
			else -> failedExecution("Invalid action: $action. Use add or remove.")
		}
	}

	private fun addTarget(playerId: UUID, name: String, position: String?): AiToolExecution {
		val source = toolContext(playerId).getOrElse { return failedExecution(it.message ?: "Unknown tool context error.") }
		val rawPosition = position?.trim().orEmpty()
		if (rawPosition.isBlank()) {
			return failedExecution("Position is required when adding a sandbox target.")
		}

		val targetPosition = parseBlockPos(source, rawPosition)
			.getOrElse { return failedExecution("Invalid position: $rawPosition") }
		return AiService.addSandboxTarget(playerId, source.level.dimension(), name, targetPosition)
			.fold(
				onSuccess = { sandbox ->
					AiToolExecution(
						payload = linkedMapOf(
							"action" to "add",
							"name" to name,
							"position" to formatPos(targetPosition),
							"targets" to sandbox.targets.map { (entryName, entryPosition) ->
								mapOf(
									"name" to entryName,
									"position" to formatPos(entryPosition),
								)
							},
						)
					)
				},
				onFailure = { failedExecution(it.message ?: "Unable to add sandbox target.") },
			)
	}

	private fun removeTarget(playerId: UUID, name: String): AiToolExecution {
		return AiService.removeSandboxTarget(playerId, name)
			.fold(
				onSuccess = { sandbox ->
					AiToolExecution(
						payload = linkedMapOf(
							"action" to "remove",
							"name" to name,
							"targets" to sandbox.targets.map { (entryName, entryPosition) ->
								mapOf(
									"name" to entryName,
									"position" to formatPos(entryPosition),
								)
							},
						)
					)
				},
				onFailure = { failedExecution(it.message ?: "Unable to remove sandbox target.") },
			)
	}

	private fun toolContext(playerId: UUID): Result<CommandSourceStack> {
		val server = OpenBlock.currentServer().getOrElse { return Result.failure(it) }
		val player = server.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Bound player is not online."))
		return Result.success(player.createCommandSourceStack())
	}

	private fun parseBlockPos(source: CommandSourceStack, rawPosition: String): Result<BlockPos> {
		val trimmed = rawPosition.trim()
		if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) {
			return Result.failure(IllegalArgumentException("Position must be a comma-separated x,y,z string."))
		}

		val parts = trimmed.split(',')
		if (parts.size != 3 || parts.any { it.isBlank() }) {
			return Result.failure(IllegalArgumentException("Position must be a comma-separated x,y,z string."))
		}

		val normalized = parts.joinToString(" ")
		return runCatching {
			BlockPosArgument.blockPos()
				.parse(StringReader(normalized))
				.getBlockPos(source)
		}.fold(
			onSuccess = { Result.success(it) },
			onFailure = {
				val message = if (it is CommandSyntaxException) {
					"Position must be a valid block position."
				} else {
					it.message ?: "Position must be a valid block position."
				}
				Result.failure(IllegalArgumentException(message, it))
			},
		)
	}

	private fun formatPos(position: BlockPos): String {
		return "${position.x} ${position.y} ${position.z}"
	}

	private fun failedExecution(message: String): AiToolExecution {
		return AiToolExecution(
			payload = mapOf("message" to message),
			isError = true,
		)
	}
}
