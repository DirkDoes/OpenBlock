package me.wanttobee.openblock.util

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

object MinecraftTextFormatter {
	private val colors = mapOf(
		'0' to ChatFormatting.BLACK,
		'1' to ChatFormatting.DARK_BLUE,
		'2' to ChatFormatting.DARK_GREEN,
		'3' to ChatFormatting.DARK_AQUA,
		'4' to ChatFormatting.DARK_RED,
		'5' to ChatFormatting.DARK_PURPLE,
		'6' to ChatFormatting.GOLD,
		'7' to ChatFormatting.GRAY,
		'8' to ChatFormatting.DARK_GRAY,
		'9' to ChatFormatting.BLUE,
		'a' to ChatFormatting.GREEN,
		'b' to ChatFormatting.AQUA,
		'c' to ChatFormatting.RED,
		'd' to ChatFormatting.LIGHT_PURPLE,
		'e' to ChatFormatting.YELLOW,
		'f' to ChatFormatting.WHITE,
	)

	fun format(text: String, baseStyle: Style = Style.EMPTY): MutableComponent {
		val component = Component.empty()
		val buffer = StringBuilder()
		var style = baseStyle
		var index = 0

		fun flushBuffer() {
			if (buffer.isEmpty()) {
				return
			}

			component.append(Component.literal(buffer.toString()).withStyle(style))
			buffer.setLength(0)
		}

		while (index < text.length) {
			if (text[index] == '§' && index + 1 < text.length) {
				val code = text[index + 1].lowercaseChar()

				if (code == '#' && index + 7 < text.length) {
					val hex = text.substring(index + 2, index + 8)
					if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
						flushBuffer()
						style = baseStyle.withColor(TextColor.fromRgb(hex.toInt(16)))
						index += 8
						continue
					}
				}

				val color = colors[code]
				if (color != null) {
					flushBuffer()
					style = baseStyle.withColor(color)
					index += 2
					continue
				}

				val updatedStyle = when (code) {
					'k' -> style.withObfuscated(true)
					'l' -> style.withBold(true)
					'm' -> style.withStrikethrough(true)
					'n' -> style.withUnderlined(true)
					'o' -> style.withItalic(true)
					'r' -> baseStyle
					else -> null
				}

				if (updatedStyle != null) {
					flushBuffer()
					style = updatedStyle
					index += 2
					continue
				}
			}

			buffer.append(text[index])
			index += 1
		}

		flushBuffer()
		return component
	}
}
