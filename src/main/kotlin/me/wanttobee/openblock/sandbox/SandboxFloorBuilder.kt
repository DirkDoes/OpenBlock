package me.wanttobee.openblock.sandbox

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks

object SandboxFloorBuilder {
	private const val UPDATE_FLAGS = 3

	fun placeFloor(level: ServerLevel, sandbox: Sandbox): Result<PlacementSummary> {
		if (level.dimension() != sandbox.dimension) {
			return Result.failure(IllegalArgumentException("Sandbox floor must be placed in the sandbox dimension."))
		}

		val minCorner = sandbox.minCorner()
		val maxCorner = sandbox.maxCorner()
		val floorY = minCorner.y - 1
		if (floorY < level.minY) {
			return Result.failure(IllegalArgumentException("Sandbox floor would be below the world build height."))
		}

		var placedIronBlocks = 0
		var placedConcreteBlocks = 0
		for (x in minCorner.x..maxCorner.x) {
			for (z in minCorner.z..maxCorner.z) {
				val position = BlockPos(x, floorY, z)
				val isEdge = x == minCorner.x || x == maxCorner.x || z == minCorner.z || z == maxCorner.z
				val blockState = if (isEdge) {
					Blocks.IRON_BLOCK.defaultBlockState()
				} else {
					Blocks.WHITE_CONCRETE.defaultBlockState()
				}
				val existingState = level.getBlockState(position)
				if (existingState == blockState) {
					if (isEdge) {
						placedIronBlocks += 1
					} else {
						placedConcreteBlocks += 1
					}
					continue
				}

				val wasPlaced = level.setBlock(position, blockState, UPDATE_FLAGS) ||
					(run {
						level.destroyBlock(position, false)
						level.setBlock(position, blockState, UPDATE_FLAGS)
					})
				if (!wasPlaced) {
					return Result.failure(
						IllegalStateException("Unable to place sandbox floor block at [${position.x}, ${position.y}, ${position.z}].")
					)
				}

				if (isEdge) {
					placedIronBlocks += 1
				} else {
					placedConcreteBlocks += 1
				}
			}
		}

		return Result.success(
			PlacementSummary(
				y = floorY,
				placedConcreteBlocks = placedConcreteBlocks,
				placedIronBlocks = placedIronBlocks,
			)
		)
	}

	fun clearFloor(level: ServerLevel, sandbox: Sandbox): Result<Unit> {
		if (level.dimension() != sandbox.dimension) {
			return Result.failure(IllegalArgumentException("Sandbox floor must be cleared in the sandbox dimension."))
		}

		val minCorner = sandbox.minCorner()
		val maxCorner = sandbox.maxCorner()
		val floorY = minCorner.y - 1
		if (floorY < level.minY) {
			return Result.success(Unit)
		}

		for (x in minCorner.x..maxCorner.x) {
			for (z in minCorner.z..maxCorner.z) {
				val position = BlockPos(x, floorY, z)
				val isEdge = x == minCorner.x || x == maxCorner.x || z == minCorner.z || z == maxCorner.z
				val expectedState = if (isEdge) {
					Blocks.IRON_BLOCK.defaultBlockState()
				} else {
					Blocks.WHITE_CONCRETE.defaultBlockState()
				}
				if (level.getBlockState(position) != expectedState) {
					continue
				}

				val wasCleared = level.setBlock(position, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS) ||
					(run {
						level.destroyBlock(position, false)
						level.setBlock(position, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS)
					})
				if (!wasCleared && !level.getBlockState(position).isAir) {
					return Result.failure(
						IllegalStateException("Unable to clear sandbox floor block at [${position.x}, ${position.y}, ${position.z}].")
					)
				}
			}
		}

		return Result.success(Unit)
	}

	data class PlacementSummary(
		val y: Int,
		val placedConcreteBlocks: Int,
		val placedIronBlocks: Int,
	)
}
