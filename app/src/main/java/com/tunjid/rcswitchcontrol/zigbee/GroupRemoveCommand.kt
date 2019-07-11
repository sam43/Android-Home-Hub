package com.tunjid.rcswitchcontrol.zigbee

import com.tunjid.rcswitchcontrol.App
import com.tunjid.rcswitchcontrol.R
import com.zsmartsystems.zigbee.ZigBeeGroupAddress
import com.zsmartsystems.zigbee.ZigBeeNetworkManager
import java.io.PrintStream


/**
 * Removes group from network state but does not affect actual ZigBeet network.
 */
class GroupRemoveCommand : AbsZigBeeCommand() {
    override val args: String = "GROUP"

    override fun getCommand(): String = App.instance.getString(R.string.zigbeeprotocol_remove_group)

    override fun getDescription(): String = "Removes group from gateway network state."

    @Throws(Exception::class)
    override fun process(networkManager: ZigBeeNetworkManager, args: Array<String>, out: PrintStream) {
        args.expect(2)

        val address = networkManager.findDestination(args[1]) as? ZigBeeGroupAddress
                ?: throw IllegalArgumentException("The address provided is not a ZigBee Group")

        networkManager.removeGroup(address.groupId)
    }
}