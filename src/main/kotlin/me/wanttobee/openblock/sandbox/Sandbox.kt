package me.wanttobee.openblock.sandbox

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

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
