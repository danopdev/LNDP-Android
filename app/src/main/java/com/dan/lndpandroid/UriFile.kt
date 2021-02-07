package com.dan.lndpandroid

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

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
            DocumentsContract.Document.COLUMN_FLAGS
        )

        private val DATE_FORMAT = SimpleDateFormat("yyyy:MM:dd hh:mm:ss")

        private const val THUMBNAIL_SIZE = 300

        private fun queryTreeUri( context: Context, queryUri: Uri, treeUri: Uri, onlyFirstRecord: Boolean ): ArrayList<UriFile> {
            val result = ArrayList<UriFile>()
            val authority = treeUri.authority ?: return result

            var cursor: Cursor? = null

            try {
                cursor = context.contentResolver.query(queryUri, PROJECTION, null, null, null)
                if (null != cursor) {
                    val indexDocumentId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val indexDisplayName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val indexMimeType = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val indexSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val indexDate = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val indexFlags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

                    if (indexDocumentId >= 0 && indexDisplayName >= 0 && indexMimeType >= 0 && indexSize >= 0 && indexDate >= 0) {
                        while (cursor.moveToNext()) {
                            val documentId = cursor.getString(indexDocumentId)
                            result.add(
                                UriFile(
                                    context,
                                    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                                    authority,
                                    documentId,
                                    cursor.getInt(indexFlags),
                                    cursor.getString(indexDisplayName),
                                    cursor.getString(indexMimeType),
                                    cursor.getLong(indexDate),
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

            return result
        }

        fun formatTimeStamp(timestamp: Long) = DATE_FORMAT.format(Date(timestamp))

        fun fromTreeUri(context: Context, treeUri: Uri): UriFile? {
            val documentId =
                if (DocumentsContract.isDocumentUri(context, treeUri) ) DocumentsContract.getDocumentId(treeUri)
                else DocumentsContract.getTreeDocumentId(treeUri)

            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val list = queryTreeUri(context, uri, uri, true)
            return if (list.size > 0) list[0] else null
        }
    }

    val date = formatTimeStamp(timestamp)
    val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
    val supportThumbnails = (flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL) > 0

    fun listFiles(): ArrayList<UriFile> {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId) ?: return ArrayList<UriFile>()
            return queryTreeUri(context, childrenUri, uri, false)
        } catch (e: Exception) {
        }

        return ArrayList<UriFile>()
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
            return DocumentsContract.getDocumentThumbnail(context.contentResolver, uri, Point(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null )
        } catch (e: Exception) {
        }

        return null
    }
}