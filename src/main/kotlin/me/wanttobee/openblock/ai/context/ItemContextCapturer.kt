package me.wanttobee.openblock.ai.context

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

object ItemContextCapturer {
	fun describeInventory(inventory: Inventory): Result<Map<String, List<Map<String, Any?>>>> {
		val rows = linkedMapOf(
			"hotbar" to 0..8,
			"row2" to 9..17,
			"row3" to 18..26,
			"row4" to 27..35,
		)

		return Result.success(
			rows.mapValues { (_, slots) ->
				slots.mapNotNull { index ->
					describeItem(inventory.getItem(index))
						.getOrNull()
						?.plus(mapOf("slot" to index))
				}
			}
		)
	}

	fun describeItem(stack: ItemStack): Result<Map<String, Any?>> {
		if (stack.isEmpty) {
			return Result.failure(NoSuchElementException("Item stack is empty."))
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

		return Result.success(itemData)
	}
}
