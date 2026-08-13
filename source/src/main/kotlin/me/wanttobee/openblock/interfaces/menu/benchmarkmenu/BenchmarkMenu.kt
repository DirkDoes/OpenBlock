package me.wanttobee.openblock.interfaces.menu.benchmarkmenu

import me.wanttobee.openblock.benchmarking.BenchmarkCatalogManager
import me.wanttobee.openblock.benchmarking.BenchmarkBookInputManager
import me.wanttobee.openblock.benchmarking.BenchmarkPresetManager
import me.wanttobee.openblock.benchmarking.BenchmarkTagManager
import me.wanttobee.openblock.interfaces.menu.base.AnvilInputMenu
import me.wanttobee.openblock.interfaces.menu.base.MenuOpener
import me.wanttobee.openblock.interfaces.menu.openblockmenu.OpenBlockMenuSupport
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal object BenchmarkMenu {
	fun open(player: ServerPlayer) {
		openMain(player)
	}

	fun openMain(player: ServerPlayer) {
		MenuOpener.open(player, Component.literal("Benchmark")) { containerId, inventory ->
			BenchmarkMainMenu(containerId, inventory, player.uuid)
		}
	}

	fun openCatalog(
		player: ServerPlayer,
		pathSegments: List<String> = emptyList(),
		page: Int = 0,
		initialSelection: String? = null,
	) {
		MenuOpener.open(player, Component.literal(catalogTitle(pathSegments))) { containerId, inventory ->
			BenchmarkCatalogMenu(containerId, inventory, player.uuid, pathSegments, page, initialSelection)
		}
	}

	fun openTags(
		player: ServerPlayer,
		page: Int = 0,
		initialSelection: String? = null,
	) {
		MenuOpener.open(player, Component.literal("Benchmark Tags")) { containerId, inventory ->
			BenchmarkTagMenu(containerId, inventory, player.uuid, page, initialSelection)
		}
	}

	fun openRuns(player: ServerPlayer) {
		MenuOpener.open(player, Component.literal("Benchmark Runs")) { containerId, inventory ->
			BenchmarkRunsMenu(containerId, inventory, player.uuid)
		}
	}

	fun openActiveRuns(player: ServerPlayer, page: Int = 0, initialSelection: java.util.UUID? = null) {
		MenuOpener.open(player, Component.literal("Currently Running")) { containerId, inventory ->
			BenchmarkActiveRunsMenu(containerId, inventory, player.uuid, page, initialSelection)
		}
	}

	fun openCompletedRuns(player: ServerPlayer, page: Int = 0, initialSelectionKey: String? = null) {
		MenuOpener.open(player, Component.literal("Completed Runs")) { containerId, inventory ->
			BenchmarkCompletedRunsMenu(containerId, inventory, player.uuid, page, initialSelectionKey)
		}
	}

	fun openAvailableModels(player: ServerPlayer, page: Int = 0) {
		MenuOpener.open(player, Component.literal("Add Benchmark Model")) { containerId, inventory ->
			BenchmarkAvailableModelsMenu(containerId, inventory, player.uuid, page)
		}
	}

	fun openModelRuns(
		player: ServerPlayer,
		providerName: String,
		modelName: String,
		pathSegments: List<String> = emptyList(),
		page: Int = 0,
		initialSelection: String? = null,
	) {
		MenuOpener.open(player, Component.literal(modelRunsTitle(providerName, modelName, pathSegments))) { containerId, inventory ->
			BenchmarkModelRunsMenu(containerId, inventory, player.uuid, providerName, modelName, pathSegments, page, initialSelection)
		}
	}

	fun openRunSelection(
		player: ServerPlayer,
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		returnPage: Int = 0,
	) {
		MenuOpener.open(player, Component.literal("Run Benchmark")) { containerId, inventory ->
			BenchmarkRunSelectionMenu(containerId, inventory, player.uuid, providerName, modelName, pathSegments, entry, returnPage)
		}
	}

	fun openPresetSessions(
		player: ServerPlayer,
		providerName: String,
		modelName: String,
		pathSegments: List<String>,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int = 0,
		initialSelection: java.util.UUID? = null,
	) {
		MenuOpener.open(player, Component.literal("Benchmark Sessions")) { containerId, inventory ->
			BenchmarkPresetSessionsMenu(containerId, inventory, player.uuid, providerName, modelName, pathSegments, entry, page, initialSelection)
		}
	}

	fun openCreateFolderInput(player: ServerPlayer, pathSegments: List<String>, returnPage: Int) {
		openNameInput(player, "Create Folder", Items.WHITE_SHULKER_BOX, "", onSubmit = { submittedName ->
			BenchmarkCatalogManager.createFolder(pathSegments, submittedName)
				.onSuccess { entry ->
					openCatalog(player, pathSegments, returnPage, entry.storedName)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to create benchmark folder.").withStyle(ChatFormatting.RED)
					)
					openCatalog(player, pathSegments, returnPage)
				}
		})
	}

	fun openCreatePresetInput(player: ServerPlayer, pathSegments: List<String>, returnPage: Int) {
		openNameInput(player, "Create Benchmark", Items.IRON_INGOT, "", onSubmit = { submittedName ->
			BenchmarkPresetManager.createPreset(player.uuid, pathSegments, submittedName)
				.onSuccess { entry ->
					openCatalog(player, pathSegments, returnPage, entry.storedName)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to create benchmark preset.").withStyle(ChatFormatting.RED)
					)
					openCatalog(player, pathSegments, returnPage)
				}
		})
	}

	fun openCreateTagInput(player: ServerPlayer, returnPage: Int) {
		openNameInput(player, "Create Tag", Items.LIME_CANDLE, "", onSubmit = { submittedName ->
			BenchmarkTagManager.createTag(submittedName)
				.onSuccess { entry ->
					openTags(player, returnPage, entry.id)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to create benchmark tag.").withStyle(ChatFormatting.RED)
					)
					openTags(player, returnPage)
				}
		})
	}

	fun openOverrideConfirm(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		val summary = BenchmarkPresetManager.currentCaptureSummary(player.uuid).getOrElse { error ->
			player.sendSystemMessage(
				Component.literal(error.message ?: "Unable to capture the current benchmark preset state.")
					.withStyle(ChatFormatting.RED),
			)
			openCatalog(player, pathSegments, returnPage, entry.storedName)
			return
		}

		MenuOpener.open(player, Component.literal("are you sure you want to override")) { containerId, inventory ->
			BenchmarkOverrideConfirmMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry, summary)
		}
	}

	fun openPresetEditMenu(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		MenuOpener.open(player, Component.literal("Preset Actions")) { containerId, inventory ->
			BenchmarkPresetEditMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry)
		}
	}

	fun openSummaryInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		val metadata = BenchmarkPresetManager.metadata(pathSegments, entry).getOrElse { error ->
			player.sendSystemMessage(
				Component.literal(error.message ?: "Unable to load benchmark preset summary.").withStyle(ChatFormatting.RED)
			)
			openPresetEditMenu(player, pathSegments, returnPage, entry)
			return
		}

		openNameInput(player, "Summary", Items.PAPER, metadata.summary, onSubmit = { submittedSummary ->
			BenchmarkPresetManager.updateSummary(pathSegments, entry, submittedSummary)
				.onSuccess {
					openPresetEditMenu(player, pathSegments, returnPage, entry)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to update benchmark preset summary.").withStyle(ChatFormatting.RED)
					)
					openPresetEditMenu(player, pathSegments, returnPage, entry)
				}
		})
	}

	fun openTaskInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		val metadata = BenchmarkPresetManager.metadata(pathSegments, entry).getOrElse { error ->
			player.sendSystemMessage(
				Component.literal(error.message ?: "Unable to load benchmark preset task.").withStyle(ChatFormatting.RED)
			)
			openPresetEditMenu(player, pathSegments, returnPage, entry)
			return
		}

		openLongTextInput(
			player = player,
			currentValue = metadata.task,
			onSubmit = { sender, submittedTask ->
				BenchmarkPresetManager.updateTask(pathSegments, entry, submittedTask)
					.onSuccess {
						openPresetEditMenu(sender, pathSegments, returnPage, entry)
					}
					.onFailure { error ->
						sender.sendSystemMessage(
							Component.literal(error.message ?: "Unable to update benchmark preset task.").withStyle(ChatFormatting.RED)
						)
						openPresetEditMenu(sender, pathSegments, returnPage, entry)
					}
			},
		)
	}

	fun openPresetTagMenu(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int = 0,
	) {
		MenuOpener.open(player, Component.literal("Preset Tags")) { containerId, inventory ->
			BenchmarkPresetTagMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry, page)
		}
	}

	fun openPresetTargetsMenu(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int = 0,
		initialSelection: String? = null,
	) {
		MenuOpener.open(player, Component.literal("Preset Targets")) { containerId, inventory ->
			BenchmarkPresetTargetsMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry, page, initialSelection)
		}
	}

	fun openPostValidationMenu(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		MenuOpener.open(player, Component.literal("Post-Validation")) { containerId, inventory ->
			BenchmarkPostValidationMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry)
		}
	}

	fun openPresetToolsMenu(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int = 0,
	) {
		MenuOpener.open(player, Component.literal("Preset Tools")) { containerId, inventory ->
			BenchmarkPresetToolsMenu(containerId, inventory, player.uuid, pathSegments, returnPage, entry, page)
		}
	}

	fun openCreatePresetTargetKeyInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int,
	) {
		openNameInput(player, "Target Key", Items.YELLOW_STAINED_GLASS, "", onSubmit = { submittedKey ->
			openCreatePresetTargetDescriptionInput(player, pathSegments, returnPage, entry, page, submittedKey)
		})
	}

	fun openRenamePresetTargetInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int,
		target: BenchmarkPresetManager.PresetTargetEntry,
	) {
		openNameInput(player, "Change Target Name", Items.YELLOW_STAINED_GLASS, target.key, onSubmit = { submittedKey ->
			BenchmarkPresetManager.renameTarget(pathSegments, entry, target.key, submittedKey)
				.onSuccess { renamedTarget ->
					openPresetTargetsMenu(player, pathSegments, returnPage, entry, page, renamedTarget.key)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to rename preset target.").withStyle(ChatFormatting.RED)
					)
					openPresetTargetsMenu(player, pathSegments, returnPage, entry, page, target.key)
				}
		})
	}

	fun openPresetTargetDescriptionInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int,
		target: BenchmarkPresetManager.PresetTargetEntry,
	) {
		openLongTextInput(
			player = player,
			currentValue = target.description,
			onSubmit = { sender, submittedDescription ->
				BenchmarkPresetManager.updateTargetDescription(pathSegments, entry, target.key, submittedDescription)
					.onSuccess { updatedTarget ->
						openPresetTargetsMenu(sender, pathSegments, returnPage, entry, page, updatedTarget.key)
					}
					.onFailure { error ->
						sender.sendSystemMessage(
							Component.literal(error.message ?: "Unable to update preset target description.").withStyle(ChatFormatting.RED)
						)
						openPresetTargetsMenu(sender, pathSegments, returnPage, entry, page, target.key)
					}
			},
		)
	}

	fun deletePresetTarget(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int,
		target: BenchmarkPresetManager.PresetTargetEntry,
	) {
		BenchmarkPresetManager.deleteTarget(pathSegments, entry, target.key)
			.onSuccess {
				openPresetTargetsMenu(player, pathSegments, returnPage, entry, page)
			}
			.onFailure { error ->
				player.sendSystemMessage(
					Component.literal(error.message ?: "Unable to delete preset target.").withStyle(ChatFormatting.RED)
				)
				openPresetTargetsMenu(player, pathSegments, returnPage, entry, page, target.key)
			}
	}

	fun openRenameInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
	) {
		openNameInput(player, "Rename", entry.iconItem(), entry.displayName, onSubmit = { submittedName ->
			BenchmarkCatalogManager.renameEntry(pathSegments, entry, submittedName)
				.onSuccess { renamedEntry ->
					openCatalog(player, pathSegments, returnPage, renamedEntry.storedName)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to rename benchmark item.").withStyle(ChatFormatting.RED)
					)
					openCatalog(player, pathSegments, returnPage, entry.storedName)
				}
		})
	}

	fun openRenameTagInput(
		player: ServerPlayer,
		returnPage: Int,
		entry: BenchmarkTagManager.TagEntry,
	) {
		openNameInput(player, "Rename Tag", Items.WHITE_CANDLE, entry.name, onSubmit = { submittedName ->
			BenchmarkTagManager.renameTag(entry, submittedName)
				.onSuccess { renamedEntry ->
					openTags(player, returnPage, renamedEntry.id)
				}
				.onFailure { error ->
					player.sendSystemMessage(
						Component.literal(error.message ?: "Unable to rename benchmark tag.").withStyle(ChatFormatting.RED)
					)
					openTags(player, returnPage, entry.id)
				}
		})
	}

	private fun openNameInput(
		player: ServerPlayer,
		title: String,
		icon: Item,
		initialName: String,
		onSubmit: (String) -> Unit,
	) {
		AnvilInputMenu.awaitResult(
			player = player,
			title = Component.literal(title),
			itemStack = ItemStack(icon),
			initialValue = initialName,
			onResult = { submittedValue ->
				if (submittedValue != null) {
					onSubmit(submittedValue)
				}
			},
		)
	}

	private fun openNameInput(
		player: ServerPlayer,
		title: String,
		itemStack: ItemStack,
		initialName: String,
		onSubmit: (String) -> Unit,
	) {
		AnvilInputMenu.awaitResult(
			player = player,
			title = Component.literal(title),
			itemStack = itemStack,
			initialValue = initialName,
			onResult = { submittedValue ->
				if (submittedValue != null) {
					onSubmit(submittedValue)
				}
			},
		)
	}

	private fun openCreatePresetTargetDescriptionInput(
		player: ServerPlayer,
		pathSegments: List<String>,
		returnPage: Int,
		entry: BenchmarkCatalogManager.CatalogEntry,
		page: Int,
		targetKey: String,
	) {
		openLongTextInput(
			player = player,
			currentValue = "",
			onSubmit = { sender, submittedDescription ->
				BenchmarkPresetManager.createTarget(pathSegments, entry, targetKey, submittedDescription)
					.onSuccess { createdTarget ->
						openPresetTargetsMenu(sender, pathSegments, returnPage, entry, page, createdTarget.key)
					}
					.onFailure { error ->
						sender.sendSystemMessage(
							Component.literal(error.message ?: "Unable to create preset target.").withStyle(ChatFormatting.RED)
						)
						openPresetTargetsMenu(sender, pathSegments, returnPage, entry, page)
					}
			},
		)
	}

	private fun openLongTextInput(
		player: ServerPlayer,
		currentValue: String,
		onSubmit: (ServerPlayer, String) -> Unit,
	) {
		BenchmarkBookInputManager.open(
			player = player,
			initialText = currentValue,
			onSubmit = onSubmit,
		)
	}

	private fun BenchmarkCatalogManager.CatalogEntry.iconItem(): Item {
		return when (kind) {
			BenchmarkCatalogManager.EntryKind.FOLDER -> Items.WHITE_SHULKER_BOX
			BenchmarkCatalogManager.EntryKind.PRESET -> Items.IRON_INGOT
		}
	}

	private fun catalogTitle(pathSegments: List<String>): String {
		return if (pathSegments.isEmpty()) {
			"Root - Benchmarks"
		} else {
			"${shortPathLabel(pathSegments)} - Benchmarks"
		}
	}

	private fun modelRunsTitle(providerName: String, modelName: String, pathSegments: List<String>): String {
		val modelLabel = OpenBlockMenuSupport.modelDisplayName(providerName, modelName)
		return if (pathSegments.isEmpty()) {
			modelLabel
		} else {
			"$modelLabel / ${shortPathLabel(pathSegments)}"
		}
	}

	private fun shortPathLabel(pathSegments: List<String>): String {
		return when {
			pathSegments.isEmpty() -> "root"
			pathSegments.size == 1 -> BenchmarkCatalogManager.displayName(pathSegments.last())
			else -> "../${BenchmarkCatalogManager.displayName(pathSegments.last())}"
		}
	}
}
