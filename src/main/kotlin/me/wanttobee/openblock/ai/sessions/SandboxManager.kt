package me.wanttobee.openblock.ai.sessions

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object SandboxManager {
	private val sandboxesByPlayer = ConcurrentHashMap<UUID, Sandbox>()
	private val updatesByPlayer = ConcurrentHashMap<UUID, SandboxUpdate>()

	fun getSandbox(playerId: UUID): Sandbox? = sandboxesByPlayer[playerId]

	fun setSandbox(
		playerId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Sandbox {
		val sandbox = Sandbox(
			dimension = dimension,
			boundary = SandboxRegion(
				firstCorner = firstCorner.immutable(),
				secondCorner = secondCorner.immutable(),
			),
		)
		sandboxesByPlayer[playerId] = sandbox
		recordUpdate(playerId, "Sandbox changed to: ${sandbox.promptDescription()}")
		return sandbox
	}

	fun clearSandbox(playerId: UUID): Sandbox? {
		val removed = sandboxesByPlayer.remove(playerId)
		recordUpdate(playerId, "Sandbox changed to: no active sandbox.")
		return removed
	}

	fun isAllowed(playerId: UUID, dimension: ResourceKey<Level>, position: BlockPos): Boolean {
		val sandbox = getSandbox(playerId) ?: return true
		return sandbox.contains(dimension, position)
	}

	fun isAreaAllowed(
		playerId: UUID,
		dimension: ResourceKey<Level>,
		firstCorner: BlockPos,
		secondCorner: BlockPos,
	): Boolean {
		val sandbox = getSandbox(playerId) ?: return true
		return sandbox.containsArea(dimension, firstCorner, secondCorner)
	}

	fun latestUpdate(playerId: UUID): SandboxUpdate? = updatesByPlayer[playerId]

	fun allSandboxes(): Map<UUID, Sandbox> = sandboxesByPlayer.toMap()

	private fun recordUpdate(playerId: UUID, description: String) {
		val nextVersion = (updatesByPlayer[playerId]?.version ?: 0L) + 1L
		updatesByPlayer[playerId] = SandboxUpdate(
			version = nextVersion,
			description = description,
		)
	}

	data class SandboxUpdate(
		val version: Long,
		val description: String,
	)
}
