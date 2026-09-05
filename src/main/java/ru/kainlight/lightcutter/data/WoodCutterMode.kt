package ru.kainlight.lightcutter.data

import ru.kainlight.lightcutter.Main

internal enum class WoodCutterMode {
    WORLD,
    REGION;

    companion object {
        // Read config on every call — fixes lazy val ignoring /reload
        fun getCurrent(): WoodCutterMode {
            val mode = Main.getInstance().config
                .getString("woodcutter-settings.mode")?.uppercase() ?: "WORLD"
            return entries.find { it.name == mode } ?: WORLD
        }
    }
}
