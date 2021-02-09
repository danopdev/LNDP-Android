package com.dan.lndpandroid

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
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.dan.lndpandroid.databinding.ServerFragmentBinding
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer


class ServerFragment(val activity: MainActivity) : Fragment() {
    companion object {
        const val INTENT_SELECT_FOLDER = 1
    }

    private val mBinding: ServerFragmentBinding by lazy { ServerFragmentBinding.inflate(layoutInflater) }
    private val mWifiManager: WifiManager by lazy { activity.getSystemService(AppCompatActivity.WIFI_SERVICE) as WifiManager }
    private val mConnectivityManager: ConnectivityManager by lazy { activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val mNsdManager: NsdManager by lazy { activity.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var mServer: ApplicationEngine? = null
    private var mWifiConnected = false
    private var mWifiIpAddress = ""
    private lateinit var mPublicUriFile: UriFile

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
            val enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (enumNetworkInterfaces.hasMoreElements() && !wifiConnected) {
                val networkInterface = enumNetworkInterfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isVirtual || networkInterface.isLoopback || !networkInterface.getName().startsWith("wl")) continue
                val enumIpAddr = networkInterface.getInetAddresses()
                while (enumIpAddr.hasMoreElements() && !wifiConnected) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (inetAddress.isSiteLocalAddress) {
                        wifiIpAddress = inetAddress.hostAddress
                        wifiConnected = true
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

    private fun lndpGetDocuemntId(uriFile: UriFile): String {
        return uriFile.documentId
    }

    private fun lndpGetUriFile(documentId: String): UriFile {
        if (documentId == "/") return mPublicUriFile
        return UriFile.fromDocumentId(requireContext(), mPublicUriFile.uri, documentId) ?: throw Exception(
            "Invalid documentId"
        )
    }

    private fun lndpQueryResponse(files: ArrayList<UriFile>): String {
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
            jsonObject.put("thumb", file.supportThumbnails)

            jsonArray.put(jsonObject)
        }

        return jsonArray.toString()
    }

    private fun lndpQueryDocument(documentId: String): String? {
        try {
            return lndpQueryResponse(arrayListOf(lndpGetUriFile(documentId)))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun lndpQueryChildDocuments(documentId: String): String? {
        try {
            return lndpQueryResponse(lndpGetUriFile(documentId).listFiles())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private suspend fun lndpRespondText(call: ApplicationCall, output: String?, contentType: ContentType) {
        if (null == output) {
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        } else {
            call.respondText(output, contentType)
        }
    }

    private suspend fun lndpRespondBinary(call: ApplicationCall, output: ByteArray?, outputSize: Int, contentType: ContentType) {
        if (null == output) {
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        } else {
            call.respondOutputStream(contentType, HttpStatusCode.OK) {
                write(output, 0, outputSize)
            }
        }
    }

    private fun startServer() {
        @Suppress("DEPRECATION")
        try {
            val server = embeddedServer(Netty, port = Settings.PORT, host = mWifiIpAddress) {
                routing {
                    get("/lndp/queryDocument") {
                        var output: String? = null
                        call.request.queryParameters.get("path")?.let { documentId ->
                            output = lndpQueryDocument(documentId)
                        }
                        lndpRespondText(call, output, ContentType.Application.Json)
                    }

                    get("/lndp/queryChildDocuments") {
                        var output: String? = null
                        call.request.queryParameters.get("path")?.let { documentId ->
                            output = lndpQueryChildDocuments(documentId)
                        }
                        lndpRespondText(call, output, ContentType.Application.Json)
                    }

                    get("/lndp/documentRead") {
                        var output: ByteArray? = null
                        var outputSize = 0

                        try {
                            val documentId = call.request.queryParameters.get("path")
                            val offsetStr = call.request.queryParameters.get("offset") ?: "0"
                            val offset = offsetStr.toInt()
                            val sizeStr = call.request.queryParameters.get("size") ?: "0"
                            val size = sizeStr.toInt()

                            if (null != documentId && offset >= 0 && size > 0) {
                                val uriFile = lndpGetUriFile(documentId)
                                activity.contentResolver.openInputStream(uriFile.uri)?.let{ inputStream ->
                                    if (offset > 0) inputStream.skip(offset.toLong())
                                    val buffer = ByteArray(size)
                                    outputSize = inputStream.read(buffer)
                                    if (outputSize > 0) output = buffer
                                    inputStream.close()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        lndpRespondBinary(call, output, outputSize, ContentType.Application.OctetStream)
                    }

                    get("/lndp/documentReadThumb") {
                        var output: ByteArray? = null
                        var outputSize = 0

                        try {
                            call.request.queryParameters.get("path")?.let{ documentId ->
                                lndpGetUriFile(documentId).getThumbnail()?.let{ bitmap ->
                                    val bos = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                                    output = bos.toByteArray()
                                    outputSize = output?.size ?: 0
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        lndpRespondBinary(call, output, outputSize, ContentType.Image.JPEG)
                    }
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

        updateServerState()
    }

    private fun stopServer() {
        try {
            mNsdManager.unregisterService(mNsdManagerRegistrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mBinding.txtUrl.text = ""
        try {
            mServer?.stop(250L, 250L, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mServer = null
        updateServerState()
    }
}
