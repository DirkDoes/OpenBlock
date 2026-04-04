package me.wanttobee.openblock.sandbox

import me.wanttobee.openblock.ai.sessions.AiSessionManager
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import kotlin.math.ceil

object SandboxOutlineRenderer {
	private const val RENDER_INTERVAL_TICKS = 10
	private const val TARGET_SPACING = 1.5
	private const val MAX_POINTS_PER_EDGE = 64
	private val WHITE_OUTLINE = DustParticleOptions(0xFFFFFF, 1.0f)
	private val YELLOW_OUTLINE = DustParticleOptions(0xFFFF00, 1.0f)

	private var tickCounter = 0

	fun bind() {
		ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
			tickCounter = (tickCounter + 1) % RENDER_INTERVAL_TICKS
			if (tickCounter == 0) {
				render(server)
			}
		})
	}

	private fun render(server: MinecraftServer) {
		for (player in server.playerList.players) {
			val sandbox = AiSessionManager.getSession(player.uuid).getOrNull()?.sandbox() ?: continue
			if (player.level().dimension() != sandbox.dimension) {
				continue
			}
			renderSandbox(player, sandbox)
		}
	}

	private fun renderSandbox(player: ServerPlayer, sandbox: Sandbox) {
		val level = player.level()
		renderRegion(level, player, sandbox.boundary, WHITE_OUTLINE)
		for (position in sandbox.interactions.values) {
			renderRegion(
				level,
				player,
				SandboxRegion(
					firstCorner = position.immutable(),
					secondCorner = position.immutable(),
				),
				YELLOW_OUTLINE,
			)
		}
	}

	private fun renderRegion(
		level: net.minecraft.server.level.ServerLevel,
		player: ServerPlayer,
		region: SandboxRegion,
		particle: DustParticleOptions,
	) {
		for (point in edgePoints(region)) {
			level.sendParticles(
				player,
				particle,
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

	private data class Point(
		val x: Double,
		val y: Double,
		val z: Double,
	)
}
