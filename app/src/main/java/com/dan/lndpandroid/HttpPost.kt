package com.dan.lndpandroid

import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder

class HttpPost(connection: HttpURLConnection) {

    companion object {
        val CRLF = "\r\n"
        val TWO_HYPHENS = "--"
        val BOUNDARY = "*****"
    }

    private var request: DataOutputStream

    init {
        with(connection) {
            setDoOutput(true)

            setRequestMethod("POST")
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Content-Type", "multipart/form-data;boundary=" + BOUNDARY)

            request = DataOutputStream(getOutputStream());
        }
    }

    fun addFormField(name: String, value: String) {
        with(request) {
            writeBytes(TWO_HYPHENS + BOUNDARY + CRLF)
            writeBytes("Content-Disposition: form-data; name=\"" + name + "\"" + CRLF)
            writeBytes("Content-Type: text/plain; charset=UTF-8" + CRLF)
            writeBytes(CRLF)
            writeBytes(URLEncoder.encode(value, "utf-8"))
            writeBytes(CRLF)
        }
    }

    fun addFileBlock(fieldName: String, fileName: String, buffer: ByteArray, bufferSize: Int) {
        with(request) {
            writeBytes(TWO_HYPHENS + BOUNDARY + CRLF)
            writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\";filename=\"" + fileName + "\"" + CRLF)
            writeBytes(CRLF)
            write(buffer, 0, bufferSize)
        }
    }

    fun finish() {
        with( request ) {
            writeBytes(CRLF)
            writeBytes(TWO_HYPHENS + BOUNDARY + TWO_HYPHENS + CRLF)
            flush()
            close()
        }
    }
}
