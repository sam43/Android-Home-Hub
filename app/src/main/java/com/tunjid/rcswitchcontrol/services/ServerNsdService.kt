/*
 * MIT License
 *
 * Copyright (c) 2019 Adetunji Dahunsi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tunjid.rcswitchcontrol.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rcswitchcontrol.protocols.CommsProtocol
import com.rcswitchcontrol.protocols.io.ConsoleWriter
import com.tunjid.androidx.communications.nsd.NsdHelper
import com.tunjid.androidx.communications.nsd.NsdHelper.createBufferedReader
import com.tunjid.androidx.communications.nsd.NsdHelper.createPrintWriter
import com.tunjid.androidx.core.components.services.SelfBinder
import com.tunjid.androidx.core.components.services.SelfBindingService
import com.tunjid.rcswitchcontrol.App
import com.tunjid.rcswitchcontrol.App.Companion.catcher
import com.tunjid.rcswitchcontrol.R
import com.tunjid.rcswitchcontrol.common.filterIsInstance
import com.tunjid.rcswitchcontrol.common.serialize
import com.tunjid.rcswitchcontrol.di.dagger
import com.tunjid.rcswitchcontrol.interfaces.ClientStartedBoundService
import com.tunjid.rcswitchcontrol.models.Broadcast
import com.tunjid.rcswitchcontrol.nsd.protocols.ProxyProtocol
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import java.io.Closeable
import java.io.IOException
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Service hosting a [CommsProtocol] on network service discovery
 */
class ServerNsdService : Service(), SelfBindingService<ServerNsdService> {

    private lateinit var nsdHelper: NsdHelper
    private lateinit var serverThread: ServerThread

    private val binder = Binder()

    private val disposable = CompositeDisposable()

    override fun onCreate() {
        super.onCreate()
        initialize()

        dagger.appComponent.broadcasts()
            .filterIsInstance<Broadcast.ServerNsd.Stop>()
            .subscribe({
                tearDown()
                stopSelf()
            }, Throwable::printStackTrace)
            .addTo(disposable)
    }

    override fun onBind(intent: Intent): SelfBinder<ServerNsdService> = binder

    private fun tearDown() {
        serverThread.close()
        nsdHelper.tearDown()
    }

    override fun onDestroy() {
        super.onDestroy()

        disposable.clear()
        tearDown()
    }

    fun restart() {
        tearDown()
        initialize()
    }

    private fun initialize() {
        val initialServiceName = serviceName

        // Since discovery will happen via Nsd, we don't need to care which port is
        // used, just grab an avaialable one and advertise it via Nsd.

        try {
            val serverSocket = ServerSocket(0)
            nsdHelper = NsdHelper.getBuilder(this)
                .setRegisterSuccessConsumer(this::onNsdServiceRegistered)
                .setRegisterErrorConsumer { service, error -> Log.i(TAG, "Could not register service " + service.serviceName + ". Error code: " + error) }
                .build()
                .apply {
                    registerService(serverSocket.localPort, initialServiceName)
                }

            serverThread = ServerThread(context = this, serverSocket = serverSocket).apply { start() }
            startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, ClientStartedBoundService.NOTIFICATION_TYPE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getText(R.string.started_server_service))
                .build())
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun onNsdServiceRegistered(service: NsdServiceInfo) {
        isServer = true
        serviceName = service.serviceName
        ClientNsdService.lastConnectedService = service.serviceName

        Log.i(TAG, "Registered data for: " + service.serviceName)
        dagger.appComponent.broadcaster(Broadcast.ClientNsd.StartDiscovery(service.takeIf { it.host != null }))
    }

    /**
     * [android.os.Binder] for [ServerNsdService]
     */
    private inner class Binder : SelfBinder<ServerNsdService>() {
        override val service: ServerNsdService
            get() = this@ServerNsdService
    }

