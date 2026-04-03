package me.wanttobee.openblock.interfaces.menu.openblockmenu

import me.wanttobee.openblock.ai.AiService
import me.wanttobee.openblock.ai.Providers
import me.wanttobee.openblock.ai.sessions.AiModel
import me.wanttobee.openblock.ai.sessions.Session
import me.wanttobee.openblock.ai.sessions.base.SessionSummary
import me.wanttobee.openblock.ai.toolcalling.base.AiTool
import me.wanttobee.openblock.ai.toolcalling.CommandToolsSupport
import me.wanttobee.openblock.interfaces.chat.ChatModeManager
import me.wanttobee.openblock.interfaces.menu.MenuItems
import me.wanttobee.openblock.interfaces.menu.base.BaseMenu
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

internal sealed interface OpenBlockMenuState {
	data object Main : OpenBlockMenuState
	data class Sessions(val page: Int = 0) : OpenBlockMenuState
	data object ProviderSelection : OpenBlockMenuState
	data class ModelSelection(val providerName: String, val page: Int = 0) : OpenBlockMenuState
	data class ReasoningSelection(val providerName: String, val modelName: String) : OpenBlockMenuState
	data class Tools(val page: Int = 0) : OpenBlockMenuState
	data class Commands(val page: Int = 0, val returnPage: Int = 0) : OpenBlockMenuState
}

