package ru.kainlight.lightcutter.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.kainlight.lightcutter.Main
import ru.kainlight.lightcutter.WorldGuardHook
import ru.kainlight.lightcutter.api.LightCutterAPI

internal class Completer(private val plugin: Main) : TabCompleter {

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String>? {
        if (!command.name.equals("lightcutter", ignoreCase = true) &&
            !command.aliases.any { it.equals("lc", ignoreCase = true) }
        ) return null
        if (!sender.hasPermission("lightcutter.help")) return null

        val completions = mutableListOf<String>()
        when (args.size) {
            1 -> completions.addAll(listOf("add", "update", "remove", "info", "list", "reload"))

            2 -> {
                when (args[0].lowercase()) {
                    "update", "remove", "info" -> {
                        val regions = LightCutterAPI.getProvider().regionHandler.getRegions().map { it.name }
                        completions.add("<name>")
                        completions.addAll(regions)
                    }
                    "add" -> {
                        completions.add("<name>")
                        if (sender is Player) {
                            val regions = WorldGuardHook.getRegionNames(sender.location)
                            completions.addAll(regions)
                        }
                    }
                    "reload" -> completions.add("+database")
                }
            }

            3 -> {
                when (args[0].lowercase()) {
                    "add", "update" -> completions.addAll(listOf("<earn>", "5", "10", "30"))
                }
            }

            4 -> {
                when (args[0].lowercase()) {
                    "add", "update" -> completions.addAll(listOf("<need_break>", "5", "10", "30"))
                }
            }

            5 -> {
                when (args[0].lowercase()) {
                    "add", "update" -> completions.addAll(listOf("<cooldown>", "5", "10", "30"))
                }
            }
        }

        return completions.distinct().filter { it.startsWith(args.last(), ignoreCase = true) }
    }
}
