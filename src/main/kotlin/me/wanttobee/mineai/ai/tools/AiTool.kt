package me.wanttobee.mineai.ai.tools

import java.util.UUID

interface AiTool {
	val name: String
	val description: String
	val enabledByDefault: Boolean
	val parameters: List<Parameter>

	fun invoke(playerId: UUID?, rawArguments: Map<String, String>): InvocationResult {
		val validatedArguments = ToolArguments.validate(parameters, rawArguments)
			?: return InvocationResult(
				execution = ExecutionResult(
					payload = mapOf("message" to ToolArguments.validationError(parameters, rawArguments)),
					isError = true,
				),
			)
		return InvocationResult(
			execution = execute(playerId, validatedArguments),
			conversationMessage = conversationMessage(playerId, validatedArguments),
		)
	}

	fun execute(playerId: UUID?, arguments: ToolArguments): ExecutionResult

	fun conversationMessage(playerId: UUID?, arguments: ToolArguments): String? {
		return null
	}

	fun suggestions(playerId: UUID?, parameterIndex: Int, arguments: Map<String, String>): List<Suggestion> {
		return emptyList()
	}

	data class Parameter(
		val name: String,
		val description: String,
		val type: Type = Type.STRING,
		val required: Boolean = true,
	)

	data class Suggestion(
		val value: String,
		val description: String? = null,
	)

	data class ExecutionResult(
		val payload: Map<String, Any?>,
		val isError: Boolean = false,
	) {
		fun asResponseMap(): Map<String, Any?> {
			return if (isError) {
				mapOf("error" to payload)
			} else {
				mapOf("output" to payload)
			}
		}
	}

	data class InvocationResult(
		val execution: ExecutionResult,
		val conversationMessage: String? = null,
	)

	enum class Type {
		STRING,
		UUID,

		;

		fun parse(rawValue: String): Any? {
			return when (this) {
				STRING -> rawValue
				UUID -> try {
					java.util.UUID.fromString(rawValue)
				} catch (_: IllegalArgumentException) {
					null
				}
			}
		}
	}
}
