package com.padbridge.wear.util

import android.content.Context
import android.content.SharedPreferences
import com.padbridge.wear.protocol.Protocol

/** All user settings. Thin wrapper over SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("padbridge", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v.trim()).apply()

    var port: Int
        get() = sp.getInt("port", Protocol.DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var transport: Transport
        get() = Transport.from(sp.getString("transport", null))
        set(v) = sp.edit().putString("transport", v.id).apply()

    /** How often the last state is re-sent while nothing changes (UDP loss insurance). */
    var keepaliveMs: Int
        get() = sp.getInt("keepalive", 100)
        set(v) = sp.edit().putInt("keepalive", v).apply()

    var linkMode: LinkMode
        get() = LinkMode.from(sp.getString("link_mode", null))
        set(v) = sp.edit().putString("link_mode", v.id).apply()

    var rightStick: RightStick
        get() = RightStick.from(sp.getString("right_stick", null))
        set(v) = sp.edit().putString("right_stick", v.id).apply()

    var swapFace: Boolean
        get() = sp.getBoolean("swap_face", false)
        set(v) = sp.edit().putBoolean("swap_face", v).apply()

    var deadZonePct: Int
        get() = sp.getInt("dead_zone", 10)
        set(v) = sp.edit().putInt("dead_zone", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    var dimScreen: Boolean
        get() = sp.getBoolean("dim_screen", true)
        set(v) = sp.edit().putBoolean("dim_screen", v).apply()

    var rumble: Boolean
        get() = sp.getBoolean("rumble", true)
        set(v) = sp.edit().putBoolean("rumble", v).apply()

    /** MAC of the pad used with the raw L2CAP link, so the bridge can reconnect it. */
    var rawPadAddress: String
        get() = sp.getString("raw_pad", "") ?: ""
        set(v) = sp.edit().putString("raw_pad", v).apply()

    enum class Transport(val id: String, val label: String) {
        UDP("udp", "UDP"), TCP("tcp", "TCP");

        fun next() = entries[(ordinal + 1) % entries.size]

        companion object {
            fun from(id: String?) = entries.firstOrNull { it.id == id } ?: UDP
        }
    }

    enum class LinkMode(val id: String, val label: String) {
        /** Android HID host if the watch has one, otherwise raw L2CAP. */
        AUTO("auto", "Auto"),
        HID("hid", "Android HID"),
        RAW("raw", "Raw L2CAP (exp.)");

        fun next() = entries[(ordinal + 1) % entries.size]

        companion object {
            fun from(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }

    enum class RightStick(val id: String, val label: String) {
        AUTO("auto", "Auto"), Z_RZ("z", "Z / RZ"), RX_RY("rx", "RX / RY");

        fun next() = entries[(ordinal + 1) % entries.size]

        companion object {
            fun from(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}
