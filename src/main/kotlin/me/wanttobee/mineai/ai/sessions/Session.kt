package me.wanttobee.mineai.ai.sessions

import me.wanttobee.mineai.ai.tools.PlayerContextCapturer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class Session(
	val systemPrompt: String? = null,
	val boundPlayerId: UUID? = null,
) {
	private val messages = CopyOnWriteArrayList<Message>()

	fun messages(): List<Message> = messages.toList()
	fun lastMessage(): Message? = messages.lastOrNull()
	fun effectiveSystemPrompt(): String? {
		val basePrompt = systemPrompt?.trim().orEmpty()
		if (boundPlayerId == null) {
			return basePrompt.ifBlank { null }
		}

		val username = PlayerContextCapturer.capture(boundPlayerId)?.username
		val bindingPrompt =
			"Session binding: this conversation is with player UUID $boundPlayerId" +
				(username?.let { " (username: $it)" } ?: "") +
				".\n" +
				"User messages may begin with live player context in the form " +
				"[game mode - pos(x, y, z)/fac(yaw, pitch) - dimension].\n" +
				"If the player is in survival or adventure mode, the prefix may also include " +
				"hp(...), xp(...), and hunger(...).\n" +
				"Treat that prefix as authoritative context about the player speaking to you."

		return listOf(basePrompt, bindingPrompt)
			.filter { it.isNotBlank() }
			.joinToString("\n\n")
	}

	fun addUserMessage(content: String) {
		val hiddenContent = boundPlayerId
			?.let(PlayerContextCapturer::capture)
			?.let { context -> "${context.promptPrefix()} $content" }
		messages += Message(
			type = Message.Type.USER,
			content = content,
			hiddenContent = hiddenContent,
		)
	}

	fun addAssistantMessage(content: String) {
		messages += Message(Message.Type.ASSISTANT, content)
	}

	fun addErrorMessage(content: String) {
		messages += Message(Message.Type.ERROR, content)
	}

	data class Message(
		val type: Type,
		val content: String,
		val hiddenContent: String? = null,
	) {
		fun combinedContent(): String {
			return hiddenContent?.takeIf { it.isNotBlank() }?.let { "$it\n$content" } ?: content
		}

		enum class Type {
			USER,
			ASSISTANT,
			ERROR,
		}
	}
}
