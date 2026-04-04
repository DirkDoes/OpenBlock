package me.wanttobee.openblock.interfaces.menu

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.ItemLike

internal object MenuItems {
	private const val MAX_TOOLTIP_LINE_LENGTH = 30
	private val nonItalicStyle = Style.EMPTY.withItalic(false)

	fun menuItem(
		item: ItemLike,
		name: Component,
		lore: List<Component> = emptyList(),
		count: Int = 1,
		glint: Boolean = false,
	): ItemStack {
		return ItemStack(item, count.coerceIn(1, 64)).apply {
			set(DataComponents.CUSTOM_NAME, name.copy().withStyle(nonItalicStyle))
			if (lore.isNotEmpty()) {
				set(DataComponents.LORE, ItemLore(wrapLore(lore)))
			}
			if (glint) {
				set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
			}
		}
	}

	fun backItem(): ItemStack {
		return menuItem(
			item = Items.ARROW,
			name = Component.literal("Back").withStyle(ChatFormatting.YELLOW),
		)
	}

	fun pageArrow(label: String): ItemStack {
		return menuItem(
			item = Items.ARROW,
			name = Component.literal(label).withStyle(ChatFormatting.YELLOW),
		)
	}

	private fun wrapLore(lore: List<Component>): List<Component> {
		return lore.flatMap(::wrapLoreLine)
	}

	private fun wrapLoreLine(line: Component): List<Component> {
		val words = mutableListOf<StyledWord>()
		line.visit({ style: Style, text: String ->
			text.split(Regex("\\s+"))
				.filter { it.isNotBlank() }
				.forEach { word -> words += StyledWord(word, style) }
			java.util.Optional.empty<Unit>()
		}, Style.EMPTY)

		if (words.isEmpty()) {
			return listOf(Component.empty())
		}

		val wrappedLines = mutableListOf<Component>()
		var currentLine = Component.empty()
		var currentLength = 0

		fun flushLine() {
			if (currentLength == 0) {
				return
			}
			wrappedLines += currentLine
			currentLine = Component.empty()
			currentLength = 0
		}

		for (word in words) {
			for (chunk in splitWord(word)) {
				val separatorLength = if (currentLength == 0) 0 else 1
				if (currentLength > 0 && currentLength + separatorLength + chunk.text.length > MAX_TOOLTIP_LINE_LENGTH) {
					flushLine()
				}

				if (currentLength > 0) {
					currentLine.append(Component.literal(" ").withStyle(chunk.style.withItalic(false)))
					currentLength += 1
				}

				currentLine.append(Component.literal(chunk.text).withStyle(chunk.style.withItalic(false)))
				currentLength += chunk.text.length

				if (chunk.text.length == MAX_TOOLTIP_LINE_LENGTH) {
					flushLine()
				}
			}
		}

		flushLine()
		return wrappedLines.map { line -> line.copy().withStyle(nonItalicStyle) }
	}

	private fun splitWord(word: StyledWord): List<StyledWord> {
		if (word.text.length <= MAX_TOOLTIP_LINE_LENGTH) {
			return listOf(word)
		}

		return word.text.chunked(MAX_TOOLTIP_LINE_LENGTH).map { chunk ->
			StyledWord(chunk, word.style)
		}
	}

	private data class StyledWord(
		val text: String,
		val style: Style,
	)
}
