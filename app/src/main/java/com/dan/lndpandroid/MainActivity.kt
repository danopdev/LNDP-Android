package com.dan.lndpandroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
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

        const val PORT = 1234
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mWifiManager: WifiManager by lazy { getSystemService(WIFI_SERVICE) as WifiManager }
    private val mConnectivityManager: ConnectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
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

        mBinding.btnStartServer.isEnabled = null == mServer && mBinding.txtName.text.isNotEmpty() && mSettings.serverUri.isNotEmpty()
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
            /*
            val data = intent.data
            if (data is Uri) {
                mSaveFolder = DocumentFile.fromTreeUri(applicationContext, data)
                settings.saveUri = data.toString()
                settings.saveProperties()

                if (mFirstCall) onValidSaveFolder()
            }
            */
        }
    }

    private fun onPermissionsAllowed() {
        setContentView(mBinding.root)

        mBinding.btnStartServer.setOnClickListener { startServer() }
        mBinding.btnStopServer.setOnClickListener { stopServer() }

        mBinding.txtName.setText(mSettings.serverName)

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

    private fun startServer() {
        @Suppress("DEPRECATION")
        val ip = Formatter.formatIpAddress( mWifiManager.getConnectionInfo().ipAddress )

        val server = embeddedServer(Netty, port = PORT, host = ip) {
            routing {
                get("/") {
                    call.respondText("Hello World!", ContentType.Text.Plain)
                }
                get("/demo") {
                    call.respondText("HELLO WORLD!")
                }
            }
        }

        server.start()

        mBinding.txtUrl.text = "http://${ip}:${PORT}/"
        mServer = server
    }

    private fun stopServer() {
        mBinding.txtUrl.text = ""
        mServer?.stop(250L, 250L, TimeUnit.MILLISECONDS)
        mServer = null
    }
}