package com.nao.md.project.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ApiClient {
    private val base = "http://176.100.37.77:30157"
    private fun conn(path: String, method: String): HttpURLConnection {
        return (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20000; readTimeout = 120000
            setRequestProperty("User-Agent", "NAO-MD-Android/2.0")
        }
    }
    private fun body(c: HttpURLConnection): String {
        val input: InputStream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        return input.bufferedReader().use { it.readText() }
    }
    fun json(path: String, obj: JSONObject): JSONObject {
        val c=conn(path,"POST"); c.doOutput=true; c.setRequestProperty("Content-Type","application/json; charset=utf-8")
        c.outputStream.use { it.write(obj.toString().toByteArray()) }
        val text=body(c); val code=c.responseCode; c.disconnect()
        val out=JSONObject(text.ifBlank { "{}" }); if(code !in 200..299) throw Exception(out.optString("error","HTTP $code")); return out
    }
    fun multipart(path:String, file:File, field:String="file", params:Map<String,String> = emptyMap(), onProgress: ((Long, Long) -> Unit)? = null): JSONObject {
        val boundary="----NAO${UUID.randomUUID()}"; val c=conn(path,"POST"); c.doOutput=true; c.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
        val head = buildString {
            for((k,v) in params){ append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        // Set panjang tetap supaya request benar-benar di-stream (bukan
        // ditumpuk di buffer lalu dikirim sekaligus) sehingga onProgress
        // melaporkan kemajuan unggah yang sesungguhnya, bukan lompat 0->100.
        val total = head.size.toLong() + file.length() + tail.size
        c.setFixedLengthStreamingMode(total)
        c.outputStream.use { out ->
            out.write(head)
            var done = head.size.toLong()
            onProgress?.invoke(done, total)
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    done += read
                    onProgress?.invoke(done, total)
                }
            }
            out.write(tail)
            done += tail.size
            onProgress?.invoke(done, total)
        }
        val text=body(c); val code=c.responseCode; c.disconnect(); val out=JSONObject(text.ifBlank{"{}"}); if(code !in 200..299) throw Exception(out.optString("error","HTTP $code")); return out
    }
    fun download(path:String,obj:JSONObject):ByteArray {
        val c=conn(path,"POST"); c.doOutput=true; c.setRequestProperty("Content-Type","application/json; charset=utf-8"); c.outputStream.use{it.write(obj.toString().toByteArray())}
        val data=ByteArrayOutputStream(); val input=if(c.responseCode in 200..299)c.inputStream else(c.errorStream?:c.inputStream); input.use{it.copyTo(data)}; val code=c.responseCode; if(code !in 200..299) throw Exception(JSONObject(data.toString("UTF-8")).optString("error","HTTP $code")); c.disconnect(); return data.toByteArray()
    }
    fun getBytes(path: String): ByteArray {
        val c = conn(path, "GET")
        val code = c.responseCode
        val input = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val data = ByteArrayOutputStream()
        input.use { it.copyTo(data) }
        c.disconnect()
        if (code !in 200..299) {
            val message = try { JSONObject(data.toString("UTF-8")).optString("error", "HTTP $code") } catch (_: Exception) { "HTTP $code" }
            throw Exception(message)
        }
        return data.toByteArray()
    }

    fun getUrlBytes(url: String, onProgress: ((Long, Long) -> Unit)? = null): ByteArray {
        require(url.startsWith("http://") || url.startsWith("https://")) { "URL media tidak valid" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 120000
            setRequestProperty("User-Agent", "NAO-MD-Android/2.0")
        }
        val code = c.responseCode
        val input = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val total = c.contentLengthLong
        val data = ByteArrayOutputStream()
        var done = 0L
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                data.write(buffer, 0, read)
                done += read
                onProgress?.invoke(done, total)
            }
        }
        c.disconnect()
        if (code !in 200..299) throw Exception("Gagal mengambil media (HTTP $code)")
        return data.toByteArray()
    }
}
