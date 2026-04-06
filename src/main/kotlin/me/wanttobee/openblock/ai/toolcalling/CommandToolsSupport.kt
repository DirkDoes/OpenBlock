package me.wanttobee.openblock.ai.toolcalling

import com.mojang.brigadier.tree.CommandNode
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.sessions.AiSessionManager
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import net.minecraft.commands.CommandResultCallback
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.util.UUID

object CommandToolsSupport {
	private val defaultAllowedRoots = setOf(
		"advancement", "attribute", "bossbar", "clear", "damage", "data", "datapack",
		"defaultgamemode", "difficulty", "effect", "enchant", "execute", "experience", "xp",
		"forceload", "function", "gamemode", "gamerule", "give", "item", "kill", "kick",
		"list", "locate", "loot", "me", "msg", "tell", "w", "particle",
		"playsound", "recipe", "ride", "say", "schedule", "scoreboard", "seed",
		"setworldspawn", "spawnpoint", "spectate", "spreadplayers", "summon", "tag", "team",
		"teammsg", "teleport", "tp", "tellraw", "time", "title", "trigger", "weather",
	)

	fun defaultAllowedCommandNames(): Set<String> = defaultAllowedRoots

	fun availableCommands(playerId: UUID? = null): Result<List<String>> {
		return allRegisteredCommands().map { commands ->
			commands.filter { command -> isAllowed(playerId, command) }
		}
	}

	fun allRegisteredCommands(): Result<List<String>> {
		return OpenBlock.currentServer().map { server ->
			server.commands.dispatcher.root.children
				.map(CommandNode<CommandSourceStack>::getName)
				.sorted()
		}
	}

	fun isAllowed(playerId: UUID? = null, commandName: String): Boolean {
		val rootName = normalizeRoot(commandName) ?: return false
		if (playerId == null) {
			return defaultAllowedRoots.contains(rootName)
		}

		val session = AiSessionManager.findSessionForScope(playerId)
			?: AiSessionManager.getSession(playerId).getOrNull()
			?: return defaultAllowedRoots.contains(rootName)
		return rootName in session.allowedCommandNames()
	}

	fun setAllowed(playerId: UUID?, commandName: String, allowed: Boolean): Result<Boolean> {
		val rootName = normalizeRoot(commandName) ?: return Result.success(false)
		val scopedPlayerId = playerId ?: return Result.success(false)
		val session = AiSessionManager.findSessionForScope(scopedPlayerId)
			?: AiSessionManager.getSession(scopedPlayerId).getOrElse { return Result.failure(it) }
		return allRegisteredCommands().map { registeredCommands ->
			if (rootName !in registeredCommands && rootName !in defaultAllowedRoots) {
				false
			} else {
				session.updateCommandState(rootName, allowed)
				true
			}
		}
	}

	fun commandEntries(playerId: UUID? = null): Result<List<CommandEntry>> {
		return allRegisteredCommands().map { commands ->
			commands.map { command ->
				CommandEntry(
					name = command,
					allowed = isAllowed(playerId, command),
					defaultAllowed = defaultAllowedRoots.contains(command),
				)
			}
		}
	}

	fun documentation(playerId: UUID?, commandName: String): Result<AiToolExecution> {
		val rootName = normalizeRoot(commandName)
			?: return Result.success(failedExecution("Command name cannot be blank."))
		if (!isAllowed(playerId, rootName)) {
			return Result.success(failedExecution("Command is not allowed for AI documentation: $rootName"))
		}

		val server = OpenBlock.currentServer().getOrElse {
			return Result.success(failedExecution(it.message ?: "Server is not available."))
		}
		val dispatcher = server.commands.dispatcher
		val node = dispatcher.root.getChild(rootName)
			?: return Result.success(failedExecution("Command is not currently registered: $rootName"))
		val source = createCommandSource(server, playerId)

		val smartUsage = dispatcher.getSmartUsage(node, source)
			.values
			.map { usage -> prefixedUsage(rootName, usage) }
			.distinct()
			.sorted()
		val allUsage = dispatcher.getAllUsage(node, source, false)
			.map { usage -> prefixedUsage(rootName, usage) }
			.distinct()
			.sorted()

		return Result.success(
			AiToolExecution(
				payload = linkedMapOf(
					"command" to rootName,
					"available" to true,
					"subcommands" to node.children.map(CommandNode<CommandSourceStack>::getName).sorted(),
					"smart_usage" to smartUsage,
					"all_usage" to allUsage,
				)
			)
		)
	}

