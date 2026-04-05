package me.wanttobee.openblock.ai.toolcalling

import me.wanttobee.openblock.ai.sessions.AiSessionManager
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import me.wanttobee.openblock.ai.toolcalling.base.AiToolInvocation
import me.wanttobee.openblock.ai.toolcalling.base.ToolArguments
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.toolcalling.tools.ExecuteCommandTool
import me.wanttobee.openblock.ai.toolcalling.tools.FillBlocksTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetBlockDetailsTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetBlocksTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetCommandDocumentationTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetOnlinePlayersTool
import me.wanttobee.openblock.ai.toolcalling.tools.GetPlayerDetailsTool
import me.wanttobee.openblock.ai.toolcalling.tools.InteractBlockTool
import me.wanttobee.openblock.ai.toolcalling.tools.PlaceBlockTool
import me.wanttobee.openblock.ai.toolcalling.tools.PopulateContainerTool
import me.wanttobee.openblock.ai.toolcalling.tools.SandboxTargetTool
import me.wanttobee.openblock.ai.toolcalling.tools.WatchTool
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object ToolManager {
	private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
		Thread(runnable, "openblock-tool").apply {
			isDaemon = true
		}
	}

	private val tools = listOf(
		GetOnlinePlayersTool,
		GetPlayerDetailsTool,

		GetCommandDocumentationTool,
		ExecuteCommandTool,

		GetBlockDetailsTool,
		GetBlocksTool,
		WatchTool,
		InteractBlockTool,
		SandboxTargetTool,
		PlaceBlockTool,
		PopulateContainerTool,
		FillBlocksTool,
	)

	fun allTools(): List<AiTool> = tools

	fun toolNames(): List<String> = tools.map(AiTool::name)

	fun defaultEnabledToolNames(): Set<String> {
		return tools.filter(AiTool::enabledByDefault).mapTo(linkedSetOf(), AiTool::name)
	}

	fun getTool(name: String): AiTool? {
		if (name.equals("get_block_area", ignoreCase = true)) {
			return GetBlocksTool
		}
		if (name.equals("observe_state", ignoreCase = true)) {
			return WatchTool
		}
		if (name.equals("sandbox_interaction", ignoreCase = true)) {
			return SandboxTargetTool
		}
		return tools.firstOrNull { it.name.equals(name, ignoreCase = true) }
	}

	fun isEnabled(playerId: UUID?, name: String): Boolean {
		val tool = getTool(name) ?: return false
		if (playerId == null) {
			return tool.enabledByDefault
		}

		val session = AiSessionManager.getSession(playerId).getOrNull() ?: return tool.enabledByDefault
		return session.enabledToolNames().any { enabledName ->
			getTool(enabledName)?.name == tool.name
		}
	}

	fun enabledTools(session: Session): List<AiTool> {
		return tools.filter { tool ->
			session.enabledToolNames().any { enabledName ->
				getTool(enabledName)?.name == tool.name
			}
		}
	}

	fun setEnabled(playerId: UUID?, name: String, enabled: Boolean): Boolean {
		val tool = getTool(name) ?: return false
		val scopedPlayerId = playerId ?: return false
		val session = AiSessionManager.getSession(scopedPlayerId).getOrNull() ?: return false
		session.updateToolState(tool.name, enabled)
		return true
	}

	fun invoke(boundedPlayerId: UUID?, name: String, arguments: Map<String, String>): Result<AiToolInvocation> {
		val prepared = prepareInvocation(boundedPlayerId, name, arguments)
			.getOrElse { return Result.failure(it) }
		return executePreparedInvocation(boundedPlayerId, prepared)
	}

	fun execute(boundedPlayerId: UUID?, name: String, arguments: Map<String, String>): Result<AiToolExecution> {
		return invoke(boundedPlayerId, name, arguments).map(AiToolInvocation::execution)
	}

	fun invokeAllParallel(
		boundedPlayerId: UUID?,
		requests: List<ToolCallRequest>,
		onInvocation: (ToolCallStarted) -> Unit = {},
	): List<ToolCallOutcome> {
		val futures = requests.map { request ->
			scheduleInvocation(boundedPlayerId, request, onInvocation)
		}
		return futures.map(CompletableFuture<ToolCallOutcome>::join)
	}

	private fun scheduleInvocation(
		boundedPlayerId: UUID?,
		request: ToolCallRequest,
		onInvocation: (ToolCallStarted) -> Unit,
	): CompletableFuture<ToolCallOutcome> {
		val prepared = prepareInvocation(boundedPlayerId, request.name, request.arguments)
		if (prepared.isFailure) {
			val error = prepared.exceptionOrNull() ?: NoSuchElementException("Unknown tool: ${request.name}")
			return CompletableFuture.completedFuture(
				ToolCallOutcome(
					name = request.name,
					arguments = request.arguments,
					invocation = Result.failure(error),
				)
			)
		}
		val invocation = prepared.getOrThrow()
		onInvocation(
			ToolCallStarted(
				name = request.name,
				arguments = request.arguments,
				conversationMessage = invocation.conversationMessage,
			)
		)

		if (invocation.tool.runsAsync) {
			return CompletableFuture.supplyAsync(
				{
					ToolCallOutcome(
						name = request.name,
						arguments = request.arguments,
						invocation = executePreparedInvocation(boundedPlayerId, invocation),
					)
				},
				executor,
			)
		}

		val server = OpenBlock.currentServer().getOrElse { error ->
			return CompletableFuture.completedFuture(
				ToolCallOutcome(
					name = request.name,
					arguments = request.arguments,
					invocation = Result.failure(error),
				)
			)
		}
		val future = CompletableFuture<ToolCallOutcome>()
		server.execute {
			future.complete(
				ToolCallOutcome(
					name = request.name,
					arguments = request.arguments,
					invocation = executePreparedInvocation(boundedPlayerId, invocation),
				)
			)
		}
		return future
	}

	private fun prepareInvocation(
		boundedPlayerId: UUID?,
		name: String,
		arguments: Map<String, String>,
	): Result<PreparedToolInvocation> {
		val tool = getTool(name)
			?: return Result.failure(NoSuchElementException("Unknown tool: $name"))
		val validatedArguments = ToolArguments.validate(tool.parameters, arguments)
			.getOrElse { return Result.failure(it) }
		val conversationMessage = tool.conversationMessage(boundedPlayerId, validatedArguments)
			.getOrElse { return Result.failure(it) }
		return Result.success(
			PreparedToolInvocation(
				tool = tool,
				validatedArguments = validatedArguments,
				conversationMessage = conversationMessage,
			)
		)
	}

	private fun executePreparedInvocation(
		boundedPlayerId: UUID?,
		prepared: PreparedToolInvocation,
	): Result<AiToolInvocation> {
		return prepared.tool.execute(boundedPlayerId, prepared.validatedArguments).map { execution ->
			AiToolInvocation(
				execution = execution,
				conversationMessage = prepared.conversationMessage,
			)
		}
	}

	data class ToolCallRequest(
		val name: String,
		val arguments: Map<String, String>,
	)

	data class ToolCallStarted(
		val name: String,
		val arguments: Map<String, String>,
		val conversationMessage: String?,
	)

	data class ToolCallOutcome(
		val name: String,
		val arguments: Map<String, String>,
		val invocation: Result<AiToolInvocation>,
	)

	private data class PreparedToolInvocation(
		val tool: AiTool,
		val validatedArguments: ToolArguments,
		val conversationMessage: String?,
	)
}
