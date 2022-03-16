package com.dan.lndpandroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.dan.lndpandroid.databinding.FileCopyFragmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.*
import java.lang.Math.min
import java.util.*
import kotlin.collections.ArrayList

class FileCopyFragment(val activity: MainActivity) : Fragment() {

    companion object {
        const val INTENT_SELECT_FOLDER = 2

        const val MENU_COPY = 1
        const val MENU_COPY_SMALL = 2
        const val MENU_UPDATE = 3

        const val MENU_ITEM_UNSELECT_ALL = 10
        const val MENU_ITEM_UNSELECT_ALL_BEFORE = 11
        const val MENU_ITEM_UNSELECT_ALL_AFTER = 12
        const val MENU_ITEM_SELECT_ALL_FILES = 20
        const val MENU_ITEM_SELECT_ALL_IMAGES = 21
        const val MENU_ITEM_SELECT_ALL_RAW = 22

        const val BITAMP_SMALL_SIZE = 1920
        const val BITMAP_QUALITY = 75

        const val MAX_FAIL_COUNTER = 3

        val RAW_EXTENSIONS = arrayOf(".RW2", ".DNG")

        var defaultFileBitmap: Bitmap? = null
        var defaultFolderBitmap: Bitmap? = null

        fun isRaw(name: String): Boolean {
            val nameUpper = name.toUpperCase(Locale.getDefault())
            for (ext in RAW_EXTENSIONS) {
                if (nameUpper.endsWith(ext))
                    return true
            }
            return false
        }

        fun getSourceAndPath(file: UriFile): Pair<String,String> {
            val authority = file.authority
            val authorityFields = authority.split('.')
            val authorityShort =
                if (authorityFields.size <= 1) {
                    authority
                } else if ("documents".equals(authorityFields[authorityFields.size-1])) {
                    authorityFields[authorityFields.size-2]
                } else {
                    authorityFields[authorityFields.size-2] + "." + authorityFields[authorityFields.size-1]
                }

            var authorityItem = ""
            var path: String

            val lastPathSegment: String? = file.uri.lastPathSegment
            if (null == lastPathSegment) {
                path = file.name
            } else {
                val pathFields = lastPathSegment.split(':')
                if (pathFields.size <= 1) {
                    path = lastPathSegment
                } else {
                    authorityItem = pathFields[0]
                    path = pathFields[pathFields.size-1]
                    if (!path.startsWith('/')) path = "/" + path
                }
            }

            var source = authorityShort
            if (authorityItem.isNotEmpty()) source += ": " + authorityItem

            return Pair(source, path)
        }
    }

    private val mBinding: FileCopyFragmentBinding by lazy { FileCopyFragmentBinding.inflate(layoutInflater) }
    private val mListAdapter: FilesViewAdapter by lazy { FilesViewAdapter(activity, mBinding.listView) }
    private var mLeftFolder: UriFile? = null
    private var mRightFolder: UriFile? = null
    private var mUpdateId = 0
    private var mSelectedSize = 0
    private var mOnSelectFolder: ((Uri)->Unit)? = null

