package ru.kainlight.lightcutter.listeners

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.EntityType
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.jetbrains.annotations.ApiStatus.ScheduledForRemoval
import ru.kainlight.lightcutter.Debug
import ru.kainlight.lightcutter.Main
import ru.kainlight.lightcutter.WorldGuardHook
import ru.kainlight.lightcutter.animations.TreeAnimation
import ru.kainlight.lightcutter.animations.isWood
import ru.kainlight.lightcutter.api.LightCutterAPI
import ru.kainlight.lightcutter.api.Region
import ru.kainlight.lightcutter.data.MessageType
import ru.kainlight.lightcutter.data.WoodCutterMode
import ru.kainlight.lightcutter.sendParsed
import ru.kainlight.lightcutter.sendParsedActionbar
import java.util.UUID

internal class BlockListener(private val plugin: Main) : Listener {

    // Key: (playerUUID, tree root location) — isolates the counter per individual tree
    private val playerBlockCount: MutableMap<Pair<UUID, Location>, Int> = mutableMapOf()

    // Cache logCount per tree so we don't BFS-scan on every hit (#2)
    private val logCountCache: MutableMap<Pair<UUID, Location>, Int> = mutableMapOf()

    private val playerCooldown: MutableMap<UUID, Long> = mutableMapOf()

    // O(1) lookup: players who currently have an active tree counter (#7)
    private val activePlayers: MutableSet<UUID> = mutableSetOf()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (plugin.disabledWorlds.contains(block.world.name)) return
        val blockType = block.type
        if (!blockType.isWood()) return

        val api = LightCutterAPI.getProvider()
        val currentWoodCutterMode = WoodCutterMode.getCurrent()

        val region: Region = WorldGuardHook.getRegionNames(block.location, true)
            .asSequence()
            .mapNotNull { api.regionHandler.getRegion(it) }
            .firstOrNull()
            ?: return

        val player = event.player

