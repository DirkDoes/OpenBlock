package me.wanttobee.openblock.interfaces.sandbox

import me.wanttobee.openblock.ai.sessions.AiSessionManager
import me.wanttobee.openblock.sandbox.Sandbox
import me.wanttobee.openblock.sandbox.SandboxManager
import me.wanttobee.openblock.sandbox.SandboxRegion
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

object ParticleSandboxRenderer {
	private const val RENDER_INTERVAL_TICKS = 1
	private const val TARGET_SPACING = 1.5
	private const val MAX_POINTS_PER_EDGE = 64
	private val WHITE_OUTLINE = DustParticleOptions(0xFFFFFF, 1.0f)
	private val YELLOW_OUTLINE = DustParticleOptions(0xFFFF00, 1.0f)
	private val RED_OUTLINE = DustParticleOptions(0xFF0000, 1.0f)
	private val cachedStatesByPlayer = ConcurrentHashMap<UUID, CachedSandboxState>()
	private var tickCounter = 0

	fun bind() {
		SandboxManager.subscribeCurrentSandboxChanges { playerId, sandbox ->
			if (SandboxManager.rendererMode(playerId) == SandboxManager.RendererMode.PARTICLES) {
				recache(playerId, sandbox)
			} else {
				cachedStatesByPlayer.remove(playerId)
			}
		}
		SandboxManager.subscribeRendererModeChanges { playerId, mode ->
			if (mode == SandboxManager.RendererMode.PARTICLES) {
				recache(playerId, currentSandbox(playerId))
			} else {
				cachedStatesByPlayer.remove(playerId)
			}
		}
		ServerPlayConnectionEvents.JOIN.register(ServerPlayConnectionEvents.Join { handler, _, _ ->
			if (SandboxManager.rendererMode(handler.player.uuid) == SandboxManager.RendererMode.PARTICLES) {
				recache(handler.player.uuid, currentSandbox(handler.player.uuid))
			}
		})
		ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { handler, _ ->
			cachedStatesByPlayer.remove(handler.player.uuid)
		})
		ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
			tickCounter += 1
			if (tickCounter % RENDER_INTERVAL_TICKS == 0) {
				render(server)
			}
		})
	}

	private fun currentSandbox(playerId: UUID): Sandbox? {
		return AiSessionManager.getSession(playerId).getOrNull()?.sandbox()
	}

	private fun recache(playerId: UUID, sandbox: Sandbox?) {
		if (sandbox == null) {
			cachedStatesByPlayer.remove(playerId)
			return
		}

		val cachedRegions = buildList {
			add(CachedRegion(sandbox.boundary, WHITE_OUTLINE))
			val targetPositions = sandbox.targets.values.map(BlockPos::immutable).toSet()
			val exclusionPositions = sandbox.exclusions.values.map(BlockPos::immutable).toSet()
			for (position in exclusionPositions) {
				val region = SandboxRegion(
					firstCorner = position.immutable(),
					secondCorner = position.immutable(),
				)
				add(CachedRegion(region, RED_OUTLINE))
				if (position in targetPositions) {
					add(CachedRegion(region, YELLOW_OUTLINE))
				}
			}
			for (position in targetPositions - exclusionPositions) {
				add(
					CachedRegion(
						SandboxRegion(
							firstCorner = position.immutable(),
							secondCorner = position.immutable(),
						),
						YELLOW_OUTLINE,
					)
				)
			}
		}
		cachedStatesByPlayer[playerId] = CachedSandboxState(
			dimension = sandbox.dimension,
			regions = cachedRegions,
		)
	}

	private fun render(server: MinecraftServer) {
		for ((playerId, cachedState) in cachedStatesByPlayer) {
			val player = server.playerList.getPlayer(playerId) ?: continue
			if (player.level().dimension() != cachedState.dimension) {
				continue
			}
			for (region in cachedState.regions) {
				renderRegion(player, region)
			}
		}
	}

	private fun renderRegion(player: ServerPlayer, region: CachedRegion) {
		val level = player.level()
		for (point in edgePoints(region.region)) {
			level.sendParticles(
				player,
				region.color,
				true,
				true,
				point.x,
				point.y,
				point.z,
				1,
				0.0,
				0.0,
				0.0,
				0.0,
			)
		}
	}

	private fun edgePoints(region: SandboxRegion): Set<Point> {
		val min = region.minCorner()
		val max = region.maxCorner()
		val x0 = min.x.toDouble()
		val y0 = min.y.toDouble()
		val z0 = min.z.toDouble()
		val x1 = max.x + 1.0
		val y1 = max.y + 1.0
		val z1 = max.z + 1.0
		val points = LinkedHashSet<Point>()

		for (x in axisSamples(x0, x1)) {
			points += Point(x, y0, z0)
			points += Point(x, y0, z1)
			points += Point(x, y1, z0)
			points += Point(x, y1, z1)
		}
		for (y in axisSamples(y0, y1)) {
			points += Point(x0, y, z0)
			points += Point(x0, y, z1)
			points += Point(x1, y, z0)
			points += Point(x1, y, z1)
		}
		for (z in axisSamples(z0, z1)) {
			points += Point(x0, y0, z)
			points += Point(x0, y1, z)
			points += Point(x1, y0, z)
			points += Point(x1, y1, z)
		}

		return points
	}

	private fun axisSamples(start: Double, endInclusive: Double): List<Double> {
		val length = (endInclusive - start).coerceAtLeast(0.0)
		if (length == 0.0) {
			return listOf(start)
		}

		val stepCount = ceil(length / TARGET_SPACING)
			.toInt()
			.coerceIn(1, MAX_POINTS_PER_EDGE)
		val stepSize = length / stepCount
		return (0..stepCount).map { index ->
			start + (stepSize * index)
		}
	}

	private data class CachedSandboxState(
		val dimension: net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
		val regions: List<CachedRegion>,
	)

	private data class CachedRegion(
		val region: SandboxRegion,
		val color: DustParticleOptions,
	)

	private data class Point(
		val x: Double,
		val y: Double,
		val z: Double,
	)
}
