package me.wanttobee.openblock.ai.context

import me.wanttobee.openblock.ai.toolcalling.AiTool
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import java.util.UUID

object PlayerInspectionFormatter {
	fun onlinePlayers(): List<Map<String, Any?>> {
		val server = PlayerContextCapturer.currentServer() ?: return emptyList()
		return server.playerList.players.map { player ->
			val context = PlayerContextCapturer.capture(player.uuid)
			mapOf(
				"uuid" to player.uuid.toString(),
				"username" to player.scoreboardName,
				"context" to context?.promptPrefix(),
			)
		}
	}

	fun onlinePlayerSuggestions(): List<AiTool.Suggestion> {
		val server = PlayerContextCapturer.currentServer() ?: return emptyList()
		return server.playerList.players.map { player ->
			AiTool.Suggestion(
				value = player.uuid.toString(),
				description = player.scoreboardName,
			)
		}
	}

	fun playerDetails(playerId: UUID): Map<String, Any?>? {
		val server = PlayerContextCapturer.currentServer() ?: return null
		val player = server.playerList.getPlayer(playerId) ?: return null
		val context = PlayerContextCapturer.capture(playerId)
		val inventory = player.inventory

		return buildMap {
			put("uuid", player.uuid.toString())
			put("username", player.scoreboardName)
			put("context", context?.promptPrefix())
			put("main_hand", describeItem(player.mainHandItem))
			put("off_hand", describeItem(player.offhandItem))
			put(
				"armor",
				mapOf(
					"helmet" to describeItem(player.getItemBySlot(EquipmentSlot.HEAD)),
					"chestplate" to describeItem(player.getItemBySlot(EquipmentSlot.CHEST)),
					"leggings" to describeItem(player.getItemBySlot(EquipmentSlot.LEGS)),
					"boots" to describeItem(player.getItemBySlot(EquipmentSlot.FEET)),
				)
			)
			put("inventory", describeInventory(inventory))
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
	}

	private fun describeInventory(inventory: net.minecraft.world.entity.player.Inventory): Map<String, List<Map<String, Any?>>> {
		val rows = linkedMapOf<String, IntRange>(
			"hotbar" to 0..8,
			"row2" to 9..17,
			"row3" to 18..26,
			"row4" to 27..35,
		)

		return rows.mapValues { (_, slots) ->
			slots.mapNotNull { index ->
				val stack = inventory.getItem(index)
				if (stack.isEmpty) {
					return@mapNotNull null
				}
				describeItem(stack)?.plus(
					mapOf(
						"slot" to index,
					)
				)
			}
		}
	}

	private fun describeItem(stack: ItemStack): Map<String, Any?>? {
		if (stack.isEmpty) {
			return null
		}

		val itemData = linkedMapOf<String, Any?>(
			"item" to BuiltInRegistries.ITEM.getKey(stack.item).toString(),
			"name" to stack.hoverName.string,
			"count" to stack.count,
		)

		if (stack.has(DataComponents.CUSTOM_NAME)) {
			itemData["custom_name"] = stack.get(DataComponents.CUSTOM_NAME)?.string
		}

		if (stack.isDamageableItem) {
			itemData["durability"] = mapOf(
				"remaining" to (stack.maxDamage - stack.damageValue),
				"max" to stack.maxDamage,
				"damage" to stack.damageValue,
			)
		}

		val enchantments = stack.get(DataComponents.ENCHANTMENTS)
		if (enchantments != null) {
			itemData["enchantments"] = enchantments.entrySet().map { entry ->
				val enchantmentId = entry.key.unwrapKey().map { it.toString() }.orElse("unknown")
				mapOf(
					"id" to enchantmentId,
					"level" to entry.intValue,
				)
			}
		}

		return itemData
	}
}
