package me.wanttobee.mineai.ai

import java.util.concurrent.CopyOnWriteArrayList

class Session {
	private val messages = CopyOnWriteArrayList<Message>()

	fun messages(): List<Message> = messages.toList()
	fun lastMessage(): Message? = messages.lastOrNull()

	fun addUserMessage(content: String) {
		messages += Message(Message.Type.USER, content)
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
	) {
		enum class Type {
			USER,
			ASSISTANT,
			ERROR,
		}
	}
}
