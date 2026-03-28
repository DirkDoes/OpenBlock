package me.wanttobee.mineai.ai.providers

import me.wanttobee.mineai.ai.sessions.AiModel
import me.wanttobee.mineai.ai.sessions.Session
import net.minecraft.ChatFormatting

interface AiProvider {
	val name: String
	val displayName: String
	val apiKeyVariable: String
	val modelVariable: String
	val defaultModel: String
	val models: List<AiModel>
	val chatColor: ChatFormatting
	val progressColorA: Int
	val progressColorB: Int

	fun ping()

	fun startingAction(model: AiModel): String {
		return if (model.usesReasoning()) "thinking" else "generating"
	}

	fun applyReasoning(model: AiModel, value: String?): AiModel? {
		return model
	}

	fun reasoningSuggestions(model: AiModel): List<ReasoningSuggestion> {
		return emptyList()
	}

	fun describeReasoning(model: AiModel): String? {
		return null
	}

	fun generate(model: AiModel, session: Session, onActionChange: (String) -> Unit = {}): Boolean

	data class ReasoningSuggestion(
		val value: String,
		val description: String? = null,
	)
}