        if (currentWoodCutterMode == WoodCutterMode.REGION) {
            val ownerBypass = plugin.config.getBoolean("region-settings.owner-bypass")
            if (ownerBypass && WorldGuardHook.isOwnerOfRegion(player, region.name)) return

            if (!player.isDefaultGamemode()) {
                event.isCancelled = true
                return
            }

            val currentMessageType = MessageType.getCurrent()
            val cooldown = region.cooldown
            val currentTime = System.currentTimeMillis()
            val cooldownEndTime = currentTime + cooldown * 1000L

            if (player.sendCooldownMessageIfPresent(currentTime, currentMessageType)) {
                event.isCancelled = true
                return
            }

            val treeRoot = block.findTreeRoot()
            val countKey = Pair(player.uniqueId, treeRoot)

            // #2 — scan once, cache for subsequent hits on the same tree
            val logCount = logCountCache.getOrPut(countKey) {
                TreeAnimation.scanLogCount(block)
            }

            // needBreak = exact log count of the tree (1 hit per log)
            val dynamicNeedBreak = logCount

            val earnMultiplier = plugin.config.getDouble("woodcutter-settings.earn-log-multiplier", 0.0)
            val effectiveEarn = if (earnMultiplier > 0.0) {
                val baseEarn = region.earn.toDoubleOrNull() ?: 0.0
                (baseEarn * logCount * earnMultiplier).toString()
            } else {
                region.earn
            }

            Debug.info("Tree size: $logCount logs, needBreak=$dynamicNeedBreak, effectiveEarn=$effectiveEarn (earnLogMultiplier=$earnMultiplier)")

            var blockCount = playerBlockCount.getOrDefault(countKey, dynamicNeedBreak)
            blockCount--
            playerBlockCount[countKey] = blockCount
            activePlayers.add(player.uniqueId) // #7

            if (blockCount > 0) {
                player.sendBreakMessage(blockCount, currentMessageType)
                event.isCancelled = true
                return
            }

            // Counter reached 0 — cut the tree
            TreeAnimation.start(plugin, event)
            api.economyHandler.depositWithRegion(player, effectiveEarn)

            // Clean up all state for this tree
            playerBlockCount.remove(countKey)
            logCountCache.remove(countKey) // #2 — clear cached logCount
            if (playerBlockCount.keys.none { it.first == player.uniqueId }) {
                activePlayers.remove(player.uniqueId) // #7
            }
            if (cooldown != 0) playerCooldown[player.uniqueId] = cooldownEndTime

            event.isCancelled = true

        } else if (currentWoodCutterMode == WoodCutterMode.WORLD) {
            if (!player.isDefaultGamemode()) return
            api.economyHandler.depositWithoutRegion(player, blockType.name.lowercase())
        }
    }

    // #4 — clear state on quit and world change to prevent unbounded map growth
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) = clearPlayerState(event.player.uniqueId)

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) = clearPlayerState(event.player.uniqueId)

    private fun clearPlayerState(uuid: UUID) {
        playerBlockCount.keys.removeIf { it.first == uuid }
        logCountCache.keys.removeIf { it.first == uuid }
        activePlayers.remove(uuid)
        // Keep cooldown entry — it should persist across world changes / brief reconnects
    }

    @Deprecated("Removed after switching to JDK21") @ScheduledForRemoval
    @EventHandler
    fun onFallingBlock(event: EntityChangeBlockEvent) {
        if (TreeAnimation.fallingBlocks.isEmpty()) return
        if (event.entityType != EntityType.FALLING_BLOCK) return
        val fallingBlock = event.entity as FallingBlock
        if (!TreeAnimation.fallingBlocks.contains(fallingBlock)) return

        val location = fallingBlock.location
        val world = location.world
        val data = fallingBlock.blockData

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            world.getBlockAt(location).type = Material.AIR
            TreeAnimation.fallingBlocks.remove(fallingBlock)
            if (fallingBlock.isOnGround) {
                world.spawnParticle(Particle.BLOCK_CRACK, location, 50, data)
            }
        }, 3L)
    }

    private fun Player.isDefaultGamemode(): Boolean {
        val inModes = plugin.config.getBoolean("woodcutter-settings.breaking-in-modes", true)
        if (!hasPermission("lightcutter.modes.bypass") && inModes) {
            val msgs = plugin.getMessages()
            val warnings = listOfNotNull(
                msgs.getString("warnings.not-survival")?.takeIf { it.isNotBlank() && gameMode != GameMode.SURVIVAL },
                msgs.getString("warnings.is-flying")?.takeIf { it.isNotBlank() && allowFlight },
                msgs.getString("warnings.is-invisible")?.takeIf { it.isNotBlank() && (isInvisible || hasMetadata("vanished")) }
            )
            warnings.forEach { sendParsed(it) }
            return warnings.isEmpty()
        }
        return true
    }

    private fun Player.sendBreakMessage(blockCount: Int, currentMessageType: MessageType) {
        val msg = plugin.getMessages().getString("region.remained")
            ?.replace("#value#", blockCount.toString()) ?: return
        if (currentMessageType == MessageType.ACTIONBAR) sendParsedActionbar(msg)
        else sendParsed(msg)
    }

    private fun Player.sendCooldownMessageIfPresent(currentTime: Long, currentMessageType: MessageType): Boolean {
        if (hasPermission("lightcutter.cooldown.bypass")) return false
        if (activePlayers.contains(uniqueId)) return false // #7 — O(1) check

        val endTime = playerCooldown[uniqueId] ?: return false
        if (endTime > currentTime) {
            val remained = (endTime - currentTime) / 1000L
            val msg = plugin.getMessages().getString("warnings.cooldown")
                ?.replace("#value#", remained.toString()) ?: return true
            if (currentMessageType == MessageType.ACTIONBAR) sendParsedActionbar(msg)
            else sendParsed(msg)
            return true
        }
        return false
    }

    private fun Block.findTreeRoot(): Location {
        val woodType = this.type
        var current = this
        while (true) {
            val below = current.getRelative(BlockFace.DOWN)
            if (below.type == woodType) current = below else break
        }
        return current.location
    }
}
