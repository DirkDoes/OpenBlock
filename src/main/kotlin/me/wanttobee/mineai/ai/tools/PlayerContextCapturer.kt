package me.wanttobee.mineai.ai.tools

import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import java.util.Locale
import java.util.UUID

object PlayerContextCapturer {
	@Volatile
	private var server: MinecraftServer? = null

	fun bind(server: MinecraftServer) {
		this.server = server
	}

	fun clear(server: MinecraftServer) {
		if (this.server === server)
			this.server = null
	}

	fun capture(playerId: UUID): PlayerContext? {
		val player = server?.playerList?.getPlayer(playerId) ?: return null
		val gameType = player.gameMode.gameModeForPlayer

		return PlayerContext(
			username = player.scoreboardName,
			gameMode = when (gameType) {
				GameType.CREATIVE -> "creative mode"
				GameType.SURVIVAL -> "survival mode"
				GameType.ADVENTURE -> "adventure mode"
				GameType.SPECTATOR -> "spectator mode"
			},
			positionX = player.x,
			positionY = player.y,
			positionZ = player.z,
			yaw = player.yRot,
			pitch = player.xRot,
			dimension = when (player.level().dimension()) {
				Level.OVERWORLD -> "overworld"
				Level.NETHER -> "the nether"
				Level.END -> "the end"
				else -> player.level().dimension().toString()
			},
			health = if (gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE) player.health else null,
			experienceLevel = if (gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE) player.experienceLevel else null,
			hunger = if (gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE) player.foodData.foodLevel else null,
		)
	}

	data class PlayerContext(
		val username: String,
		val gameMode: String,
		val positionX: Double,
		val positionY: Double,
		val positionZ: Double,
		val yaw: Float,
		val pitch: Float,
		val dimension: String,
		val health: Float?,
		val experienceLevel: Int?,
		val hunger: Int?,
	) {
		fun promptPrefix(): String {
			val parts = mutableListOf(
				gameMode,
			)

			if (health != null && experienceLevel != null && hunger != null) {
				parts += "hp(${formatNumber(health.toDouble())})"
				parts += "xp($experienceLevel)"
				parts += "hunger($hunger)"
			}

			parts += "pos(${formatNumber(positionX)}, ${formatNumber(positionY)}, ${formatNumber(positionZ)})/fac(${formatNumber(yaw.toDouble())}, ${formatNumber(pitch.toDouble())})"
			parts += dimension

			return "[${parts.joinToString(" - ")}]"
		}

		private fun formatNumber(value: Double): String {
			return String.format(Locale.ROOT, "%.1f", value)
		}
	}
}
