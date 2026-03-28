package me.wanttobee.mineai.ai.sessions

data class AiModel(
	val apiName: String,
	val displayName: String,
	val reasoningSupport: ReasoningSupport = ReasoningSupport.unsupported(),
	val reasoning: Reasoning? = null,
) {
	val displaySlug: String
		get() = displayName.replace(' ', '-')

	fun usesReasoning(): Boolean {
		return reasoning?.isEnabled() == true
	}

	data class Reasoning(
		val value: String? = null,
		val budgetTokens: Int? = null,
		val includeThoughts: Boolean? = null,
	) {
		fun isEnabled(): Boolean {
			return when {
				budgetTokens != null -> budgetTokens > 0
				value != null -> !value.equals("none", ignoreCase = true)
				else -> false
			}
		}
	}

	data class ReasoningSupport(
		val kind: Kind,
		val values: List<String> = emptyList(),
		val numericExamples: List<Int> = emptyList(),
		val allowsNone: Boolean = false,
	) {
		fun supportsReasoning(): Boolean {
			return kind != Kind.UNSUPPORTED
		}

		enum class Kind {
			UNSUPPORTED,
			TEXT,
			NUMBER,
		}

		companion object {
			fun unsupported(): ReasoningSupport {
				return ReasoningSupport(Kind.UNSUPPORTED)
			}

			fun text(values: List<String>, allowsNone: Boolean): ReasoningSupport {
				return ReasoningSupport(
					kind = Kind.TEXT,
					values = values,
					allowsNone = allowsNone,
				)
			}

			fun number(numericExamples: List<Int>, allowsNone: Boolean): ReasoningSupport {
				return ReasoningSupport(
					kind = Kind.NUMBER,
					numericExamples = numericExamples,
					allowsNone = allowsNone,
				)
			}
		}
	}
}