	fun execute(playerId: UUID?, command: String): Result<AiToolExecution> {
		val normalizedCommand = normalizeCommand(command)
			?: return Result.success(failedExecution("Command cannot be blank."))
		validateExecutableCommand(playerId, normalizedCommand).getOrElse {
			return Result.success(failedExecution(it.message ?: "Command validation failed."))
		}

		return runCommand(playerId, normalizedCommand)
	}

	internal fun executeInternal(playerId: UUID?, command: String): Result<AiToolExecution> {
		val normalizedCommand = normalizeCommand(command)
			?: return Result.success(failedExecution("Command cannot be blank."))
		return runCommand(playerId, normalizedCommand)
	}

	private fun runCommand(playerId: UUID?, normalizedCommand: String): Result<AiToolExecution> {
		val server = OpenBlock.currentServer().getOrElse {
			return Result.success(failedExecution(it.message ?: "Server is not available."))
		}
		val output = mutableListOf<String>()
		var success = false
		var resultCount = 0
		val source = createCommandSource(server, playerId, output).withCallback { succeeded, result ->
            success = succeeded
            resultCount = result
        }

        return try {
			server.commands.performPrefixedCommand(source, normalizedCommand)
			Result.success(
				AiToolExecution(
					payload = linkedMapOf(
						"command" to "/$normalizedCommand",
						"success" to success,
						"result_count" to resultCount,
						"output" to output,
					)
				)
			)
		} catch (exception: Exception) {
			Result.success(failedExecution(exception.message ?: "Unknown command execution error."))
		}
	}

	internal fun createCommandSource(
		server: net.minecraft.server.MinecraftServer,
		playerId: UUID?,
		output: MutableList<String> = mutableListOf(),
	): CommandSourceStack {
		val capturingSource = object : CommandSource {
			override fun sendSystemMessage(component: Component) {
				output += component.string
			}

			override fun acceptsSuccess(): Boolean = true
			override fun acceptsFailure(): Boolean = true
			override fun shouldInformAdmins(): Boolean = false
		}

		var source = server.createCommandSourceStack().withSource(capturingSource)
		val player = playerId?.let(server.playerList::getPlayer)
		if (player != null) {
			source = player.createCommandSourceStack()
				.withSource(capturingSource)
		}
		return source
	}

	private fun prefixedUsage(rootName: String, usage: String): String {
		val trimmed = usage.trim()
		return when {
			trimmed.isEmpty() -> "/$rootName"
			trimmed.startsWith("/") -> trimmed
			trimmed.startsWith(rootName) -> "/$trimmed"
			else -> "/$rootName $trimmed"
		}
	}

	private fun normalizeRoot(commandName: String): String? {
		return commandName.trim()
			.removePrefix("/")
			.substringBefore(' ')
			.lowercase()
			.ifBlank { null }
	}

	private fun normalizeCommand(command: String): String? {
		return command.trim()
			.removePrefix("/")
			.ifBlank { null }
	}

	private fun validateExecutableCommand(playerId: UUID?, command: String): Result<Unit> {
		val rootName = command.substringBefore(' ')
		if (!isAllowed(playerId, rootName)) {
			return Result.failure(IllegalArgumentException("Command is not allowed for AI execution: $rootName"))
		}

		if (rootName != "execute") {
			return Result.success(Unit)
		}

		val runCommand = nestedRunCommand(command) ?: return Result.success(Unit)
		return validateExecutableCommand(playerId, runCommand)
	}

	private fun nestedRunCommand(command: String): String? {
		val tokens = command.split(Regex("\\s+"))
		val runIndex = tokens.indexOfFirst { it == "run" }
		if (runIndex < 0 || runIndex == tokens.lastIndex) {
			return null
		}
		return tokens.drop(runIndex + 1)
			.joinToString(" ")
			.ifBlank { null }
	}

	private fun failedExecution(message: String): AiToolExecution {
		return AiToolExecution(
			payload = mapOf("message" to message),
			isError = true,
		)
	}

	data class CommandEntry(
		val name: String,
		val allowed: Boolean,
		val defaultAllowed: Boolean,
	)
}
