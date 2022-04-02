package com.dan.lndpandroid

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.*


class UriFile(
    val context: Context,
    val uri: Uri,
    val authority: String,
    val documentId: String,
    val flags: Int,
    val name: String,
    val mimeType: String,
    val timestamp: Long,
    val length: Long
) {

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        @SuppressLint("SimpleDateFormat")
        private val DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd hh:mm:ss")

        private fun queryUri( context: Context, uriQuery: Uri, uri: Uri, onlyFirstRecord: Boolean, isTreeUri: Boolean ): List<UriFile> {
            val result = mutableListOf<UriFile>()
            val authority = uri.authority ?: return result
            var cursor: Cursor? = null

            try {
                cursor = context.contentResolver.query(uriQuery, PROJECTION, null, null, null)
                if (null != cursor) {
                    val indexDocumentId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val indexDisplayName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val indexMimeType = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val indexSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val indexDate = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val indexFlags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

                    if (indexDocumentId >= 0 && indexDisplayName >= 0 && indexMimeType >= 0 && indexSize >= 0) {
                        while (cursor.moveToNext()) {
                            val documentId = cursor.getString(indexDocumentId)
                            result.add(
                                UriFile(
                                    context,
                                    if (isTreeUri) DocumentsContract.buildDocumentUriUsingTree(uri, documentId) else uri,
                                    authority,
                                    documentId,
                                    cursor.getInt(indexFlags),
                                    cursor.getString(indexDisplayName),
                                    cursor.getString(indexMimeType),
                                    if (indexDate < 0) 0L else cursor.getLong(indexDate),
                                    cursor.getLong(indexSize)
                                )
                            )

                            if (onlyFirstRecord) break
                        }
                    }
                }
            } catch (e: Exception) {
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
    val supportThumbnails = (flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL) > 0

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
            0,
            displayName,
            DocumentsContract.Document.MIME_TYPE_DIR,
            System.currentTimeMillis(),
            0 )
    }

    fun getThumbnail(): Bitmap? {
        if (!supportThumbnails)
            return null

        try {
            return DocumentsContract.getDocumentThumbnail(
                context.contentResolver,
                uri,
                Point(Settings.THUMBNAIL_SIZE, Settings.THUMBNAIL_SIZE),
                null )
        } catch (e: Exception) {
        }

        return null
    }
}