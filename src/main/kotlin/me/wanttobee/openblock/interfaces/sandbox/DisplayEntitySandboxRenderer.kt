package me.wanttobee.openblock.interfaces.sandbox

import com.mojang.math.Transformation
import me.wanttobee.openblock.OpenBlock
import me.wanttobee.openblock.ai.sessions.AiSessionManager
import me.wanttobee.openblock.sandbox.Sandbox
import me.wanttobee.openblock.sandbox.SandboxManager
import me.wanttobee.openblock.sandbox.SandboxRegion
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Brightness
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DisplayEntitySandboxRenderer {
	private const val LINE_THICKNESS = 0.04f
	private const val LINE_OVERLAP = 0.02f
	private const val OUTWARD_RATIO = 1.0 / 3.0
	private const val INWARD_RATIO = 2.0 / 3.0
	private val WHITE_BLOCK_STATE: BlockState = Blocks.WHITE_CONCRETE.defaultBlockState()
	private val YELLOW_BLOCK_STATE: BlockState = Blocks.YELLOW_CONCRETE.defaultBlockState()
	private val RED_BLOCK_STATE: BlockState = Blocks.RED_CONCRETE.defaultBlockState()
	private val renderedEntitiesByPlayer = ConcurrentHashMap<UUID, List<RenderedEntity>>()

	fun bind() {
		SandboxManager.subscribeCurrentSandboxChanges { playerId, sandbox ->
			if (SandboxManager.rendererMode(playerId) != SandboxManager.RendererMode.DISPLAY_ENTITIES) {
				OpenBlock.currentServer().getOrNull()?.execute { clearRenderedSandbox(playerId) }
				return@subscribeCurrentSandboxChanges
			}
			OpenBlock.currentServer().getOrNull()?.execute { renderSandbox(playerId, sandbox) }
		}
		SandboxManager.subscribeRendererModeChanges { playerId, mode ->
			OpenBlock.currentServer().getOrNull()?.execute {
				if (mode == SandboxManager.RendererMode.DISPLAY_ENTITIES) {
					renderSandbox(playerId, currentSandbox(playerId))
				} else {
					clearRenderedSandbox(playerId)
				}
			}
		}
		ServerPlayConnectionEvents.JOIN.register(ServerPlayConnectionEvents.Join { handler, _, server ->
			server.execute {
				if (SandboxManager.rendererMode(handler.player.uuid) == SandboxManager.RendererMode.DISPLAY_ENTITIES) {
					renderSandbox(handler.player.uuid, currentSandbox(handler.player.uuid))
				}
			}
		})
		ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { handler, server ->
			server.execute {
				clearRenderedSandbox(handler.player.uuid)
			}
		})
	}

	private fun currentSandbox(playerId: UUID): Sandbox? {
		return AiSessionManager.getSession(playerId).getOrNull()?.sandbox()
	}

	private fun renderSandbox(playerId: UUID, sandbox: Sandbox?) {
		clearRenderedSandbox(playerId)
		if (sandbox == null) {
			return
		}

		val server = OpenBlock.currentServer().getOrNull() ?: return
		val level = server.getLevel(sandbox.dimension) ?: return
		val renderedEntities = buildList {
			addAll(spawnRegionEdges(level, sandbox.boundary, WHITE_BLOCK_STATE))
			val interactionPositions = sandbox.interactions.values.map(BlockPos::immutable).toSet()
			val exclusionPositions = sandbox.exclusions.values.map(BlockPos::immutable).toSet()
			for (position in exclusionPositions) {
				val region = SandboxRegion(
					firstCorner = position.immutable(),
					secondCorner = position.immutable(),
				)
				if (position in interactionPositions) {
					addAll(spawnOverlapRegionEdges(level, region))
				} else {
					addAll(spawnRegionEdges(level, region, RED_BLOCK_STATE))
				}
			}
			for (position in interactionPositions - exclusionPositions) {
				addAll(
					spawnRegionEdges(
						level,
						SandboxRegion(
							firstCorner = position.immutable(),
							secondCorner = position.immutable(),
						),
						YELLOW_BLOCK_STATE,
					)
				)
			}
		}
		renderedEntitiesByPlayer[playerId] = renderedEntities
	}

	private fun clearRenderedSandbox(playerId: UUID) {
		for (renderedEntity in renderedEntitiesByPlayer.remove(playerId).orEmpty()) {
			OpenBlock.currentServer().getOrNull()
				?.getLevel(renderedEntity.dimension)
				?.getEntity(renderedEntity.entityUuid)
				?.discard()
		}
	}

	private fun spawnRegionEdges(
		level: ServerLevel,
		region: SandboxRegion,
		blockState: BlockState,
	): List<RenderedEntity> {
		return spawnRegionEdges(level, region, blockState, blockState)
	}

	private fun spawnRegionEdges(
		level: ServerLevel,
		region: SandboxRegion,
		horizontalBlockState: BlockState,
		verticalBlockState: BlockState,
	): List<RenderedEntity> {
		val min = region.minCorner()
		val max = region.maxCorner()
		val xLength = (max.x - min.x + 1).toFloat() + LINE_OVERLAP * 2.0f
		val yLength = (max.y - min.y + 1).toFloat() + LINE_OVERLAP * 2.0f
		val zLength = (max.z - min.z + 1).toFloat() + LINE_OVERLAP * 2.0f
		val overlapStart = LINE_OVERLAP.toDouble()
		val minX = minBoundaryStart(min.x.toDouble())
		val minY = minBoundaryStart(min.y.toDouble())
		val minZ = minBoundaryStart(min.z.toDouble())
		val maxX = maxBoundaryStart(max.x + 1.0)
		val maxY = maxBoundaryStart(max.y + 1.0)
		val maxZ = maxBoundaryStart(max.z + 1.0)

		return buildList {
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, minY, minZ, xLength, LINE_THICKNESS, LINE_THICKNESS, horizontalBlockState))
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, minY, maxZ, xLength, LINE_THICKNESS, LINE_THICKNESS, horizontalBlockState))
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, maxY, minZ, xLength, LINE_THICKNESS, LINE_THICKNESS, horizontalBlockState))
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, maxY, maxZ, xLength, LINE_THICKNESS, LINE_THICKNESS, horizontalBlockState))

			addIfPresent(spawnDisplay(level, minX, min.y.toDouble() - overlapStart, minZ, LINE_THICKNESS, yLength, LINE_THICKNESS, verticalBlockState))
			addIfPresent(spawnDisplay(level, maxX, min.y.toDouble() - overlapStart, minZ, LINE_THICKNESS, yLength, LINE_THICKNESS, verticalBlockState))
			addIfPresent(spawnDisplay(level, minX, min.y.toDouble() - overlapStart, maxZ, LINE_THICKNESS, yLength, LINE_THICKNESS, verticalBlockState))
			addIfPresent(spawnDisplay(level, maxX, min.y.toDouble() - overlapStart, maxZ, LINE_THICKNESS, yLength, LINE_THICKNESS, verticalBlockState))

			addIfPresent(spawnDisplay(level, minX, minY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, horizontalBlockState))
			addIfPresent(spawnDisplay(level, maxX, minY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, horizontalBlockState))
			addIfPresent(spawnDisplay(level, minX, maxY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, horizontalBlockState))
			addIfPresent(spawnDisplay(level, maxX, maxY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, horizontalBlockState))
		}
	}

	private fun spawnOverlapRegionEdges(
		level: ServerLevel,
		region: SandboxRegion,
	): List<RenderedEntity> {
		val min = region.minCorner()
		val max = region.maxCorner()
		val xLength = (max.x - min.x + 1).toFloat() + LINE_OVERLAP * 2.0f
		val yLength = (max.y - min.y + 1).toFloat() + LINE_OVERLAP * 2.0f
		val zLength = (max.z - min.z + 1).toFloat() + LINE_OVERLAP * 2.0f
		val overlapStart = LINE_OVERLAP.toDouble()
		val minX = minBoundaryStart(min.x.toDouble())
		val minY = minBoundaryStart(min.y.toDouble())
		val minZ = minBoundaryStart(min.z.toDouble())
		val maxX = maxBoundaryStart(max.x + 1.0)
		val maxY = maxBoundaryStart(max.y + 1.0)
		val maxZ = maxBoundaryStart(max.z + 1.0)

		return buildList {
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, minY, minZ, xLength, LINE_THICKNESS, LINE_THICKNESS, RED_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, minY, maxZ, xLength, LINE_THICKNESS, LINE_THICKNESS, YELLOW_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, maxY, minZ, xLength, LINE_THICKNESS, LINE_THICKNESS, YELLOW_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, min.x.toDouble() - overlapStart, maxY, maxZ, xLength, LINE_THICKNESS, LINE_THICKNESS, RED_BLOCK_STATE))

			addIfPresent(spawnDisplay(level, minX, min.y.toDouble() - overlapStart, minZ, LINE_THICKNESS, yLength, LINE_THICKNESS, RED_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, maxX, min.y.toDouble() - overlapStart, minZ, LINE_THICKNESS, yLength, LINE_THICKNESS, YELLOW_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, minX, min.y.toDouble() - overlapStart, maxZ, LINE_THICKNESS, yLength, LINE_THICKNESS, YELLOW_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, maxX, min.y.toDouble() - overlapStart, maxZ, LINE_THICKNESS, yLength, LINE_THICKNESS, RED_BLOCK_STATE))

			addIfPresent(spawnDisplay(level, minX, minY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, RED_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, maxX, minY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, YELLOW_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, minX, maxY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, YELLOW_BLOCK_STATE))
			addIfPresent(spawnDisplay(level, maxX, maxY, min.z.toDouble() - overlapStart, LINE_THICKNESS, LINE_THICKNESS, zLength, RED_BLOCK_STATE))
		}
	}

	private fun minBoundaryStart(boundary: Double): Double {
		return boundary - (LINE_THICKNESS * OUTWARD_RATIO)
	}

	private fun maxBoundaryStart(boundary: Double): Double {
		return boundary - (LINE_THICKNESS * INWARD_RATIO)
	}

	private fun MutableList<RenderedEntity>.addIfPresent(renderedEntity: RenderedEntity?) {
		if (renderedEntity != null) {
			add(renderedEntity)
		}
	}

	private fun spawnDisplay(
		level: ServerLevel,
		x: Double,
		y: Double,
		z: Double,
		scaleX: Float,
		scaleY: Float,
		scaleZ: Float,
		blockState: BlockState,
	): RenderedEntity? {
		val display = Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level)
		display.setPos(x, y, z)
		display.setBlockState(blockState)
		display.setTransformation(
			Transformation(
				Vector3f(),
				Quaternionf(),
				Vector3f(scaleX, scaleY, scaleZ),
				Quaternionf(),
			)
		)
		display.setBrightnessOverride(Brightness.FULL_BRIGHT)
		display.setNoGravity(true)
		if (!level.addFreshEntity(display)) {
			return null
		}
		return RenderedEntity(
			dimension = level.dimension(),
			entityUuid = display.uuid,
		)
	}

	private data class RenderedEntity(
		val dimension: ResourceKey<Level>,
		val entityUuid: UUID,
	)
}
