package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseHopperMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.UUID

internal class SessionActionMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val sessionId: UUID,
	private val returnPage: Int,
) : BaseHopperMenu(playerId, containerId, playerInventory) {
	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	private fun refreshMenu() {
		resetMenu()

		val session = AiService.allSessions(playerId).firstOrNull { it.id == sessionId }

		setButton(
			0,
			MenuItems.menuItem(
				item = Items.ARROW,
				name = Component.literal("Cancel").withStyle(ChatFormatting.YELLOW),
			)
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				OpenBlockMenu.openSessions(player, returnPage)
			}
		}

		setDisplayItem(2, sessionInfoItem(session))

		setButton(
			3,
			MenuItems.menuItem(
				item = Items.EMERALD,
				name = Component.literal("Select").withStyle(ChatFormatting.GREEN),
				lore = listOf(Component.literal("Make this the active session").withStyle(ChatFormatting.GRAY)),
			)
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				AiService.selectSession(playerId, sessionId)
					.onSuccess { OpenBlockMenu.openSessions(player, returnPage) }
			}
		}

		setButton(
			4,
			MenuItems.menuItem(
				item = Items.BARRIER,
				name = Component.literal("Delete").withStyle(ChatFormatting.RED),
				lore = listOf(Component.literal("Remove this logged session").withStyle(ChatFormatting.GRAY)),
			)
		) { player, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				AiService.deleteSession(playerId, sessionId)
					.onSuccess { OpenBlockMenu.openSessions(player, returnPage) }
			}
		}

		broadcastChanges()
	}

	private fun sessionInfoItem(session: SessionSummary?) = MenuItems.menuItem(
		item = OpenBlockMenuSupport.providerWool(session?.lastResponseProviderName),
		name = Component.literal(session?.let(OpenBlockMenuSupport::sessionLabel) ?: "Unknown Session").withStyle(ChatFormatting.YELLOW),
		lore = buildList {
			if (session == null) {
				add(Component.literal("The session log could not be loaded.").withStyle(ChatFormatting.RED))
				return@buildList
			}

			add(
				Component.literal("User messages: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(session.userMessageCount.toString()).withStyle(ChatFormatting.WHITE))
			)
			session.lastResponseProviderName?.let { providerName ->
				add(
					Component.literal("Last responder: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(OpenBlockMenuSupport.providerDisplayName(providerName)).withStyle(ChatFormatting.WHITE))
				)
			}
		},
	)
}
