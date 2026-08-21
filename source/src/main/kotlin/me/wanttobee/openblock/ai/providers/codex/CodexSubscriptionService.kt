package me.wanttobee.openblock.ai.providers.codex

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.wanttobee.openblock.ai.AiActionBarManager
import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.ai.sessions.base.SessionTokenUsage
import me.wanttobee.openblock.ai.toolcalling.ToolManager
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.base.AiToolExecution
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

object CodexSubscriptionService {
	private const val RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"
	private const val MAX_TOOL_CALLS = 50
	private val gson = Gson()
	private val httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(30))
		.build()

	fun accountStatus(): Result<AccountStatus> {
		return CodexSubscriptionAuth.accountStatus().map { status ->
			AccountStatus(status.type, status.email, status.plan, status.loginPending, status.loginError)
		}
	}

	fun requireChatGptAccount(): Result<AccountStatus> {
		return CodexSubscriptionAuth.requireCredentials().map { credentials ->
			AccountStatus("chatgpt", credentials.email, credentials.plan, false, null)
		}
	}

	fun startDeviceLogin(): Result<LoginInstructions> {
		return CodexSubscriptionAuth.startDeviceLogin().map { login ->
			LoginInstructions(login.verificationUrl, login.userCode)
		}
	}

	fun logout(): Result<Unit> = CodexSubscriptionAuth.logout()

	fun generate(
		model: AiModel,
		session: Session,
		generationId: Long,
		enabledTools: List<AiTool>,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): Result<GenerationResult> {
		val credentials = CodexSubscriptionAuth.requireCredentials().getOrElse { return Result.failure(it) }
		val systemPrompt = session.effectiveSystemPrompt().getOrElse { return Result.failure(it) }
		return runCatching {
			generateDirect(
				credentials,
				model,
				session,
				generationId,
				systemPrompt,
				enabledTools,
				onActionChange,
				onMessageAdded,
			)
		}
	}

	fun close() {
		CodexSubscriptionAuth.close()
	}

	private fun generateDirect(
		credentials: CodexSubscriptionAuth.Credentials,
		model: AiModel,
		session: Session,
		generationId: Long,
		systemPrompt: String,
		enabledTools: List<AiTool>,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	): GenerationResult {
		val input = conversationInput(session.messages())
		val requestSessionId = UUID.randomUUID().toString()
		var usage: SessionTokenUsage? = null

		repeat(MAX_TOOL_CALLS + 1) { iteration ->
			if (session.isGenerationInterrupted(generationId)) {
				return GenerationResult("", interrupted = true, usage)
			}

			val response = requestResponse(
				credentials,
				requestSessionId,
				requestBody(model, systemPrompt, input, enabledTools),
			).getOrThrow()
			usage = combineUsage(usage, responseUsage(response))
			val output = response.getAsJsonArray("output") ?: JsonArray()
			val toolCalls = toolCalls(output)

			if (toolCalls.isEmpty()) {
				onActionChange("generating", AiActionBarManager.IndicatorState.PROVIDER_PROGRESS)
				return GenerationResult(
					text = responseText(output).ifBlank { "Codex returned an empty response." },
					interrupted = session.isGenerationInterrupted(generationId),
					usage = usage,
				)
			}

			if (iteration == MAX_TOOL_CALLS) {
				return GenerationResult("Codex Subscription exceeded the tool call limit.", false, usage)
			}
			output.forEach { item -> input.add(item.deepCopy()) }
			executeTools(session, toolCalls, input, onActionChange, onMessageAdded)
		}

		return GenerationResult("Codex Subscription exceeded the tool call limit.", false, usage)
	}

	private fun requestBody(
		model: AiModel,
		systemPrompt: String,
		input: JsonArray,
		enabledTools: List<AiTool>,
	): JsonObject {
		return JsonObject().apply {
			addProperty("model", model.apiName)
			addProperty("store", false)
			addProperty("stream", true)
			addProperty("parallel_tool_calls", true)
			addProperty(
				"instructions",
				"""
				You are the language-model backend for the OpenBlock Minecraft server mod.
				OpenBlock owns the conversation, UI, and tool execution. Answer the Minecraft user directly.
				Only call the functions supplied in this request when a tool is needed.

				$systemPrompt
				""".trimIndent(),
			)
			add("input", input.deepCopy())
			add("include", JsonArray().apply { add("reasoning.encrypted_content") })
			model.reasoning?.value?.let { effort ->
				add("reasoning", JsonObject().apply { addProperty("effort", effort.lowercase()) })
			}
			if (enabledTools.isNotEmpty()) {
				addProperty("tool_choice", "auto")
				add("tools", JsonArray().apply {
					enabledTools.map(CodexProtocol::responseTool).forEach(::add)
				})
			}
		}
	}

	private fun requestResponse(
		credentials: CodexSubscriptionAuth.Credentials,
		sessionId: String,
		body: JsonObject,
	): Result<JsonObject> {
		val first = sendResponse(credentials, sessionId, body)
		val failure = first.exceptionOrNull()
		if (failure !is HttpStatusException || failure.statusCode != 401) return first
		val refreshed = CodexSubscriptionAuth.forceRefresh().getOrElse { return Result.failure(it) }
		return sendResponse(refreshed, sessionId, body)
	}

	private fun sendResponse(
		credentials: CodexSubscriptionAuth.Credentials,
		sessionId: String,
		body: JsonObject,
	): Result<JsonObject> {
		return runCatching {
			val request = HttpRequest.newBuilder(URI.create(RESPONSES_URL))
				.timeout(Duration.ofMinutes(10))
				.header("Authorization", "Bearer ${credentials.accessToken}")
				.header("ChatGPT-Account-ID", credentials.accountId)
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream")
				.header("Originator", "openblock")
				.header("User-Agent", "openblock/1.0.0")
				.header("session_id", sessionId)
				.header("thread_id", sessionId)
				.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
				.build()
			val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
			if (response.statusCode() !in 200..299) {
				throw HttpStatusException(response.statusCode(), apiErrorMessage(response.body()))
			}
			completedResponse(response.body()).getOrThrow()
		}
	}

	private fun completedResponse(body: String): Result<JsonObject> {
		return runCatching {
			if (!body.lineSequence().any { line -> line.startsWith("data:") }) {
				val json = JsonParser.parseString(body).asJsonObject
				return@runCatching json.getAsJsonObject("response") ?: json
			}

			var completed: JsonObject? = null
			var failure: String? = null
			body.lineSequence()
				.filter { line -> line.startsWith("data:") }
				.map { line -> line.removePrefix("data:").trim() }
				.filter { data -> data.isNotEmpty() && data != "[DONE]" }
				.forEach { data ->
					val event = JsonParser.parseString(data).asJsonObject
					when (event.string("type")) {
						"response.completed" -> completed = event.getAsJsonObject("response")
						"response.failed", "response.incomplete", "error" -> {
							failure = event.getAsJsonObject("response")
								?.getAsJsonObject("error")
								?.string("message")
								?: event.getAsJsonObject("error")?.string("message")
								?: event.string("message")
						}
					}
				}
			completed ?: throw IllegalStateException(failure ?: "Codex returned no completed response.")
		}
	}

	private fun conversationInput(messages: List<SessionMessage>): JsonArray {
		return JsonArray().apply {
			messages.forEach { message ->
				when (message.type) {
					SessionMessage.Type.USER -> add(messageInput("user", message.combinedContent()))
					SessionMessage.Type.ASSISTANT -> add(messageInput("assistant", message.combinedContent()))
					SessionMessage.Type.TOOL,
					SessionMessage.Type.ERROR -> Unit
				}
			}
		}
	}

	private fun messageInput(role: String, content: String): JsonObject {
		return JsonObject().apply {
			addProperty("role", role)
			addProperty("content", content)
		}
	}

	private fun toolCalls(output: JsonArray): List<ToolCall> {
		return output.mapNotNull { item ->
			item.takeIf(JsonElement::isJsonObject)?.asJsonObject
				?.takeIf { objectValue -> objectValue.string("type") == "function_call" }
				?.let { objectValue ->
					val name = objectValue.string("name") ?: return@let null
					val callId = objectValue.string("call_id") ?: return@let null
					val arguments = objectValue.string("arguments").orEmpty()
					ToolCall(name, callId, CodexProtocol.arguments(
						runCatching { JsonParser.parseString(arguments) }.getOrElse { JsonObject() }
					))
				}
		}
	}

	private fun executeTools(
		session: Session,
		toolCalls: List<ToolCall>,
		input: JsonArray,
		onActionChange: (String, AiActionBarManager.IndicatorState) -> Unit,
		onMessageAdded: (SessionMessage) -> Unit,
	) {
		val label = toolBatchActionLabel(toolCalls.map(ToolCall::name))
		onActionChange(label, AiActionBarManager.IndicatorState.TOOL_PROCESSING)
		val outcomes = ToolManager.invokeAllParallel(
			session.toolScopeId,
			toolCalls.map { call -> ToolManager.ToolCallRequest(call.name, call.arguments) },
		) { started ->
			started.conversationMessage?.let { content ->
				session.addToolMessage(content)
				session.lastMessage().getOrNull()?.let(onMessageAdded)
			}
		}
		val executions = toolCalls.zip(outcomes).map { (call, outcome) ->
			outcome.invocation.fold(
				onSuccess = { invocation ->
					session.recordToolInvocation(call.name, outcome.arguments, invocation.execution, invocation.conversationMessage)
					invocation.execution
				},
				onFailure = { error ->
					AiToolExecution(
						mapOf("message" to (error.message ?: "Tool invocation failed: ${call.name}")),
						isError = true,
					).also { execution ->
						session.recordToolInvocation(call.name, outcome.arguments, execution, null)
					}
				},
			)
		}
		onActionChange(
			label,
			if (executions.any(AiToolExecution::isError)) {
				AiActionBarManager.IndicatorState.TOOL_ERROR
			} else {
				AiActionBarManager.IndicatorState.TOOL_SUCCESS
			},
		)
		toolCalls.zip(executions).forEach { (call, execution) ->
			input.add(JsonObject().apply {
				addProperty("type", "function_call_output")
				addProperty("call_id", call.callId)
				addProperty("output", gson.toJson(execution.asResponseMap()))
			})
		}
	}

	private fun responseText(output: JsonArray): String {
		return output
			.mapNotNull { item -> item.takeIf(JsonElement::isJsonObject)?.asJsonObject }
			.filter { item -> item.string("type") == "message" }
			.flatMap { item -> item.getAsJsonArray("content")?.toList().orEmpty() }
			.mapNotNull { content -> content.takeIf(JsonElement::isJsonObject)?.asJsonObject }
			.filter { content -> content.string("type") == "output_text" }
			.joinToString("") { content -> content.string("text").orEmpty() }
	}

	private fun toolBatchActionLabel(toolNames: List<String>): String {
		return when (toolNames.size) {
			0 -> "using tools"
			1 -> "using ${toolNames.first()}"
			2 -> "using ${toolNames[0]} + ${toolNames[1]}"
			else -> "using ${toolNames.first()} + ${toolNames.size - 1} more"
		}
	}

	private fun responseUsage(response: JsonObject): SessionTokenUsage? {
		val usage = response.getAsJsonObject("usage") ?: return null
		return SessionTokenUsage(
			inputTokens = usage.long("input_tokens"),
			outputTokens = usage.long("output_tokens"),
			totalTokens = usage.long("total_tokens"),
			cachedInputTokens = usage.getAsJsonObject("input_tokens_details")?.long("cached_tokens"),
			reasoningTokens = usage.getAsJsonObject("output_tokens_details")?.long("reasoning_tokens"),
		)
	}

	private fun combineUsage(first: SessionTokenUsage?, second: SessionTokenUsage?): SessionTokenUsage? {
		if (first == null) return second
		if (second == null) return first
		return SessionTokenUsage(
			inputTokens = sum(first.inputTokens, second.inputTokens),
			outputTokens = sum(first.outputTokens, second.outputTokens),
			totalTokens = sum(first.totalTokens, second.totalTokens),
			cachedInputTokens = sum(first.cachedInputTokens, second.cachedInputTokens),
			reasoningTokens = sum(first.reasoningTokens, second.reasoningTokens),
		)
	}

	private fun sum(first: Long?, second: Long?): Long? {
		return if (first == null && second == null) null else (first ?: 0L) + (second ?: 0L)
	}

	private fun apiErrorMessage(body: String): String {
		return runCatching {
			val json = JsonParser.parseString(body).asJsonObject
			json.getAsJsonObject("error")?.string("message")
				?: json.string("message")
				?: "Codex subscription request failed."
		}.getOrDefault("Codex subscription request failed.")
	}

	private fun JsonObject.string(name: String): String? {
		return get(name)?.takeUnless { it.isJsonNull }?.asString
	}

	private fun JsonObject.long(name: String): Long? {
		return get(name)?.takeUnless { it.isJsonNull }?.asLong
	}

	data class AccountStatus(
		val type: String?,
		val email: String?,
		val plan: String?,
		val loginPending: Boolean,
		val loginError: String?,
	)

	data class LoginInstructions(
		val verificationUrl: String,
		val userCode: String,
	)

	data class GenerationResult(
		val text: String,
		val interrupted: Boolean,
		val usage: SessionTokenUsage?,
	)

	private data class ToolCall(
		val name: String,
		val callId: String,
		val arguments: Map<String, String>,
	)

	private class HttpStatusException(
		val statusCode: Int,
		message: String,
	) : IllegalStateException("$message HTTP $statusCode.")
}
