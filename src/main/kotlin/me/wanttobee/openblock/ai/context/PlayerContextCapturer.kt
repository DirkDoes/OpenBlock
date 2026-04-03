package me.wanttobee.openblock.ai.context

import me.wanttobee.openblock.ai.toolcalling.base.AiToolSuggestion
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
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

	fun currentServer(): Result<MinecraftServer> {
		return server?.let(Result.Companion::success)
			?: Result.failure(IllegalStateException("Minecraft server is not bound yet."))
	}

	fun capture(playerId: UUID): Result<PlayerContext> {
		val currentServer = currentServer().getOrElse { return Result.failure(it) }
		val player = currentServer.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Player is not online: $playerId"))
		val gameType = player.gameMode.gameModeForPlayer
		val lookingAt = when (val hitResult = player.pick(LOOK_BLOCK_DISTANCE, 0.0f, false)) {
			is BlockHitResult -> if (hitResult.type == HitResult.Type.BLOCK) {
				val position = hitResult.blockPos
				val blockId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).block).toString()
				PlayerContext.LookedAtBlock(
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

		return Result.success(PlayerContext(
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
		))
	}

	fun onlinePlayers(): Result<List<Map<String, Any?>>> {
		val currentServer = currentServer().getOrElse { return Result.failure(it) }
		return Result.success(
			currentServer.playerList.players.map { player ->
				mapOf(
					"uuid" to player.uuid.toString(),
					"username" to player.scoreboardName,
					"context" to capture(player.uuid).getOrNull()?.promptPrefix(),
				)
			}
		)
	}

	fun onlinePlayerSuggestions(): Result<List<AiToolSuggestion>> {
		val currentServer = currentServer().getOrElse { return Result.failure(it) }
		return Result.success(
			currentServer.playerList.players.map { player ->
				AiToolSuggestion(
					value = player.uuid.toString(),
					description = player.scoreboardName,
				)
			}
		)
	}

	fun playerDetails(playerId: UUID): Result<Map<String, Any?>> {
		val currentServer = currentServer().getOrElse { return Result.failure(it) }
		val player = currentServer.playerList.getPlayer(playerId)
			?: return Result.failure(NoSuchElementException("Player is not online: $playerId"))
		val context = capture(playerId).getOrElse { return Result.failure(it) }
		val inventory = ItemContextCapturer.describeInventory(player.inventory).getOrElse { return Result.failure(it) }

		return Result.success(
			buildMap {
				put("uuid", player.uuid.toString())
				put("username", player.scoreboardName)
				put("context", context.promptPrefix())
				put("main_hand", ItemContextCapturer.describeItem(player.mainHandItem).getOrNull())
				put("off_hand", ItemContextCapturer.describeItem(player.offhandItem).getOrNull())
				put(
					"armor",
					mapOf(
						"helmet" to ItemContextCapturer.describeItem(player.getItemBySlot(EquipmentSlot.HEAD)).getOrNull(),
						"chestplate" to ItemContextCapturer.describeItem(player.getItemBySlot(EquipmentSlot.CHEST)).getOrNull(),
						"leggings" to ItemContextCapturer.describeItem(player.getItemBySlot(EquipmentSlot.LEGS)).getOrNull(),
						"boots" to ItemContextCapturer.describeItem(player.getItemBySlot(EquipmentSlot.FEET)).getOrNull(),
					)
				)
				put("inventory", inventory)
				put(
					"effects",
					player.activeEffectsMap.values.map { effect ->
						mapOf(
							"effect" to effect.effect.unwrapKey().map { it.toString() }.orElse("unknown"),
							"amplifier" to (effect.amplifier + 1),
							"duration_ticks" to effect.duration,
							"ambient" to effect.isAmbient,
							"visible" to effect.isVisible,
						)
					}
				)
			}
		)
	}
}
