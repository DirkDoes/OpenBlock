package me.wanttobee.openblock.ai.toolcalling.base

data class AiToolParameter(
	val name: String,
	val description: String,
	val type: ParameterType = ParameterType.STRING,
	val required: Boolean = true,
	val manualInput: ManualInput = ManualInput.WORD,
) {
	enum class ParameterType {
		STRING,
		UUID,

		;

		fun parse(rawValue: String): Result<Any> {
			return when (this) {
				STRING -> Result.success(rawValue)
				UUID -> runCatching { java.util.UUID.fromString(rawValue) }
					.fold(
						onSuccess = Result.Companion::success,
						onFailure = { Result.failure(IllegalArgumentException("Invalid UUID: $rawValue")) },
					)
			}
		}
	}

	enum class ManualInput {
		WORD,
		BLOCK_POS,
	}
}
