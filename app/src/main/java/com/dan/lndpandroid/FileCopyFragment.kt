package com.dan.lndpandroid

import android.app.ProgressDialog
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

class FileCopyFragment(private val activity: MainActivity) : Fragment() {

    companion object {
        const val INTENT_SELECT_DESTINATION = 1
        const val INTENT_SELECT_SOURCE_FOLDER = 2
        const val INTENT_SELECT_SOURCE_FILES = 3

        const val MENU_COPY = 1
        const val MENU_COPY_SMALL = 2
        const val MENU_UPDATE = 3

        const val MENU_ITEM_UNSELECT_ALL = 10
        const val MENU_ITEM_UNSELECT_ALL_BEFORE = 11
        const val MENU_ITEM_UNSELECT_ALL_AFTER = 12
        const val MENU_ITEM_SELECT_ALL_FILES = 20
        const val MENU_ITEM_SELECT_ALL_IMAGES = 21
        const val MENU_ITEM_SELECT_ALL_RAW = 22

        const val BITMAP_SMALL_SIZE = 1920
        const val BITMAP_QUALITY = 75

        const val MAX_FAIL_COUNTER = 3

        private val RAW_EXTENSIONS = arrayOf(".RW2", ".DNG")

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
    }

    private val mBinding: FileCopyFragmentBinding by lazy { FileCopyFragmentBinding.inflate(layoutInflater) }
    private val mListAdapter: FilesViewAdapter by lazy { FilesViewAdapter(activity, mBinding.listView) }
    private var mDestFolder: UriFile? = null
    private var mUpdateId = 0
    private var mSelectedSize = 0

    init {
        BusyDialog.create(activity)
    }

    private fun runAsync(job: ()->Unit) {
        @Suppress("DEPRECATION")
        val dialog = ProgressDialog.show( context, "Please wait !", "", true )

        GlobalScope.launch(Dispatchers.IO) {
            try {
                job()
            } catch (e: Exception) {
            }

            activity.runOnUiThread {
                dialog.dismiss()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (AppCompatActivity.RESULT_OK != resultCode) return

        when(requestCode) {
            INTENT_SELECT_DESTINATION -> if (null != intent && null != intent.data) {
                runAsync {
                    val uriFile = UriFile.fromTreeUri(requireContext(), intent.data as Uri)

                    activity.runOnUiThread {
                        mDestFolder = uriFile

                        if (null == uriFile) {
                            mBinding.btnDestination.setTypeface(null, Typeface.ITALIC)
                            mBinding.btnDestination.text = "Select destination"
                        } else {
                            val lastPath = (uriFile.uri.lastPathSegment ?: "").replace(':', '\n')
                            mBinding.btnDestination.setTypeface(null, Typeface.NORMAL)
                            mBinding.btnDestination.text = lastPath
                        }

                        updateCopyButton()
                    }
                }
            }

            INTENT_SELECT_SOURCE_FOLDER -> if (null != intent && null != intent.data) {
                runAsync {
                    val uriFile = UriFile.fromTreeUri(requireContext(), intent.data as Uri)
                    if (null != uriFile) {
                        val uriFileList = uriFile.listFiles()

                        activity.runOnUiThread {
                            updateSourceItems(uriFileList)
                        }
                    }
                }
            }

            INTENT_SELECT_SOURCE_FILES -> if (null != intent) {
                runAsync {
                    val uriFileList = mutableListOf<UriFile>()
                    val clipData = intent.clipData
                    if (null != clipData) {
                        val count = clipData.itemCount
                        for (i in 0 until count) {
                            val uriFile =
                                UriFile.fromSingleUri(requireContext(), clipData.getItemAt(i).uri)
                            if (null != uriFile) uriFileList.add(uriFile)
                        }
                    } else {
                        val data = intent.data
                        if (null != data) {
                            val uriFile = UriFile.fromSingleUri(requireContext(), data)
                            if (null != uriFile) uriFileList.add(uriFile)
                        }
                    }

                    activity.runOnUiThread {
                        updateSourceItems(uriFileList)
                    }
                }
            }
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

    private fun selectDestination() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

        startActivityForResult(intent, INTENT_SELECT_DESTINATION)
    }

    private fun selectSourceFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        startActivityForResult(intent, INTENT_SELECT_SOURCE_FOLDER)
    }

    private fun selectSourceFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .putExtra( Intent.EXTRA_ALLOW_MULTIPLE, true )
            .addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setType("*/*")

        startActivityForResult(intent, INTENT_SELECT_SOURCE_FILES)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)

        if (null == defaultFileBitmap) defaultFileBitmap = AppCompatResources.getDrawable(requireContext(), R.mipmap.ic_file)?.toBitmap()
        if (null == defaultFolderBitmap) defaultFolderBitmap = AppCompatResources.getDrawable(requireContext(), R.mipmap.ic_folder)?.toBitmap()

        mBinding.listView.adapter = mListAdapter
        mBinding.listView.layoutManager = LinearLayoutManager(context)

        mBinding.btnDestination.setOnClickListener { selectDestination() }
        mBinding.btnSourceFolder.setOnClickListener { selectSourceFolder() }
        mBinding.btnSourceFiles.setOnClickListener { selectSourceFiles() }
        mBinding.btnCopy.setOnClickListener { copy(MENU_COPY) }
        mBinding.btnCopy.setOnCreateContextMenuListener { contextMenu, _, _ -> onCopyToContextMenu(contextMenu) }

        updateCopyButton()

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
        return mBinding.root
    }

