package me.wanttobee.mineai.ai

object AiService {
	fun generateResponse(target: AiTarget, prompt: String): String {
		return target.provider.generateResponse(target.modelId, prompt)
	}

	fun pingProviders(): List<AiProviderStatus> {
		return Providers.all.map { provider ->
			try {
				provider.ping()
				AiProviderStatus(provider, true, "ready")
			} catch (exception: Exception) {
				AiProviderStatus(provider, false, exception.message ?: "request failed")
			}
		}
	}
}
