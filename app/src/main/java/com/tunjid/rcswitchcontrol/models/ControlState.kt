package com.tunjid.rcswitchcontrol.models

import android.content.res.Resources
import androidx.fragment.app.Fragment
import com.rcswitchcontrol.protocols.CommsProtocol
import com.rcswitchcontrol.protocols.Name
import com.rcswitchcontrol.protocols.models.Payload
import com.rcswitchcontrol.zigbee.models.ZigBeeAttribute
import com.rcswitchcontrol.zigbee.models.ZigBeeCommandInfo
import com.tunjid.rcswitchcontrol.R
import com.tunjid.rcswitchcontrol.fragments.DevicesFragment
import com.tunjid.rcswitchcontrol.fragments.HostFragment
import com.tunjid.rcswitchcontrol.fragments.RecordFragment
import com.tunjid.rcswitchcontrol.utils.Tab
import java.util.HashMap
import java.util.Locale

data class ProtocolKey(val key: CommsProtocol.Key) : Tab {
    val title get() = key.value.split(".").last().toUpperCase(Locale.US).removeSuffix("PROTOCOL")

    override fun title(res: Resources) = title

    override fun createFragment(): Fragment = RecordFragment.commandInstance(this)
}

data class ControlState(
    val isNew: Boolean = false,
    val connectionState: String = "",
    val commandInfo: ZigBeeCommandInfo? = null,
    val history: List<Record> = listOf(),
    val commands: Map<CommsProtocol.Key, List<Record.Command>> = mapOf(),
    val devices: List<Device> = listOf()
)

enum class Page : Tab {

    HOST, HISTORY, DEVICES;

    override fun createFragment(): Fragment = when (this) {
        HOST -> HostFragment.newInstance()
        HISTORY -> RecordFragment.historyInstance()
        DEVICES -> DevicesFragment.newInstance()
    }

    override fun title(res: Resources): CharSequence = when (this) {
        HOST -> res.getString(R.string.host)
        HISTORY -> res.getString(R.string.history)
        DEVICES -> res.getString(R.string.devices)
    }
}

val ControlState.keys
    get() = commands.keys
        .sortedBy(CommsProtocol.Key::value)
        .map(::ProtocolKey)

fun ControlState.reduceDevices(fetched: List<Device>?) = when {
    fetched != null -> copy(devices = (fetched + devices)
        .distinctBy(Device::diffId)
        .sortedBy(Device::name))
    else -> this
}

fun ControlState.reduceZigBeeAttributes(fetched: List<ZigBeeAttribute>?) = when (fetched) {
    null -> this
    else -> copy(devices = devices
        .filterIsInstance<Device.ZigBee>()
        .map { it.foldAttributes(fetched) }
        .plus(devices)
        .distinctBy(Device::diffId)
    )
}

fun ControlState.reduceHistory(record: Record?) = when {
    record != null -> copy(history = (history + record).takeLast(500))
    else -> this
}

fun ControlState.reduceCommands(payload: Payload) = copy(
    commands = HashMap(commands).apply {
        this[payload.key] = payload.commands.map { Record.Command(key = payload.key, command = it) }
    }
)

fun ControlState.reduceDeviceName(name: Name?) = when (name) {
    null -> this
    else -> copy(devices = listOfNotNull(devices.firstOrNull { it.id == name.id }.let {
        when (it) {
            is Device.RF -> it.copy(switch = it.switch.copy(name = name.value))
            is Device.ZigBee -> it.copy(givenName = name.value)
            else -> it
        }
    })
        .plus(devices)
        .distinctBy(Device::diffId)
    )
}
