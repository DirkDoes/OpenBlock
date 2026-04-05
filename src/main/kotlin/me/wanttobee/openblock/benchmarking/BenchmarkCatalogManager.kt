package me.wanttobee.openblock.benchmarking

import me.wanttobee.openblock.OpenBlock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator

object BenchmarkCatalogManager {
	private const val ROOT_DIR = "openblock/benchmarks/presets"
	private const val PRESET_SUFFIX = ".json"
	private const val GITKEEP_FILE = ".gitkeep"

	fun listEntries(pathSegments: List<String>): Result<List<CatalogEntry>> {
		val directory = directoryFor(pathSegments).getOrElse { return Result.failure(it) }
		if (!Files.exists(directory)) {
			return Result.success(emptyList())
		}
		if (!Files.isDirectory(directory)) {
			return Result.failure(IllegalArgumentException("Benchmark folder does not exist: ${pathLabel(pathSegments)}"))
		}

		return runCatching {
			Files.list(directory).use { files ->
				files.iterator().asSequence()
					.mapNotNull(::entryForPath)
					.sortedWith(compareBy<CatalogEntry>({ it.kind.sortOrder }, { it.displayName.lowercase() }, { it.storedName.lowercase() }))
					.toList()
			}
		}
	}

	fun createFolder(pathSegments: List<String>, rawName: String): Result<CatalogEntry> {
		val storedName = storedName(rawName).getOrElse { return Result.failure(it) }
		val directory = directoryFor(pathSegments).getOrElse { return Result.failure(it) }
		if (stemExists(directory, storedName)) {
			return Result.failure(IllegalArgumentException("A benchmark folder or preset with that name already exists here."))
		}

		return runCatching {
			val createdDirectory = Files.createDirectories(directory.resolve(storedName))
			Files.writeString(
				createdDirectory.resolve(GITKEEP_FILE),
				"",
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE,
			)
			CatalogEntry.folder(storedName)
		}
	}

	fun createPreset(pathSegments: List<String>, rawName: String, contents: String): Result<CatalogEntry> {
		val storedName = storedName(rawName).getOrElse { return Result.failure(it) }
		val directory = directoryFor(pathSegments).getOrElse { return Result.failure(it) }
		if (stemExists(directory, storedName)) {
			return Result.failure(IllegalArgumentException("A benchmark folder or preset with that name already exists here."))
		}

		return runCatching {
			Files.writeString(
				directory.resolve("$storedName$PRESET_SUFFIX"),
				contents,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE,
			)
			CatalogEntry.preset(storedName)
		}
	}

	fun overwritePreset(pathSegments: List<String>, entry: CatalogEntry, contents: String): Result<CatalogEntry> {
		if (entry.kind != EntryKind.PRESET) {
			return Result.failure(IllegalArgumentException("Only benchmark presets can be overwritten."))
		}

		val directory = directoryFor(pathSegments).getOrElse { return Result.failure(it) }
		val target = pathForEntry(directory, entry)
		if (!Files.exists(target)) {
			return Result.failure(IllegalArgumentException("That benchmark preset no longer exists."))
		}

		return runCatching {
			Files.writeString(
				target,
				contents,
				StandardCharsets.UTF_8,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE,
			)
			entry
		}
	}

	fun readPreset(pathSegments: List<String>, entry: CatalogEntry): Result<String> {
		if (entry.kind != EntryKind.PRESET) {
			return Result.failure(IllegalArgumentException("Only benchmark presets can be read."))
		}

		val directory = directoryFor(pathSegments).getOrElse { return Result.failure(it) }
		val target = pathForEntry(directory, entry)
		if (!Files.exists(target)) {
			return Result.failure(IllegalArgumentException("That benchmark preset no longer exists."))
		}

		return runCatching {
			Files.readString(target, StandardCharsets.UTF_8)
		}
	}

	fun renameEntry(pathSegments: List<String>, entry: CatalogEntry, rawName: String): Result<CatalogEntry> {
		val nextStoredName = storedName(rawName).getOrElse { return Result.failure(it) }
		if (entry.storedName == nextStoredName) {
			return Result.success(entry.withStoredName(nextStoredName))
		}

		val directory = directoryFor(pathSegments).getOrElse { return Result.failure(it) }
		if (stemExists(directory, nextStoredName)) {
			return Result.failure(IllegalArgumentException("A benchmark folder or preset with that name already exists here."))
		}

		return runCatching {
			Files.move(pathForEntry(directory, entry), pathForEntry(directory, entry.withStoredName(nextStoredName)))
			entry.withStoredName(nextStoredName)
		}
	}

