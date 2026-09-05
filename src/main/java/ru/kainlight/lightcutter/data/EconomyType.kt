package ru.kainlight.lightcutter.data

import ru.kainlight.lightcutter.Main

enum class EconomyType {
    VAULT,
    PLAYERPOINTS;

    companion object {
        // Read config on every call — fixes lazy val ignoring /reload
        fun getCurrent(): EconomyType {
            val type = Main.getInstance().config
                .getString("woodcutter-settings.economy")?.uppercase() ?: "VAULT"
            return entries.find { it.name == type } ?: VAULT
        }
    }
}
