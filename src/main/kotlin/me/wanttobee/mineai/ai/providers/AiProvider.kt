package me.wanttobee.mineai.ai.providers

import me.wanttobee.mineai.ai.AiModel
import me.wanttobee.mineai.ai.Session
import net.minecraft.ChatFormatting

interface AiProvider {
	val name: String
	val displayName: String
	val apiKeyVariable: String
	val modelVariable: String
	val defaultModel: String
	val models: List<AiModel>
	val chatColor: ChatFormatting

	fun ping()
	fun generateResponse(model: AiModel, session: Session): Boolean
}
