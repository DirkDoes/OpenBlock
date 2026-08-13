package me.wanttobee.openblock.sandbox

import me.wanttobee.openblock.ai.sessions.AiSessionManager
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SandboxManager {
	private val sandboxesBySession = ConcurrentHashMap<UUID, Sandbox>()
	private val updatesBySession = ConcurrentHashMap<UUID, SandboxUpdate>()
	private val currentSandboxListeners = CopyOnWriteArrayList<(UUID, Sandbox?) -> Unit>()
	private val rendererModesByPlayer = ConcurrentHashMap<UUID, RendererMode>()
	private val currentRendererModeListeners = CopyOnWriteArrayList<(UUID, RendererMode) -> Unit>()

	fun bind() {
		AiSessionManager.subscribeCurrentSessionChanges { playerId, session ->
			notifyCurrentSandboxChanged(playerId, session.sandbox())
		}
	}

	fun bindSession(sessionId: UUID, sandbox: Sandbox?) {
		if (sandbox == null) {
			sandboxesBySession.remove(sessionId)
		} else {
			sandboxesBySession[sessionId] = sandbox
		}
		notifySessionSandboxChanged(sessionId, sandbox)
	}

	fun unbindSession(sessionId: UUID) {
		sandboxesBySession.remove(sessionId)
		updatesBySession.remove(sessionId)
		notifySessionSandboxChanged(sessionId, null)
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
		notifySessionSandboxChanged(sessionId, sandbox)
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
		notifySessionSandboxChanged(sessionId, updated)
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
		notifySessionSandboxChanged(sessionId, updated)
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
		notifySessionSandboxChanged(sessionId, updated)
		return Result.success(updated)
	}

	fun addTarget(sessionId: UUID, dimension: ResourceKey<Level>, name: String, position: BlockPos): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (sandbox.dimension != dimension) {
			return Result.failure(IllegalArgumentException("Target must be added in the sandbox dimension."))
		}
		if (!sandbox.boundary.contains(position)) {
			return Result.failure(IllegalArgumentException("Target must stay inside the sandbox boundary."))
		}
		if (name.isBlank()) {
			return Result.failure(IllegalArgumentException("Target name cannot be blank."))
		}
		if (name in sandbox.targets) {
			return Result.failure(IllegalArgumentException("Sandbox target already exists: $name"))
		}

		val updated = sandbox.copy(targets = sandbox.targets + (name to position.immutable()))
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		notifySessionSandboxChanged(sessionId, updated)
		return Result.success(updated)
	}

	fun removeTarget(sessionId: UUID, name: String): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (name !in sandbox.targets) {
			return Result.failure(NoSuchElementException("Unknown sandbox target: $name"))
		}

		val updated = sandbox.copy(targets = sandbox.targets - name)
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		notifySessionSandboxChanged(sessionId, updated)
		return Result.success(updated)
	}

	fun clearTargets(sessionId: UUID): Result<Sandbox> {
		val sandbox = getSandbox(sessionId).getOrElse { return Result.failure(it) }
		if (sandbox.targets.isEmpty()) {
			return Result.success(sandbox)
		}

		val updated = sandbox.copy(targets = emptyMap())
		sandboxesBySession[sessionId] = updated
		recordUpdate(sessionId, "Sandbox changed to: ${updated.promptDescription()}")
		notifySessionSandboxChanged(sessionId, updated)
		return Result.success(updated)
	}

	fun clearSandbox(sessionId: UUID): Result<Sandbox> {
		val removed = sandboxesBySession.remove(sessionId)
		recordUpdate(sessionId, "Sandbox changed to: no active sandbox.")
		notifySessionSandboxChanged(sessionId, null)
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

	fun targetNames(sessionId: UUID): Result<List<String>> {
		return getSandbox(sessionId).map { sandbox ->
			sandbox.targets.keys.sorted()
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

	fun subscribeCurrentSandboxChanges(listener: (UUID, Sandbox?) -> Unit) {
		currentSandboxListeners += listener
	}

	fun rendererMode(playerId: UUID): RendererMode {
		return rendererModesByPlayer[playerId] ?: RendererMode.PARTICLES
	}

	fun setRendererMode(playerId: UUID, mode: RendererMode) {
		rendererModesByPlayer[playerId] = mode
		for (listener in currentRendererModeListeners) {
			listener(playerId, mode)
		}
	}

	fun subscribeRendererModeChanges(listener: (UUID, RendererMode) -> Unit) {
		currentRendererModeListeners += listener
	}

	private fun notifySessionSandboxChanged(sessionId: UUID, sandbox: Sandbox?) {
		for (playerId in AiSessionManager.ownersWithSelectedSession(sessionId)) {
			notifyCurrentSandboxChanged(playerId, sandbox)
		}
	}

	private fun notifyCurrentSandboxChanged(playerId: UUID, sandbox: Sandbox?) {
		for (listener in currentSandboxListeners) {
			listener(playerId, sandbox)
		}
	}

	data class SandboxUpdate(
		val version: Long,
		val description: String,
	)

	enum class RendererMode(
		val commandName: String,
	) {
		PARTICLES("particles"),
		DISPLAY_ENTITIES("display-entities");

		companion object {
			fun fromCommandName(value: String): RendererMode? {
				return entries.firstOrNull { it.commandName.equals(value, ignoreCase = true) }
			}
		}
	}
}
