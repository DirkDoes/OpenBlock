package me.wanttobee.openblock.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

object EnvironmentVariables {
	const val OPENBLOCK_FILE_NAME = "openblock.env"
	const val DOTENV_FILE_NAME = ".env"

	private val runtimeOverrides = ConcurrentHashMap<String, String>()

	fun ensureFileExists() {
		ensureFile(defaultOpenBlockPath())
	}

	fun read(): Map<String, String> {
		val merged = linkedMapOf<String, String>()
		merged.putAll(readFile(defaultDotenvPath()).getOrElse { emptyMap() })
		for ((key, value) in readFile(defaultOpenBlockPath()).getOrElse { emptyMap() }) {
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

	fun get(key: String): Result<String> {
		return read().entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
			?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("Unknown environment variable: $key"))
	}

	fun reveal(key: String): Result<String> = get(key)

	fun keySet(): Set<String> = read().keys

	fun setRuntimeOverride(key: String, value: String) {
		runtimeOverrides[key] = value
	}

	fun parseQuotedValue(rawValue: String): Result<String> {
		val trimmed = rawValue.trim()
		if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) {
			return Result.failure(IllegalArgumentException("Value must be wrapped in double quotes."))
		}

		return Result.success(buildString {
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
		})
	}

	private fun defaultOpenBlockPath(): Path = configDirectory().resolve(OPENBLOCK_FILE_NAME)

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
			ANTHROPIC_API_KEY=
			GOOGLE_API_KEY=
		""".trimIndent()
	}

	private fun readFile(path: Path): Result<Map<String, String>> {
		if (!Files.exists(path)) {
			return Result.success(emptyMap())
		}

		return runCatching {
			Files.readAllLines(path)
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
}
