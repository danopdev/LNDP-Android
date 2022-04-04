package com.dan.lndpandroid

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.*
import android.provider.DocumentsProvider
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.timer


class LNDocumentsProvider : DocumentsProvider() {
    companion object {
        const val LOG_TAG = "LNDP"
        const val AUTHORITY = "com.dan.lndpandroid"
        const val HTTP_ERROR = "HTTP Error"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        private fun serviceUseSSL(service: NsdServiceInfo): Boolean {
            try {
                val sslAttr = service.attributes["ssl"].toString().toUpperCase()
                return sslAttr == "TRUE" || sslAttr == "T" || sslAttr == "1"
            } catch(e: Exception) {
            }

            return false
        }
    }

    private val resolvedServers = ConcurrentHashMap<String, NsdServiceInfo>()
    private val foundServers = ConcurrentHashMap<String, NsdServiceInfo>()

    private lateinit var nsdManager: NsdManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var deviceId: String
    private var isConnected = false
    private var isResolving = false

    private val connectivityManagerNetworkCallback = object: ConnectivityManager.NetworkCallback() {
        override fun onUnavailable() {
            updateNetworkState()
        }

        override fun onAvailable(network: Network) {
            updateNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateNetworkState()
        }

        override fun onLost(network: Network) {
            updateNetworkState()
        }
    }

    private val discoveryListener = object: NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(p0: String?, p1: Int) {
        }

        override fun onStopDiscoveryFailed(p0: String?, p1: Int) {
        }

        override fun onDiscoveryStarted(p0: String?) {
        }

        override fun onDiscoveryStopped(p0: String?) {
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            if (null != serviceInfo && isConnected) {
                serviceFound(serviceInfo)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            if (null != serviceInfo && isConnected) {
                serviceLost(serviceInfo.serviceName)
            }
        }
    }

