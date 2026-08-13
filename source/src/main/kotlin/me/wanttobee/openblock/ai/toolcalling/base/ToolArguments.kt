package me.wanttobee.openblock.ai.toolcalling.base

class ToolArguments private constructor(
	@PublishedApi internal val values: Map<String, Any>,
) {
	inline fun <reified T : Any> get(name: String): Result<T> {
		val value = values[name]
			?: return Result.failure(NoSuchElementException("Missing validated argument: $name"))
		return (value as? T)?.let(Result.Companion::success)
			?: Result.failure(IllegalStateException("Validated argument $name is not a ${T::class.simpleName}"))
	}

	inline fun <reified T : Any> getOrNull(name: String): Result<T?> {
		val value = values[name] ?: return Result.success(null)
		return (value as? T)?.let { Result.success(it) }
			?: Result.failure(IllegalStateException("Validated argument $name is not a ${T::class.simpleName}"))
	}

	companion object {
		fun validate(parameters: List<AiToolParameter>, rawArguments: Map<String, String>): Result<ToolArguments> {
			val values = linkedMapOf<String, Any>()
			for (parameter in parameters) {
				val rawValue = rawArguments[parameter.name]
				if (rawValue == null) {
					if (parameter.required) {
						return Result.failure(NoSuchElementException("Missing required argument: ${parameter.name}"))
					}
					continue
				}

				val parsedValue = parameter.type.parse(rawValue).getOrElse { return Result.failure(it) }
				values[parameter.name] = parsedValue
			}
			return Result.success(ToolArguments(values))
		}

		fun validationError(parameters: List<AiToolParameter>, rawArguments: Map<String, String>): String {
			for (parameter in parameters) {
				val rawValue = rawArguments[parameter.name]
				if (rawValue == null) {
					if (parameter.required) {
						return "Missing required argument: ${parameter.name}"
					}
					continue
				}

				if (parameter.type.parse(rawValue).isFailure) {
					return when (parameter.type) {
						AiToolParameter.ParameterType.STRING -> "Invalid value for ${parameter.name}"
						AiToolParameter.ParameterType.UUID -> "Invalid UUID for ${parameter.name}: $rawValue"
					}
				}
			}
			return "Invalid tool arguments."
		}
	}
}
