package org.yameida.worktool.utils

import com.blankj.utilcode.util.*
import org.json.JSONObject
import org.yameida.worktool.Constant
import org.yameida.worktool.R
import org.yameida.worktool.model.network.CheckUpdateResult
import org.yameida.worktool.model.network.GetMyConfigResult
import org.yameida.worktool.service.log
import org.yameida.worktool.utils.envcheck.CheckRoot
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object HttpUtil {

    /**
     * 检查更新 - 已禁用 (依赖不可用)
     */
    @Suppress("UNUSED")
    fun checkUpdate(url: String? = null) {
        LogUtils.w("检查更新功能已禁用")
    }

    /**
     * 获取机器人配置
     */
    fun getMyConfig(toast: Boolean = true) {
        if (Constant.robotId.isBlank()) {
            if (toast) {
                ToastUtils.showLong("请先填写机器人ID")
            }
            return
        }
        try {
            val conn = (URL(Constant.getMyConfig()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
            }
            val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val commonResult = GsonUtils.fromJson(response, GetMyConfigResult::class.java)
            if (commonResult.code != 200) {
                ToastUtils.showLong("获取配置失败 请检查机器人ID")
                LogUtils.e("获取配置失败 请检查机器人ID")
                return
            }
            LogUtils.i("获取配置", commonResult.data)
            SPUtils.getInstance().put("risk", false)
            if (CheckRoot.isDeviceRooted()) {
                val date = TimeUtils.string2Date(commonResult.data.createTime, "yyyy-MM-dd'T'HH:mm:ss")
                if (System.currentTimeMillis() - date.time < 15 * 68400 * 1000) {
                    LogUtils.e("环境监测异常，请勿使用本应用！")
                    ToastUtils.showLong("环境监测异常，请勿使用本应用！")
                    SPUtils.getInstance().put("risk", true)
                }
            }
            commonResult.data?.apply {
                Constant.qaUrl = this.callbackUrl ?: ""
                Constant.openCallback = this.openCallback ?: 0
                Constant.replyStrategy = (this.replyAll ?: 0) + 1
                // 同步本地模式配置
                if (this.useLocalMode != null) {
                    Constant.useLocalMode = this.useLocalMode == 1
                }
                if (this.localHttpPort != null) {
                    Constant.localHttpPort = this.localHttpPort!!
                }
                if (this.localCallbackUrl != null) {
                    Constant.localCallbackUrl = this.localCallbackUrl!!
                }
            }
        } catch (e: Exception) {
            ToastUtils.showLong("获取配置失败 请检查机器人ID")
            LogUtils.e("获取配置失败", e)
        }
    }

    /**
     * 推送图片
     */
    fun pushImage(url: String, titleList: List<String>, receivedName: String?, imagePath: String, roomType: Int) {
        return pushImage(url, titleList, receivedName, File(imagePath).readBytes(), roomType)
    }

    /**
     * 推送图片
     */
    fun pushImage(url: String, titleList: List<String>, receivedName: String?, byteArray: ByteArray, roomType: Int) {
        try {
            val json = JSONObject()
            if (receivedName != null) {
                json.put("receivedName", receivedName)
                json.put("groupName", titleList.lastOrNull())
                if (titleList.size > 1) {
                    json.put("groupRemark", titleList.first())
                } else {
                    json.put("groupRemark", null)
                }
            } else {
                json.put("receivedName", titleList.lastOrNull { !it.contains("＠") } ?: "")
                json.put("groupName", null)
                json.put("groupRemark", null)
            }
            json.put("image", EncodeUtils.base64Encode2String(byteArray))
            json.put("robotId", Constant.robotId)
            json.put("roomType", roomType)
            json.put("atMe", false)
            json.put("textType", 2)

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                doOutput = true
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 10000
                readTimeout = 10000
            }
            conn.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(json.toString()) }
            val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()

            LogUtils.d("推送图片成功: ${titleList.joinToString()} $receivedName")
            log("推送图片成功: ${titleList.joinToString()} $receivedName")
        } catch (e: Exception) {
            ToastUtils.showLong("推送图片失败")
            LogUtils.e("推送图片失败", e)
            error("推送图片失败: ${titleList.joinToString()} $receivedName")
        }
    }

    /**
     * 推送本地文件 - 已禁用 (依赖不可用)
     */
    @Suppress("UNUSED")
    fun pushLocalFile(file: File) {
        LogUtils.w("推送本地文件功能已禁用")
    }
}