    private val resolveListener = object: NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, p1: Int) {
            isResolving = false

            if (null != serviceInfo && isConnected) {
                serviceLost(serviceInfo.serviceName)
            }
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
            isResolving = false

            if (null != serviceInfo && isConnected) {
                serviceResolved(serviceInfo)
            }
        }

    }

    data class ServerInfo(val name: String, val path: String, val host: String, val port: Int, val useSSL: Boolean, val protocol: String)
    data class ServerUrl( val name: String, val url: URL )
    data class ServerConnection(val serverUrl: ServerUrl, val connection: HttpURLConnection)

    private fun startDiscovery() {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                nsdManager.discoverServices(Settings.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (e: Exception) {
            }
        }
    }

    private fun stopDiscovery() {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
            }
        }
    }

    private fun resolveNext() {
        GlobalScope.launch(Dispatchers.Main) {
            try {
                if (isConnected && !isResolving && foundServers.size > 0) {
                    val key = foundServers.keys().nextElement()
                    val serviceInfo = foundServers[key]
                    foundServers.remove(key)

                    if( null != serviceInfo ) {
                        nsdManager.resolveService(serviceInfo, resolveListener)
                        Log.i(LOG_TAG, "Try to resolve -> ${serviceInfo.serviceName}")
                        isResolving = true
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun updateNetworkState() {
        GlobalScope.launch(Dispatchers.Main) {
            var newIsConnected = false
            val network = connectivityManager.activeNetwork

            if (null != network) {
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                if (null != networkCapabilities) {
                    newIsConnected =
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }
            }

            if (newIsConnected != isConnected) {
                isConnected = newIsConnected
                if (isConnected) {
                    startDiscovery()
                    Log.i(LOG_TAG, "WiFi connected -> start discovery")
                } else {
                    stopDiscovery()

                    foundServers.clear()

                    if (resolvedServers.size > 0) {
                        resolvedServers.clear()
                        notifyRootChanged()
                    }

                    Log.i(LOG_TAG, "WiFi NOT connected -> stop discovery and clear all")
                }
            }
        }
    }

    private fun serviceFound( serviceInfo: NsdServiceInfo ) {
        GlobalScope.launch(Dispatchers.Main) {
            if (isConnected) {
                val name = serviceInfo.serviceName

                if (!foundServers.containsKey(name)) {
                    foundServers[name] = serviceInfo
                }

                Log.i(LOG_TAG, "Service found -> ${name}")
                resolveNext()
            }
        }
    }

    private fun serviceLost( name: String ) {
        GlobalScope.launch(Dispatchers.Main) {
            if (isConnected) {
                if (foundServers.containsKey(name)) {
                    foundServers.remove(name)
                }

                if (resolvedServers.containsKey(name)) {
                    resolvedServers.remove(name)
                    notifyRootChanged()
                }

                Log.i(LOG_TAG, "Service lost -> $name")
                resolveNext()
            }
        }
    }

    private fun serviceResolved( serviceInfo: NsdServiceInfo ) {
        GlobalScope.launch(Dispatchers.Main) {
            if (isConnected) {
                val name = serviceInfo.serviceName

                if (foundServers.containsKey(name)) {
                    foundServers.remove(name)
                }

                Log.i(LOG_TAG, "Service resolved -> $name - ${serviceInfo.host.hostAddress} : ${serviceInfo.port}")

                resolvedServers[name] = serviceInfo
                notifyRootChanged()
                resolveNext()
            }
        }
    }

    private fun getServerInfo(documentId: String?): ServerInfo? {
        if (null == documentId) return null
        val index = documentId.indexOf(':')
        if (index < 1) return null
        val name = documentId.substring(0, index)
        val path = documentId.substring(index+1)
        val serviceInfo = resolvedServers[name] ?: return null
        val host = serviceInfo.host ?: return null
        val useSSL = serviceUseSSL(serviceInfo)
        return ServerInfo( name, path, host.hostAddress, serviceInfo.port, useSSL, if (useSSL) "https" else "http")
    }

    private fun getGetDocumentUrl(documentId: String?, prefix: String, extra: String = ""): ServerUrl? {
        val serviceInfo = getServerInfo(documentId) ?: return null
        @Suppress("DEPRECATION")
        return ServerUrl(serviceInfo.name, URL("${serviceInfo.protocol}://${serviceInfo.host}:${serviceInfo.port}/${prefix}?path=${URLEncoder.encode(serviceInfo.path)}${extra}"))
    }

    private fun getPutUrl(documentId: String?, prefix: String): Pair<ServerUrl,String>? {
        val serviceInfo = getServerInfo(documentId) ?: return null
        @Suppress("DEPRECATION")
        return Pair(
            ServerUrl(serviceInfo.name, URL("${serviceInfo.protocol}://${serviceInfo.host}:${serviceInfo.port}/${prefix}?path=${URLEncoder.encode(serviceInfo.path)}") ),
            serviceInfo.path)
    }

    private fun getUrlConnection( serverUrl: ServerUrl? ): ServerConnection? {
        if (null == serverUrl) return null
        val connection = serverUrl.url.openConnection() as HttpURLConnection?
        if (null == connection) return null

        connection.connectTimeout = Settings.URL_TIMEOUT
        connection.readTimeout = Settings.URL_TIMEOUT
        connection.setRequestProperty("Authorization", "Bearer " + deviceId)
        connection.setUseCaches(false)
        connection.setDoInput(true)

        return ServerConnection(serverUrl, connection)
    }

    private fun readUrlConnection( serverConnection: ServerConnection? ): Pair<Int, ByteArray>? {
        if (null == serverConnection) return null
        val data = serverConnection.connection.inputStream.readBytes()
        serverConnection.connection.inputStream.close()

        try {
            serverConnection.connection.disconnect()
        } catch (e: Exception) {
        }

        return Pair(serverConnection.connection.responseCode, data)
    }

    private fun readDocumentsList( cursor: MatrixCursor, projection: Array<out String>, serverConnection: ServerConnection? ) {
        if (null == serverConnection) throw FileNotFoundException()
        val data = readUrlConnection( serverConnection ) ?: throw FileNotFoundException()
        val items = JSONArray(data.second.toString(Charsets.UTF_8))
        val itemsSize = items.length()
        val host = serverConnection.serverUrl.name
        for (index in 0 until itemsSize) {
            val item = items.getJSONObject(index)
            val isDir = item.getBoolean("isdir")
            val isReadOnly = item.getBoolean("isreadonly")

            var flags = 0
            if (!isReadOnly)
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_RENAME
            if (isDir && !isReadOnly)
                flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            if (item.getBoolean("thumb"))
                flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL

            val mimeType = if (isDir) Document.MIME_TYPE_DIR else item.getString("type")

            val row = cursor.newRow()
            for (field in projection) {
                when (field) {
                    Document.COLUMN_DISPLAY_NAME -> row.add(field, item.getString("name"))
                    Document.COLUMN_DOCUMENT_ID -> row.add(field, host + ":" + item.getString("id"))
                    Document.COLUMN_MIME_TYPE -> row.add(field, mimeType)
                    Document.COLUMN_LAST_MODIFIED -> row.add(field, item.getLong("date"))
                    Document.COLUMN_SIZE -> row.add(field, item.getLong("size"))
                    Document.COLUMN_FLAGS -> row.add(field, flags)
                }
            }
        }
    }

    private fun readDocument( documentId: String ): ParcelFileDescriptor {
        val pipes = ParcelFileDescriptor.createReliablePipe()
        val readPipe = pipes[0]
        val writePipe = pipes[1]
        var canReturn = false

        GlobalScope.launch(Dispatchers.Default) {
            var success = true

            try {
                var offset = 0
                val fos = FileOutputStream(writePipe.fileDescriptor)

                while (true) {
                    val result = readUrlConnection(getUrlConnection(getGetDocumentUrl(documentId, "documentRead", "&offset=${offset}&size=${Settings.BUFFER_SIZE}"))) ?: break

                    if (HttpURLConnection.HTTP_OK != result.first) {
                        success = false
                        break
                    }

                    if (result.second.isEmpty()) break

                    canReturn = true
                    fos.write(result.second)
                    offset += result.second.size
                }
            }
            catch (e: Exception) {
                e.printStackTrace()
            }

            canReturn = true

            try {
                if (success) {
                    writePipe.close()
                } else {
                    writePipe.closeWithError(HTTP_ERROR)
                }
            } catch (e: Exception) {
            }
        }

        //wait for the first read
        while (!canReturn) {
            Thread.sleep(100)
        }

        return readPipe
    }

    private fun writeDocument( documentId: String ): ParcelFileDescriptor {
        val pipes = ParcelFileDescriptor.createReliablePipe()
        val readPipe = pipes[0]
        val writePipe = pipes[1]

        GlobalScope.launch(Dispatchers.Default) {
            var success = false

            try {
                val buffer = ByteArray(Settings.BUFFER_SIZE)
                val fis = FileInputStream(readPipe.fileDescriptor)
                createOrTruncateDocument(documentId)

                while (true) {
                    readPipe.checkError()
                    writePipe.checkError()

                    val readSize = fis.read(buffer)
                    if (readSize < 0) {
                        success = true
                        break
                    }

                    if (0 == readSize) {
                        Thread.sleep(100) //wait 100 ms to allow more data
                        continue
                    }

                    val urlAndFile = getPutUrl(documentId, "documentAppend") ?: break
                    val serverConnection = getUrlConnection( urlAndFile.first ) ?: break
                    val httpPost = HttpPost(serverConnection.connection)
                    httpPost.addFileBlock("block", "block", buffer, readSize)
                    httpPost.finish()
                    val data = readUrlConnection(serverConnection) ?: break
                    if (HttpURLConnection.HTTP_OK != data.first) break
                }
            }
            catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                if (success) {
                    readPipe.close()
                } else {
                    readPipe.closeWithError(HTTP_ERROR)
                }
            } catch (e: Exception) {
            }
        }

        return writePipe
    }

    private fun notifyRootChanged() {
        val rootsUri: Uri = buildRootsUri(AUTHORITY )
        context?.contentResolver?.notifyChange(rootsUri, null)
    }

    override fun onCreate(): Boolean {
        val context = this.context ?: return false
        deviceId = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID)
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.registerDefaultNetworkCallback(connectivityManagerNetworkCallback)
        updateNetworkState()

        timer(null, false, Settings.WIFI_POOL_STATE_TIMEOUT, Settings.WIFI_POOL_STATE_TIMEOUT) {
            updateNetworkState()
        }

        return true
    }

    override fun queryRoots(projection_: Array<out String>?): Cursor {
        val projection = projection_ ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor( projection)

        resolvedServers.forEachKey(Long.MAX_VALUE) { server ->
            val row = cursor.newRow()
            for (field in projection) {
                when(field) {
                    Root.COLUMN_ROOT_ID -> row.add( field, server )
                    Root.COLUMN_FLAGS -> row.add( field, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD )
                    Root.COLUMN_ICON -> row.add( field, R.mipmap.ic_launcher )
                    Root.COLUMN_TITLE -> row.add( field, server )
                    Root.COLUMN_DOCUMENT_ID -> row.add(field, "${server}:/")
                }
            }
        }

        return cursor
    }

    override fun queryDocument(documentId: String?, projection_: Array<out String>?): Cursor {
        val projection = projection_ ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor( projection )
        readDocumentsList( cursor, projection, getUrlConnection( getGetDocumentUrl( documentId, "queryDocument" ) ) )
        return cursor
    }

    override fun queryChildDocuments(documentId: String?, projection_: Array<out String>?, sortOrder: String?): Cursor {
        val projection = projection_ ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor( projection )
        readDocumentsList( cursor, projection, getUrlConnection( getGetDocumentUrl( documentId, "queryChildDocuments" ) ) )
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        var fullParentId = parentDocumentId
        if (null == fullParentId || null == documentId)
            return false

        if (!fullParentId.endsWith('/'))
            fullParentId += "/"

        if (!documentId.startsWith(fullParentId))
            return false

        return documentId.indexOf( '/', fullParentId.length ) < 0
    }

    override fun openDocument(documentId: String?, openMode: String?, cancellationSignal: CancellationSignal?): ParcelFileDescriptor {
        if (null == documentId || null == openMode) throw FileNotFoundException()

        if ("r" == openMode)
            return readDocument( documentId )
        if ("w" == openMode)
            return writeDocument( documentId )

        throw FileNotFoundException()
    }

    private fun createOrTruncateDocument(parentId: String) {
        @Suppress("DEPRECATION")
        val serverConnection = getUrlConnection( getGetDocumentUrl( parentId, "documentCreate" ) ) ?: throw FileNotFoundException()
        val data = readUrlConnection( serverConnection ) ?: throw FileNotFoundException()
        if (HttpURLConnection.HTTP_OK != data.first) throw FileNotFoundException()
    }

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        if (null == parentDocumentId || null == mimeType || null == displayName) throw FileNotFoundException()

        val isdir = if (Document.MIME_TYPE_DIR.equals(mimeType)) 1 else 0
        @Suppress("DEPRECATION")
        val serverConnection = getUrlConnection( getGetDocumentUrl( parentDocumentId, "documentCreate", "&name=${URLEncoder.encode(displayName)}&isdir=${isdir}" ) ) ?: throw FileNotFoundException()
        val data = readUrlConnection( serverConnection ) ?: throw FileNotFoundException()
        if (HttpURLConnection.HTTP_OK != data.first) throw FileNotFoundException()
        val item = JSONObject(data.second.toString(Charsets.UTF_8))
        val id = item.getString("id")
        return serverConnection.serverUrl.name + ":" + id
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        if (null == documentId || null == displayName) throw FileNotFoundException()

        @Suppress("DEPRECATION")
        val serverConnection = getUrlConnection( getGetDocumentUrl( documentId, "documentRename", "&newname=${URLEncoder.encode(displayName)}" ) ) ?: throw FileNotFoundException()
        val data = readUrlConnection( serverConnection ) ?: throw FileNotFoundException()
        if (HttpURLConnection.HTTP_OK != data.first) throw FileNotFoundException()
        val item = JSONObject(data.second.toString(Charsets.UTF_8))
        val id = item.getString("id")
        return serverConnection.serverUrl.name + ":" + id
    }

    override fun openDocumentThumbnail(documentId: String?, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        if (null == documentId) throw FileNotFoundException()

        val pipes = ParcelFileDescriptor.createPipe()
        val readPipe = pipes[0]
        val writePipe = pipes[1]
        var size = -1

        try {
            val result = readUrlConnection(getUrlConnection( getGetDocumentUrl(documentId, "documentReadThumb")))

            if (null != result && HttpURLConnection.HTTP_OK == result.first) {
                val fos = FileOutputStream(writePipe.fileDescriptor)
                fos.write(result.second)
                size = result.second.size
            }
        } catch (e: Exception) {}

        try {
            writePipe.close()
        } catch (e: Exception) {
        }

        if (size > 0)
            return AssetFileDescriptor(readPipe, 0, size.toLong() )

        try {
            readPipe.close()
        } catch (e: Exception) {
        }

        throw FileNotFoundException()
    }
}