    /**
     * Thread for communications between [ServerNsdService] and it's clients
     */
    private class ServerThread(
        context: Context,
        private val serverSocket: ServerSocket
    ) : Thread(), Closeable {

        @Volatile
        var isRunning: Boolean = false
        private val portMap = ConcurrentHashMap<Int, Connection>()

        private val protocol = ProxyProtocol(context, ConsoleWriter(this::broadcastToClients))
        private val pool = Executors.newFixedThreadPool(5)

        override fun run() {
            isRunning = true

            Log.d(TAG, "ServerSocket Created, awaiting connections.")

            while (isRunning) {
                try {
                    Connection( // Create new connection for every new client
                        serverSocket.accept(), // Block this ServerThread till a socket connects
                        this::onClientWrite,
                        this::broadcastToClients,
                        this::onConnectionOpened,
                        this::onConnectionClosed)
                        .apply { pool.submit(this) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating client connection: ", e)
                }
            }

            Log.d(TAG, "ServerSocket Dead.")
        }

        private fun onConnectionOpened(port: Int, connection: Connection) {
            portMap[port] = connection
            Log.d(TAG, "Client connected. Number of clients: ${portMap.size}")
        }

        private fun onConnectionClosed(port: Int) {
            portMap.remove(port)
            Log.d(TAG, "Client left. Number of clients: ${portMap.size}")
        }

        private fun onClientWrite(input: String?): String {
//            Log.d(TAG, "Read from client stream: $input")
            return protocol.processInput(input).serialize()
        }

        @Synchronized
        private fun broadcastToClients(output: String) {
//            Log.d(TAG, "Writing to all connections: ${JSONObject(output).toString(4)}")
            pool.execute { portMap.values.forEach { it.outWriter.println(output) } }
        }

        override fun close() {
            isRunning = false

            for (key in portMap.keys) catcher(TAG, "Closing server connection with id $key") { portMap[key]?.close() }

            portMap.clear()
            catcher(TAG, "Closing server socket.") { serverSocket.close() }
            catcher(TAG, "Shutting down execution pool.") { pool.shutdown() }
        }
    }

    /**
     * Connection between [ServerNsdService] and it's clients
     */
    private class Connection(
        private val socket: Socket,
        private val inputProcessor: (input: String?) -> String,
        private val outputProcessor: (output: String) -> Unit,
        private val onOpen: (port: Int, connection: Connection) -> Unit,
        private val onClose: (port: Int) -> Unit
    ) : Runnable, Closeable {

        val port: Int = socket.port
        lateinit var outWriter: PrintWriter

        override fun run() {
            if (!socket.isConnected) return

            onOpen.invoke(port, this)

            try {
                outWriter = createPrintWriter(socket)
                val reader = createBufferedReader(socket)

                // Initiate conversation with client
                outputProcessor.invoke(inputProcessor.invoke(CommsProtocol.pingAction.value))

                while (true) {
                    val input = reader.readLine() ?: break
                    val output = inputProcessor.invoke(input)
                    outputProcessor.invoke(output)

                    if (output == "Bye.") break
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                try {
                    close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        @Throws(IOException::class)
        override fun close() {
            onClose.invoke(port)
            socket.close()
        }
    }

    companion object {
        private val TAG = ServerNsdService::class.java.simpleName

        private const val SERVER_FLAG = "com.tunjid.rcswitchcontrol.ServerNsdService.services.server.flag"
        private const val SERVICE_NAME_KEY = "com.tunjid.rcswitchcontrol.ServerNsdService.services.server.serviceName"
        private const val WIRELESS_SWITCH_SERVICE = "Wireless Switch Service"
        private const val NOTIFICATION_ID = 3

        var serviceName: String
            get() = App.preferences.getString(SERVICE_NAME_KEY, WIRELESS_SWITCH_SERVICE)
                ?: WIRELESS_SWITCH_SERVICE
            set(value) = App.preferences.edit().putString(SERVICE_NAME_KEY, value).apply()

        var isServer: Boolean
            get() = App.preferences.getBoolean(SERVER_FLAG, App.isAndroidThings)
            set(value) = App.preferences.edit().putBoolean(SERVER_FLAG, value).apply()
    }
}