internal class OpenBlockMenuHost(
	containerId: Int,
	playerInventory: Inventory,
	playerId: UUID,
	initialState: OpenBlockMenuState,
) : BaseMenu(playerId, containerId, playerInventory, 6) {
	private var state: OpenBlockMenuState = initialState

	override val refreshIntervalTicks: Long
		get() = if (state is OpenBlockMenuState.Main) 6L else Long.MAX_VALUE

	init {
		renderCurrent()
	}

	override fun tick(player: ServerPlayer, tick: Long) {
		if (state is OpenBlockMenuState.Main) {
			renderCurrent()
		}
	}

	fun switchTo(nextState: OpenBlockMenuState) {
		state = nextState
		renderCurrent()
	}

	private fun renderCurrent() {
		if (state is OpenBlockMenuState.Main) {
			OpenBlockMenuSupport.requestProviderPingRefresh()
		}

		resetMenu()
		when (val currentState = state) {
			OpenBlockMenuState.Main -> renderMainMenu()
			is OpenBlockMenuState.Sessions -> renderSessionsMenu(currentState.page)
			OpenBlockMenuState.ProviderSelection -> renderProviderSelectionMenu()
			is OpenBlockMenuState.ModelSelection -> renderModelSelectionMenu(currentState.providerName, currentState.page)
			is OpenBlockMenuState.ReasoningSelection -> renderReasoningSelectionMenu(currentState.providerName, currentState.modelName)
			is OpenBlockMenuState.Tools -> renderToolsMenu(currentState.page)
			is OpenBlockMenuState.Commands -> renderCommandsMenu(currentState.page, currentState.returnPage)
		}
		broadcastChanges()
	}

	private fun renderMainMenu() {
		setDisplayItem(PING_SLOT, pingItem())
		setButton(SESSIONS_SLOT, sessionsItem()) { _, button, input ->
			if (input != ContainerInput.PICKUP) {
				return@setButton
			}
			if (button == 0) {
				switchTo(OpenBlockMenuState.Sessions())
			} else if (button == 1 && AiService.clearSession(playerId)) {
				renderCurrent()
			}
		}
		setButton(MODEL_SLOT, modelItem()) { _, button, input ->
			if (input != ContainerInput.PICKUP) {
				return@setButton
			}
			if (button == 0) {
				switchTo(OpenBlockMenuState.ProviderSelection)
				return@setButton
			}

			val target = AiService.currentTarget(playerId).getOrNull() ?: return@setButton
			if (button == 1 && target.model.reasoningSupport.supportsReasoning()) {
				switchTo(OpenBlockMenuState.ReasoningSelection(target.provider.name, target.model.apiName))
			}
		}
		setButton(TOOLS_SLOT, toolsItem()) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				switchTo(OpenBlockMenuState.Tools())
			}
		}
		setButton(CHATMODE_SLOT, chatModeItem()) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				ChatModeManager.toggle(playerId)
				renderCurrent()
			}
		}
	}

	private fun renderSessionsMenu(page: Int) {
		val sessions = AiService.allSessions(playerId)
		val selectedSessionId = AiService.currentSessionId(playerId)
		val entriesPerPage = entriesPerPage(5)
		val normalizedPage = normalizedPage(page, sessions.size, entriesPerPage)
		if (normalizedPage != page) {
			state = OpenBlockMenuState.Sessions(normalizedPage)
		}

		if (sessions.isEmpty()) {
			setDisplayItem(
				centerContentSlot(5),
				MenuItems.menuItem(
					item = Items.WRITABLE_BOOK,
					name = Component.literal("No sessions yet").withStyle(ChatFormatting.YELLOW),
				)
			)
		} else {
			pageEntries(sessions, normalizedPage, entriesPerPage).forEachIndexed { index, session ->
				setButton(index, sessionItem(index, normalizedPage, entriesPerPage, session, selectedSessionId == session.id)) { _, button, input ->
					if (button == 0 && input == ContainerInput.PICKUP && AiService.selectSession(playerId, session.id)) {
						renderCurrent()
					}
				}
			}
		}

		addPageNavigation(
			totalEntryCount = sessions.size,
			page = normalizedPage,
			contentRows = 5,
			onBack = { switchTo(OpenBlockMenuState.Main) },
			onPageChange = { switchTo(OpenBlockMenuState.Sessions(it)) },
		)
	}

	private fun renderProviderSelectionMenu() {
		setProviderButton(11, "openai")
		setProviderButton(13, "claude")
		setProviderButton(15, "google")
		setButton(22, MenuItems.backItem()) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				switchTo(OpenBlockMenuState.Main)
			}
		}
	}

	private fun renderModelSelectionMenu(providerName: String, page: Int) {
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: run {
			switchTo(OpenBlockMenuState.ProviderSelection)
			return
		}
		val models = Providers.modelList(providerName).getOrElse { emptyList() }
		val currentTarget = AiService.currentTarget(playerId).getOrNull()
		val entriesPerPage = entriesPerPage(2)
		val normalizedPage = normalizedPage(page, models.size, entriesPerPage)
		if (normalizedPage != page) {
			state = OpenBlockMenuState.ModelSelection(providerName, normalizedPage)
		}

		pageEntries(models, normalizedPage, entriesPerPage).forEachIndexed { index, model ->
			val selected = currentTarget?.provider?.name == providerName &&
				currentTarget.model.apiName.equals(model.apiName, ignoreCase = true)
			setButton(index, modelItem(provider, model, selected)) { _, button, input ->
				if (button != 0 || input != ContainerInput.PICKUP) {
					return@setButton
				}

				val target = AiService.selectTarget(playerId, providerName, model.apiName).getOrElse { return@setButton }
				if (target.model.reasoningSupport.supportsReasoning()) {
					switchTo(OpenBlockMenuState.ReasoningSelection(providerName, target.model.apiName))
				} else {
					switchTo(OpenBlockMenuState.Main)
				}
			}
		}

		addPageNavigation(
			totalEntryCount = models.size,
			page = normalizedPage,
			contentRows = 2,
			onBack = { switchTo(OpenBlockMenuState.ProviderSelection) },
			onPageChange = { switchTo(OpenBlockMenuState.ModelSelection(providerName, it)) },
		)
	}

	private fun renderReasoningSelectionMenu(providerName: String, modelName: String) {
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: run {
			switchTo(OpenBlockMenuState.Main)
			return
		}
		val model = Providers.resolveModel(providerName, modelName).getOrNull() ?: run {
			switchTo(OpenBlockMenuState.Main)
			return
		}
		val options = OpenBlockMenuSupport.reasoningOptions(provider, model)
		val currentReasoningValue = OpenBlockMenuSupport.currentReasoningValue(playerId, providerName, model.apiName)
		val centeredSlots = listOf(20, 21, 22, 23, 24)

		options.take(centeredSlots.size).forEachIndexed { index, option ->
			setButton(
				centeredSlots[index],
				MenuItems.menuItem(
					item = option.item,
					name = Component.literal(option.label).withStyle(ChatFormatting.WHITE),
					lore = option.description?.let { listOf(Component.literal(it).withStyle(ChatFormatting.GRAY)) } ?: emptyList(),
					glint = currentReasoningValue.equals(option.value, ignoreCase = true),
				)
			) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					AiService.selectTarget(playerId, providerName, modelName, option.value)
						.onSuccess { switchTo(OpenBlockMenuState.Main) }
				}
			}
		}

		setButton(49, MenuItems.backItem()) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				switchTo(OpenBlockMenuState.ModelSelection(providerName))
			}
		}
	}

	private fun renderToolsMenu(page: Int) {
		val tools = AiService.allTools()
		val entriesPerPage = entriesPerPage(2)
		val normalizedPage = normalizedPage(page, tools.size, entriesPerPage)
		if (normalizedPage != page) {
			state = OpenBlockMenuState.Tools(normalizedPage)
		}

		pageEntries(tools, normalizedPage, entriesPerPage).forEachIndexed { index, tool ->
			setButton(index, toolItem(tool)) { _, button, input ->
				if (input != ContainerInput.PICKUP) {
					return@setButton
				}
				if (button == 1 && tool.hasConfigurationMenu) {
					switchTo(OpenBlockMenuState.Commands(returnPage = normalizedPage))
					return@setButton
				}
				if (button == 0 && AiService.setToolEnabled(playerId, tool.name, !AiService.isToolEnabled(playerId, tool.name))) {
					renderCurrent()
				}
			}
		}

		addPageNavigation(
			totalEntryCount = tools.size,
			page = normalizedPage,
			contentRows = 2,
			onBack = { switchTo(OpenBlockMenuState.Main) },
			onPageChange = { switchTo(OpenBlockMenuState.Tools(it)) },
		)
	}

	private fun renderCommandsMenu(page: Int, returnPage: Int) {
		val commands = CommandToolsSupport.commandEntries()
		val entriesPerPage = entriesPerPage(5)
		val normalizedPage = normalizedPage(page, commands.size, entriesPerPage)
		if (normalizedPage != page) {
			state = OpenBlockMenuState.Commands(normalizedPage, returnPage)
		}

		pageEntries(commands, normalizedPage, entriesPerPage).forEachIndexed { index, command ->
			setButton(index, commandItem(command)) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					CommandToolsSupport.setAllowed(command.name, !CommandToolsSupport.isAllowed(command.name))
					renderCurrent()
				}
			}
		}

		addPageNavigation(
			totalEntryCount = commands.size,
			page = normalizedPage,
			contentRows = 5,
			onBack = { switchTo(OpenBlockMenuState.Tools(returnPage)) },
			onPageChange = { switchTo(OpenBlockMenuState.Commands(it, returnPage)) },
		)
	}

	private fun setProviderButton(slot: Int, providerName: String) {
		val provider = Providers.getProviderByName(providerName).getOrNull() ?: return
		val selected = AiService.currentTarget(playerId).getOrNull()?.provider?.name == providerName
		setButton(
			slot,
			MenuItems.menuItem(
				item = OpenBlockMenuSupport.providerWool(provider),
				name = Component.literal(provider.displayName).withStyle(ChatFormatting.WHITE),
				lore = listOf(Component.literal("Select ${provider.displayName}").withStyle(ChatFormatting.GRAY)),
				glint = selected,
			)
		) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				switchTo(OpenBlockMenuState.ModelSelection(provider.name))
			}
		}
	}

	private fun pingItem() = MenuItems.menuItem(
		item = Items.COMPASS,
		name = Component.literal("Ping").withStyle(ChatFormatting.YELLOW),
		lore = providerPingLore(),
	)

	private fun sessionsItem(): ItemStack {
		val session = AiService.currentSessionSummary(playerId)
		val lore = buildList {
			add(Component.literal("Left click: open sessions").withStyle(ChatFormatting.GRAY))
			add(Component.literal("Right click: unselect current session").withStyle(ChatFormatting.GRAY))
			if (session == null) {
				add(Component.literal("Current session: none").withStyle(ChatFormatting.DARK_GRAY))
			} else {
				add(
					Component.literal("Current session: ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(OpenBlockMenuSupport.sessionLabel(session)).withStyle(ChatFormatting.WHITE))
				)
			}
		}

		return MenuItems.menuItem(
			item = if (session == null) Items.PAPER else Items.WRITABLE_BOOK,
			name = Component.literal("Sessions").withStyle(ChatFormatting.YELLOW),
			lore = lore,
		)
	}

	private fun modelItem() = MenuItems.menuItem(
		item = AiService.currentTarget(playerId).getOrNull()?.provider?.let(OpenBlockMenuSupport::providerWool) ?: Items.WHITE_WOOL,
		name = Component.literal("Model").withStyle(ChatFormatting.YELLOW),
		lore = buildList {
			val target = AiService.currentTarget(playerId).getOrNull()
			add(Component.literal("Left click: choose provider and model").withStyle(ChatFormatting.GRAY))
			add(Component.literal("Right click: choose reasoning").withStyle(ChatFormatting.GRAY))
			add(
				Component.literal("Provider: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(target?.provider?.displayName ?: "none").withStyle(ChatFormatting.WHITE))
			)
			add(
				Component.literal("Model: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(target?.model?.displayName ?: "none").withStyle(ChatFormatting.WHITE))
			)
			add(
				Component.literal("Reasoning: ").withStyle(ChatFormatting.GRAY)
					.append(
						Component.literal(
							OpenBlockMenuSupport.reasoningLabel(target?.provider, target?.model)
						).withStyle(ChatFormatting.WHITE)
					)
			)
		},
	)

	private fun toolsItem() = MenuItems.menuItem(
		item = Items.REDSTONE_TORCH,
		name = Component.literal("Tools").withStyle(ChatFormatting.YELLOW),
		lore = AiService.allTools().map { tool ->
			val enabled = AiService.isToolEnabled(playerId, tool.name)
			Component.literal("${OpenBlockMenuSupport.toolDisplayName(tool)}: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
		},
	)

	private fun chatModeItem(): ItemStack {
		val enabled = ChatModeManager.isEnabled(playerId)
		return MenuItems.menuItem(
			item = Items.BOOK,
			name = Component.literal("Chat Mode").withStyle(ChatFormatting.YELLOW),
			lore = listOf(
				Component.literal("Capture normal chat as AI prompts").withStyle(ChatFormatting.GRAY),
				Component.literal("State: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)),
			),
			glint = enabled,
		)
	}

	private fun sessionItem(
		index: Int,
		page: Int,
		entriesPerPage: Int,
		session: SessionSummary,
		selected: Boolean,
	) = MenuItems.menuItem(
		item = Items.WRITABLE_BOOK,
		name = Component.literal("Session ${index + 1 + page * entriesPerPage}").withStyle(ChatFormatting.YELLOW),
		lore = buildList {
			add(
				Component.literal("User messages: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(session.userMessageCount.toString()).withStyle(ChatFormatting.WHITE))
			)
			session.firstUserMessage?.takeIf { it.isNotBlank() }?.let { firstMessage ->
				add(Component.literal(OpenBlockMenuSupport.trimPreview(firstMessage)).withStyle(ChatFormatting.DARK_GRAY))
			}
		},
		count = session.userMessageCount.coerceIn(1, 64),
		glint = selected,
	)

	private fun modelItem(provider: me.wanttobee.openblock.ai.providers.AiProvider, model: AiModel, selected: Boolean): ItemStack {
		return MenuItems.menuItem(
			item = OpenBlockMenuSupport.providerWool(provider),
			name = Component.literal(model.displayName).withStyle(ChatFormatting.WHITE),
			lore = listOf(
				Component.literal("Provider: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.WHITE)),
				Component.literal("Reasoning: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(OpenBlockMenuSupport.reasoningSupportLabel(model)).withStyle(ChatFormatting.WHITE)),
			),
			glint = selected,
		)
	}

	private fun toolItem(tool: AiTool) = MenuItems.menuItem(
		item = tool.menuIcon,
		name = Component.literal(OpenBlockMenuSupport.toolDisplayName(tool)).withStyle(ChatFormatting.WHITE),
		lore = buildList {
			val enabled = AiService.isToolEnabled(playerId, tool.name)
			add(Component.literal(tool.description).withStyle(ChatFormatting.GRAY))
			add(
				Component.literal("State: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(if (enabled) "on" else "off").withStyle(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED))
			)
			if (tool.hasConfigurationMenu) {
				add(Component.literal("Right click: allowed commands").withStyle(ChatFormatting.DARK_GRAY))
			}
		},
		glint = AiService.isToolEnabled(playerId, tool.name),
	)

	private fun commandItem(command: CommandToolsSupport.CommandEntry) = MenuItems.menuItem(
		item = Items.NAME_TAG,
		name = Component.literal(command.name).withStyle(if (command.allowed) ChatFormatting.GREEN else ChatFormatting.RED),
		lore = listOf(
			Component.literal("State: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(if (command.allowed) "on" else "off").withStyle(if (command.allowed) ChatFormatting.GREEN else ChatFormatting.RED)),
			Component.literal("Default: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(if (command.defaultAllowed) "on" else "off").withStyle(if (command.defaultAllowed) ChatFormatting.GREEN else ChatFormatting.RED)),
		),
		glint = command.allowed,
	)

	private fun providerPingLore(): List<Component> {
		val statuses = OpenBlockMenuSupport.providerStatuses()
		if (statuses.isEmpty()) {
			return listOf(Component.literal("Checking providers...").withStyle(ChatFormatting.GRAY))
		}

		return Providers.all.map { provider ->
			val status = statuses[provider.name]
			if (status == null) {
				Component.literal("? ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.GRAY))
			} else if (status.reachable) {
				Component.literal("● ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(OpenBlockMenuSupport.animatedProviderColor(provider))))
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.WHITE))
			} else {
				Component.literal("X ").withStyle(ChatFormatting.RED)
					.append(Component.literal(provider.displayName).withStyle(ChatFormatting.RED))
			}
		}
	}

	private fun entriesPerPage(contentRows: Int): Int = contentRows * 9

	private fun centerContentSlot(contentRows: Int): Int = (entriesPerPage(contentRows) - 1) / 2

	private fun pageCount(totalEntryCount: Int, entriesPerPage: Int): Int {
		return totalEntryCount.coerceAtLeast(1).let { ((it - 1) / entriesPerPage) + 1 }
	}

	private fun normalizedPage(page: Int, totalEntryCount: Int, entriesPerPage: Int): Int {
		return page.coerceIn(0, pageCount(totalEntryCount, entriesPerPage) - 1)
	}

	private fun <T> pageEntries(entries: List<T>, page: Int, entriesPerPage: Int): List<T> {
		return entries.drop(page * entriesPerPage).take(entriesPerPage)
	}

	private fun addPageNavigation(
		totalEntryCount: Int,
		page: Int,
		contentRows: Int,
		onBack: () -> Unit,
		onPageChange: (Int) -> Unit,
	) {
		val entriesPerPage = entriesPerPage(contentRows)
		val pageCount = pageCount(totalEntryCount, entriesPerPage)
		val previousPageSlot = contentRows * 9
		val backSlot = previousPageSlot + 4
		val nextPageSlot = previousPageSlot + 8

		if (page > 0) {
			setButton(previousPageSlot, MenuItems.pageArrow("Previous Page")) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					onPageChange(page - 1)
				}
			}
		}
		setButton(backSlot, MenuItems.backItem()) { _, button, input ->
			if (button == 0 && input == ContainerInput.PICKUP) {
				onBack()
			}
		}
		if (page < pageCount - 1) {
			setButton(nextPageSlot, MenuItems.pageArrow("Next Page")) { _, button, input ->
				if (button == 0 && input == ContainerInput.PICKUP) {
					onPageChange(page + 1)
				}
			}
		}
	}

	private companion object {
		const val PING_SLOT = 10
		const val SESSIONS_SLOT = 13
		const val MODEL_SLOT = 16
		const val TOOLS_SLOT = 22
		const val CHATMODE_SLOT = 25
	}
}
