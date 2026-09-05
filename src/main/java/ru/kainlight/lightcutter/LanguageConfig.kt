package ru.kainlight.lightcutter

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Loads and manages a language-based messages YAML file.
 * Replaces LightLibrary's LightConfig.
 */
internal class LanguageConfig(private val plugin: Main) {

    private var config: FileConfiguration = YamlConfiguration()
    private var currentLanguage: String = "ENGLISH"

    /** Load/reload messages for the language specified at [configKey] in the main config. */
    fun reload(configKey: String = "main-settings.language") {
        currentLanguage = plugin.config.getString(configKey, "ENGLISH")!!.uppercase()
        val fileNameUpper = "$currentLanguage.yml"
        val fileNameLower = "${currentLanguage.lowercase()}.yml"

        val messagesDir = File(plugin.dataFolder, "messages")
        messagesDir.mkdirs()

        // On Linux fs is case-sensitive — find the file regardless of case
        val file: File = messagesDir.listFiles()
            ?.firstOrNull { it.name.equals(fileNameUpper, ignoreCase = true) }
            ?: File(messagesDir, fileNameUpper)  // will be created below if missing

        // Copy default from jar if file doesn't exist on disk
        if (!file.exists()) {
            val resource = plugin.getResource("messages/$fileNameLower")
                ?: plugin.getResource("messages/$fileNameUpper")
                ?: plugin.getResource("messages/english.yml")
                ?: plugin.getResource("messages/ENGLISH.yml")
                ?: return
            resource.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
        }

        config = YamlConfiguration.loadConfiguration(file)

        // Merge defaults from jar so missing keys fall back to bundled values
        val jarResource = plugin.getResource("messages/$fileNameLower")
            ?: plugin.getResource("messages/$fileNameUpper")
            ?: plugin.getResource("messages/english.yml")
        if (jarResource != null) {
            val defaults = YamlConfiguration.loadConfiguration(
                InputStreamReader(jarResource, StandardCharsets.UTF_8)
            )
            config.setDefaults(defaults)
        }

        plugin.logger.info("Loaded messages from: ${file.path}")
    }

    fun getConfig(): FileConfiguration = config
    fun getString(path: String): String? = config.getString(path)
    fun getStringList(path: String): List<String> = config.getStringList(path)
}
