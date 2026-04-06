package me.wanttobee.openblock.benchmarking

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.wanttobee.openblock.OpenBlock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object BenchmarkTagManager {
	private const val TAGS_FILE = "openblock-data/benchmarks/tags.json"
	private const val LEGACY_TAGS_DIR = "openblock-data/benchmarks/tags"
	private const val LEGACY_TAG_SUFFIX = ".tag"
	private val gson = GsonBuilder()
		.setPrettyPrinting()
		.create()

	fun listTags(): Result<List<TagEntry>> {
		return loadSnapshot().map { snapshot ->
			snapshot.entries.values
				.sortedBy(TagEntry::name)
		}
	}

	fun createTag(rawName: String): Result<TagEntry> {
		val storedName = storedName(rawName).getOrElse { return Result.failure(it) }
		val snapshot = loadSnapshot().getOrElse { return Result.failure(it) }
		if (snapshot.entries.values.any { it.name == storedName }) {
			return Result.failure(IllegalArgumentException("A benchmark tag with that name already exists."))
		}

		val createdEntry = TagEntry(
			id = storedName,
			name = storedName,
		)
		return saveSnapshot(snapshot.copy(entries = snapshot.entries + (createdEntry.id to createdEntry)))
			.map { createdEntry }
	}

	fun renameTag(entry: TagEntry, rawName: String): Result<TagEntry> {
		val storedName = storedName(rawName).getOrElse { return Result.failure(it) }
		val snapshot = loadSnapshot().getOrElse { return Result.failure(it) }
		val existingEntry = snapshot.entries[entry.id]
			?: return Result.failure(IllegalArgumentException("That benchmark tag no longer exists."))
		if (existingEntry.name == storedName) {
			return Result.success(existingEntry)
		}
		if (snapshot.entries.values.any { it.id != entry.id && it.name == storedName }) {
			return Result.failure(IllegalArgumentException("A benchmark tag with that name already exists."))
		}

		val renamedEntry = existingEntry.copy(name = storedName)
		return saveSnapshot(snapshot.copy(entries = snapshot.entries + (renamedEntry.id to renamedEntry)))
			.map { renamedEntry }
	}

	fun deleteTag(entry: TagEntry): Result<Unit> {
		val snapshot = loadSnapshot().getOrElse { return Result.failure(it) }
		if (entry.id !in snapshot.entries) {
			return Result.failure(IllegalArgumentException("That benchmark tag no longer exists."))
		}

		return saveSnapshot(snapshot.copy(entries = snapshot.entries - entry.id))
	}

	private fun loadSnapshot(): Result<TagSnapshot> {
		val file = tagsFile().getOrElse { return Result.failure(it) }
		if (Files.exists(file)) {
			return runCatching {
				Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
					parseSnapshot(reader.readText())
				}
			}
		}

		return migrateLegacySnapshotToJson()
	}

	private fun parseSnapshot(contents: String): TagSnapshot {
		val trimmed = contents.trim()
		if (trimmed.isBlank()) {
			return TagSnapshot()
		}

		val element = JsonParser.parseString(trimmed)
		if (!element.isJsonObject) {
			return TagSnapshot()
		}

		val rootObject = element.asJsonObject
		val entries = linkedMapOf<String, TagEntry>()
		val tagsElement = rootObject.get("tags")
		if (tagsElement?.isJsonArray == true) {
			for (entry in tagsElement.asJsonArray) {
				if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
					continue
				}
				val normalizedName = storedName(entry.asString).getOrNull() ?: continue
				entries[normalizedName] = TagEntry(
					id = normalizedName,
					name = normalizedName,
				)
			}
			return TagSnapshot(entries)
		}

		val objectToParse = if (tagsElement?.isJsonObject == true) tagsElement.asJsonObject else rootObject
		for ((id, value) in objectToParse.entrySet().sortedBy(Map.Entry<String, *>::key)) {
			if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
				continue
			}
			val normalizedId = storedName(id).getOrNull() ?: continue
			val normalizedName = storedName(value.asString).getOrNull() ?: continue
			entries[normalizedId] = TagEntry(
				id = normalizedId,
				name = normalizedName,
			)
		}
		return TagSnapshot(entries)
	}

	private fun migrateLegacySnapshotToJson(): Result<TagSnapshot> {
		val legacyDirectory = legacyTagsDirectory().getOrElse { return Result.failure(it) }
		if (!Files.exists(legacyDirectory)) {
			return Result.success(TagSnapshot())
		}
		if (!Files.isDirectory(legacyDirectory)) {
			return Result.failure(IllegalArgumentException("Benchmark legacy tag storage is not a directory."))
		}

		val snapshot = runCatching {
			val entries = linkedMapOf<String, TagEntry>()
			Files.list(legacyDirectory).use { files ->
				for (tagName in files.iterator().asSequence().filter(Files::isRegularFile).mapNotNull(::legacyTagName).sorted()) {
					entries[tagName] = TagEntry(
						id = tagName,
						name = tagName,
					)
				}
			}
			TagSnapshot(entries)
		}.getOrElse { return Result.failure(it) }

		saveSnapshot(snapshot).getOrElse { return Result.failure(it) }
		return Result.success(snapshot)
	}

	private fun saveSnapshot(snapshot: TagSnapshot): Result<Unit> {
		val file = tagsFile().getOrElse { return Result.failure(it) }
		return runCatching {
			Files.createDirectories(file.parent)
			val serializedTags = JsonObject()
			for (entry in snapshot.entries.values.sortedBy(TagEntry::name)) {
				serializedTags.addProperty(entry.id, entry.name)
			}
			Files.writeString(
				file,
				gson.toJson(serializedTags) + "\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE,
			)
			Unit
		}
	}

	private fun tagsFile(): Result<Path> {
		return OpenBlock.currentServer().map { server ->
			server.getFile(TAGS_FILE)
		}
	}

	private fun legacyTagsDirectory(): Result<Path> {
		return OpenBlock.currentServer().map { server ->
			server.getFile(LEGACY_TAGS_DIR)
		}
	}

	private fun storedName(rawName: String): Result<String> {
		val input = rawName.trim()
		if (input.isBlank()) {
			return Result.failure(IllegalArgumentException("Tag name cannot be blank."))
		}
		if (input.any(Char::isWhitespace)) {
			return Result.failure(IllegalArgumentException("Tag names cannot contain spaces."))
		}
		val sanitized = input.map { character ->
			if (character.isLetterOrDigit() || character == '_' || character == '-') {
				character
			} else {
				'_'
			}
		}.joinToString("")
		if (sanitized.isBlank()) {
			return Result.failure(IllegalArgumentException("Tag name cannot be blank."))
		}
		return Result.success(sanitized)
	}

	private fun legacyTagName(path: Path): String? {
		val fileName = path.fileName.toString()
		if (!fileName.endsWith(LEGACY_TAG_SUFFIX, ignoreCase = true)) {
			return null
		}
		return storedName(fileName.removeSuffix(LEGACY_TAG_SUFFIX)).getOrNull()
	}

	data class TagEntry(
		val id: String,
		val name: String,
	)

	private data class TagSnapshot(
		val entries: Map<String, TagEntry> = emptyMap(),
	)
}
