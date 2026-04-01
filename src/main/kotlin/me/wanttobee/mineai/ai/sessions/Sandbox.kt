package me.wanttobee.mineai.ai.sessions

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class Sandbox(
	val id: UUID = UUID.randomUUID(),
	val dimension: ResourceKey<Level>,
	val boundary: SandboxRegion,
) {
	fun minCorner(): BlockPos = boundary.minCorner()

	fun maxCorner(): BlockPos = boundary.maxCorner()

	fun contains(dimension: ResourceKey<Level>, position: BlockPos): Boolean {
		if (this.dimension != dimension) {
			return false
		}

		return boundary.contains(position)
	}

	fun containsArea(dimension: ResourceKey<Level>, firstCorner: BlockPos, secondCorner: BlockPos): Boolean {
		if (this.dimension != dimension) {
			return false
		}

		val region = SandboxRegion(
			firstCorner = firstCorner.immutable(),
			secondCorner = secondCorner.immutable(),
		)
		return boundary.fullyContains(region)
	}

	fun promptDescription(): String {
		return "Sandbox restriction: AI tool calls must stay inside ${boundary.description()} in dimension $dimension."
	}
}

data class SandboxRegion(
	val id: UUID = UUID.randomUUID(),
	val firstCorner: BlockPos,
	val secondCorner: BlockPos,
) {
	val minX: Int = min(firstCorner.x, secondCorner.x)
	val minY: Int = min(firstCorner.y, secondCorner.y)
	val minZ: Int = min(firstCorner.z, secondCorner.z)
	val maxX: Int = max(firstCorner.x, secondCorner.x)
	val maxY: Int = max(firstCorner.y, secondCorner.y)
	val maxZ: Int = max(firstCorner.z, secondCorner.z)

	fun minCorner(): BlockPos = BlockPos(minX, minY, minZ)

	fun maxCorner(): BlockPos = BlockPos(maxX, maxY, maxZ)

	fun contains(position: BlockPos): Boolean {
		return position.x in minX..maxX &&
			position.y in minY..maxY &&
			position.z in minZ..maxZ
	}

	fun fullyContains(other: SandboxRegion): Boolean {
		return other.minX >= minX &&
			other.maxX <= maxX &&
			other.minY >= minY &&
			other.maxY <= maxY &&
			other.minZ >= minZ &&
			other.maxZ <= maxZ
	}

	fun description(): String {
		return "[${minX}, ${minY}, ${minZ}] -> [${maxX}, ${maxY}, ${maxZ}]"
	}
}