    init {
        BusyDialog.create(activity)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        if (INTENT_SELECT_FOLDER == requestCode && AppCompatActivity.RESULT_OK == resultCode && null != intent && null != intent.data) {
            val uri = intent.data as Uri
            mOnSelectFolder?.invoke(uri)
            mOnSelectFolder = null
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        when(item.order) {
            MENU_COPY, MENU_COPY_SMALL, MENU_UPDATE -> copy(item.order)

            MENU_ITEM_UNSELECT_ALL -> unselectAll()

            MENU_ITEM_UNSELECT_ALL_BEFORE -> {
                for (index in 0..item.itemId) {
                    setItemState(index, false)
                }
                updateCopyButton()
            }

            MENU_ITEM_UNSELECT_ALL_AFTER -> {
                for (index in item.itemId until mListAdapter.itemCount) {
                    setItemState(index, false)
                }
                updateCopyButton()
            }

            MENU_ITEM_SELECT_ALL_FILES -> {
                for (index in mListAdapter.items.indices) {
                    setItemState(index, true)
                }
                updateCopyButton()
            }

            MENU_ITEM_SELECT_ALL_IMAGES -> {
                for (index in mListAdapter.items.indices) {
                    val fileItem = mListAdapter.items[index]
                    if (fileItem.file.mimeType.startsWith("image/") && !isRaw(fileItem.file.name)) {
                        setItemState(index, true)
                    }
                }
                updateCopyButton()
            }

            MENU_ITEM_SELECT_ALL_RAW -> {
                for (index in mListAdapter.items.indices) {
                    val fileItem = mListAdapter.items[index]
                    if (isRaw(fileItem.file.name)) {
                        setItemState(index, true)
                    }
                }
                updateCopyButton()
            }
        }

        return true
    }

    private fun onCopyToContextMenu(contextMenu: ContextMenu?) {
        if (null == contextMenu) return
        contextMenu.add( 0, 0, MENU_COPY, "Copy To" )
        contextMenu.add( 0, 0, MENU_COPY_SMALL, "Copy Small To" )
        contextMenu.add( 0, 0, MENU_UPDATE, "Update To" )
    }

    fun selectFolder(callback: (Uri) -> Unit) {
        mOnSelectFolder = callback

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
        intent.addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

        startActivityForResult(intent, INTENT_SELECT_FOLDER)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)

        if (null == defaultFileBitmap) defaultFileBitmap = AppCompatResources.getDrawable(requireContext(), R.mipmap.ic_file)?.toBitmap()
        if (null == defaultFolderBitmap) defaultFolderBitmap = AppCompatResources.getDrawable(requireContext(), R.mipmap.ic_folder)?.toBitmap()

        mBinding.listView.adapter = mListAdapter
        mBinding.listView.layoutManager = LinearLayoutManager(context)

        mBinding.layoutLeft.setOnClickListener { selectFolder { uri -> setFolderUri(uri, true) } }
        mBinding.layoutRight.setOnClickListener { selectFolder { uri -> setFolderUri(uri, false) } }

        mBinding.btnCopy.setOnClickListener { copy(MENU_COPY) }
        mBinding.btnCopy.setOnCreateContextMenuListener { contextMenu, _, _ -> onCopyToContextMenu(contextMenu) }
        updateCopyButton()

        mBinding.btnSwitch.setOnClickListener {
            val oldLeftFolder = this.mLeftFolder
            val oldRightFolder = this.mRightFolder
            setFolder(oldRightFolder, true)
            setFolder(oldLeftFolder, false)
        }

        mListAdapter.setOnSelectClickListener { index ->
            if (!mBinding.swipeRefresh.isRefreshing) {
                val item = mListAdapter.items[index]
                item.isSelected = !item.isSelected
                mListAdapter.notifyItemChanged(index)

                if (item.isSelected) {
                    mSelectedSize++
                } else {
                    mSelectedSize--
                }

                updateCopyButton()
            }
        }

        mListAdapter.setOnCreateMenuListener { index, contextMenu, _, _ ->
            if (null != contextMenu) {
                contextMenu.add(0, index, MENU_ITEM_UNSELECT_ALL, "Unselect All")
                contextMenu.add(0, index, MENU_ITEM_UNSELECT_ALL_BEFORE, "Unselect All Before")
                contextMenu.add(0, index, MENU_ITEM_UNSELECT_ALL_AFTER, "Unselect All After")
                contextMenu.add(0, index, MENU_ITEM_SELECT_ALL_FILES, "Select All")
                contextMenu.add(0, index, MENU_ITEM_SELECT_ALL_IMAGES, "Select All JPEG")
                contextMenu.add(0, index, MENU_ITEM_SELECT_ALL_RAW, "Select All RAW")
            }
        }

        mBinding.swipeRefresh.setOnRefreshListener { listFolder() }

        var uri: Uri? = null
        try {
            uri = Uri.parse(activity.settings.leftSourceUri)
        } catch (e: Exception) {
        }
        setFolderUri(uri, true)

        uri = null
        try {
            uri = Uri.parse(activity.settings.rightSourceUri)
        } catch (e: Exception) {
        }
        setFolderUri(uri, false)

        return mBinding.root
    }

