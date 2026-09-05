package ru.kainlight.lightcutter.animations

import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.util.Transformation
import org.jetbrains.annotations.ApiStatus.ScheduledForRemoval
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.kainlight.lightcutter.Debug
import ru.kainlight.lightcutter.Main
import ru.kainlight.lightcutter.isVersionLower
import ru.kainlight.lightcutter.sendParsed
import kotlin.math.abs
import kotlin.random.Random

// Thanks max1mde — https://github.com/max1mde/FancyPhysics

internal class TreeAnimation(private val plugin: Main, private val origin: Block) {

    private val lower_1_19_4: Boolean = isVersionLower(plugin.server.version, "1.19.4")

    /** Returns true if the tree's properties are characteristic of a naturally generated tree. */
    private var isNatural: Boolean

    /** The material of the tree's stem. */
    private val woodMaterial: Material

    /** The material of the tree's leaves. */
    private val leaveMaterial: Material

    private val woods: MutableList<Block> = mutableListOf()
    private val leaves: MutableList<Block> = mutableListOf()

    private val scannedBlocks: MutableList<Block> = mutableListOf()
    private val oldBlocklist: MutableMap<Location, BlockData> = mutableMapOf()

    private val advancedStemScanMaterials = listOf(Material.COCOA_BEANS, Material.VINE, Material.SNOW)
    private val blockFaceList =
        listOf(BlockFace.DOWN, BlockFace.UP, BlockFace.SOUTH, BlockFace.NORTH, BlockFace.WEST, BlockFace.EAST)

    init {
        val aboveOrigin: Block = origin.location.clone().add(0.0, 1.0, 0.0).block
        this.woodMaterial = aboveOrigin.type
        this.leaveMaterial = getLeaveType(this.woodMaterial)
        scanTree(aboveOrigin)
        woods.add(origin)
        this.isNatural = this.woods.size > 3 && this.leaves.size > 5
    }

    companion object {
        // For < 1.19.4
        val fallingBlocks: MutableList<FallingBlock> = mutableListOf()

        private val displayList = mutableListOf<Display>()

        /** Removes all tracked display entities and clears lists. Call from onDisable. */
        fun cleanup() {
            displayList.toList().forEach { it.remove() }
            displayList.clear()
            fallingBlocks.clear()
        }

        /**
         * Scans the tree at [origin] and returns the number of log blocks.
         * Used BEFORE breaking to calculate dynamic needBreak / earn.
         * Does not modify any blocks.
         */
        fun scanLogCount(origin: Block): Int {
            val aboveOrigin = origin.location.clone().add(0.0, 1.0, 0.0).block
            val woodMaterial = aboveOrigin.type
            if (!woodMaterial.isWood()) return 1

            val visited = mutableSetOf<Block>()
            val queue = ArrayDeque<Block>()
            val faces = listOf(BlockFace.DOWN, BlockFace.UP, BlockFace.SOUTH, BlockFace.NORTH, BlockFace.WEST, BlockFace.EAST)

            visited.add(origin)
            queue.add(aboveOrigin)

            var logCount = 1 // count origin itself

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (visited.contains(current)) continue
                visited.add(current)

                if (current.type != woodMaterial) continue
                if (abs(current.x - origin.x) > 10 || abs(current.z - origin.z) > 10) continue
                if (visited.size > 250) break // safety cap

                logCount++

                for (face in faces) {
                    val next = current.getRelative(face)
                    if (!visited.contains(next)) {
                        if (face == BlockFace.DOWN && (next.y < origin.y - 12 || next.type != woodMaterial)) continue
                        queue.add(next)
                    }
                }
            }

            return logCount
        }

