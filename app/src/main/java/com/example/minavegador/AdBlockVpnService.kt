package com.example.minavegador

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!isRunning) {
                startVpn()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("MiNavegador Pro VPN")
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                isRunning = true
                vpnThread = Thread({
                    try {
                        val descriptor = vpnInterface ?: return@Thread
                        val inputStream = FileInputStream(descriptor.fileDescriptor)
                        val outputStream = FileOutputStream(descriptor.fileDescriptor)
                        val buffer = ByteBuffer.allocate(32767)

                        while (isRunning) {
                            val length = inputStream.read(buffer.array())
                            if (length > 0) {
                                // Aquí se filtran o devuelven paquetes de red locales si es necesario
                                buffer.clear()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, "VpnDataProcessorThread")

                vpnThread?.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            isRunning = false
            vpnThread?.interrupt()
            vpnInterface?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vpnInterface = null
    }
}