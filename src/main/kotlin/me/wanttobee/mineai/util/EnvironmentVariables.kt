package me.wanttobee.mineai.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

object EnvironmentVariables {
	const val MINEAI_FILE_NAME = "mineai.env"
	const val DOTENV_FILE_NAME = ".env"

	private val runtimeOverrides = ConcurrentHashMap<String, String>()

	fun ensureFileExists() {
		ensureFile(defaultDotenvPath())
		ensureFile(defaultMineAiPath())
	}

	fun read(): Map<String, String> {
		val merged = linkedMapOf<String, String>()
		merged.putAll(readFile(defaultDotenvPath()))
		for ((key, value) in readFile(defaultMineAiPath())) {
			if (value.isNotBlank()) {
				merged[key] = value
			} else if (!merged.containsKey(key)) {
				merged[key] = value
			}
		}
		for ((key, value) in runtimeOverrides) {
			merged[key] = value
		}
		return TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(merged) }
	}

	fun get(key: String): String? = read().entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

	fun reveal(key: String): String? = get(key)

	fun keySet(): Set<String> = read().keys

	fun setRuntimeOverride(key: String, value: String) {
		runtimeOverrides[key] = value
	}

	fun parseQuotedValue(rawValue: String): String? {
		val trimmed = rawValue.trim()
		if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) {
			return null
		}

		return buildString {
			var escaping = false
			for (character in trimmed.substring(1, trimmed.length - 1)) {
				if (escaping) {
					append(
						when (character) {
							'\\' -> '\\'
							'"' -> '"'
							'n' -> '\n'
							't' -> '\t'
							'r' -> '\r'
							else -> character
						}
					)
					escaping = false
				} else if (character == '\\') {
					escaping = true
				} else {
					append(character)
				}
			}

			if (escaping) {
				append('\\')
			}
		}
	}

	private fun defaultMineAiPath(): Path = configDirectory().resolve(MINEAI_FILE_NAME)

	private fun defaultDotenvPath(): Path = configDirectory().resolve(DOTENV_FILE_NAME)

	private fun configDirectory(): Path = Paths.get("config")

	private fun ensureFile(path: Path) {
		if (Files.exists(path)) {
			return
		}

		Files.createDirectories(path.parent)
		Files.writeString(path, defaultContents())
	}

	private fun defaultContents(): String {
		return """
			OPENAI_API_KEY=
			OPENAI_MODEL=gpt-5.2
			ANTHROPIC_API_KEY=
			ANTHROPIC_MODEL=claude-sonnet-4-20250514
			GOOGLE_API_KEY=
			GOOGLE_MODEL=gemini-2.5-flash
			
		""".trimIndent()
	}

	private fun readFile(path: Path): Map<String, String> {
		if (!Files.exists(path)) {
			return emptyMap()
		}

		return Files.readAllLines(path)
			.asSequence()
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.startsWith('#') }
			.mapNotNull { line ->
				val separatorIndex = line.indexOf('=')
				if (separatorIndex <= 0) {
					null
				} else {
					val key = line.substring(0, separatorIndex).trim()
					val value = line.substring(separatorIndex + 1)
					key to value
				}
			}
			.toMap(TreeMap(String.CASE_INSENSITIVE_ORDER))
	}
}