        /** Creates a new Tree object and plays a break animation */
        fun start(plugin: Main, event: BlockBreakEvent) {
            if (event.isCancelled) return
            val block = event.block
            if (!block.getRelative(BlockFace.UP).type.isWood()) return
            val tree = TreeAnimation(plugin, block)
            val player = event.player

            val isFullItemDamage = plugin.config.getBoolean("woodcutter-settings.full-item-damage", true)
            tree.damageItem(player.inventory.itemInMainHand, isFullItemDamage)

            val isIncrementStatistics = plugin.config.getBoolean("woodcutter-settings.increment-statistics", true)
            if (isIncrementStatistics) tree.incrementStatistics(player)

            tree.breakAndFall()

            val regenerationEnabled = plugin.config.getBoolean("region-settings.regeneration.enable", true)
            val delayBeforeRestore: Int = plugin.config.getInt("region-settings.regeneration.delay", 5)
            if (regenerationEnabled && delayBeforeRestore > 0) {
                tree.startAnimation(delayBeforeRestore)
            }
        }
    }

    /** Breaks the tree with a falling animation if the tree is natural. */
    private fun breakAndFall() {
        if (!isNatural) return
        val isAnimated: Boolean = plugin.config.getBoolean("woodcutter-settings.animation", false)
        Debug.info("Animation is [$isAnimated]")

        if (!isAnimated) {
            treeCapitator()
        } else {
            if (lower_1_19_4) {
                Debug.info("Animation «spawnFallingBlocks» started")
                woods.forEach { spawnFallingBlocks(it) }
                leaves.forEach { spawnFallingBlocks(it) }
            } else {
                Debug.info("Animation «spawnDisplay» started")
                woods.forEach { spawnDisplay(it) }
                leaves.forEach { spawnDisplay(it) }
            }
        }
    }

    @Deprecated("Removed after switching to JDK21")
    @ScheduledForRemoval
    private fun spawnFallingBlocks(block: Block) {
        val fallingBlock = block.world.spawnFallingBlock(block.location, block.blockData)
        fallingBlock.setHurtEntities(false)
        fallingBlock.dropItem = false
        fallingBlock.isInvulnerable = true
        drop(block)
        fallingBlocks.add(fallingBlock)
    }

    /** Breaks the tree instantly without any animation if the tree is natural. */
    private fun treeCapitator() {
        woods.forEach { drop(it) }
        leaves.forEach { drop(it) }
    }

    private fun spawnDisplay(block: Block) {
        val location = block.location
        val blockDisplay: Any = location.world.spawn(location, BlockDisplay::class.java)

        if (blockDisplay is BlockDisplay) {
            displayList.add(blockDisplay)
            val blockData = block.type.createBlockData()

            blockDisplay.block = blockData
            blockDisplay.addScoreboardTag("lightcutter_tree")
            block.type = Material.AIR

            val lowestBlock = woods.minByOrNull { it.location.y } ?: origin
            val baseY = lowestBlock.y

            val transformationY = -1 + (baseY - block.y).toInt()
            val transformationZ = (baseY - block.y + (baseY - block.y) / 0.9).toFloat()

            val scheduler = plugin.server.scheduler

            scheduler.runTaskLater(plugin, Runnable {
                val newY = (baseY - (block.y + 0.7)).toFloat()
                val blockDisplayLocation =
                    blockDisplay.location.add(0.0, newY + 1.5, (transformationY - 0.5f).toDouble())

                var impactLocation = blockDisplayLocation.block
                if (impactLocation.type.isSolid) {
                    var tries = 0
                    while (impactLocation.type.isSolid && tries < 5) {
                        impactLocation = impactLocation.getRelative(BlockFace.UP)
                        tries++
                    }
                }

                val translation =
                    Vector3f(0f, transformationY + (baseY - (block.y + 0.6)).toFloat() / 2, transformationZ)
                val leftRotation =
                    Quaternionf(
                        -1.0f + blockDisplayLocation.distance(impactLocation.location).toFloat() / 10,
                        0f, 0f, 0.1f
                    )
                val scale = Vector3f(1f, 1f, 1f)
                val rightRotation = blockDisplay.transformation.rightRotation

                val transformation = Transformation(translation, leftRotation, scale, rightRotation)
                blockDisplay.interpolationDuration = 30
                blockDisplay.interpolationDelay = -1
                blockDisplay.transformation = transformation

                val finalImpactLocation = impactLocation.location
                val dist = (blockDisplayLocation.distance(impactLocation.location) * 2).toInt()
                val delayForTask = 12L - minOf(11, dist).toLong()

                scheduler.runTaskLater(plugin, Runnable {
                    blockDisplay.location.world.spawnParticle(
                        Particle.BLOCK_CRACK, finalImpactLocation, 50, blockData
                    )
                    removeTree(blockDisplay, transformationY.toFloat(), blockData)
                }, delayForTask)
            }, 2L)
        }
    }

    private fun removeTree(blockDisplay: BlockDisplay, transformationY: Float, blockData: BlockData) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val block =
                blockDisplay.location.add(0.0, (transformationY + 2).toDouble(), transformationY.toDouble()).block
            if (block.type == Material.AIR) {
                block.type = blockData.material
                drop(block)
            }
            displayList.remove(blockDisplay)
            blockDisplay.remove()
        }, 4L)
    }

    private fun restore(
        particlesEnabled: Boolean,
        particleName: String,
        particleCount: Int,
        soundEnabled: Boolean,
        soundName: String,
        soundVolume: Float,
        soundPitch: Float,
        delayBeforeRestore: Int
    ) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            for (location in oldBlocklist.keys) {
                location.handleRestoreBlocks()
                if (particlesEnabled) location.particle(particleName, particleCount)
            }
            if (soundEnabled) origin.location.sound(soundName, soundVolume, soundPitch)
            origin.type = woodMaterial
        }, 20L * delayBeforeRestore)
    }

    private fun restoreAnimated(
        particlesEnabled: Boolean,
        particleName: String,
        particleCount: Int,
        soundEnabled: Boolean,
        soundName: String,
        soundVolume: Float,
        soundPitch: Float,
        delayBeforeRestore: Int
    ) {
        var delayPerBlock: Long = plugin.config.getLong("region-settings.regeneration.animation.delayPerBlock", 1L)
        if (delayPerBlock <= 0) delayPerBlock = 1L
        var currentDelay = 0L

        val sch = plugin.server.scheduler

        // #9 — use runTaskLater (main thread) instead of runTaskLaterAsynchronously.
        // oldBlocklist is not thread-safe, and all block operations must be on the main thread.
        sch.runTaskLater(plugin, Runnable {
            val leafBlocks =
                oldBlocklist.filterValues { it.material == leaveMaterial }.keys.sortedByDescending { it.y }
            val woodBlocks =
                oldBlocklist.filterValues { it.material == woodMaterial }.keys.sortedByDescending { it.y }

            for (location in leafBlocks) {
                val delay = currentDelay
                sch.runTaskLater(plugin, Runnable {
                    location.handleRestoreBlocks()
                    if (particlesEnabled) location.particle(particleName, particleCount)
                    if (soundEnabled) location.sound(soundName, soundVolume, soundPitch)
                }, delay)
                currentDelay += delayPerBlock
            }

            for (location in woodBlocks) {
                val delay = currentDelay
                sch.runTaskLater(plugin, Runnable {
                    location.handleRestoreBlocks()
                    if (particlesEnabled) location.particle(particleName, particleCount)
                    if (soundEnabled) location.sound(soundName, soundVolume, soundPitch)
                }, delay)
                currentDelay += delayPerBlock
            }

            val finalDelay = currentDelay
            sch.runTaskLater(plugin, Runnable { origin.type = woodMaterial }, finalDelay)
        }, 20L * delayBeforeRestore)
    }

    /** Recursively scans the tree structure, populating the stem and leaves lists. */
    private fun scanTree(scannedBlock: Block) {
        var scanAmount = 0

        scannedBlocks.add(scannedBlock)
        scanAmount++
        if (abs(scannedBlock.x - origin.x) > 10 || abs(scannedBlock.z - origin.z) > 10) return

        val scanMaxStemSize = 200
        val scanMaxLeavesSize = 260

        if (scannedBlock.type == woodMaterial) {
            if (woods.size < scanMaxStemSize) {
                if (woods.contains(scannedBlock)) return
                woods.add(scannedBlock)
                oldBlocklist[scannedBlock.location] = scannedBlock.blockData.clone()
            } else {
                isNatural = false
                return
            }
        } else if (scannedBlock.type == leaveMaterial) {
            if (leaves.size < scanMaxLeavesSize) {
                if (leaves.contains(scannedBlock)) return
                (scannedBlock.blockData as? Leaves)?.apply {
                    isPersistent = true
                    scannedBlock.blockData = this
                }
                leaves.add(scannedBlock)
                oldBlocklist[scannedBlock.location] = scannedBlock.blockData.clone()
            } else {
                isNatural = false
                return
            }
        }

        var blockDistanceToLastValid = 0
        val advancedStemScan = false
        val maxInvalidScans = 2700
        val maxInvalidBlockDistance = 2

        if (advancedStemScanMaterials.contains(scannedBlock.type) && advancedStemScan) {
            scannedBlock.breakNaturally()
        }

        blockFaceList.forEach { blockFace ->
            val currentBlock = scannedBlock.getRelative(blockFace)

            var scan = (currentBlock.type == woodMaterial || currentBlock.type == leaveMaterial)

            if (blockFace == BlockFace.DOWN) {
                val isWithinBounds = currentBlock.y >= origin.y - 12
                val isValidMaterial = currentBlock.type == woodMaterial
                if (!isWithinBounds || !isValidMaterial) scan = false
            }

            if (scan) {
                scanTree(currentBlock)
                blockDistanceToLastValid = 0
                return@forEach
            }

            if (scanAmount < maxInvalidScans && woods.size > 4 && advancedStemScan &&
                blockDistanceToLastValid < maxInvalidBlockDistance
            ) {
                blockDistanceToLastValid++
                if (!scannedBlocks.contains(currentBlock)) scanTree(currentBlock)
            }
        }
    }

    private fun drop(block: Block) {
        val drops = plugin.config.getConfigurationSection("woodcutter-settings.drops")!!
        if (block.type.isWood()) {
            if (drops.getBoolean("logs")) block.breakNaturally() else block.type = Material.AIR
        }
        if (block.type.isLeave()) {
            if (drops.getBoolean("leaves")) block.breakNaturally() else block.type = Material.AIR
        }
    }

    private fun isDroppableLogs(): Boolean =
        plugin.config.getConfigurationSection("woodcutter-settings.drops")!!.getBoolean("logs")

    private fun isDroppableLeaves(): Boolean =
        plugin.config.getConfigurationSection("woodcutter-settings.drops")!!.getBoolean("leaves")

    private fun damageItem(mainHand: ItemStack, full: Boolean = false) {
        val itemMeta = mainHand.itemMeta
        if (itemMeta !is Damageable) return

        val baseDamage = if (full) {
            val logDamage = if (isDroppableLogs()) getLogsSize() else 1
            val leafDamage = if (isDroppableLeaves()) getLeavesSize() else 0
            logDamage + leafDamage
        } else 1

        val unbreakingLevel = mainHand.getEnchantmentLevel(Enchantment.DURABILITY)
        var finalDamage = 0
        for (i in 0 until baseDamage) {
            if (unbreakingLevel > 0) {
                if (Random.nextInt(unbreakingLevel + 1) == 0) finalDamage++
            } else {
                finalDamage++
            }
        }
        itemMeta.damage += finalDamage
        mainHand.itemMeta = itemMeta
    }

    private fun incrementStatistics(player: Player) {
        if (woods.isNotEmpty() && isDroppableLogs()) {
            val woodSize = getLogsSize()
            if (woodSize > 1) player.incrementStatistic(Statistic.MINE_BLOCK, woodMaterial, woodSize)
        }
        if (leaves.isNotEmpty() && isDroppableLeaves()) {
            val leavesSize = getLeavesSize()
            if (leavesSize > 1) player.incrementStatistic(Statistic.MINE_BLOCK, leaveMaterial, leavesSize)
        }
    }

    private fun startAnimation(delayBeforeRestore: Int) {
        val animationEnabled = plugin.config.getBoolean("region-settings.regeneration.animation.enable", false)

        val particlesEnabled = plugin.config.getBoolean("region-settings.regeneration.particle.enable", false)
        val particleArgs = plugin.config.getStringList("region-settings.regeneration.particle.types")
            .random().split(":")
        val particleName = particleArgs.getOrNull(0) ?: "DOLPHIN"
        val particleCount = particleArgs.getOrNull(1)?.toIntOrNull()
            ?: plugin.config.getInt("region-settings.regeneration.particle.default-count", 1)

        val soundEnabled = plugin.config.getBoolean("region-settings.regeneration.sound.enable", false)
        val soundArgs = plugin.config.getStringList("region-settings.regeneration.sound.types")
            .random().split(":")
        val soundName = soundArgs.getOrNull(0) ?: "BLOCK_WOOD_PLACE"
        val soundVolume = soundArgs.getOrNull(1)?.toFloatOrNull()
            ?: plugin.config.getDouble("region-settings.regeneration.sound.default-volume", 1.0).toFloat()
        val soundPitch = soundArgs.getOrNull(2)?.toFloatOrNull()
            ?: plugin.config.getDouble("region-settings.regeneration.sound.default-pitch", 0.8).toFloat()

        if (animationEnabled) {
            restoreAnimated(particlesEnabled, particleName, particleCount,
                soundEnabled, soundName, soundVolume, soundPitch, delayBeforeRestore)
        } else {
            restore(particlesEnabled, particleName, particleCount,
                soundEnabled, soundName, soundVolume, soundPitch, delayBeforeRestore)
        }
    }

    private fun Location.handleRestoreBlocks() {
        val block = this.block
        val blockType = block.type
        if (blockType != Material.AIR) return
        block.blockData = oldBlocklist[this]!!
        Debug.info("Restored ${blockType.name} at $this location")
    }

    private fun Location.particle(name: String?, count: Int?) {
        if (name == null || count == null) return
        try {
            val particle = Particle.valueOf(name)
            val changedLocation = this.clone().add(0.5, 0.5, 0.5)
            this.world.spawnParticle(particle, changedLocation, count)
            Debug.info("Spawned $name particle with count $count")
        } catch (e: IllegalArgumentException) {
            Debug.error("An attempt to spawn an unsupported particle $name at $this", e)
        }
    }

    private fun Location.sound(soundName: String?, volume: Float?, pitch: Float?) {
        if (soundName == null || volume == null || pitch == null) return
        val name = soundName.uppercase()
        this.world.playSound(this, Sound.valueOf(name), volume, pitch)
        Debug.info("Played $name sound with volume $volume and pitch $pitch")
    }

    private fun getLeaveType(material: Material): Material {
        return when (material) {
            Material.OAK_LOG, Material.STRIPPED_OAK_LOG -> Material.OAK_LEAVES
            Material.DARK_OAK_LOG, Material.STRIPPED_DARK_OAK_LOG -> Material.DARK_OAK_LEAVES
            Material.JUNGLE_LOG, Material.STRIPPED_JUNGLE_LOG -> Material.JUNGLE_LEAVES
            Material.ACACIA_LOG, Material.STRIPPED_ACACIA_LOG -> Material.ACACIA_LEAVES
            Material.BIRCH_LOG, Material.STRIPPED_BIRCH_LOG -> Material.BIRCH_LEAVES
            Material.SPRUCE_LOG, Material.STRIPPED_SPRUCE_LOG -> Material.SPRUCE_LEAVES
            Material.CHERRY_LOG, Material.STRIPPED_CHERRY_LOG -> Material.CHERRY_LEAVES
            Material.MANGROVE_LOG, Material.STRIPPED_MANGROVE_LOG -> Material.MANGROVE_LEAVES
            Material.WARPED_STEM, Material.NETHER_WART_BLOCK -> Material.WARPED_WART_BLOCK
            Material.CRIMSON_STEM -> Material.NETHER_WART_BLOCK
            else -> Material.AIR
        }
    }

    private fun getLogsSize() = woods.size - 1
    private fun getLeavesSize() = leaves.size
}

fun Material.isWood(): Boolean = this.name.endsWith("LOG") || this.name.endsWith("STEM")
fun Material.isLeave(): Boolean = this.name.endsWith("LEAVES")
