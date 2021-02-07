package com.dan.lndpandroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dan.lndpandroid.databinding.ActivityMainBinding
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.lang.Exception
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )

        const val REQUEST_PERMISSIONS = 1
        const val INTENT_SELECT_FOLDER = 2

        const val SERVICE_TYPE = "_lndp._tcp"
        const val PORT = 1234
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mWifiManager: WifiManager by lazy { getSystemService(WIFI_SERVICE) as WifiManager }
    private val mConnectivityManager: ConnectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val mNsdManager: NsdManager by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var mServer: ApplicationEngine? = null
    private var mWifiConnected = false
    private val mSettings: Settings by lazy { Settings(this) }

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
        var wifiConnected = false

        mConnectivityManager.activeNetwork?.let { network ->
            mConnectivityManager.getNetworkCapabilities(network)?.let { networkCapabilities ->
                wifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }

        if (wifiConnected != mWifiConnected) {
            mWifiConnected = wifiConnected
            updateServerState()
        }
    }

    private fun updateServerState() {
        if (!mWifiConnected && null != mServer) {
            stopServer()
        }

        mBinding.btnStartServer.isEnabled = null == mServer && mBinding.txtName.text.isNotEmpty() && mSettings.publicFolderUri.isNotEmpty()
        mBinding.btnStopServer.isEnabled = null != mServer
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!askPermissions()) onPermissionsAllowed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (INTENT_SELECT_FOLDER == requestCode && RESULT_OK == resultCode && null != intent) {
            val data = intent.data
            if (data is Uri) {
                mSettings.publicFolderUri = data.toString()
                mBinding.txtFolder.text = mSettings.publicFolderName
                updateServerState()
            }
        }
    }

    private fun onPermissionsAllowed() {
        setContentView(mBinding.root)

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

        mBinding.txtName.setText(mSettings.serverName)
        mBinding.txtFolder.text = mSettings.publicFolderName

        mConnectivityManager.registerDefaultNetworkCallback(mConnectivityManagerNetworkCallback)
        updateWifiState()
        updateServerState()
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }

    private fun handleRequestPermissions(grantResults: IntArray) {
        if (grantResults.size < PERMISSIONS.size)
            return

        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for ( result in grantResults ) {
                if (result != PackageManager.PERMISSION_GRANTED ) {
                    allowedAll = false
                    break
                }
            }
        }

        if( allowedAll ) {
            onPermissionsAllowed()
        } else {
            fatalError("Permissions are mandatory !")
        }
    }

    private fun exitApp() {
        setResult(0)
        finish()
        exitProcess(0)
    }

    private fun fatalError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(msg)
            .setIcon(android.R.drawable.stat_notify_error)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> exitApp() }
            .show()
    }

    private fun lndpGetDocuemntId(val uri: String): String {
        if (uri == mSettings.publicFolderUri) return "/"
        return mSettings.publicFolderUri.substring(mSettings.publicUriBase.length)
    }

    private fun lndpGetUri(val documentId: String): String {
        if (documentId == "/") return mSettings.publicFolderUri
        return mSettings.publicUriBase + documentId
    }

    private fun lndpQueryDocument(val path: String): String? {
        try {

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun startServer() {
        @Suppress("DEPRECATION")
        val ip = Formatter.formatIpAddress(mWifiManager.getConnectionInfo().ipAddress)

        try {
            val server = embeddedServer(Netty, port = PORT, host = ip) {
                routing {
                    get("/lndp/queryDocument") {
                        call.respondText("Hello World!", ContentType.Text.Plain)
                    }
                    get("/demo") {
                        call.respondText("HELLO WORLD!")
                    }
                }
            }

            server.start()
            mServer = server
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        mBinding.txtUrl.text = "http://${ip}:${PORT}/"

        val serviceInfo = NsdServiceInfo()
        serviceInfo.serviceName = mBinding.txtName.text.toString()
        serviceInfo.serviceType = SERVICE_TYPE
        serviceInfo.port = PORT
        serviceInfo.host = InetAddress.getByName(ip)

        try {
            mNsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, mNsdManagerRegistrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mSettings.serverName = mBinding.txtName.text.toString()
        mSettings.saveProperties()

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