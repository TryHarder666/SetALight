package de.kai_morich.simple_bluetooth_le_terminal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

import java.io.IOException
import java.util.ArrayList
import java.util.Arrays
import java.util.UUID

/**
 * wrap BLE communication into socket like class
 * - connect, disconnect and write as methods,
 * - read + status is returned by SerialListener
 */
internal class SerialSocket : BluetoothGattCallback() {

    private val writeBuffer: ArrayList<ByteArray>
    private val pairingIntentFilter: IntentFilter
    private val pairingBroadcastReceiver: BroadcastReceiver
    private val disconnectBroadcastReceiver: BroadcastReceiver

    private var context: Context? = null
    private var listener: SerialListener? = null
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var writePending: Boolean = false
    private var canceled: Boolean = false
    private var connected: Boolean = false

    init {
        writeBuffer = ArrayList()
        pairingIntentFilter = IntentFilter()
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        pairingIntentFilter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        pairingBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onPairingBroadcastReceive(context, intent)
            }
        }
        disconnectBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (listener != null)
                    listener!!.onSerialIoError(IOException("background disconnect"))
                disconnect() // disconnect now, else would be queued until UI re-attached
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        listener = null // ignore remaining data and errors
        device = null
        canceled = true
        synchronized(writeBuffer) {
            writePending = false
            writeBuffer.clear()
        }
        readCharacteristic = null
        writeCharacteristic = null
        if (gatt != null) {
            Log.d(TAG, "gatt.disconnect")
            gatt!!.disconnect()
            Log.d(TAG, "gatt.close")
            try {
                gatt!!.close()
            } catch (ignored: Exception) {
            }

            gatt = null
            connected = false
        }
        try {
            context!!.unregisterReceiver(pairingBroadcastReceiver)
        } catch (ignored: Exception) {
        }

        try {
            context!!.unregisterReceiver(disconnectBroadcastReceiver)
        } catch (ignored: Exception) {
        }

    }

    /**
     * connect-success and most connect-errors are returned asynchronously to listener
     */
    @Throws(IOException::class)
    fun connect(context: Context, listener: SerialListener, device: BluetoothDevice) {
        if (connected || gatt != null)
            throw IOException("already connected")
        canceled = false
        this.context = context
        this.listener = listener
        this.device = device
        context.registerReceiver(disconnectBroadcastReceiver, IntentFilter(Constants.INTENT_ACTION_DISCONNECT))
        Log.d(TAG, "connect $device")
        context.registerReceiver(pairingBroadcastReceiver, pairingIntentFilter)
        if (Build.VERSION.SDK_INT < 23) {
            Log.d(TAG, "connectGatt")
            gatt = device.connectGatt(context, false, this)
        } else {
            Log.d(TAG, "connectGatt,LE")
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        }
        if (gatt == null)
            throw IOException("connectGatt failed")
        // continues asynchronously in onPairingBroadcastReceive() and onConnectionStateChange()
    }

    private fun onPairingBroadcastReceive(context: Context, intent: Intent) {
        // for ARM Mbed, Microbit, ... use pairing from Android bluetooth settings
        // for HM10-clone, ... pairing is initiated here
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device == null || device != this.device)
            return
        when (intent.action) {
            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                Log.d(TAG, "pairing request $pairingVariant")
                onSerialConnectError(IOException(context.getString(R.string.pairing_request)))
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                Log.d(TAG, "bond state $previousBondState->$bondState")
            }
            else -> Log.d(TAG, "unknown broadcast " + intent.action!!)
        }// pairing dialog brings app to background (onPause), but it is still partly visible (no onStop), so there is no automatic disconnect()
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        // status directly taken from gat_api.h, e.g. 133=0x85=GATT_ERROR ~= timeout
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.d(TAG, "connect status $status, discoverServices")
            if (!gatt.discoverServices())
                onSerialConnectError(IOException("discoverServices failed"))
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (connected)
                onSerialIoError(IOException("gatt status $status"))
            else
                onSerialConnectError(IOException("gatt status $status"))
        } else {
            Log.d(TAG, "unknown connect state $newState $status")
        }
        // continues asynchronously in onServicesDiscovered()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Log.d(TAG, "servicesDiscovered, status $status")
        if (canceled)
            return
        writePending = false
        for (gattService in gatt.services) {
            if (gattService.uuid == BLUETOOTH_LE_CC254X_SERVICE) {
                Log.d(TAG, "service cc254x uart")
                //for(BluetoothGattCharacteristic characteristic : gattService.getCharacteristics())
                //    Log.d(TAG, "characteristic "+characteristic.getUuid());
                readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW)
                writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_CC254X_CHAR_RW)
            }
            if (gattService.uuid == BLUETOOTH_LE_RN4870_SERVICE) {
                Log.d(TAG, "service rn4870 uart")
                //for(BluetoothGattCharacteristic characteristic : gattService.getCharacteristics())
                //    Log.d(TAG, "characteristic "+characteristic.getUuid());
                readCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_RN4870_CHAR_RW)
                writeCharacteristic = gattService.getCharacteristic(BLUETOOTH_LE_RN4870_CHAR_RW)
            }
            if (gattService.uuid == BLUETOOTH_LE_NRF_SERVICE) {
                Log.d(TAG, "service nrf uart")
                //for(BluetoothGattCharacteristic characteristic : gattService.getCharacteristics())
                //    Log.d(TAG, "characteristic "+characteristic.getUuid());
                val rw2 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW2)
                val rw3 = gattService.getCharacteristic(BLUETOOTH_LE_NRF_CHAR_RW3)
                if (rw2 != null && rw3 != null) {
                    val rw2prop = rw2.properties
                    val rw3prop = rw3.properties
                    val rw2write = rw2prop and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    val rw3write = rw3prop and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                    Log.d(TAG, "characteristic properties $rw2prop/$rw3prop")
                    if (rw2write && rw3write) {
                        onSerialConnectError(IOException("multiple write characteristics ($rw2prop/$rw3prop)"))
                        return
                    } else if (rw2write) { // some devices use this ...
                        writeCharacteristic = rw2
                        readCharacteristic = rw3
                    } else if (rw3write) { // ... and other devices use this characteristic
                        writeCharacteristic = rw3
                        readCharacteristic = rw2
                    } else {
                        onSerialConnectError(IOException("no write characteristic ($rw2prop/$rw3prop"))
                        return
                    }
                }
            }
        }
        if (readCharacteristic == null || writeCharacteristic == null) {
            for (gattService in gatt.services) {
                Log.d(TAG, "service " + gattService.uuid)
            }
            onSerialConnectError(IOException("no serial profile found"))
            return
        }
        val writeProperties = writeCharacteristic!!.properties
        if (writeProperties and BluetoothGattCharacteristic.PROPERTY_WRITE +     // Microbit,HM10-clone have WRITE
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) { // HM10,TI uart have only WRITE_NO_RESPONSE
            onSerialConnectError(IOException("write characteristic not writable"))
            return
        }
        if (!gatt.setCharacteristicNotification(readCharacteristic, true)) {
            onSerialConnectError(IOException("no notification for read characteristic"))
            return
        }
        val readDescriptor = readCharacteristic!!.getDescriptor(BLUETOOTH_LE_CCCD)
        if (readDescriptor == null) {
            onSerialConnectError(IOException("no CCCD descriptor for read characteristic"))
            return
        }
        val readProperties = readCharacteristic!!.properties
        if (readProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            Log.d(TAG, "enable read indication")
            readDescriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else if (readProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            Log.d(TAG, "enable read notification")
            readDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            onSerialConnectError(IOException("no indication/notification for read characteristic ($readProperties)"))
            return
        }
        Log.d(TAG, "writing read characterictic descriptor")
        if (!gatt.writeDescriptor(readDescriptor)) {
            onSerialConnectError(IOException("read characteristic CCCD descriptor not writable"))
        }
        Log.d(TAG, "writing read characteristic descriptor")
        // continues asynchronously in onDescriptorWrite()
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.characteristic === readCharacteristic) {
            Log.d(TAG, "writing read characteristic descriptor finished, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSerialConnectError(IOException("write descriptor failed"))
            } else {
                onSerialConnect()
                connected = true
                Log.d(TAG, "connected")
            }
        } else {
            Log.d(TAG, "unknown write descriptor finished, status=$status")
        }
    }

    /*
     * read
     */
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (canceled)
            return
        if (characteristic === readCharacteristic) {
            val data = readCharacteristic!!.value
            onSerialRead(data)
            Log.d(TAG, "read, len=" + data.size)
        }
    }

    /*
     * write
     */
    @Throws(IOException::class)
    fun write(data: ByteArray) {
        if (canceled || !connected || writeCharacteristic == null)
            throw IOException("not connected")
        var data0: ByteArray?
        synchronized(writeBuffer) {
            if (data.size <= 20) {
                data0 = data
            } else {
                data0 = Arrays.copyOfRange(data, 0, 20)
            }
            if (!writePending && writeBuffer.isEmpty()) {
                writePending = true
            } else {
                writeBuffer.add(data)
                Log.d(TAG, "write queued, len=" + data0!!.size)
                data0 = null
            }
            if (data.size > 20) {
                for (i in 1 until (data.size + 19) / 20) {
                    val from = i * 20
                    val to = Math.min(from + 20, data.size)
                    writeBuffer.add(Arrays.copyOfRange(data, from, to))
                    Log.d(TAG, "write queued, len=" + (to - from))
                }
            }
        }
        if (data0 != null) {
            writeCharacteristic!!.value = data0
            if (!gatt!!.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=" + data0!!.size)
            }
        }
        // continues asynchronously in onCharacteristicWrite()
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (canceled || !connected || writeCharacteristic == null)
            return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            onSerialIoError(IOException("write failed"))
            return
        }
        if (characteristic === writeCharacteristic) {
            Log.d(TAG, "write finished, status=$status")
            writeNext()
        }
    }

    private fun writeNext() {
        var data: ByteArray?
        synchronized(writeBuffer) {
            if (!writeBuffer.isEmpty()) {
                writePending = true
                data = writeBuffer.removeAt(0)
            } else {
                writePending = false
                data = null
            }
        }
        if (data != null) {
            writeCharacteristic!!.value = data
            if (!gatt!!.writeCharacteristic(writeCharacteristic)) {
                onSerialIoError(IOException("write failed"))
            } else {
                Log.d(TAG, "write started, len=" + data!!.size)
            }
        }
    }

    /**
     * SerialListener
     */
    private fun onSerialConnect() {
        if (listener != null)
            listener!!.onSerialConnect()
    }

    private fun onSerialConnectError(e: Exception) {
        canceled = true
        if (listener != null)
            listener!!.onSerialConnectError(e)
    }

    private fun onSerialRead(data: ByteArray) {
        if (listener != null)
            listener!!.onSerialRead(data)
    }

    private fun onSerialIoError(e: Exception) {
        writePending = false
        canceled = true
        if (listener != null)
            listener!!.onSerialIoError(e)
    }

    companion object {

        private val BLUETOOTH_LE_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BLUETOOTH_LE_CC254X_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val BLUETOOTH_LE_CC254X_CHAR_RW = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val BLUETOOTH_LE_NRF_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val BLUETOOTH_LE_NRF_CHAR_RW2 = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // read on microbit, write on adafruit
        private val BLUETOOTH_LE_NRF_CHAR_RW3 = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val BLUETOOTH_LE_RN4870_SERVICE = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        private val BLUETOOTH_LE_RN4870_CHAR_RW = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")

        private val TAG = "SerialSocket"
    }

}
