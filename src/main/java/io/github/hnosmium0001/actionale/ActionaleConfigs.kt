package io.github.hnosmium0001.actionale

import me.sargunvohra.mcmods.autoconfig1u.AutoConfig
import me.sargunvohra.mcmods.autoconfig1u.ConfigData
import me.sargunvohra.mcmods.autoconfig1u.annotation.Config
import me.sargunvohra.mcmods.autoconfig1u.annotation.ConfigEntry.BoundedDiscrete
import me.sargunvohra.mcmods.autoconfig1u.annotation.ConfigEntry.Gui.Tooltip
import org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH

@Config(name = Actionale.MODID)
class ActionaleConfigData : ConfigData {
    @Tooltip
    var exportNamedKeys = true

    @Tooltip
    @BoundedDiscrete(min = 3L, max = 16L)
    var radialMenuMinSides = 6

    @Tooltip
    @BoundedDiscrete(min = 0L, max = 256)
    var radialMenuRadius = 80

    @Tooltip
    var leaderKey = GLFW_KEY_BACKSLASH
}

val modConfig: ActionaleConfigData by lazy { AutoConfig.getConfigHolder(ActionaleConfigData::class.java).config }