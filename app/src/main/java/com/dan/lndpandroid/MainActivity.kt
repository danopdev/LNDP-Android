package com.dan.lndpandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import com.dan.lndpandroid.databinding.ActivityMainBinding
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {

    companion object {
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )

        const val REQUEST_PERMISSIONS = 1

        const val TAB_FILES = 0
        const val TAB_SERVER = 1

        val TAB_TITLES = arrayOf( "File Copy", "Server" )
    }

    private val mBinding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mServerFragment: ServerFragment by lazy { ServerFragment(this) }
    private val mFileCopyFragment: FileCopyFragment by lazy { FileCopyFragment(this) }

    val settings: Settings by lazy { Settings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!askPermissions()) onPermissionsAllowed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(grantResults)
        }
    }

    private fun onPermissionsAllowed() {
        val pagerAdapter = object : FragmentPagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            override fun getItem(position: Int): Fragment =
                when (position) {
                    TAB_FILES -> mFileCopyFragment
                    else -> mServerFragment
                }

            override fun getPageTitle(position: Int): CharSequence? = TAB_TITLES[position]

            override fun getCount(): Int = TAB_TITLES.size
        }

        mBinding.viewPager.adapter = pagerAdapter
        mBinding.tabs.setupWithViewPager( mBinding.viewPager )

        setContentView(mBinding.root)

        if (null != intent && null != intent.action) {
            val initialSourceUriList = mutableListOf<Uri>()

            when(intent.action) {
                Intent.ACTION_SEND -> {
                    intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM)?.let {
                        initialSourceUriList.add(it as Uri)
                    }
                }

                Intent.ACTION_VIEW -> {
                    intent.data?.let {
                        initialSourceUriList.add(it)
                    }
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
                        ?.let { list ->
                            list.forEach { initialSourceUriList.add(it as Uri) }
                        }
                }
            }

            if (initialSourceUriList.size > 0) {
                mFileCopyFragment.setInitialSourceUriList(initialSourceUriList.toList())
            }
        }
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
}