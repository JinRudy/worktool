package org.yameida.worktool.utils

import com.blankj.utilcode.util.LogUtils
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 轻量 HTTP 工具类 - 替代 OkGo
 */
object HttpHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun get(url: String): String? {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                return response.body()?.string()
            }
            LogUtils.e("HTTP GET 失败: $url")
            return null
        } catch (e: Exception) {
            LogUtils.e("HTTP GET 异常", e)
            return null
        }
    }

    fun postJson(url: String, json: String): String? {
        try {
            val requestBody = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                json
            )
            val request = Request.Builder().url(url).post(requestBody).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                return response.body()?.string()
            }
            LogUtils.e("HTTP POST 失败: $url")
            return null
        } catch (e: Exception) {
            LogUtils.e("HTTP POST 异常", e)
            return null
        }
    }

    fun downloadFile(url: String, destFile: File): Boolean {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body()?.byteStream()?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return true
            }
            LogUtils.e("下载文件失败: $url")
            return false
        } catch (e: Exception) {
            LogUtils.e("下载文件异常", e)
            return false
        }
    }
}