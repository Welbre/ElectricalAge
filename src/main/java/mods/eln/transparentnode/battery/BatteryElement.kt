package mods.eln.transparentnode.battery

import mods.eln.Eln
import mods.eln.i18n.I18N
import mods.eln.misc.Direction
import mods.eln.misc.LRDU
import mods.eln.misc.Utils
import mods.eln.node.NodeBase
import mods.eln.node.NodePeriodicPublishProcess
import mods.eln.node.transparent.TransparentNode
import mods.eln.node.transparent.TransparentNodeDescriptor
import mods.eln.node.transparent.TransparentNodeElement
import mods.eln.sim.ElectricalLoad
import mods.eln.sim.ThermalLoad
import mods.eln.sim.mna.component.Resistor
import mods.eln.sim.mna.component.VoltageSource
import mods.eln.sim.mna.misc.MnaConst
import mods.eln.sim.nbt.NbtBatteryProcess
import mods.eln.sim.nbt.NbtBatterySlowProcess
import mods.eln.sim.nbt.NbtElectricalLoad
import mods.eln.sim.nbt.NbtThermalLoad
import mods.eln.sim.process.destruct.ThermalLoadWatchDog
import mods.eln.sim.process.destruct.WorldExplosion
import mods.eln.sim.process.heater.ElectricalLoadHeatThermalLoad
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt.NBTTagCompound
import java.io.DataOutputStream
import java.io.IOException
import java.util.*

class BatteryElement(transparentNode: TransparentNode, descriptor: TransparentNodeDescriptor) : TransparentNodeElement(transparentNode, descriptor) {
    var descriptor: BatteryDescriptor = descriptor as BatteryDescriptor
    var positiveLoad = NbtElectricalLoad("positiveLoad")
    var negativeLoad = NbtElectricalLoad("negativeLoad")
    var voltageSource = VoltageSource("volSrc", positiveLoad, negativeLoad)
    var thermalLoad = NbtThermalLoad("thermalLoad")
    var negativeETProcess = ElectricalLoadHeatThermalLoad(negativeLoad, thermalLoad)
    var thermalWatchdog = ThermalLoadWatchDog()
    var batteryProcess = NbtBatteryProcess(positiveLoad, negativeLoad, this.descriptor.UfCharge, 0.0, voltageSource, thermalLoad)
    var dischargeResistor = Resistor(positiveLoad, negativeLoad)
    var batterySlowProcess = NbtBatterySlowProcess(node, batteryProcess, thermalLoad)
    var fromItemStack = false
    var fromItemstackCharge = 0.0
    var fromItemstackLife = 0.0
    override fun getElectricalLoad(side: Direction, lrdu: LRDU): ElectricalLoad? {
        if (lrdu != LRDU.Down) return null
        if (side == front.left()) return positiveLoad
        return if (side == front.right()) negativeLoad else null
    }

    override fun getThermalLoad(side: Direction, lrdu: LRDU): ThermalLoad? {
        if (lrdu != LRDU.Down) return null
        if (side == front.left()) return thermalLoad
        return if (side == front.right()) thermalLoad else null
    }

    override fun getConnectionMask(side: Direction, lrdu: LRDU): Int {
        if (lrdu != LRDU.Down) return 0
        if (side == front.left()) return NodeBase.maskElectricalPower
        return if (side == front.right()) NodeBase.maskElectricalPower else 0
    }

    override fun multiMeterString(side: Direction): String {
        var str = ""
        str += Utils.plotVolt("Ubat:", batteryProcess.u)
        str += Utils.plotAmpere("I:", batteryProcess.dischargeCurrent)
        str += Utils.plotPercent("Charge:", batteryProcess.charge)
        // batteryProcess.life is a percentage from 1.0 to 0.0.
        str += Utils.plotPercent("Life:", batteryProcess.life)
        return str
    }

    override fun thermoMeterString(side: Direction): String {
        return Utils.plotCelsius("Tbat:", thermalLoad.Tc)
    }

