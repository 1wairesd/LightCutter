package ru.kainlight.lightcutter

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player

/**
 * Thin wrapper around WorldGuard API.
 * Replaces LightLibrary's WorldGuardAPI.
 */
internal object WorldGuardHook {

    /**
     * Returns all WorldGuard region names at [location].
     * [includeGlobal] — whether to include the __global__ region.
     */
    fun getRegionNames(location: Location, includeGlobal: Boolean = false): List<String> {
        val world = location.world ?: return emptyList()
        val container = WorldGuard.getInstance().platform.regionContainer
        val wgWorld = BukkitAdapter.adapt(world)
        val vec = BukkitAdapter.asBlockVector(location)
        val manager = container.get(wgWorld) ?: return emptyList()

        return manager.getApplicableRegions(vec)
            .regions
            .filter { includeGlobal || it.id != "__global__" }
            .map { it.id }
    }

    /**
     * Returns true if a region with [regionName] exists in [world].
     */
    fun hasRegion(world: World, regionName: String): Boolean {
        val container = WorldGuard.getInstance().platform.regionContainer
        val wgWorld = BukkitAdapter.adapt(world)
        val manager = container.get(wgWorld) ?: return false
        return manager.hasRegion(regionName)
    }

    /**
     * Returns true if [player] is an owner of the region named [regionName].
     */
    fun isOwnerOfRegion(player: Player, regionName: String): Boolean {
        val world = player.world
        val container = WorldGuard.getInstance().platform.regionContainer
        val wgWorld = BukkitAdapter.adapt(world)
        val manager = container.get(wgWorld) ?: return false
        val region: ProtectedRegion = manager.getRegion(regionName) ?: return false
        val wgPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        return region.owners.contains(wgPlayer)
    }
}
