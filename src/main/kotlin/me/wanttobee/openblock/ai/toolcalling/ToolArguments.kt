package me.wanttobee.openblock.ai.toolcalling

class ToolArguments private constructor(
	@PublishedApi internal val values: Map<String, Any>,
) {
	inline fun <reified T : Any> get(name: String): T {
		val value = values[name]
			?: error("Missing validated argument: $name")
		return value as? T
			?: error("Validated argument $name is not a ${T::class.simpleName}")
	}

	inline fun <reified T : Any> getOrNull(name: String): T? {
		val value = values[name] ?: return null
		return value as? T
	}

	companion object {
		fun validate(parameters: List<AiTool.Parameter>, rawArguments: Map<String, String>): ToolArguments? {
			val values = linkedMapOf<String, Any>()
			for (parameter in parameters) {
				val rawValue = rawArguments[parameter.name]
				if (rawValue == null) {
					if (parameter.required) {
						return null
					}
					continue
				}

				val parsedValue = parameter.type.parse(rawValue) ?: return null
				values[parameter.name] = parsedValue
			}
			return ToolArguments(values)
		}

		fun validationError(parameters: List<AiTool.Parameter>, rawArguments: Map<String, String>): String {
			for (parameter in parameters) {
				val rawValue = rawArguments[parameter.name]
				if (rawValue == null) {
					if (parameter.required) {
						return "Missing required argument: ${parameter.name}"
					}
					continue
				}

				if (parameter.type.parse(rawValue) == null) {
					return when (parameter.type) {
						AiTool.Type.STRING -> "Invalid value for ${parameter.name}"
						AiTool.Type.UUID -> "Invalid UUID for ${parameter.name}: $rawValue"
					}
				}
			}
			return "Invalid tool arguments."
		}
	}
}
