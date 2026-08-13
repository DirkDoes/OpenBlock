package me.wanttobee.openblock.ai.context

import java.util.Locale

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

	data class LookedAtBlock(
		val block: String,
		val positionX: Int,
		val positionY: Int,
		val positionZ: Int,
	)
}
