package com.dan.lndpandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import com.dan.lndpandroid.databinding.ServerFragmentBinding
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import kotlin.concurrent.timer


class ServerFragment(val activity: MainActivity) : Fragment() {
    companion object {
        const val INTENT_SELECT_FOLDER = 10

        const val NOTIFICATION_ID = 1
    }

    private val mBinding: ServerFragmentBinding by lazy { ServerFragmentBinding.inflate(layoutInflater) }
    private val mWifiManager: WifiManager by lazy { activity.getSystemService(AppCompatActivity.WIFI_SERVICE) as WifiManager }
    private val mConnectivityManager: ConnectivityManager by lazy { activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val mNsdManager: NsdManager by lazy { activity.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val mNotificationManager: NotificationManager by lazy { activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var mServer: NanoHTTPD? = null
    private var mWifiConnected = false
    private var mWifiIpAddress = ""
    private lateinit var mPublicUriFile: UriFile
    private var mNotification: NotificationCompat.Builder? = null

    init {
        val appName = activity.getString(R.string.app_name)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel( appName, appName, NotificationManager.IMPORTANCE_LOW )
            notificationChannel.description = appName
            notificationChannel.enableLights(false)
            notificationChannel.enableVibration(false)

            mNotificationManager.createNotificationChannel(notificationChannel)
        }

        mNotification = NotificationCompat.Builder(activity.applicationContext, appName).apply {
            setContentTitle(appName)
            setContentText("Server started")
            setSmallIcon(R.drawable.ic_launcher_foreground)
            setPriority(NotificationCompat.PRIORITY_LOW)
        }
    }

    private val mConnectivityManagerNetworkCallback = object: ConnectivityManager.NetworkCallback() {
        override fun onUnavailable() {
            updateWifiState()
        }

        override fun onAvailable(network: Network) {
            updateWifiState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateWifiState()
        }

        override fun onLost(network: Network) {
            updateWifiState()
        }
    }

    private val mNsdManagerRegistrationListener = object: NsdManager.RegistrationListener {
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
        }

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
        }
    }

    private fun updateWifiState() {
        activity.runOnUiThread {
            var wifiConnected = false
            var wifiIpAddress = ""

            mConnectivityManager.activeNetwork?.let { network ->
                mConnectivityManager.getNetworkCapabilities(network)?.let { networkCapabilities ->
                    wifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    if (wifiConnected) {
                        @Suppress("DEPRECATION")
                        wifiIpAddress = Formatter.formatIpAddress(mWifiManager.getConnectionInfo().ipAddress)
                    }
                }
            }

            if (!wifiConnected) {
                val enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces()
                while (enumNetworkInterfaces.hasMoreElements() && !wifiConnected) {
                    val networkInterface = enumNetworkInterfaces.nextElement()
                    if (!networkInterface.isUp ||
                        networkInterface.isVirtual ||
                        networkInterface.isLoopback ||
                        !networkInterface.getName().startsWith("wl")) {
                        continue
                    }

                    val enumIpAddr = networkInterface.getInetAddresses()
                    while (enumIpAddr.hasMoreElements() && !wifiConnected) {
                        val inetAddress = enumIpAddr.nextElement()
                        if (inetAddress.isSiteLocalAddress) {
                            wifiIpAddress = inetAddress.hostAddress
                            wifiConnected = true
                        }
                    }
                }
            }

            if (wifiConnected != mWifiConnected) {
                mWifiIpAddress = wifiIpAddress
                mWifiConnected = wifiConnected
                mBinding.txtWifi.text = if (wifiConnected) "WiFi: ON" else "WiFi: OFF"
                updateServerState()
            }
        }
    }

    private fun updateServerState() {
        if (!mWifiConnected && null != mServer) {
            activity.runOnUiThread {
                stopServer()
            }
        }

        mBinding.btnStartServer.isEnabled = mWifiConnected && null == mServer && mBinding.txtName.text.isNotEmpty() && activity.settings.publicFolderUri.isNotEmpty()
        mBinding.btnStopServer.isEnabled = null != mServer
        mBinding.txtName.isEnabled = null == mServer
        mBinding.btnSelectFolder.isEnabled = null == mServer
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (INTENT_SELECT_FOLDER == requestCode && AppCompatActivity.RESULT_OK == resultCode && null != intent) {
            val data = intent.data
            if (data is Uri) {
                activity.settings.publicFolderUri = data.toString()
                mBinding.txtFolder.text = getFolderName(activity.settings.publicFolderUri)
                updateServerState()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        mBinding.btnStartServer.setOnClickListener { startServer() }
        mBinding.btnStopServer.setOnClickListener { stopServer() }

        mBinding.btnSelectFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            intent.addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )

            startActivityForResult(intent, INTENT_SELECT_FOLDER)
        }

        mBinding.txtName.setText(activity.settings.serverName)
        mBinding.txtFolder.text = getFolderName(activity.settings.publicFolderUri)

        mConnectivityManager.registerDefaultNetworkCallback(mConnectivityManagerNetworkCallback)
        updateWifiState()
        updateServerState()

        timer(null, false, Settings.WIFI_POOL_STATE_TIMEOUT, Settings.WIFI_POOL_STATE_TIMEOUT) {
            updateWifiState()
        }

        return mBinding.root
    }

