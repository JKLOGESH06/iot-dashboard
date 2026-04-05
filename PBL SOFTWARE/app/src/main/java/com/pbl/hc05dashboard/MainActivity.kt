package com.pbl.hc05dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var spinnerDevices: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvRealTimeData: TextView
    private lateinit var tvAlert: TextView
    private lateinit var tvLogs: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var readJob: Job? = null

    // Standard SPP UUID
    // Standard SPP UUID
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val PERMISSION_REQUEST_CODE = 101
    private val NOTIFICATION_CHANNEL_ID = "overload_alert_channel"

    private var devicesList = mutableListOf<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvRealTimeData = findViewById(R.id.tvRealTimeData)
        tvAlert = findViewById(R.id.tvAlert)
        tvLogs = findViewById(R.id.tvLogs)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            finish()
        }

        createNotificationChannel()

        checkPermissionsAndLoadDevices()

        btnConnect.setOnClickListener {
            val selectedPosition = spinnerDevices.selectedItemPosition
            if (selectedPosition >= 0 && selectedPosition < devicesList.size) {
                val device = devicesList[selectedPosition]
                connectToDevice(device)
            } else {
                Toast.makeText(this, "Please select a device", Toast.LENGTH_SHORT).show()
            }
        }

        btnDisconnect.setOnClickListener {
            disconnect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Overload Alerts"
            val descriptionText = "Notifications for when current load is cut off"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun checkPermissionsAndLoadDevices() {
        val permissionsNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            loadBondedDevices()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadBondedDevices()
            } else {
                Toast.makeText(this, "Permissions required to use Bluetooth", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadBondedDevices() {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        val deviceNames = mutableListOf<String>()
        
        pairedDevices?.forEach { device ->
            devicesList.add(device)
            deviceNames.add("${device.name} (${device.address})")
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = adapter
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        tvStatus.text = "Connecting..."
        btnConnect.isEnabled = false
        
        scope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()
                inputStream = bluetoothSocket?.inputStream

                withContext(Dispatchers.Main) {
                    tvStatus.text = getString(R.string.status_connected)
                    tvStatus.setTextColor(getColor(R.color.green_status))
                    btnConnect.isEnabled = false
                    btnDisconnect.isEnabled = true
                    startReadingData()
                    appendLog("Connected to ${device.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Connection Failed"
                    btnConnect.isEnabled = true
                    appendLog("Error: ${e.message}")
                    try { bluetoothSocket?.close() } catch (ignored: Exception) {}
                }
            }
        }
    }

    private fun startReadingData() {
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            var bytes: Int
            val stringBuilder = StringBuilder()

            while (isActive) {
                try {
                    bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val message = String(buffer, 0, bytes)
                        stringBuilder.append(message)
                        
                        // Parse complete lines
                        var endOfLineIndex = stringBuilder.indexOf("\n")
                        while (endOfLineIndex > 0) {
                            val dataLine = stringBuilder.substring(0, endOfLineIndex).trim()
                            stringBuilder.delete(0, endOfLineIndex + 1)
                            
                            withContext(Dispatchers.Main) {
                                processIncomingData(dataLine)
                            }
                            endOfLineIndex = stringBuilder.indexOf("\n")
                        }
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        disconnect()
                        appendLog("Connection lost")
                    }
                    break
                }
            }
        }
    }

    private fun processIncomingData(data: String) {
        // If it's the specific alert string
        if (data.contains("Overload detected!", ignoreCase = true)) {
            triggerAlert()
            appendLog("ALERT: Overload Detected!")
        } else {
            // Assume it is normal data like "0.023 A"
            tvRealTimeData.text = data
        }
    }

    private fun triggerAlert() {
        tvAlert.text = "OVERLOAD DETECTED!"
        tvAlert.visibility = View.VISIBLE
        
        // Vibrate
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }

        // Play sound
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Show System Push Notification
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Electrical Overload Alert!")
            .setContentText("High value detected. The load has been cut off.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        // Hide alert after 3 seconds
        scope.launch {
            delay(3000)
            tvAlert.visibility = View.GONE
        }
    }

    private fun disconnect() {
        readJob?.cancel()
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        
        tvStatus.text = getString(R.string.status_disconnected)
        tvStatus.setTextColor(getColor(R.color.black))
        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        tvRealTimeData.text = "0.000 A"
        appendLog("Disconnected")
    }

    private fun appendLog(msg: String) {
        val currentText = tvLogs.text.toString()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        tvLogs.text = "[$timestamp] $msg\n$currentText"
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scope.cancel()
    }
}
