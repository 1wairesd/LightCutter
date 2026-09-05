package ru.kainlight.lightcutter.api

import net.milkbowl.vault.economy.Economy
import org.bukkit.entity.Player
import ru.kainlight.lightcutter.Debug
import ru.kainlight.lightcutter.Main
import ru.kainlight.lightcutter.data.EconomyType
import ru.kainlight.lightcutter.sendParsed
import java.text.DecimalFormat
import java.util.UUID
import kotlin.random.Random

internal class IEconomyHandler(val plugin: Main, val economy: EconomyType) : EconomyHandler {

    // #10 — cached so it's not re-created on every randomized payment
    private val decimalFormat: DecimalFormat by lazy {
        DecimalFormat(plugin.config.getString("woodcutter-settings.economy-format", "#.#")!!)
    }

    override fun depositWithRegion(player: Player, earn: String) {
        val cost = getOrRandomCost(earn)
        val message = plugin.getMessages().getString("region.earn").orEmpty()
        salary(player, cost, message)
    }

    override fun depositWithoutRegion(player: Player, blockName: String) {
        val logName = plugin.getMessages().getString("log-names.$blockName")
            ?: "Unnamed block: $blockName"
        val message = plugin.getMessages().getString("world.earn")
            ?.replace("#block#", logName)
            .orEmpty()
        val cost = getOrRandomCost(plugin.config.getString("world-settings.costs.$blockName"))
        salary(player, cost, message)
    }

    private fun salary(player: Player, treeCost: Double, message: String) {
        val treeCostInt: Int = treeCost.toInt()

        val isDeposited: Boolean = when (economy) {
            EconomyType.VAULT -> depositVault(player, treeCost)
            EconomyType.PLAYERPOINTS -> depositPlayerPoints(player, treeCost)
        }

        val formattedMessage = message
            .replace("#amount#", treeCost.toString())
            .replace("#amount_rounded#", treeCostInt.toString())

        if (isDeposited) player.sendParsed(formattedMessage)
        else Debug.warn("Deposit problem for player ${player.name}")
    }

    private fun depositVault(player: Player, amount: Double): Boolean {
        val eco: Economy = plugin.vaultEconomy ?: run {
            Debug.warn("Vault economy is not available")
            return false
        }
        return eco.depositPlayer(player, amount).transactionSuccess()
    }

    private fun depositPlayerPoints(player: Player, amount: Double): Boolean {
        val pp = plugin.server.pluginManager.getPlugin("PlayerPoints") ?: run {
            Debug.warn("PlayerPoints plugin not found")
            return false
        }
        return try {
            // Access PlayerPoints API via reflection to avoid compile-time dependency
            val apiMethod = pp.javaClass.getMethod("getAPI")
            val api = apiMethod.invoke(pp)
            val giveMethod = api.javaClass.getMethod("give", UUID::class.java, Int::class.java)
            giveMethod.invoke(api, player.uniqueId, amount.toInt()) as? Boolean ?: false
        } catch (e: Exception) {
            Debug.error("PlayerPoints deposit failed: ${e.message}", e)
            false
        }
    }

    private fun getOrRandomCost(costString: String?): Double {
        if (costString.isNullOrEmpty()) return 0.0

        if (costString.contains("-")) {
            val parts = costString.split("-")
            val min = parts[0].toDouble()
            val max = parts[1].toDouble()
            val randomValue = min + (max - min) * Random.nextDouble()
            return decimalFormat.format(randomValue).replace(",", ".").toDouble()
        }

        return costString.toDouble()
    }
}