    private fun setFolder(folder: UriFile?, isLeft: Boolean) {
        if (isLeft) {
            mLeftFolder = folder
            if (null != folder) {
                activity.settings.leftSourceUri = folder.uri.toString()
                activity.settings.saveProperties()
            }
        }
        else {
            mRightFolder = folder
            if (null != folder) {
                activity.settings.rightSourceUri = folder.uri.toString()
                activity.settings.saveProperties()
            }
        }

        val txtSource = if (isLeft) mBinding.txtLeftSource else mBinding.txtRightSource
        val txtPath = if (isLeft) mBinding.txtLeftPath else mBinding.txtRightPath

        if (null == folder) {
            txtSource.text = ""
            txtPath.text = "<select folder>"
            txtPath.alpha = 0.5f
            txtPath.setTypeface(null, Typeface.ITALIC)
        } else {
            val sourceAndPath = getSourceAndPath(folder)
            txtSource.text = sourceAndPath.first
            txtPath.text = sourceAndPath.second
            txtPath.alpha = 1f
            txtPath.setTypeface(null, Typeface.NORMAL)
            if (isLeft) listFolder()
        }
    }

    private fun setFolderUri(uri: Uri?, isLeft: Boolean) {
        if (null == uri) setFolder(null, isLeft)
        else {
            GlobalScope.launch(Dispatchers.IO) {
                var folder: UriFile? = null
                try {
                    folder = UriFile.fromTreeUri(requireContext(), uri)
                } catch (e: Exception) {
                }
                activity.runOnUiThread {
                    setFolder(folder, isLeft)
                }
            }
        }
    }

    private fun listFolder() {
        val leftFolder = mLeftFolder
        if( null == leftFolder ) {
            mBinding.swipeRefresh.isRefreshing = false
            return
        }

        mSelectedSize = 0
        updateCopyButton()
        mBinding.swipeRefresh.isRefreshing = true
        mUpdateId++
        mListAdapter.items.clear()
        mListAdapter.notifyDataSetChanged()
        mBinding.listView.scrollToPosition(0)
        GlobalScope.launch(Dispatchers.IO) { listFolderAsync(leftFolder, mUpdateId) }
    }

