package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkRunsManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseListMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class BenchmarkPresetSessionsMenu(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	private val providerName: String,
	private val modelName: String,
	private val pathSegments: List<String>,
	private val entry: BenchmarkCatalogManager.CatalogEntry,
	initialPage: Int = 0,
	initialSelection: UUID? = null,
) : BaseListMenu(playerId, containerId, playerInventory, rows = 3, contentRows = 2, initialPage = initialPage) {
	private var selectedSessionId: UUID? = initialSelection

	init {
		refreshMenu()
	}

	override fun tick(player: ServerPlayer, tick: Long) = Unit

	override fun refreshMenu() {
		resetMenu()
		val sessions = BenchmarkRunsManager.presetRunSessions(providerName, modelName, pathSegments, entry).getOrElse { error ->
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.BARRIER,
					name = Component.literal("Unable to load sessions").withStyle(ChatFormatting.RED),
					lore = listOf(Component.literal(error.message ?: "Unknown benchmark session error.").withStyle(ChatFormatting.GRAY)),
				),
			)
			addPageNavigation(0) { player ->
				BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments, page, entry.storedName)
			}
			broadcastChanges()
			return
		}

		if (selectedSessionId !in sessions.map(BenchmarkRunsManager.PresetRunSession::sessionId).toSet()) {
			selectedSessionId = null
		}

		if (sessions.isEmpty()) {
			setDisplayItem(
				centerContentSlot(),
				MenuItems.menuItem(
					item = Items.GRAY_WOOL,
					name = Component.literal("No sessions").withStyle(ChatFormatting.GRAY),
					lore = listOf(Component.literal("This benchmark preset has not been run yet.").withStyle(ChatFormatting.GRAY)),
				),
			)
		} else {
			pageEntries(sessions).forEachIndexed { index, session ->
				setButton(index, sessionItem(session)) { _, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP) {
						selectedSessionId = if (selectedSessionId == session.sessionId) null else session.sessionId
						refreshMenu()
					}
				}
			}
		}

		addPageNavigation(sessions.size) { player ->
			BenchmarkMenu.openModelRuns(player, providerName, modelName, pathSegments, page, entry.storedName)
		}
		val selectedSession = sessions.firstOrNull { session -> session.sessionId == selectedSessionId }
		if (selectedSession != null) {
			setButton(
				footerLeftOuterSlot,
				MenuItems.menuItem(
					item = Items.STRUCTURE_BLOCK,
					name = Component.literal("Place It").withStyle(ChatFormatting.YELLOW),
					lore = listOf(Component.literal("Place this session result at your current position.").withStyle(ChatFormatting.GRAY)),
				),
			) { player, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkRunsManager.placeRunSession(player.uuid, providerName, modelName, selectedSession.benchmarkPath, selectedSession.sessionId)
						.onFailure { error ->
							player.sendSystemMessage(
								Component.literal(error.message ?: "Unable to place that benchmark session.").withStyle(ChatFormatting.RED),
							)
						}
				}
			}
			setButton(
				footerRightInnerSlot,
				MenuItems.menuItem(
					item = Items.LIME_WOOL,
					name = Component.literal("Set Success").withStyle(ChatFormatting.GREEN),
				),
			) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkRunsManager.setRunValidation(
						providerName,
						modelName,
						selectedSession.benchmarkPath,
						selectedSession.sessionId,
						BenchmarkRunsManager.RunValidationStatus.SUCCESS,
					)
					refreshMenu()
				}
			}
			setButton(
				footerRightOuterSlot,
				MenuItems.menuItem(
					item = Items.RED_WOOL,
					name = Component.literal("Set Failure").withStyle(ChatFormatting.RED),
				),
			) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					BenchmarkRunsManager.setRunValidation(
						providerName,
						modelName,
						selectedSession.benchmarkPath,
						selectedSession.sessionId,
						BenchmarkRunsManager.RunValidationStatus.FAILURE,
					)
					refreshMenu()
				}
			}
		}
		broadcastChanges()
	}

	override fun hasDeselectableSelection(): Boolean = selectedSessionId != null

	override fun clearSelection() {
		selectedSessionId = null
		refreshMenu()
	}

	private fun sessionItem(session: BenchmarkRunsManager.PresetRunSession) = MenuItems.menuItem(
		item = when {
			!session.considered -> Items.LIGHT_GRAY_WOOL
			session.status == BenchmarkRunsManager.RunValidationStatus.SUCCESS -> Items.LIME_WOOL
			session.status == BenchmarkRunsManager.RunValidationStatus.FAILURE -> Items.RED_WOOL
			else -> Items.CYAN_WOOL
		},
		name = Component.literal("Session ${session.sessionId.toString().take(8)}").withStyle(ChatFormatting.WHITE),
		lore = listOf(
			Component.literal("ran: ${formattedTimestamp(session.recordedAt)}").withStyle(ChatFormatting.GRAY),
			Component.literal("status: ${statusLabel(session)}").withStyle(ChatFormatting.GRAY),
			Component.literal("input tokens: ${session.tokenUsage.inputTokens}").withStyle(ChatFormatting.GRAY),
			Component.literal("output tokens: ${session.tokenUsage.outputTokens}").withStyle(ChatFormatting.GRAY),
			Component.literal("cached tokens: ${session.tokenUsage.cachedTokens}").withStyle(ChatFormatting.GRAY),
		),
		glint = selectedSessionId == session.sessionId,
	)

	private fun statusLabel(session: BenchmarkRunsManager.PresetRunSession): String {
		return if (!session.considered) {
			"ignored"
		} else when (session.status) {
			BenchmarkRunsManager.RunValidationStatus.SUCCESS -> "success"
			BenchmarkRunsManager.RunValidationStatus.FAILURE -> "failure"
			BenchmarkRunsManager.RunValidationStatus.UNDETERMINED -> "undetermined"
		}
	}

	private fun formattedTimestamp(value: String?): String {
		val timestamp = value ?: return "unknown"
		return runCatching {
			OffsetDateTime.parse(timestamp).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
		}.getOrDefault(timestamp)
	}
}
