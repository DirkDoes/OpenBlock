package me.wanttobee.mineai.ai.context

import net.minecraft.server.MinecraftServer
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.core.registries.BuiltInRegistries
import java.util.Locale
import java.util.UUID

object PlayerContextCapturer {
	private const val LOOK_BLOCK_DISTANCE = 7.0

	@Volatile
	private var server: MinecraftServer? = null

	fun bind(server: MinecraftServer) {
		this.server = server
	}

	fun clear(server: MinecraftServer) {
		if (this.server === server)
			this.server = null
	}

	fun currentServer(): MinecraftServer? = server

	fun capture(playerId: UUID): PlayerContext? {
		val player = server?.playerList?.getPlayer(playerId) ?: return null
		val gameType = player.gameMode.gameModeForPlayer
		val lookingAt = when (val hitResult = player.pick(LOOK_BLOCK_DISTANCE, 0.0f, false)) {
			is BlockHitResult -> if (hitResult.type == HitResult.Type.BLOCK) {
				val position = hitResult.blockPos
				val blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).block).toString()
				LookedAtBlock(
					block = blockId,
					positionX = position.x,
					positionY = position.y,
					positionZ = position.z,
				)
			} else {
				null
			}
			else -> null
		}

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
			lookingAt = lookingAt,
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
		val lookingAt: LookedAtBlock?,
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
			parts += lookingAt?.let { lookedAt ->
				"look(${lookedAt.block} at ${lookedAt.positionX}, ${lookedAt.positionY}, ${lookedAt.positionZ})"
			} ?: "look(no block)"
			parts += dimension

			return "[${parts.joinToString(" - ")}]"
		}

		private fun formatNumber(value: Double): String {
			return String.format(Locale.ROOT, "%.1f", value)
		}
	}

	data class LookedAtBlock(
		val block: String,
		val positionX: Int,
		val positionY: Int,
		val positionZ: Int,
	)
}
