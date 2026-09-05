package ru.kainlight.lightcutter

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.java.JavaPlugin
import ru.kainlight.lightcutter.api.ILightCutterAPI
import ru.kainlight.lightcutter.api.LightCutterAPI
import ru.kainlight.lightcutter.animations.TreeAnimation
import ru.kainlight.lightcutter.commands.Completer
import ru.kainlight.lightcutter.commands.MainCommand
import ru.kainlight.lightcutter.data.Database
import ru.kainlight.lightcutter.data.WoodCutterMode
import ru.kainlight.lightcutter.listeners.BlockListener
import java.util.concurrent.CopyOnWriteArrayList

class Main : JavaPlugin() {

    internal lateinit var database: Database
    internal lateinit var messageConfig: LanguageConfig

    /** Vault Economy service, set during onEnable. */
    internal var vaultEconomy: Economy? = null

    val disabledWorlds = CopyOnWriteArrayList<String>()

    override fun onEnable() {
        instance = this

        // Config
        saveDefaultConfig()

        // Messages
        messageConfig = LanguageConfig(this)

        // Database
        reloadDatabase()

        // API
        LightCutterAPI.setProvider(ILightCutterAPI(this))

        // Hook Vault
        setupVault()

        // Reload configs (messages + main config state)
        reloadConfigurations()

        if (WoodCutterMode.getCurrent() == WoodCutterMode.REGION) {
            val regions = database.getRegions()
            if (regions.isEmpty()) Debug.warn("The list of regions is empty")
            else Debug.info("Regions " + regions.map { it.name } + " successfully loaded")
        }

        // Register command
        val cmd: PluginCommand = getCommand("lightcutter")
            ?: throw IllegalStateException("Command 'lightcutter' not found in plugin.yml")
        val executor = MainCommand(this)
        cmd.setExecutor(executor)
        cmd.tabCompleter = Completer(this)

        // Register listener
        Bukkit.getPluginManager().registerEvents(BlockListener(this), this)

        logger.info("LightCutter v${description.version} enabled!")
    }

    override fun onDisable() {
        TreeAnimation.cleanup()
        database.disconnect()
        logger.info("LightCutter disabled.")
    }

    fun reloadConfigurations() {
        saveDefaultConfig()
        reloadConfig()
        TextParser.parseMode = config.getString("main-settings.parse_mode", "MINIMESSAGE")!!
        Debug.isEnabled = config.getBoolean("debug")
        disabledWorlds.clear()
        disabledWorlds.addAll(config.getStringList("woodcutter-settings.disabled-worlds"))
        messageConfig.reload("main-settings.language")
    }

    internal fun reloadDatabase() {
        database = Database(this)
        database.connect()
        database.createTables()
        database.initializeCache()
    }

    /** Returns the active messages config. */
    internal fun getMessages(): LanguageConfig = messageConfig

    private fun setupVault() {
        if (server.pluginManager.getPlugin("Vault") == null) {
            Debug.warn("Vault not found — economy features disabled.")
            return
        }
        val rsp: RegisteredServiceProvider<Economy>? =
            server.servicesManager.getRegistration(Economy::class.java)
        vaultEconomy = rsp?.provider
        if (vaultEconomy == null) Debug.warn("No Vault economy provider found.")
        else Debug.info("Vault economy hooked: ${vaultEconomy!!.name}")
    }

    companion object {
        private lateinit var instance: Main

        fun getInstance(): Main = instance
    }
}
