package com.dan.lndpandroid

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import java.lang.Long.parseLong
import java.text.SimpleDateFormat
import java.util.*


class UriFile(
    private val context: Context,
    val uri: Uri,
    val authority: String,
    val documentId: String,
    val name: String,
    val mimeType: String,
    val timestamp: Long,
    val length: Long,
    val hasThumb: Boolean
) {

    companion object {
        private val DOCUMENT_ID_COLUMNS = listOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            "_id"
        )

        private val DATE_COLUMNS = listOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            "date_modified"
        )

        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd hh:mm:ss")

        private fun getColumnIndex( cursor: Cursor, columnNames: List<String> ): Int {
            for (columnName in columnNames) {
                val index = cursor.getColumnIndex(columnName)
                if (index >= 0) return index
            }
            return -1
        }

        private fun queryUri( context: Context, uriQuery: Uri, uri: Uri, onlyFirstRecord: Boolean, isTreeUri: Boolean ): List<UriFile> {
            val result = mutableListOf<UriFile>()
            val authority = uri.authority ?: return result
            var cursor: Cursor? = null

            try {
                cursor = context.contentResolver.query(uriQuery, null, null, null, null)
                if (null != cursor) {
                    val indexDocumentId = getColumnIndex(cursor, DOCUMENT_ID_COLUMNS)
                    val indexDisplayName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val indexMimeType = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val indexSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val indexDate = getColumnIndex(cursor, DATE_COLUMNS)
                    val indexFlags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

                    if (indexDocumentId >= 0 && indexDisplayName >= 0 && indexMimeType >= 0 && indexSize >= 0) {
                        while (cursor.moveToNext()) {
                            val documentId = cursor.getString(indexDocumentId)
                            val mimeType = cursor.getString(indexMimeType)
                            var hasThumb = false

                            if (authority == MediaStore.AUTHORITY && (mimeType.startsWith("video/") || mimeType.startsWith("image/"))) {
                                hasThumb = true
                            } else if (indexFlags >= 0) {
                                hasThumb = (cursor.getInt(indexFlags) and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL) != 0
                            }

                            result.add(
                                UriFile(
                                    context,
                                    if (isTreeUri) DocumentsContract.buildDocumentUriUsingTree(uri, documentId) else uri,
                                    authority,
                                    documentId,
                                    cursor.getString(indexDisplayName),
                                    mimeType,
                                    if (indexDate < 0) 0L else cursor.getLong(indexDate),
                                    cursor.getLong(indexSize),
                                    hasThumb
                                )
                            )

                            if (onlyFirstRecord) break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if( null != cursor) {
                try {
                    cursor.close()
                } catch (e: Exception) {
                }
            }

            return result.toList()
        }

        private fun formatTimeStamp(timestamp: Long): String = DATE_FORMAT.format(Date(timestamp))

        fun fromTreeDocumentId( context: Context, treeUri: Uri, documentId: String ): UriFile? {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val list = queryUri(context, uri, uri, onlyFirstRecord = true, isTreeUri = true)
            return if (list.isNotEmpty()) list[0] else null
        }

        fun fromTreeUri(context: Context, uri: Uri): UriFile? {
            val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                DocumentsContract.getTreeDocumentId(uri)
            }

            return fromTreeDocumentId( context, uri, documentId )
        }

        fun fromSingleUri(context: Context, uri: Uri): UriFile? {
            val list = queryUri(context, uri, uri, onlyFirstRecord = true, isTreeUri = false)
            return if (list.isNotEmpty()) list[0] else null
        }
    }

    val date = formatTimeStamp(timestamp)
    val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)

    fun listFiles(): List<UriFile> {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId) ?: return listOf<UriFile>()
            return queryUri(context, childrenUri, uri, onlyFirstRecord = false, isTreeUri = true)
        } catch (e: Exception) {
        }

        return listOf<UriFile>()
    }

    fun createFile( mimeType: String, displayName: String ): Uri? =
        DocumentsContract.createDocument(context.contentResolver, uri, mimeType, displayName)


    fun createDirectory( displayName: String ): UriFile? {
        if (!isDirectory) return null
        val newUri = createFile(DocumentsContract.Document.MIME_TYPE_DIR, displayName) ?: return null
        return UriFile(
            context,
            newUri,
            authority,
            DocumentsContract.getDocumentId(newUri),
            displayName,
            DocumentsContract.Document.MIME_TYPE_DIR,
            System.currentTimeMillis(),
            length = 0,
            hasThumb = false
        )
    }

    fun getThumbnail(): Bitmap? {
        if (!hasThumb) return null

        try {
            val thumb = DocumentsContract.getDocumentThumbnail(
                context.contentResolver,
                uri,
                Point(Settings.THUMBNAIL_SIZE, Settings.THUMBNAIL_SIZE),
                null
            )
            if (null != thumb) return thumb
        } catch (e: Exception) {
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(
                    uri,
                    Size(Settings.THUMBNAIL_SIZE, Settings.THUMBNAIL_SIZE),
                    null
                )
            } catch (e: Exception) {
            }
        }

        if (mimeType.startsWith("image/")) {
            try {
                val thumb = MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    parseLong(documentId),
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
                if (null != thumb) return thumb
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (mimeType.startsWith("video/")) {
            try {
                val thumb = MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    parseLong(documentId),
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
                if (null != thumb) return thumb
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return null
    }
}