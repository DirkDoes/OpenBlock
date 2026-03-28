package me.wanttobee.mineai

import com.mojang.brigadier.StringReader
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.Path

object EnvironmentVariables {
	const val MINEAI_FILE_NAME = "mineai.env"
	const val DOTENV_FILE_NAME = ".env"

	private val configDir: Path
		get() = FabricLoader.getInstance().configDir

	private val mineAiFilePath: Path
		get() = configDir.resolve(MINEAI_FILE_NAME)

	private val dotEnvFilePath: Path
		get() = configDir.resolve(DOTENV_FILE_NAME)

	private val runtimeOverrides = ConcurrentHashMap<String, String>()

	private val defaultContents = """
		OPENAI_API_KEY=
		OPENAI_MODEL=gpt-5.2
		ANTHROPIC_API_KEY=
		ANTHROPIC_MODEL=claude-sonnet-4-20250514
		GOOGLE_API_KEY=
		GOOGLE_MODEL=gemini-2.5-flash
	""".trimIndent() + "\n"

	fun ensureFileExists() {
		ensureFileExists(dotEnvFilePath)
		ensureFileExists(mineAiFilePath)
	}

	fun read(): Map<String, String> {
		ensureFileExists()

		val dotEnvVariables = readFile(dotEnvFilePath)
		val mineAiVariables = readFile(mineAiFilePath)
		val variables = linkedMapOf<String, String>()
		variables.putAll(dotEnvVariables)

		for ((key, value) in mineAiVariables) {
			if (value.isNotBlank()) {
				variables[key] = value
			} else if (!variables.containsKey(key)) {
				variables[key] = value
			}
		}

		for ((key, value) in runtimeOverrides) {
			variables[key] = value
		}

		return variables
	}

	fun keySet(): Set<String> = read().keys

	fun get(name: String): String? = read()[name]

	fun setRuntimeOverride(name: String, value: String) {
		runtimeOverrides[name] = value
	}

	fun reveal(name: String): String? = read()[name]

	fun parseQuotedValue(rawValue: String): String? {
		if (!rawValue.startsWith("\"")) {
			return null
		}

		return try {
			val reader = StringReader(rawValue)
			val parsed = reader.readString()
			if (reader.canRead()) null else parsed
		} catch (_: Exception) {
			null
		}
	}

	private fun ensureFileExists(path: Path) {
		if (Files.exists(path)) {
			return
		}

		Files.createDirectories(path.parent)
		Files.writeString(path, defaultContents, StandardCharsets.UTF_8)
	}

	private fun readFile(path: Path): Map<String, String> {
		val variables = linkedMapOf<String, String>()
		for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
			val trimmedLine = line.trim()
			if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
				continue
			}

			val separatorIndex = trimmedLine.indexOf('=')
			if (separatorIndex <= 0) {
				continue
			}

			val key = trimmedLine.substring(0, separatorIndex).trim()
			val value = trimmedLine.substring(separatorIndex + 1).trim()
			variables[key] = value
		}

		return variables
	}
}
