package me.wanttobee.mineai.ai

import me.wanttobee.mineai.ai.sessions.AiTargetManager
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.cos

object AiActionBarManager {
	private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { runnable ->
		Thread(runnable, "mineai-actionbar").apply {
			isDaemon = true
		}
	}
	private val tasks = ConcurrentHashMap<UUID, ActionBarTask>()

	fun start(server: MinecraftServer, playerId: UUID, target: AiTargetManager.AiTarget, action: String) {
		stop(server, playerId)

		val state = ActionBarState(
			modelName = target.model.displayName,
			action = action,
			colorA = target.provider.progressColorA,
			colorB = target.provider.progressColorB,
			startedAtMillis = System.currentTimeMillis(),
		)

		sendFrame(server, playerId, state)
		val future = scheduler.scheduleAtFixedRate(
			{ sendFrame(server, playerId, state) },
			150L,
			150L,
			TimeUnit.MILLISECONDS,
		)
		tasks[playerId] = ActionBarTask(state, future)
	}

	fun updateAction(server: MinecraftServer, playerId: UUID, action: String) {
		val existing = tasks[playerId] ?: return
		val updated = existing.state.copy(action = action)
		existing.future.cancel(false)
		sendFrame(server, playerId, updated)
		val future = scheduler.scheduleAtFixedRate(
			{ sendFrame(server, playerId, updated) },
			150L,
			150L,
			TimeUnit.MILLISECONDS,
		)
		tasks[playerId] = ActionBarTask(updated, future)
	}

	fun stop(server: MinecraftServer, playerId: UUID) {
		tasks.remove(playerId)?.future?.cancel(false)
		server.execute {
			val player = server.playerList.getPlayer(playerId) ?: return@execute
			player.connection.send(ClientboundSetActionBarTextPacket(Component.empty()))
		}
	}

	private fun sendFrame(server: MinecraftServer, playerId: UUID, state: ActionBarState) {
		server.execute {
			val player = server.playerList.getPlayer(playerId) ?: return@execute
			val color = interpolateColor(
				state.colorA,
				state.colorB,
				normalizedPhase(state.startedAtMillis),
			)
			val component = Component.literal("● ")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)))
				.append(Component.literal("${state.modelName} - ${state.action}").withStyle(Style.EMPTY.withColor(0xFFFFFF)))
			player.connection.send(ClientboundSetActionBarTextPacket(component))
		}
	}

	private fun normalizedPhase(startedAtMillis: Long): Float {
		val elapsed = System.currentTimeMillis() - startedAtMillis
		val radians = (elapsed % 1600L).toDouble() / 1600.0 * (Math.PI * 2.0)
		return ((1.0 - cos(radians)) / 2.0).toFloat()
	}

	private fun interpolateColor(colorA: Int, colorB: Int, t: Float): Int {
		val red = interpolateChannel(colorA shr 16 and 0xFF, colorB shr 16 and 0xFF, t)
		val green = interpolateChannel(colorA shr 8 and 0xFF, colorB shr 8 and 0xFF, t)
		val blue = interpolateChannel(colorA and 0xFF, colorB and 0xFF, t)
		return (red shl 16) or (green shl 8) or blue
	}

	private fun interpolateChannel(a: Int, b: Int, t: Float): Int {
		return (a + ((b - a) * t)).toInt().coerceIn(0, 255)
	}

	private data class ActionBarTask(
		val state: ActionBarState,
		val future: ScheduledFuture<*>,
	)

	private data class ActionBarState(
		val modelName: String,
		val action: String,
		val colorA: Int,
		val colorB: Int,
		val startedAtMillis: Long,
	)
}
