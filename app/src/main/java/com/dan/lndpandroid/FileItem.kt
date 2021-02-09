package com.dan.lndpandroid

import android.graphics.Bitmap

class FileItem(val file: UriFile, var thumbnail: Bitmap? = null ) {
    val details = "${file.date}  |  ${file.length} byte(s)"
    var isSelected = false
}
