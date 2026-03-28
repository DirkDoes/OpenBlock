package me.wanttobee.mineai

import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object EnvironmentVariables {
	const val FILE_NAME = "mineai.env"

	private val filePath: Path
		get() = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

	private val defaultContents = """
		EXAMPLE_VALUE=hello from MineAI
	""".trimIndent() + "\n"

	fun ensureFileExists() {
		val path = filePath

		if (Files.exists(path)) {
			return
		}

		Files.createDirectories(path.parent)
		Files.writeString(path, defaultContents, StandardCharsets.UTF_8)
	}

	fun read(): Map<String, String> {
		ensureFileExists()

		val variables = linkedMapOf<String, String>()
		for (line in Files.readAllLines(filePath, StandardCharsets.UTF_8)) {
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
