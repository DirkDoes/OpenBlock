package me.wanttobee.openblock.sandbox

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SandboxManager {
	private val sandboxesBySession = ConcurrentHashMap<UUID, Sandbox>()
	private val updatesBySession = ConcurrentHashMap<UUID, SandboxUpdate>()

	fun bindSession(sessionId: UUID, sandbox: Sandbox?) {
		if (sandbox == null) {
			sandboxesBySession.remove(sessionId)
		} else {
			sandboxesBySession[sessionId] = sandbox
		}
	}

	fun unbindSession(sessionId: UUID) {
		sandboxesBySession.remove(sessionId)
		updatesBySession.remove(sessionId)
	}

	fun getSandbox(sessionId: UUID): Result<Sandbox> {
		return sandboxesBySession[sessionId]?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("No active sandbox."))
	}

	fun setSandbox(
		sessionId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Result<Sandbox> {
		val sandbox = Sandbox(
			dimension = dimension,
			boundary = SandboxRegion(
				firstCorner = firstCorner.immutable(),
				secondCorner = secondCorner.immutable(),
			),
		)
		sandboxesBySession[sessionId] = sandbox
		recordUpdate(sessionId, "Sandbox changed to: ${sandbox.promptDescription()}")
		return Result.success(sandbox)
	}

	fun addExclusion(sessionId: UUID, dimension: ResourceKey<Level>, position: BlockPos): Result<Sandbox> {
		return addExclusion(sessionId, dimension, "exclusion_${position.x}_${position.y}_${position.z}", position)
	}

	fun addExclusion(sessionId: UUID, dimension: ResourceKey<Level>, name: String, position: BlockPos): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (sandbox.dimension != dimension) {
			return Result.failure(IllegalArgumentException("Exclusion must be added in the sandbox dimension."))
		}
		if (!sandbox.boundary.contains(position)) {
			return Result.failure(IllegalArgumentException("Exclusion must stay inside the sandbox boundary."))
		}
		if (name.isBlank()) {
			return Result.failure(IllegalArgumentException("Exclusion name cannot be blank."))
		}
		if (name in sandbox.exclusions) {
			return Result.failure(IllegalArgumentException("Sandbox exclusion already exists: $name"))
		}

		val normalizedPosition = position.immutable()
		val updated = sandbox.copy(exclusions = sandbox.exclusions + (name to normalizedPosition))
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		return Result.success(updated)
	}

	fun removeExclusion(sessionId: UUID, name: String): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (name !in sandbox.exclusions) {
			return Result.failure(NoSuchElementException("Unknown sandbox exclusion: $name"))
		}

		val updated = sandbox.copy(exclusions = sandbox.exclusions - name)
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		return Result.success(updated)
	}

	fun clearExclusions(sessionId: UUID): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (sandbox.exclusions.isEmpty()) {
			return Result.success(sandbox)
		}

		val updated = sandbox.copy(exclusions = emptyMap())
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		return Result.success(updated)
	}

	fun addInteraction(sessionId: UUID, dimension: ResourceKey<Level>, name: String, position: BlockPos): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (sandbox.dimension != dimension) {
			return Result.failure(IllegalArgumentException("Interaction must be added in the sandbox dimension."))
		}
		if (!sandbox.boundary.contains(position)) {
			return Result.failure(IllegalArgumentException("Interaction must stay inside the sandbox boundary."))
		}
		if (name.isBlank()) {
			return Result.failure(IllegalArgumentException("Interaction name cannot be blank."))
		}
		if (name in sandbox.interactions) {
			return Result.failure(IllegalArgumentException("Sandbox interaction already exists: $name"))
		}

		val updated = sandbox.copy(interactions = sandbox.interactions + (name to position.immutable()))
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		return Result.success(updated)
	}

	fun removeInteraction(sessionId: UUID, name: String): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (name !in sandbox.interactions) {
			return Result.failure(NoSuchElementException("Unknown sandbox interaction: $name"))
		}

		val updated = sandbox.copy(interactions = sandbox.interactions - name)
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		return Result.success(updated)
	}

	fun clearInteractions(sessionId: UUID): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (sandbox.interactions.isEmpty()) {
			return Result.success(sandbox)
		}

		val updated = sandbox.copy(interactions = emptyMap())
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		return Result.success(updated)
	}

	fun clearSandbox(sessionId: UUID): Result<Sandbox> {
		val removed = sandboxesBySession.remove(sessionId)
		recordUpdate(sessionId, "Sandbox changed to: no active sandbox.")
		return removed?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("No active sandbox."))
	}

	fun isAllowed(sessionId: UUID, dimension: ResourceKey<Level>, position: BlockPos): Boolean {
		val sandbox = getSandbox(sessionId).getOrNull() ?: return true
		return sandbox.contains(dimension, position)
	}

	fun isChangeAllowed(sessionId: UUID, dimension: ResourceKey<Level>, position: BlockPos): Boolean {
		val sandbox = getSandbox(sessionId).getOrNull() ?: return true
		return sandbox.contains(dimension, position) && !sandbox.isExcluded(position)
	}

	fun isAreaAllowed(
		sessionId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Boolean {
		val sandbox = getSandbox(sessionId).getOrNull() ?: return true
		return sandbox.containsArea(dimension, firstCorner, secondCorner)
	}

	fun exclusionsInArea(
		sessionId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Result<List<Sandbox.NamedPoint>> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (sandbox.dimension != dimension) {
			return Result.success(emptyList())
		}

		return Result.success(
			sandbox.exclusionEntriesInside(
			SandboxRegion(
				firstCorner = firstCorner.immutable(),
				secondCorner = secondCorner.immutable(),
			)
			)
		)
	}

	fun exclusionNames(sessionId: UUID): Result<List<String>> {
		return getSandbox(sessionId).map { sandbox ->
			sandbox.exclusions.keys.sorted()
		}
	}

	fun interactionNames(sessionId: UUID): Result<List<String>> {
		return getSandbox(sessionId).map { sandbox ->
			sandbox.interactions.keys.sorted()
		}
	}

	fun latestUpdate(sessionId: UUID): Result<SandboxUpdate> {
		return updatesBySession[sessionId]?.let(Result.Companion::success)
			?: Result.failure(NoSuchElementException("No sandbox updates recorded."))
	}

	fun allSandboxes(): Map<UUID, Sandbox> = sandboxesBySession.toMap()

	private fun recordUpdate(sessionId: UUID, description: String) {
		val nextVersion = (updatesBySession[sessionId]?.version ?: 0L) + 1L
		updatesBySession[sessionId] = SandboxUpdate(
			version = nextVersion,
			description = description,
		)
	}

	data class SandboxUpdate(
		val version: Long,
		val description: String,
	)
}
