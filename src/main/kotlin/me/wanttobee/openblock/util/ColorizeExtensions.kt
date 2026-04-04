package me.wanttobee.openblock.util

import net.minecraft.ChatFormatting
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.Block

private val chatFormattingToDyeName = mapOf(
	ChatFormatting.DARK_BLUE to "blue",
	ChatFormatting.BLUE to "blue",
	ChatFormatting.DARK_AQUA to "cyan",
	ChatFormatting.AQUA to "light_blue",
	ChatFormatting.DARK_GREEN to "green",
	ChatFormatting.GREEN to "lime",
	ChatFormatting.YELLOW to "yellow",
	ChatFormatting.GOLD to "orange",
	ChatFormatting.RED to "red",
	ChatFormatting.DARK_RED to "brown",
	ChatFormatting.LIGHT_PURPLE to "magenta",
	ChatFormatting.DARK_PURPLE to "purple",
	ChatFormatting.BLACK to "black",
	ChatFormatting.DARK_GRAY to "gray",
	ChatFormatting.GRAY to "light_gray",
)

private val dyePrefixes = listOf(
	"white",
	"orange",
	"magenta",
	"light_blue",
	"yellow",
	"lime",
	"pink",
	"gray",
	"light_gray",
	"cyan",
	"purple",
	"blue",
	"brown",
	"green",
	"red",
	"black",
)

// Maps chat colors to the nearest dye color and swaps the color prefix when that variant exists.
fun ItemLike.colorize(color: ChatFormatting): ItemLike {
	val dyeName = chatFormattingToDyeName[color] ?: return this
	val item = asItem()
	val itemId = BuiltInRegistries.ITEM.getKey(item)
	val recoloredId = Identifier.fromNamespaceAndPath(itemId.namespace, recoloredPath(itemId.path, dyeName))
	if (!BuiltInRegistries.ITEM.containsKey(recoloredId)) {
		return item
	}
	return BuiltInRegistries.ITEM.getValue(recoloredId)
}

fun Block.colorize(color: ChatFormatting): Block {
	val dyeName = chatFormattingToDyeName[color] ?: return this
	val blockId = BuiltInRegistries.BLOCK.getKey(this)
	val recoloredId = Identifier.fromNamespaceAndPath(blockId.namespace, recoloredPath(blockId.path, dyeName))
	if (!BuiltInRegistries.BLOCK.containsKey(recoloredId)) {
		return this
	}
	return BuiltInRegistries.BLOCK.getValue(recoloredId)
}

private fun recoloredPath(path: String, dyeName: String): String {
	val basePath = dyePrefixes.firstOrNull { prefix -> path.startsWith("${prefix}_") }
		?.let { prefix -> path.removePrefix("${prefix}_") }
		?: path
	return "${dyeName}_$basePath"
}
