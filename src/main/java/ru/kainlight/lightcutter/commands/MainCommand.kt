package ru.kainlight.lightcutter.commands

import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.kainlight.lightcutter.Main
import ru.kainlight.lightcutter.TextParser
import ru.kainlight.lightcutter.WorldGuardHook
import ru.kainlight.lightcutter.api.IRegion
import ru.kainlight.lightcutter.api.LightCutterAPI
import ru.kainlight.lightcutter.api.Region
import ru.kainlight.lightcutter.sendParsed

@Suppress("WARNINGS")
internal class MainCommand(private val plugin: Main) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty() && command.name.equals("lightcutter", ignoreCase = true)) {
            if (sender.hasNoPermissionAndMessage("lightcutter.help")) return true
            plugin.getMessages().getStringList("help.commands").forEach { sender.sendParsed(it) }
            return true
        }

        val regionHandler = LightCutterAPI.getProvider().regionHandler

        when (args[0].lowercase()) {
            "add" -> {
                if (sender.hasNoPermissionAndMessage("lightcutter.add")) return true

                if (args.size <= 4) {
                    sender.sendParsed(plugin.getMessages().getString("help.add"))
                    return true
                }

                val regionName = args[1]
                if (sender is Player) {
                    if (!WorldGuardHook.hasRegion(sender.world, regionName)) {
                        sender.sendParsed(
                            plugin.getMessages().getString("region.not-exists")
                                ?.replace("#region#", regionName)
                        )
                        return true
                    }
                }

                val earn = args[2]
                val needBreak = args[3].toIntOrNull()
                val cooldown = args[4].toIntOrNull()
                if (needBreak == null || cooldown == null) {
                    sender.sendParsed(plugin.getMessages().getString("warnings.invalid-number")
                        ?: "&cИспользуйте целые числа для количества блоков и задержки.")
                    return true
                }

                val newRegion = regionHandler.addRegion(regionName, earn, needBreak, cooldown)
                if (newRegion != null) {
                    sender.sendRegionMessage(plugin.getMessages().getString("region.added"), newRegion)
                } else {
                    sender.sendParsed(
                        plugin.getMessages().getString("region.exists")?.replace("#region#", regionName)
                    )
                }
                return true
            }

            "update" -> {
                if (sender.hasNoPermissionAndMessage("lightcutter.update")) return true

                if (args.size <= 4) {
                    sender.sendParsed(plugin.getMessages().getString("help.update"))
                    return true
                }

                val regionName = args[1]
                val earn = args[2]
                val needBreak = args[3].toIntOrNull()
                val cooldown = args[4].toIntOrNull()
                if (needBreak == null || cooldown == null) {
                    sender.sendParsed(plugin.getMessages().getString("warnings.invalid-number")
                        ?: "&cИспользуйте целые числа для количества блоков и задержки.")
                    return true
                }

                val newRegion = IRegion(regionName, earn, needBreak, cooldown)
                if (regionHandler.updateRegion(newRegion) > 0) {
                    sender.sendRegionMessage(plugin.getMessages().getString("region.updated"), newRegion)
                } else {
                    sender.sendParsed(
                        plugin.getMessages().getString("region.not-exists")?.replace("#region#", regionName)
                    )
                }
                return true
            }

            "remove" -> {
                if (sender.hasNoPermissionAndMessage("lightcutter.remove")) return true

                if (args.size == 1) {
                    sender.sendParsed(plugin.getMessages().getString("help.remove"))
                    return true
                }

                val regionName = args[1]
                if (regionHandler.removeRegion(regionName) > 0) {
                    sender.sendParsed(
                        plugin.getMessages().getString("region.removed")?.replace("#region#", regionName)
                    )
                } else {
                    sender.sendParsed(
                        plugin.getMessages().getString("region.not-exists")?.replace("#region#", regionName)
                    )
                }
            }

            "information", "info", "i" -> {
                if (sender.hasNoPermissionAndMessage("lightcutter.info")) return true

                if (args.size == 1) {
                    sender.sendParsed(plugin.getMessages().getString("help.info"))
                    return true
                }

                val regionName = args[1]
                val region = regionHandler.getRegion(regionName)
                if (region != null) {
                    sender.sendParsed(region.getInfo())
                } else {
                    sender.sendParsed(
                        plugin.getMessages().getString("region.not-exists")?.replace("#region#", regionName)
                    )
                }
                return true
            }

            "list" -> {
                if (sender.hasNoPermissionAndMessage("lightcutter.list")) return true

                val separator = plugin.getMessages().getString("list.separator") ?: "|"
                val header = plugin.getMessages().getString("list.header")
                    ?: "Name #separator# Earn #separator# Need break #separator# Cooldown"

                val builder = StringBuilder()
                    .appendLine()
                    .append(header.replace("#separator#", separator))
                    .appendLine()

                regionHandler.getRegions().forEach {
                    builder.append(it.name).append(" ").append(separator).append(" ")
                        .append(it.earn).append(" ").append(separator).append(" ")
                        .append(it.needBreak).append(" ").append(separator).append(" ")
                        .append(it.cooldown)
                        .appendLine()
                }

                sender.sendParsed(builder.toString())
                return true
            }

            "reload" -> {
                if (sender.hasNoPermissionAndMessage("lightcutter.reload")) return true

                val arg = args.getOrNull(1)
                if (arg != null && arg.equals("+database", ignoreCase = true)) {
                    plugin.reloadDatabase()
                }

                plugin.reloadConfigurations()
                sender.sendParsed(plugin.getMessages().getString("reload-config"))
                return true
            }
        }
        return true
    }

    /** Returns true and sends a no-permission message if sender lacks [permission]. */
    private fun CommandSender.hasNoPermissionAndMessage(permission: String): Boolean {
        if (!hasPermission(permission)) {
            sendParsed(
                plugin.getMessages().getString("warnings.no-permissions")
                    ?.replace("#permission#", permission)
            )
            return true
        }
        return false
    }

    /**
     * Sends a region message with click-to-info hover if sender is online.
     * For console senders the hover/click is irrelevant, so we just send plain text.
     */
    private fun CommandSender.sendRegionMessage(template: String?, region: Region) {
        if (template == null) return
        val text = template.replace("#region#", region.name)

        if (this is Player) {
            val component = TextParser.parse(text)
                .hoverEvent(
                    net.kyori.adventure.text.event.HoverEvent.showText(
                        TextParser.parse(region.getInfo())
                    )
                )
                .clickEvent(ClickEvent.runCommand("/lightcutter info ${region.name}"))
            sendMessage(component)
        } else {
            sendParsed(text)
        }
    }
}