	fun deleteEntry(pathSegments: List<String>, entry: CatalogEntry): Result<Unit> {
		val directory = directoryFor(pathSegments).getOrElse { return Result.failure(it) }
		val target = pathForEntry(directory, entry)
		if (!Files.exists(target)) {
			return Result.failure(IllegalArgumentException("That benchmark item no longer exists."))
		}

		return runCatching {
			if (entry.kind == EntryKind.FOLDER) {
				Files.walk(target).use { paths ->
					paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
				}
			} else {
				Files.deleteIfExists(target)
			}
			Unit
		}
	}

	fun moveEntry(
		sourcePathSegments: List<String>,
		entry: CatalogEntry,
		targetFolderPathSegments: List<String>,
	): Result<CatalogEntry> {
		val sourceDirectory = directoryFor(sourcePathSegments).getOrElse { return Result.failure(it) }
		val targetDirectory = directoryFor(targetFolderPathSegments).getOrElse { return Result.failure(it) }
		if (stemExists(targetDirectory, entry.storedName)) {
			return Result.failure(IllegalArgumentException("A benchmark folder or preset with that name already exists in the destination."))
		}
		if (entry.kind == EntryKind.FOLDER && targetFolderPathSegments == sourcePathSegments + entry.storedName) {
			return Result.failure(IllegalArgumentException("A folder cannot be moved into itself."))
		}

		return runCatching {
			Files.move(pathForEntry(sourceDirectory, entry), pathForEntry(targetDirectory, entry))
			entry
		}
	}

	fun displayName(storedName: String): String {
		return storedName.replace('_', ' ')
	}

	fun pathLabel(pathSegments: List<String>): String {
		return if (pathSegments.isEmpty()) {
			"root"
		} else {
			pathSegments.joinToString(" / ", transform = ::displayName)
		}
	}

	private fun directoryFor(pathSegments: List<String>): Result<Path> {
		val root = OpenBlock.currentServer()
			.map { server -> server.getFile(ROOT_DIR) }
			.getOrElse { return Result.failure(it) }

		return runCatching {
			Files.createDirectories(root)
			pathSegments.fold(root) { current, segment ->
				val normalizedSegment = validateStoredSegment(segment)
				current.resolve(normalizedSegment)
			}
		}
	}

	private fun validateStoredSegment(segment: String): String {
		require(segment.isNotBlank()) { "Benchmark path segments cannot be blank." }
		require(segment == sanitizeName(segment)) { "Invalid benchmark path segment: $segment" }
		return segment
	}

	private fun storedName(rawName: String): Result<String> {
		val input = rawName
		if (input.isBlank()) {
			return Result.failure(IllegalArgumentException("Name cannot be blank."))
		}

		val sanitized = sanitizeName(input)
		if (sanitized.isBlank()) {
			return Result.failure(IllegalArgumentException("Name cannot be blank."))
		}

		return Result.success(sanitized)
	}

	private fun sanitizeName(rawName: String): String {
		return rawName.map { character ->
			if (character.isLetterOrDigit() || character == '-') {
				character
			} else {
				'_'
			}
		}.joinToString("")
	}

	private fun stemExists(directory: Path, storedName: String): Boolean {
		return Files.exists(directory.resolve(storedName)) || Files.exists(directory.resolve("$storedName$PRESET_SUFFIX"))
	}

	private fun entryForPath(path: Path): CatalogEntry? {
		val fileName = path.fileName.toString()
		return when {
			Files.isDirectory(path) -> CatalogEntry.folder(fileName)
			Files.isRegularFile(path) && fileName.endsWith(PRESET_SUFFIX, ignoreCase = true) ->
				CatalogEntry.preset(fileName.removeSuffix(PRESET_SUFFIX))
			else -> null
		}
	}

	private fun pathForEntry(directory: Path, entry: CatalogEntry): Path {
		return when (entry.kind) {
			EntryKind.FOLDER -> directory.resolve(entry.storedName)
			EntryKind.PRESET -> directory.resolve("${entry.storedName}$PRESET_SUFFIX")
		}
	}

	data class CatalogEntry(
		val storedName: String,
		val displayName: String,
		val kind: EntryKind,
	) {
		fun withStoredName(nextStoredName: String): CatalogEntry {
			return copy(
				storedName = nextStoredName,
				displayName = displayName(nextStoredName),
			)
		}

		companion object {
			fun folder(storedName: String): CatalogEntry {
				return CatalogEntry(
					storedName = storedName,
					displayName = BenchmarkCatalogManager.displayName(storedName),
					kind = EntryKind.FOLDER,
				)
			}

			fun preset(storedName: String): CatalogEntry {
				return CatalogEntry(
					storedName = storedName,
					displayName = BenchmarkCatalogManager.displayName(storedName),
					kind = EntryKind.PRESET,
				)
			}
		}
	}

	enum class EntryKind(
		val sortOrder: Int,
	) {
		FOLDER(0),
		PRESET(1),
	}
}
