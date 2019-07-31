package de.kai_morich.simple_bluetooth_le_terminal

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.app.Fragment
import android.support.v4.app.ListFragment
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView

import java.util.ArrayList
import java.util.Collections

/**
 * show list of BLE devices
 */
class DevicesFragment : ListFragment() {

    private var menu: Menu? = null
    private val bluetoothAdapter: BluetoothAdapter?
    private val bleDiscoveryBroadcastReceiver: BroadcastReceiver
    private val bleDiscoveryIntentFilter: IntentFilter

    private val listItems = ArrayList<BluetoothDevice>()
    private var listAdapter: ArrayAdapter<BluetoothDevice>? = null

    init {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        bleDiscoveryBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothDevice.ACTION_FOUND) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                        activity!!.runOnUiThread { updateScan(device) }
                    }
                }
                if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                    stopScan()
                }
            }
        }
        bleDiscoveryIntentFilter = IntentFilter()
        bleDiscoveryIntentFilter.addAction(BluetoothDevice.ACTION_FOUND)
        bleDiscoveryIntentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        listAdapter = object : ArrayAdapter<BluetoothDevice>(activity!!, 0, listItems) {
            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                var view = view
                val device = listItems[position]
                if (view == null)
                    view = activity!!.layoutInflater.inflate(R.layout.device_list_item, parent, false)
                val Lamp1_text = view!!.findViewById<TextView>(R.id.Lamp1_text)
                val Lamp2_text = view.findViewById<TextView>(R.id.Lamp2_text)
                if (device.name == null || device.name.isEmpty())
                    Lamp1_text.text = "<unnamed>"
                else
                    Lamp1_text.text = device.name
                Lamp2_text.text = device.address
                return view
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setListAdapter(null)
        val header = activity!!.layoutInflater.inflate(R.layout.device_list_header, null, false)
        listView.addHeaderView(header, null, false)
        setEmptyText("initializing...")
        (listView.emptyView as TextView).textSize = 18f
        setListAdapter(listAdapter)
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        inflater!!.inflate(R.menu.menu_devices, menu)
        this.menu = menu
        if (!activity!!.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
            menu!!.findItem(R.id.bt_settings).isEnabled = false
        if (bluetoothAdapter == null || !activity!!.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            menu!!.findItem(R.id.ble_scan).isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        activity!!.registerReceiver(bleDiscoveryBroadcastReceiver, bleDiscoveryIntentFilter)
        if (bluetoothAdapter == null || !activity!!.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            setEmptyText("<bluetooth LE not supported>")
        else if (!bluetoothAdapter.isEnabled)
            setEmptyText("<bluetooth is disabled>")
        else
            setEmptyText("<use SCAN to refresh devices>")
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        activity!!.unregisterReceiver(bleDiscoveryBroadcastReceiver)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        val id = item!!.itemId
        if (id == R.id.ble_scan) {
            startScan()
            return true
        } else if (id == R.id.ble_scan_stop) {
            stopScan()
            return true
        } else if (id == R.id.bt_settings) {
            val intent = Intent()
            intent.action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            startActivity(intent)
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity!!.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                val builder = AlertDialog.Builder(activity)
                builder.setTitle(getText(R.string.location_permission_title))
                builder.setMessage(getText(R.string.location_permission_message))
                builder.setPositiveButton(android.R.string.ok
                ) { dialog, which -> requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 0) }
                builder.show()
                return
            }
        }
        listItems.clear()
        listAdapter!!.notifyDataSetChanged()
        setEmptyText("<scanning...>")
        menu!!.findItem(R.id.ble_scan).isVisible = false
        menu!!.findItem(R.id.ble_scan_stop).isVisible = true
        bluetoothAdapter!!.startDiscovery()
        //  BluetoothLeScanner.startScan(...) would return more details, but that's not needed here
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        // ignore requestCode as there is only one in this fragment
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Handler(Looper.getMainLooper()).postDelayed({ this.startScan() }, 1) // run after onResume to avoid wrong empty-text
        } else {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle(getText(R.string.location_denied_title))
            builder.setMessage(getText(R.string.location_denied_message))
            builder.setPositiveButton(android.R.string.ok, null)
            builder.show()
        }
    }

    private fun updateScan(device: BluetoothDevice) {
        if (listItems.indexOf(device) < 0) {
            listItems.add(device)
            Collections.sort(listItems, Comparator<BluetoothDevice> { a, b -> compareTo(a, b) })
            listAdapter!!.notifyDataSetChanged()
        }
    }

    private fun stopScan() {
        setEmptyText("<no bluetooth devices found>")
        if (menu != null) {
            menu!!.findItem(R.id.ble_scan).isVisible = true
            menu!!.findItem(R.id.ble_scan_stop).isVisible = false
        }
        bluetoothAdapter!!.cancelDiscovery()
    }

    override fun onListItemClick(l: ListView?, v: View?, position: Int, id: Long) {
        stopScan()
        val device = listItems[position - 1]
        val args = Bundle()
        args.putString("device", device.address)
        val fragment = TerminalFragment()
        fragment.arguments = args
        fragmentManager!!.beginTransaction().replace(R.id.fragment, fragment, "terminal").addToBackStack(null).commit()
    }

    companion object {

        /**
         * sort by name, then address. sort named devices first
         */
        internal fun compareTo(a: BluetoothDevice, b: BluetoothDevice): Int {
            val aValid = a.name != null && !a.name.isEmpty()
            val bValid = b.name != null && !b.name.isEmpty()
            if (aValid && bValid) {
                val ret = a.name.compareTo(b.name)
                return if (ret != 0) ret else a.address.compareTo(b.address)
            }
            if (aValid) return -1
            return if (bValid) +1 else a.address.compareTo(b.address)
        }
    }
}