    override fun networkSerialize(stream: DataOutputStream) {
        super.networkSerialize(stream)
        try {
            stream.writeFloat((batteryProcess.u * batteryProcess.dischargeCurrent).toFloat())
            stream.writeFloat(batteryProcess.energy.toFloat())
            stream.writeShort((batteryProcess.life * 1000).toInt())
            node.lrduCubeMask.getTranslate(Direction.YN).serialize(stream)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun initialize() {
        descriptor.applyTo(batteryProcess)
        descriptor.applyTo(thermalLoad)
        descriptor.applyTo(dischargeResistor)
        descriptor.applyTo(batterySlowProcess)
        positiveLoad.rs = descriptor.electricalRs
        negativeLoad.rs = descriptor.electricalRs
        dischargeResistor.r = MnaConst.highImpedance
        if (fromItemStack) {
            println("Loading from item stack")
            batteryProcess.life = fromItemstackLife
            batteryProcess.charge = fromItemstackCharge
            fromItemStack = false
        }
        connect()
    }

    override fun reconnect() {
        disconnect()
        connect()
    }

    override fun onBlockActivated(entityPlayer: EntityPlayer, side: Direction, vx: Float, vy: Float, vz: Float): Boolean {
        return false
    }

    override fun hasGui(): Boolean {
        return true
    }

    override fun readItemStackNBT(nbt: NBTTagCompound?) {
        super.readItemStackNBT(nbt)
        fromItemstackCharge = nbt?.getDouble("charge")?: 0.5
        fromItemstackLife = nbt?.getDouble("life")?: 1.0
        fromItemStack = true
    }

    override fun getItemStackNBT(): NBTTagCompound {
        val nbt = NBTTagCompound()
        nbt.setDouble("charge", batteryProcess.charge)
        nbt.setDouble("life", batteryProcess.life)
        return nbt
    }

    override fun getWaila(): Map<String, String> {
        val info: MutableMap<String, String> = HashMap()
        info[I18N.tr("Charge")] = Utils.plotPercent("", batteryProcess.charge)
        info[I18N.tr("Energy")] = Utils.plotEnergy("", batteryProcess.energy)
        info[I18N.tr("Life")] = Utils.plotPercent("", batteryProcess.life)
        if (Eln.wailaEasyMode) {
            info[I18N.tr("Voltage")] = Utils.plotVolt("", batteryProcess.u)
            info[I18N.tr("Current")] = Utils.plotAmpere("", batteryProcess.dischargeCurrent)
            info[I18N.tr("Temperature")] = Utils.plotCelsius("", thermalLoad.Tc)
        }
        val subSystemSize = positiveLoad.subSystem.component.size
        var textColor = ""
        textColor = when {
            subSystemSize <= 8 -> {
                "§a"
            }
            subSystemSize <= 15 -> {
                "§6"
            }
            else -> {
                "§c"
            }
        }
        info[I18N.tr("Subsystem Matrix Size")] = textColor + subSystemSize
        return info
    }

    init {
        electricalLoadList.add(positiveLoad)
        electricalLoadList.add(negativeLoad)
        electricalComponentList.add(Resistor(positiveLoad, null))
        electricalComponentList.add(Resistor(negativeLoad, null))
        electricalComponentList.add(dischargeResistor)
        electricalComponentList.add(voltageSource)
        thermalLoadList.add(thermalLoad)
        electricalProcessList.add(batteryProcess)
        thermalFastProcessList.add(negativeETProcess)
        slowProcessList.add(batterySlowProcess)
        slowProcessList.add(NodePeriodicPublishProcess(transparentNode!!, 1.0, 0.0))
        batteryProcess.IMax = this.descriptor.IMax
        slowProcessList.add(thermalWatchdog)
        thermalWatchdog
            .set(thermalLoad)
            .setTMax(this.descriptor.thermalWarmLimit)
            .set(WorldExplosion(this).machineExplosion())
    }
}
