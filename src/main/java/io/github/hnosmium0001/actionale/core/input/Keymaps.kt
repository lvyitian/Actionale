@file:Suppress("NAME_SHADOWING")

package io.github.hnosmium0001.actionale.core.input

import com.google.common.base.Preconditions
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.hnosmium0001.actionale.IdentityHashListenerMap
import io.github.hnosmium0001.actionale.ListenerMap
import io.github.hnosmium0001.actionale.mixinextension.ExtendedKeyBinding
import io.github.hnosmium0001.actionale.modConfig
import io.github.hnosmium0001.actionale.pack
import net.minecraft.client.options.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE

enum class KeymapType(val tag: String) {
    /**
     * Keymaps automatically generated from vanilla's KeyBinding. Can be added neither in
     * code nor in-game.
     */
    MIGRATED("#migrated"),

    /**
     * Keymaps created by mod developers, immutable and can't be changed by players.
     */
    DEVELOPER_DEFINED("#developer_defined"),

    /**
     * Keymaps created by players, this will override both developer-defined keymaps
     * and migrated KeyBinding keymaps. This is the only keymaps that are persisted on disk.
     */
    USER_DEFINED("#user_defined"),
    ;

    companion object {
        val types = values() // Cached members array
        val tag2typeMap: Map<String, KeymapType> = HashMap<String, KeymapType>().apply {
            for (type in types) {
                put(type.tag, type)
            }
        }

        fun fromTag(tag: String) = tag2typeMap[tag]
    }
}

/**
 * A keymap is an ordered combination of different key chords. It is "pressed" when all of the key chords are pressed in
 * the given order, and released when any of the key chords are released.
 *
 * @see [KeymapType]
 */
class Keymap(
    val name: String,
    val type: KeymapType = KeymapType.DEVELOPER_DEFINED,
    combination: Array<out KeyChord>
) {
    var combination: Array<out KeyChord> = combination
        set(value) {
            // Remove the old listeners, add new listeners
            for (chord in field) {
                chord.listeners.remove(this)
            }
            for (chord in value) {
                chord.listeners[this] = this::onChordChanged
            }
            field = value
        }

    /**
     * Listeners to be called whenever this keymap changes state, i.e. from `PRESSED` to `RELEASED` or vice versa. Add
     * or remove listeners according to the need.
     */
    val listeners: ListenerMap<(Keymap, InputAction) -> Unit> = IdentityHashListenerMap()
    var state: InputAction = GLFW_RELEASE
        private set(value) {
            if (field != value) {
                field = value
                for (listener in listeners.values) {
                    listener.invoke(this, value)
                }
            }
        }
    val pressed get() = state == GLFW_PRESS
    val released get() = state == GLFW_RELEASE

    init {
        Preconditions.checkArgument(combination.isNotEmpty())

        for (chord in combination) {
            chord.listeners[this] = this::onChordChanged
        }
    }

    private var expectedIndex = 0
    private var expectedAction = GLFW_PRESS
    private fun onChordChanged(chord: KeyChord, action: InputAction) {
        if (combination[expectedIndex] == chord && expectedAction == action) {
            // For the last key chord, press/release this keymap when it gets pressed/released
            if (expectedIndex == combination.size - 1) {
                state = action
            }

            expectedAction = when (expectedAction) {
                GLFW_PRESS -> GLFW_RELEASE
                GLFW_RELEASE -> {
                    // The current key chord has completed both actions, move on to the next one
                    expectedIndex++
                    GLFW_PRESS
                }
                else -> throw RuntimeException()
            }

            // Went through all of the key chords
            if (expectedIndex >= combination.size) {
                expectedIndex = 0
            }
        } else {
            // Pressing streak failed, reset to beginning
            expectedIndex = 0
            // Retry from the start because the input might be the first key chord
            this.onChordChanged(chord, action)
        }
    }

    override fun toString(): String {
        return combination.asSequence()
            .map { it.toString() }
            .joinToString { it }
    }

    fun translate(): String {
        return combination.asSequence()
            .map { it.translate() }
            .joinToString { it }
    }
}

fun Keymap.serializeCombinations() =
    // KeyChord[] -> JsonArray
    combination.pack { chord ->
        // KeyChord -> JsonObject
        JsonObject().also { chordData ->
            // Key[] -> JsonArray
            chordData.add("keys", chord.keys.pack { key ->
                // Key -> JsonObject
                JsonObject().also { keyData ->
                    if (modConfig.exportNamedKeys) {
                        keyData.addProperty("type", key.categoryName)
                        keyData.addProperty("keycode", key.indicator)
                    } else {
                        keyData.addProperty("type", key.category.ordinal)
                        keyData.addProperty("keycode", key.keyCode)
                    }
                }
                // ...
            })
        }
        // ...
    }

fun deserializeKeymapCombinations(data: JsonArray) =
    // JsonArray -> KeyChord[]
    Array(data.size()) { chordIdx ->
        // JsonObject -> KeyChord
        data.get(chordIdx).asJsonObject.let { chordData ->
            val keysData = chordData.get("keys").asJsonArray
            // JsonArray -> Key[]
            KeyChordManager.obtain(*Array(keysData.size()) { keyIdx ->
                // JsonObject -> Key
                keysData.get(keyIdx).asJsonObject.let { keyData ->
                    val typeJson = keyData.get("type").asJsonPrimitive
                    val keyCodeJson = keyData.get("keycode").asJsonPrimitive

                    when {
                        typeJson.isNumber && keyCodeJson.isNumber ->
                            InputUtil.Type.values()[typeJson.asInt].createFromCode(keyCodeJson.asInt)
                        typeJson.isString && keyCodeJson.isString ->
                            keyFrom(typeJson.asString, keyCodeJson.asString)
                        else ->
                            throw RuntimeException()
                    }
                }
                // ...
            })
        }
        // ...
    }

