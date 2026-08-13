package me.wanttobee.openblock.sandbox

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

data class Sandbox(
	val id: UUID = UUID.randomUUID(),
	val dimension: ResourceKey<Level>,
	val boundary: SandboxRegion,
	val exclusions: Map<String, BlockPos> = emptyMap(),
	val targets: Map<String, BlockPos> = emptyMap(),
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

	fun isExcluded(position: BlockPos): Boolean {
		return exclusions.values.any { it == position }
	}

	fun exclusionEntriesInside(region: SandboxRegion): List<NamedPoint> {
		return exclusions
			.mapValues { (_, position) -> position.immutable() }
			.filterValues(region::contains)
			.map { (name, position) -> NamedPoint(name, position) }
			.sortedWith(compareBy({ it.position.x }, { it.position.y }, { it.position.z }, { it.name }))
	}

	fun exclusionDescription(): String {
		if (exclusions.isEmpty()) {
			return "none"
		}

		return exclusions
			.map { (name, position) -> NamedPoint(name, position.immutable()) }
			.sortedWith(compareBy({ it.position.x }, { it.position.y }, { it.position.z }, { it.name }))
			.joinToString(", ") { entry ->
				"${entry.name}=[${entry.position.x}, ${entry.position.y}, ${entry.position.z}]"
			}
	}

	fun targetDescription(): String {
		if (targets.isEmpty()) {
			return "none"
		}

		return targets
			.map { (name, position) -> NamedPoint(name, position.immutable()) }
			.sortedWith(compareBy({ it.position.x }, { it.position.y }, { it.position.z }, { it.name }))
			.joinToString(", ") { entry ->
				"${entry.name}=[${entry.position.x}, ${entry.position.y}, ${entry.position.z}]"
			}
	}

	fun promptDescription(): String {
		return buildString {
			append("Sandbox restriction: AI tool calls must stay inside ${boundary.description()} in dimension $dimension.")
			append(" Unless otherwise specified, there are always support blocks directly under the sandbox that may be used for placement.")
			if (exclusions.isNotEmpty()) {
				append(" Excluded blocks inside the sandbox may be read and interacted with, but must not be changed: ")
				append(exclusionDescription())
				append('.')
			}
			if (targets.isNotEmpty()) {
				append(" Named sandbox target points for interaction or observation: ")
				append(targetDescription())
				append('.')
			}
		}
	}

	data class NamedPoint(
		val name: String,
		val position: BlockPos,
	)
}