    private fun listFolderAsync(folder: UriFile, updateId: Int) {
        val files = ArrayList<FileItem>()

        val items = folder.listFiles()
        for (item in items) {
            val name = item.name
            if (name.startsWith('.')) continue
            files.add(FileItem(item))
        }

        files.sortByDescending {
            if (it.file.isDirectory)
                "Z " + it.file.name
            else
                "A " + it.file.date + " " + it.file.name
        }

        activity.runOnUiThread {
            if (updateId == mUpdateId) {
                mListAdapter.items.addAll(files)
                mListAdapter.notifyDataSetChanged()
                mBinding.swipeRefresh.isRefreshing = false
            }
        }

        //load thumbnails
        for (index in files.indices) {
            if (updateId != mUpdateId)
                break

            val file = files[index]
            file.thumbnail = file.file.getThumbnail()
            if (null != file.thumbnail) {
                activity.runOnUiThread {
                    if (updateId == mUpdateId) {
                        mListAdapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    private fun setItemState( index: Int, selected: Boolean ) {
        if( selected != mListAdapter.items[index].isSelected ) {
            mListAdapter.items[index].isSelected = selected
            mListAdapter.notifyItemChanged(index)
            if (selected)
                mSelectedSize++
            else
                mSelectedSize--
        }
    }

    fun unselectAll() {
        for (index in mListAdapter.items.indices)
            setItemState(index, false)
        updateCopyButton()
    }

    private fun updateCopyButton() {
        if (activity.settings.leftSourceUri == activity.settings.rightSourceUri) {
            mBinding.btnCopy.isEnabled = false
        } else {
            mBinding.btnCopy.isEnabled = mSelectedSize > 0 && null != mRightFolder
        }
    }

    private fun bitmapResizesInputStream( srcInputStream: InputStream): Pair<Long, InputStream>? {
        try {
            var bitmap = BitmapFactory.decodeStream(srcInputStream)
            srcInputStream.close()
            if (null == bitmap) return null

            if (bitmap.width < BITAMP_SMALL_SIZE && bitmap.height < BITAMP_SMALL_SIZE) return null

            val newWidth: Int
            val newHeight: Int

            if (bitmap.width < bitmap.height) {
                newHeight = BITAMP_SMALL_SIZE
                newWidth = BITAMP_SMALL_SIZE * bitmap.width / bitmap.height
            } else {
                newWidth = BITAMP_SMALL_SIZE
                newHeight = BITAMP_SMALL_SIZE * bitmap.height / bitmap.width
            }

            bitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            val memOutputStream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, BITMAP_QUALITY, memOutputStream) ) return null
            return Pair(memOutputStream.size().toLong(), ByteArrayInputStream( memOutputStream.toByteArray() ))
        } catch (e: java.lang.Exception) {
        }

        return null
    }

    private fun getInputStream( sourceUri: UriFile, small: Boolean ): Triple<String, Long, InputStream> {
        var inputStream = activity.contentResolver.openInputStream(sourceUri.uri) ?: throw FileNotFoundException(sourceUri.name)
        if (!(small && sourceUri.mimeType.startsWith("image/"))) {
            Log.i("COPY_FILE", "Full size")
            return Triple(sourceUri.name, sourceUri.length, inputStream)
        }

        var name = sourceUri.name
        val smallBitmap = bitmapResizesInputStream( inputStream )
        if (null == smallBitmap) {
            Log.i("COPY_FILE", "Full size (2)")
            inputStream = activity.contentResolver.openInputStream(sourceUri.uri) ?: throw FileNotFoundException(sourceUri.name)
            return Triple(sourceUri.name, sourceUri.length, inputStream )
        }

        inputStream = smallBitmap.second
        val dotIndex = name.lastIndexOf('.')
        val basename: String
        var ext: String

        if (dotIndex >= 0) {
            basename = name.substring(0, dotIndex)
            ext = name.substring(dotIndex)
            val extUpper = ext.toUpperCase(Locale.getDefault())
            if (!extUpper.equals(".JPG") && extUpper.equals(".JPEG"))
                ext = ".jpg"
        } else {
            basename = name
            ext = ".jpg"
        }

        name = basename + ".small" + ext
        Log.i("COPY_FILE", "Small size")
        return Triple(name, smallBitmap.first, inputStream )
    }

    private fun copyFileAsync( txtPrefix: String, sourceUri: UriFile, destFolder: UriFile, copyMode: Int, buffer: ByteArray, existingUri: UriFile? ) {
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        val small = MENU_COPY_SMALL == copyMode

        if (null != existingUri && MENU_UPDATE == copyMode) {
            if (existingUri.timestamp > sourceUri.timestamp && existingUri.length == sourceUri.length) {
                Log.i("COPY_FILE", "Update: '${txtPrefix}${sourceUri.name}' already exists and is newer => no copy")
                return
            }
        }

        var sourceSize = -1L
        var destSize = 0L

        Log.i("COPY_FILE", "Copy: '${txtPrefix}${sourceUri.name}'")
        try {
            val sourceInfo = getInputStream(sourceUri, small)
            val name = sourceInfo.first
            sourceSize = sourceInfo.second
            inputStream = sourceInfo.third
            BusyDialog.updateDetails(txtPrefix + name)
            BusyDialog.updateProgress(0, sourceSize)
            BusyDialog.updateProgressInfo("")

            if (sourceSize > 0) {
                val newDocumentUri = existingUri?.uri ?: destFolder.createFile(sourceUri.mimeType, name)
                if (null != newDocumentUri) {
                    outputStream = activity.contentResolver.openOutputStream(newDocumentUri, "w")
                    if (null != outputStream) {
                        var remainingSize = sourceSize
                        var failCounter = 0
                        val startTime = System.currentTimeMillis()
                        while (remainingSize > 0) {
                            val currentReadSize = min(buffer.size.toLong(), remainingSize).toInt()
                            val readSize = inputStream.read(buffer, 0, currentReadSize)
                            if (readSize <= 0) {
                                failCounter++
                                if (failCounter >= MAX_FAIL_COUNTER) break
                                continue
                            }

                            failCounter = 0
                            outputStream.write(buffer, 0, readSize)
                            destSize += readSize
                            remainingSize -= readSize
                            BusyDialog.updateProgress(destSize, sourceSize)

                            val now = System.currentTimeMillis()
                            val delta = now - startTime
                            if (delta >= 1000L) {
                                val debit = ((destSize / 1024.0) / (delta / 1000.0)).toLong()
                                if (debit > 0) BusyDialog.updateProgressInfo("{speed} kb/s")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (null != inputStream)
                inputStream.close()
        } catch (e: Exception) {
        }

        try {
            if (null != outputStream)
                outputStream.close()
        } catch (e: Exception) {
        }

        if (sourceSize < 0 || sourceSize != destSize) {
            activity.runOnUiThread {
                Toast.makeText(activity.applicationContext, "Failed: ${sourceUri.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    data class CopyFolderInfo( val txtPrefix: String, val srcFolder: UriFile, val srcItems: ArrayList<UriFile>?, val destFolder: UriFile )

    private fun copyItemsAsync( copyInfoRoot: CopyFolderInfo, copyMode: Int, buffer: ByteArray ) {
        val copyInfoMQ = mutableListOf(copyInfoRoot)

        while (copyInfoMQ.size > 0) {
            BusyDialog.updateCounter(copyInfoMQ.size)

            val copyInfo = copyInfoMQ.removeAt(0)
            val sourceItems = copyInfo.srcItems ?: copyInfo.srcFolder.listFiles()
            val existingItems = copyInfo.destFolder.listFiles()

            for (sourceItem in sourceItems) {
                val existingItem = existingItems.find { it.name == sourceItem.name }
                if (sourceItem.isDirectory) {
                    var destSubFolder: UriFile
                    if (null != existingItem) {
                        if (!existingItem.isDirectory)
                            continue
                        destSubFolder = existingItem
                    } else {
                        destSubFolder = copyInfo.destFolder.createDirectory(sourceItem.name) ?: continue
                    }

                    Log.i("COPY_FILE", "Copy folder content: ${copyInfo.txtPrefix}${sourceItem.name}/")
                    copyInfoMQ.add(
                        CopyFolderInfo(
                            copyInfo.txtPrefix + sourceItem.name + "/",
                            sourceItem,
                            null,
                            destSubFolder)
                    )
                } else {
                    if (null != existingItem && existingItem.isDirectory) continue
                    copyFileAsync(copyInfo.txtPrefix, sourceItem, copyInfo.destFolder, copyMode, buffer, existingItem)
                }
            }
        }
    }

    private fun copyAsync(destFolder: UriFile, copyMode: Int) {
        val buffer = ByteArray(Settings.BUFFER_SIZE)
        val allItems = mListAdapter.items
        val selectedItems = ArrayList<UriFile>()

        for (item in allItems) {
            if (item.isSelected)
                selectedItems.add(item.file)
        }

        val leftFolder = mLeftFolder ?: return
        copyItemsAsync(CopyFolderInfo("", leftFolder, selectedItems, destFolder), copyMode, buffer)
    }

    private fun copy(copyMode: Int) {
        val destFolder = mRightFolder ?: return

        BusyDialog.show(requireFragmentManager(), "Copy Files", "Scanning...")

        GlobalScope.launch(Dispatchers.IO) {
            try {
                copyAsync(destFolder, copyMode)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            activity.runOnUiThread { unselectAll() }
            BusyDialog.dismiss()
        }
    }
}
