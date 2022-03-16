package com.dan.lndpandroid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.dan.lndpandroid.databinding.BusyDialogBinding

class BusyDialog(private val title: String, private var details: String): DialogFragment() {

    private var binding: BusyDialogBinding? = null
    private var indeterminate: Boolean = true
    private var progress: Int = 0

    companion object {
        private const val FRAGMENT_TAG = "busy"
        private var currentDialog: BusyDialog? = null
        private lateinit var activity: MainActivity
        private var title: String = ""

        fun create(activity_: MainActivity) {
            activity = activity_
        }

        fun show(supportFragmentManager: FragmentManager, title_: String, details: String) {
            if (null == currentDialog) {
                title = title_
                val dialog = BusyDialog(title, details)
                dialog.isCancelable = false
                dialog.show(supportFragmentManager, FRAGMENT_TAG)
                currentDialog = dialog
            }
        }

        fun dismiss() {
            activity.runOnUiThread {
                currentDialog?.dismiss()
                currentDialog = null
            }
        }

        fun updateDetails(details: String) {
            activity.runOnUiThread {
                currentDialog?.let { dialog ->
                    dialog.details = details
                    dialog.binding?.txtDetails?.text = details
                }
            }
        }

        fun updateCounter(counter: Int) {
            activity.runOnUiThread {
                currentDialog?.let { dialog ->
                    dialog.binding?.txtCounter?.text = if (counter > 0) counter.toString() else ""
                }
            }
        }

        fun updateProgress(progress: Long, size: Long) {
            activity.runOnUiThread {
                currentDialog?.let { dialog ->
                    dialog.binding?.progressBar?.isIndeterminate = false
                    dialog.progress = if (size > 0) (progress * 100 / size).toInt() else 0
                    dialog.binding?.progressBar?.setProgress(dialog.progress)
                }
            }
        }

        fun updateProgressInfo(info: String) {
            activity.runOnUiThread {
                currentDialog?.let { dialog ->
                    dialog.binding?.textProgressInfo?.text = info
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = BusyDialogBinding.inflate( inflater )

        binding.txtTitle.text = title
        binding.txtDetails.text = details
        binding.progressBar.isIndeterminate = indeterminate
        binding.progressBar.progress = progress

        this.binding = binding
        return binding.root
    }
}
