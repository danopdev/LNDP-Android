package com.dan.lndpandroid

import android.graphics.Bitmap

class FileItem(val file: UriFile, var thumbnail: Bitmap? = null ) {
    companion object {
        private const val KB = 1024L
        private const val MB = KB * KB
        private const val GB = KB * MB

        fun formatSize(sizeInBytes: Long): String {
            return when {
                sizeInBytes < KB -> "$sizeInBytes B"
                sizeInBytes < (10L * KB) -> "${String.format("%.2f", sizeInBytes / KB.toDouble())} KB"
                sizeInBytes < MB -> "${sizeInBytes / KB} KB"
                sizeInBytes < (10L * MB) -> "${String.format("%.2f", sizeInBytes / MB.toDouble())} MB"
                sizeInBytes < GB -> "${sizeInBytes / MB} MB"
                sizeInBytes < (10L * GB) -> "${String.format("%.2f", sizeInBytes / GB.toDouble())} GB"
                else -> "${sizeInBytes / GB} GB"
            }
        }
    }

    val details = "${file.date}  |  ${formatSize(file.length)}"
    var isSelected = false
}
