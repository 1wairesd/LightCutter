package ru.kainlight.lightcutter.api

import ru.kainlight.lightcutter.Main

internal data class IRegion(
    override val name: String,
    override val earn: String,
    override val needBreak: Int,
    override val cooldown: Int
) : Region {

    override fun getInfo(): String {
        val messages = Main.getInstance().getMessages()
        val info: String = messages.getString("region.info") ?: "null"
        return info
            .replace("#region#", this.name)
            .replace("#earn#", this.earn)
            .replace("#count#", this.needBreak.toString())
            .replace("#cooldown#", this.cooldown.toString())
    }
}