fun Keymap.copyWith(
    newName: String? = null,
    newType: KeymapType? = null,
    newCombination: Array<out KeyChord>? = null,
    transferListeners: Boolean = false
): Keymap {
    val updated = Keymap(
        name = newName ?: this.name,
        type = newType ?: this.type,
        combination = newCombination ?: this.combination
    )
    if (transferListeners) {
        for (listener in this.listeners.values) {
            updated.listeners += listener
        }
    }
    return updated
}

fun Keymap.copy() = this.copyWith(transferListeners = true)

object KeymapManager {
    /**
     * Keymaps automatically generated from [KeyBinding]s. Cannot be created manually but its generation can be
     * configured through keymap overrides.
     */
    val migratedKeymaps: Map<String, Pair<KeyBinding, Keymap>> = HashMap()

    /**
     * Developer-defined immutable (in terms of players) keymaps. This may contain action-related keymaps *if* they are
     * defined by the developer, otherwise (defined by players) they will exist in [keymapOverrides]. Nor will these
     * keymaps be persisted.
     */
    val keymaps: Map<String, Keymap> = HashMap()

    /**
     * Player-defined key maps that are persisted onto the disk. The overrides has higher priority than the developer-
     * defined keymaps when querying and deleting.
     *
     * Note: player-defined overrides are meant to be used in combination with radial action menus. Their callbacks are
     * reattached by radial menus after deserialization. If developers make use of overrides, they need to reattach
     * callbacks to the overrides after deserialization too since lambdas can't be serialized (easily).
     */
    // Modified by users through GUI or options file
    val keymapOverrides: MutableMap<String, Keymap> = HashMap()

    /**
     * Generate and add migrated keymaps
     */
    fun generateMigrations(keyBindings: Array<out KeyBinding>) {
        migratedKeymaps as MutableMap
        for (keyBinding in keyBindings) {
            val migration = Keymap(
                name = keyBinding.id,
                type = KeymapType.MIGRATED,
                combination = arrayOf(KeyChordManager.obtain(keyBinding.defaultKeyCode))
            )
            migration.listeners += { _, action ->
                keyBinding as ExtendedKeyBinding
                when (action) {
                    GLFW_PRESS -> keyBinding.press()
                    GLFW_RELEASE -> keyBinding.release()
                    else -> throw RuntimeException()
                }
            }
            migratedKeymaps[keyBinding.id] = Pair(keyBinding, migration)
        }
    }

    /**
     * Update key combination of the named migrated keymap. This should **NOT** be called by developers for
     * customization usages. Instead this is meant for syncing key combination when other mods tries to modify
     * [KeyBinding]'s key code
     *
     * @see io.github.hnosmium0001.actionale.mixin.MixinKeyBinding.setKeyCodeHook
     */
    fun updateMigration(name: String, newKey: Key) {
        migratedKeymaps as MutableMap
        val (_, original) = migratedKeymaps[name] ?: return
        original.combination = arrayOf(KeyChordManager.obtain(newKey))
    }

    fun registerKeymap(name: String, keymap: Keymap) {
        Preconditions.checkArgument(keymap.type == KeymapType.DEVELOPER_DEFINED)
        Preconditions.checkArgument(!keymaps.containsKey(name))

        keymaps as MutableMap
        keymaps[name] = keymap
    }

    operator fun get(name: String): Keymap? {
        return keymapOverrides[name] ?: keymaps[name] ?: migratedKeymaps[name]?.second
    }

    /**
     * Attempt to remove a registered keymap. Try on user-defined overrides first, otherwise try on developer-defined
     * keymaps.
     */
    fun remove(name: String): Boolean {
        // Removing migrated keymaps is not allowed
        return if (keymapOverrides.remove(name) != null) {
            true
        } else {
            keymaps as MutableMap
            keymaps.remove(name) != null
        }
    }

    private fun serializeCategory(keymaps: Map<String, Keymap>): JsonArray {
        // Map<String, Keymap> -> JsonArray
        return keymaps.entries.pack { (_, keymap) ->
            // Keymap -> JsonObject
            // The `name` map key and the `type` is pretended to be a part of a Keymap
            JsonObject().also { keymapData ->
                keymapData.addProperty("name", keymap.name)
                // Currently discarded, reserved for future uses
                keymapData.addProperty("type", keymap.type.tag)
                keymapData.add("combinations", keymap.serializeCombinations())
            }
            // ...
        }
    }

    // TODO The `type` property in serialized form would be useless, consider removing it?
    fun serialize() = serializeCategory(keymapOverrides)

    private fun deserializeCategory(data: JsonArray, keymaps: MutableMap<String, Keymap>) {
        // JsonArray -> Map<String, Keymap>
        for (keymapData in data) {
            // JsonObject -> Keymap
            // The `name` map key and the `type` is pretended to be a part of a Keymap
            val keymapData = keymapData.asJsonObject
            val keymap = Keymap(
                name = keymapData.get("name").asString,
                type = KeymapType.fromTag(keymapData.get("type").asString)!!,
                combination = deserializeKeymapCombinations(keymapData.get("combinations").asJsonArray)
            )
            // Drop unmapped overrides
            if (keymaps.contains(keymap.name)) {
                keymaps[keymap.name] = keymap
            }
            // ...
        }
    }

    fun deserialize(data: JsonArray) = deserializeCategory(data, keymapOverrides)
}