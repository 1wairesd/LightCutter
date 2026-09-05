package ru.kainlight.lightcutter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

// ---------------------------------------------------------------------------
// Debug logger
// ---------------------------------------------------------------------------

internal object Debug {
    var isEnabled: Boolean = false
    private val logger get() = Main.getInstance().logger

    fun info(message: String) { if (isEnabled) logger.info("[DEBUG] $message") }
    fun warn(message: String) { logger.warning(message) }
    fun error(message: String, ex: Throwable? = null) {
        logger.severe(message)
        ex?.printStackTrace()
    }
}

// ---------------------------------------------------------------------------
// Text parsing (MiniMessage or Legacy &-codes)
// ---------------------------------------------------------------------------

internal object TextParser {
    var parseMode: String = "MINIMESSAGE"

    fun parse(text: String): Component {
        return if (parseMode.equals("LEGACY", ignoreCase = true)) {
            LegacyComponentSerializer.legacyAmpersand().deserialize(text)
        } else {
            MiniMessage.miniMessage().deserialize(text)
        }
    }
}

// ---------------------------------------------------------------------------
// Audience / messaging extensions
// ---------------------------------------------------------------------------

internal fun CommandSender.sendParsed(message: String?) {
    if (message.isNullOrBlank()) return
    this.sendMessage(TextParser.parse(message))
}

internal fun Player.sendParsedActionbar(message: String?) {
    if (message.isNullOrBlank()) return
    this.sendActionBar(TextParser.parse(message))
}

// ---------------------------------------------------------------------------
// Version comparison util  (replaces LightCommon.lower)
// ---------------------------------------------------------------------------

/**
 * Returns true if [version] is lower than [compareVersion].
 * Parses "1.X.Y-SNAPSHOT-..." style version strings.
 */
internal fun isVersionLower(version: String, compareVersion: String): Boolean {
    fun parts(v: String) = v.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
    val a = parts(version)
    val b = parts(compareVersion)
    val len = maxOf(a.size, b.size)
    for (i in 0 until len) {
        val ai = a.getOrElse(i) { 0 }
        val bi = b.getOrElse(i) { 0 }
        if (ai < bi) return true
        if (ai > bi) return false
    }
    return false
}