    private fun updateSourceItems( uriFileList: List<UriFile> ) {
        mUpdateId++
        val updateId = mUpdateId
        mSelectedSize = 0
        mListAdapter.items.clear()

        mBinding.swipeRefresh.isRefreshing = true

        val sortedUriFileList = uriFileList.toMutableList()
        sortedUriFileList.sortByDescending {
            if (it.isDirectory)
                "Z " + it.name
            else
                "A " + it.name
        }

        for(uriFile in sortedUriFileList) {
            mListAdapter.items.add(FileItem(uriFile))
        }

        mListAdapter.notifyDataSetChanged()
        updateCopyButton()

        if (sortedUriFileList.isNotEmpty()) mBinding.listView.scrollToPosition(0)

        if (sortedUriFileList.isEmpty()) {
            mBinding.swipeRefresh.isRefreshing = false
        } else {
            val listCopy = mutableListOf<FileItem>()
            listCopy.addAll(mListAdapter.items)

            //load thumbnails
            GlobalScope.launch(Dispatchers.IO) {
                for (index in listCopy.indices) {
                    if (updateId != mUpdateId) break

                    val file = listCopy[index]
                    file.thumbnail = file.file.getThumbnail()
                    if (null != file.thumbnail) {
                        activity.runOnUiThread {
                            if (updateId == mUpdateId) mListAdapter.notifyItemChanged(index)
                        }
                    }
                }

                activity.runOnUiThread {
                    if (updateId == mUpdateId) mBinding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun setItemState( index: Int, selected: Boolean ) {
        if( selected != mListAdapter.items[index].isSelected ) {
            mListAdapter.items[index].isSelected = selected
            mListAdapter.notifyItemChanged(index)
            mSelectedSize += if (selected) 1 else -1
        }
    }

    private fun unselectAll() {
        for (index in mListAdapter.items.indices) {
            setItemState(index, false)
        }
        updateCopyButton()
    }

    private fun updateCopyButton() {
        mBinding.btnCopy.isEnabled = null != mDestFolder && mSelectedSize > 0
    }

    private fun bitmapResizedInputStream( srcInputStream: InputStream): Pair<Long, InputStream>? {
        try {
            var bitmap = BitmapFactory.decodeStream(srcInputStream)
            srcInputStream.close()
            if (null == bitmap) return null
            if (bitmap.width < BITMAP_SMALL_SIZE && bitmap.height < BITMAP_SMALL_SIZE) return null

            val newWidth: Int
            val newHeight: Int

            if (bitmap.width < bitmap.height) {
                newHeight = BITMAP_SMALL_SIZE
                newWidth = BITMAP_SMALL_SIZE * bitmap.width / bitmap.height
            } else {
                newWidth = BITMAP_SMALL_SIZE
                newHeight = BITMAP_SMALL_SIZE * bitmap.height / bitmap.width
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
        val smallBitmap = bitmapResizedInputStream( inputStream )
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

        name = "$basename.small$ext"
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
                val startTime = System.currentTimeMillis()
                val newDocumentUri = existingUri?.uri ?: destFolder.createFile(sourceUri.mimeType, name)
                if (null != newDocumentUri) {
                    outputStream = activity.contentResolver.openOutputStream(newDocumentUri, "w")
                    if (null != outputStream) {
                        var remainingSize = sourceSize
                        var failCounter = 0
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
                                if (debit > 0) BusyDialog.updateProgressInfo("$debit kb/s")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            inputStream?.close()
        } catch (e: Exception) {
        }

        try {
            outputStream?.close()
        } catch (e: Exception) {
        }

        if (sourceSize < 0 || sourceSize != destSize) {
            activity.runOnUiThread {
                Toast.makeText(activity.applicationContext, "Failed: ${sourceUri.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    data class CopyFolderInfo( val txtPrefix: String, val srcItems: List<UriFile>, val destFolder: UriFile )

    private fun copyItemsAsync( copyInfoRoot: CopyFolderInfo, copyMode: Int, buffer: ByteArray ) {
        val copyInfoMQ = mutableListOf(copyInfoRoot)
        var total = 0
        var counter = 0

        while (copyInfoMQ.size > 0) {
            val copyInfo = copyInfoMQ.removeAt(0)
            val sourceItems = copyInfo.srcItems
            val existingItems = copyInfo.destFolder.listFiles()

            total += sourceItems.size

            for (sourceItem in sourceItems) {
                counter++
                BusyDialog.updateProgressTotal("$counter / $total")
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
                            sourceItem.listFiles(),
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
        val selectedItems = mutableListOf<UriFile>()

        for (item in allItems) {
            if (item.isSelected)
                selectedItems.add(item.file)
        }

        copyItemsAsync(CopyFolderInfo("", selectedItems.toList(), destFolder), copyMode, buffer)
    }

    private fun copy(copyMode: Int) {
        val destFolder = mDestFolder ?: return

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
