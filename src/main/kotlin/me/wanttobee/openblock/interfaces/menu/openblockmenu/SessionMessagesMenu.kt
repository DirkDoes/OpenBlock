package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.sessions.base.SessionMessage
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Items
import java.util.UUID

internal class SessionMessagesMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val sessionId: UUID,
	private val returnPage: Int,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 6, contentRows = 5) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()

		val messages = AiService.loadSession(playerId, sessionId)
			.map { session ->
				session.messages().filter { message ->
					message.type == SessionMessage.Type.USER ||
						message.type == SessionMessage.Type.ASSISTANT ||
						message.type == SessionMessage.Type.ERROR
				}
			}
			.getOrElse { emptyList() }

		if (messages.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.WRITABLE_BOOK,
					name = Component.literal("No visible messages").withStyle(ChatFormatting.YELLOW),
					lore = listOf(
						Component.literal("User messages, final AI replies, and errors are shown here.")
							.withStyle(ChatFormatting.GRAY)
					),
				)
			)
		} else {
			pageEntries(messages).forEachIndexed { index, message ->
				setDisplayItem(index, messageItem(message))
			}
		}

		addPageNavigation(messages.size) { player ->
			OpenBlockMenu.openSessions(player, returnPage)
		}
		broadcastChanges()
	}

	private fun messageItem(message: SessionMessage) = MenuItems.menuItem(
		item = when (message.type) {
			SessionMessage.Type.USER -> Items.LIGHT_GRAY_WOOL
			SessionMessage.Type.ERROR -> Items.RED_STAINED_GLASS
			SessionMessage.Type.ASSISTANT -> OpenBlockMenuSupport.providerWool(message.providerName)
			SessionMessage.Type.TOOL -> Items.GRAY_WOOL
		},
		name = Component.literal(messageTitle(message)).withStyle(messageTitleStyle(message), ChatFormatting.ITALIC),
		lore = messageLore(message),
	)

	private fun messageTitle(message: SessionMessage): String {
		return when (message.type) {
			SessionMessage.Type.USER -> "User"
			SessionMessage.Type.ERROR -> {
				val modelDisplayName = OpenBlockMenuSupport.modelDisplayName(message.providerName, message.modelName)
				if (modelDisplayName == "Unknown") "Error" else "Error: $modelDisplayName"
			}
			SessionMessage.Type.ASSISTANT -> OpenBlockMenuSupport.modelDisplayName(message.providerName, message.modelName)
			SessionMessage.Type.TOOL -> "Tool"
		}
	}

	private fun messageTitleStyle(message: SessionMessage): ChatFormatting {
		return if (message.type == SessionMessage.Type.ERROR) {
			ChatFormatting.RED
		} else {
			ChatFormatting.GRAY
		}
	}

	private fun messageLore(message: SessionMessage): List<Component> {
		return buildList {
			message.generationDurationMillis?.let { durationMillis ->
				add(
					Component.literal("Generation time: ").withStyle(ChatFormatting.GRAY)
						.append(
							Component.literal(OpenBlockMenuSupport.formatGenerationDuration(durationMillis))
								.withStyle(ChatFormatting.WHITE)
						)
				)
			}
			addAll(
				OpenBlockMenuSupport.messageLore(
					message.content,
					if (message.type == SessionMessage.Type.ERROR) ChatFormatting.RED else ChatFormatting.WHITE,
				)
			)
		}
	}
}