    private fun getFolderName(uriStr: String): String {
        @Suppress("DEPRECATION")
        val pathFields = URLDecoder.decode(uriStr).split(':')
        if (pathFields.size <= 1) {
            return activity.settings.publicFolderUri
        } else {
            return pathFields[pathFields.size - 1]
        }
    }

    private fun nanoHttpdGetParam(session: NanoHTTPD.IHTTPSession, name: String, defaultValue: String? = null): String {
        val list = session.parameters[name]
        if (null == list || list.isEmpty()) {
            if (null != defaultValue) return defaultValue
            throw Exception("ParamNotFound")
        }

        return list[0]
    }

    private fun lndpGetDocuemntId(uriFile: UriFile): String {
        return uriFile.documentId
    }

    private fun lndpGetUriFile(documentId: String): UriFile {
        if (documentId == "/") return mPublicUriFile
        return UriFile.fromTreeDocumentId(requireContext(), mPublicUriFile.uri, documentId) ?: throw Exception(
            "Invalid documentId"
        )
    }

    private fun lndpQueryResponse(files: List<UriFile>): String {
        val jsonArray = JSONArray()

        for( file in files ) {
            val jsonObject = JSONObject()
            jsonObject.put("id", lndpGetDocuemntId(file))
            jsonObject.put("name", file.name)
            jsonObject.put("isdir", file.isDirectory)
            jsonObject.put("isreadonly", true)
            jsonObject.put("size", file.length)
            jsonObject.put("date", file.timestamp)
            jsonObject.put("type", file.mimeType)
            jsonObject.put("thumb", file.hasThumb )

            jsonArray.put(jsonObject)
        }

        return jsonArray.toString()
    }

    private fun lndpQueryDocument(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        try {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                lndpQueryResponse(arrayListOf(lndpGetUriFile(nanoHttpdGetParam(session, "path")))) )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun lndpQueryChildDocuments(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        try {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                lndpQueryResponse(lndpGetUriFile(nanoHttpdGetParam(session, "path")).listFiles()))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun lndpDocumentRead(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        try {
            val documentId = nanoHttpdGetParam(session, "path")
            val size = nanoHttpdGetParam(session, "size").toInt()
            val offset = nanoHttpdGetParam(session, "offset", "0").toInt()

            if (offset >= 0 && size > 0) {
                val uriFile = lndpGetUriFile(documentId)
                activity.contentResolver.openInputStream(uriFile.uri)?.let{ inputStream ->
                    if (offset > 0) inputStream.skip(offset.toLong())
                    val buffer = ByteArray(size)
                    val outputSize = inputStream.read(buffer)
                    inputStream.close()

                    if (outputSize > 0) {
                        return newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            "application/octet-stream",
                            ByteArrayInputStream(buffer, 0, outputSize),
                            outputSize.toLong() )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun lndpDocumentReadThumb(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        try {
            lndpGetUriFile(nanoHttpdGetParam(session, "path")).getThumbnail()?.let{ bitmap ->
                val bos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                val buffer = bos.toByteArray()
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(buffer),
                    buffer.size.toLong() )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun startServer() {
        @Suppress("DEPRECATION")
        try {
            val server = object: NanoHTTPD(mWifiIpAddress, Settings.PORT) {
                override fun serve(session: IHTTPSession?): Response {
                    var response: Response? = null

                    if (null != session) {
                        when (session.uri) {
                            "/queryDocument" -> response = lndpQueryDocument(session)
                            "/queryChildDocuments" -> response = lndpQueryChildDocuments(session)
                            "/documentRead" -> response = lndpDocumentRead(session)
                            "/documentReadThumb" -> response = lndpDocumentReadThumb(session)
                        }
                    }

                    return response ?: newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/pain", "")
                }
            }

            UriFile.fromTreeUri(requireContext(), Uri.parse(activity.settings.publicFolderUri))?.let{ uriFile ->
                mPublicUriFile = uriFile
            }

            server.start()
            mServer = server
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        mBinding.txtUrl.text = "${mWifiIpAddress}:${Settings.PORT}"

        val serviceInfo = NsdServiceInfo()
        serviceInfo.serviceName = mBinding.txtName.text.toString()
        serviceInfo.serviceType = Settings.SERVICE_TYPE
        serviceInfo.port = Settings.PORT
        serviceInfo.host = InetAddress.getByName(mWifiIpAddress)

        try {
            mNsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                mNsdManagerRegistrationListener
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        activity.settings.serverName = mBinding.txtName.text.toString()
        activity.settings.saveProperties()

        mNotification?.let { notification ->
            mNotificationManager.notify(NOTIFICATION_ID, notification.build())
        }

        updateServerState()
    }

    private fun stopServer() {
        try {
            mNotificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            mNsdManager.unregisterService(mNsdManagerRegistrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mBinding.txtUrl.text = ""
        try {
            //mServer?.stop(250L, 250L, TimeUnit.MILLISECONDS)
            mServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mServer = null
        updateServerState()
    }
}
