package org.yameida.worktool.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.*
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import org.json.JSONObject
import org.yameida.worktool.Constant
import org.yameida.worktool.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * 自动更新检查器
 * 从 version.json 获取最新版本信息
 */
object AutoUpdateChecker {

    private const val VERSION_JSON_URL = "https://sulingai-wza.minifog.org.cn/version.json"

    /**
     * 检查更新（静默模式，仅日志输出 + 自动下载）
     * 在应用启动或后台时调用
     */
    fun checkUpdateSilent() {
        thread {
            try {
                val update = fetchVersionInfo()
                if (update == null) {
                    LogUtils.w("AutoUpdate: 获取版本信息失败")
                    return@thread
                }

                val currentVersionCode = SPUtils.getInstance().getInt("appVersionCode", 0)
                val latestVersionCode = update.optInt("version_code", 0)

                if (latestVersionCode > currentVersionCode) {
                    LogUtils.i("AutoUpdate: 发现新版本 ${update.optString("version")} (当前: $currentVersionCode)")
                    downloadAndInstallSilent(update)
                } else {
                    LogUtils.d("AutoUpdate: 已是最新版本 ${update.optString("version")}")
                }
            } catch (e: Exception) {
                LogUtils.e("AutoUpdate: 检查更新异常", e)
            }
        }
    }

    /**
     * 静默下载安装（无弹窗）
     * 下载成功后尝试安装，权限不足则记录日志
     */
    private fun downloadAndInstallSilent(update: JSONObject) {
        val downloadUrl = update.optString("download_url")
        if (downloadUrl.isBlank()) {
            LogUtils.e("AutoUpdate: 下载链接为空")
            return
        }

        val version = update.optString("version", "latest")
        val apkFile = File(Utils.getApp().cacheDir, "update_${version}.apk")

        thread {
            try {
                LogUtils.i("AutoUpdate: 开始下载 $version")
                val success = HttpHelper.downloadFile(downloadUrl, apkFile)

                if (success && apkFile.exists() && apkFile.length() > 1000000) {
                    LogUtils.i("AutoUpdate: 下载完成 ${apkFile.length()} bytes")
                    installApkSilent(apkFile)
                } else {
                    LogUtils.e("AutoUpdate: 下载失败或文件不完整 (size=${apkFile.length()})")
                }
            } catch (e: Exception) {
                LogUtils.e("AutoUpdate: 下载异常", e)
            }
        }
    }

    /**
     * 静默安装（无弹窗，权限不足则记录日志）
     */
    private fun installApkSilent(apkFile: File) {
        if (!apkFile.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasPermission = Utils.getApp().packageManager.canRequestPackageInstalls()
            if (!hasPermission) {
                LogUtils.w("AutoUpdate: 需要安装未知应用权限，跳过自动安装")
                return
            }
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                val apkUri = FileProvider.getUriForFile(
                    Utils.getApp(),
                    Utils.getApp().packageName + ".fileprovider",
                    apkFile
                )
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Utils.getApp().startActivity(intent)
            LogUtils.i("AutoUpdate: 安装意图已发送 (silent)")
        } catch (e: Exception) {
            LogUtils.e("AutoUpdate: 静默安装失败", e)
        }
    }

    /**
     * 检查更新（弹窗模式，用于手动触发）
     */
    fun checkUpdateWithDialog(activity: Activity) {
        thread {
            try {
                val update = fetchVersionInfo()
                if (update == null) {
                    activity.runOnUiThread {
                        ToastUtils.showLong("检查更新失败，请检查网络")
                    }
                    return@thread
                }

                val currentVersionCode = SPUtils.getInstance().getInt("appVersionCode", 0)
                val latestVersionCode = update.optInt("version_code", 0)
                val version = update.optString("version")
                val releaseNotes = update.optString("release_notes", "")
                val downloadUrl = update.optString("download_url")

                activity.runOnUiThread {
                    if (latestVersionCode > currentVersionCode) {
                        showUpdateDialog(activity, version, releaseNotes, downloadUrl)
                    } else {
                        ToastUtils.showLong("当前已是最新版本: $version")
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    ToastUtils.showLong("检查更新失败: ${e.message}")
                }
                LogUtils.e("AutoUpdate: 检查更新异常", e)
            }
        }
    }

    /**
     * 从 version.json 获取版本信息
     */
    private fun fetchVersionInfo(): JSONObject? {
        try {
            LogUtils.i("AutoUpdate: 从 $VERSION_JSON_URL 获取版本信息")
            val conn = (URL(VERSION_JSON_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "素灵智能-WorkTool")
            }
            val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val json = JSONObject(response)
            val version = json.optString("version", "")
            val versionCode = json.optInt("version_code", 0)
            val downloadUrl = json.optString("download_url", "")

            LogUtils.i("AutoUpdate: 最新版本 $version (code=$versionCode) download=$downloadUrl")
            return json
        } catch (e: Exception) {
            LogUtils.e("AutoUpdate: 获取版本信息失败", e)
            return null
        }
    }

    /**
     * 下载 APK 并安装（带 UI 反馈）
     */
    private fun downloadAndInstall(update: JSONObject, activity: Activity) {
        val downloadUrl = update.optString("download_url")
        if (downloadUrl.isBlank()) {
            activity.runOnUiThread { ToastUtils.showLong("下载链接为空") }
            return
        }

        val version = update.optString("version", "latest")
        val apkFile = File(Utils.getApp().cacheDir, "update_${version}.apk")

        thread {
            activity.runOnUiThread { ToastUtils.showLong("开始下载 $version...") }
            try {
                val success = HttpHelper.downloadFile(downloadUrl, apkFile)

                if (success && apkFile.exists() && apkFile.length() > 1000000) {
                    LogUtils.i("AutoUpdate: 下载完成 ${apkFile.length()} bytes")
                    activity.runOnUiThread { ToastUtils.showLong("下载完成，正在安装...") }
                    installApk(apkFile, activity)
                } else {
                    LogUtils.e("AutoUpdate: 下载失败或文件不完整")
                    activity.runOnUiThread { ToastUtils.showLong("下载失败，请检查网络后重试") }
                }
            } catch (e: Exception) {
                LogUtils.e("AutoUpdate: 下载异常", e)
                activity.runOnUiThread { ToastUtils.showLong("下载异常: ${e.message}") }
            }
        }
    }

    /**
     * 安装 APK（带权限引导）
     */
    private fun installApk(apkFile: File, activity: Activity) {
        if (!apkFile.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasPermission = Utils.getApp().packageManager.canRequestPackageInstalls()
            if (!hasPermission) {
                activity.runOnUiThread {
                    QMUIDialog.MessageDialogBuilder(activity)
                        .setTitle("需要安装权限")
                        .setMessage("请允许安装未知应用以继续更新")
                        .addAction("取消") { dialog, _ -> dialog.dismiss() }
                        .addAction("去开启") { dialog, _ ->
                            dialog.dismiss()
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${Utils.getApp().packageName}")
                            }
                            activity.startActivity(intent)
                        }
                        .create(R.style.QMUI_Dialog)
                        .show()
                }
                return
            }
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                val apkUri = FileProvider.getUriForFile(
                    Utils.getApp(),
                    Utils.getApp().packageName + ".fileprovider",
                    apkFile
                )
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Utils.getApp().startActivity(intent)
            LogUtils.i("AutoUpdate: 安装意图已发送")
        } catch (e: Exception) {
            LogUtils.e("AutoUpdate: 安装失败", e)
            activity.runOnUiThread { ToastUtils.showLong("安装失败: ${e.message}") }
        }
    }

    /**
     * 显示更新弹窗
     */
    private fun showUpdateDialog(
        activity: Activity,
        version: String,
        releaseNotes: String,
        downloadUrl: String
    ) {
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle("发现新版本 $version")
            .setMessage(releaseNotes.ifBlank { "立即更新到最新版本" })
            .addAction("立即下载") { dialog, _ ->
                dialog.dismiss()
                downloadAndInstall(JSONObject().apply {
                    put("version", version)
                    put("download_url", downloadUrl)
                    put("version_code", parseVersionCode(version))
                }, activity)
            }
            .addAction("取消") { dialog, _ -> dialog.dismiss() }
            .create(R.style.QMUI_Dialog)
            .show()
    }

    /**
     * 将版本号字符串转换为数字: 1.0.0 -> 10000
     */
    private fun parseVersionCode(versionName: String): Int {
        val parts = versionName.split(".")
        return try {
            val major = parts.getOrElse(0) { "0" }.toInt()
            val minor = parts.getOrElse(1) { "0" }.toInt()
            val patch = parts.getOrElse(2) { "0" }.toInt()
            major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            0
        }
    }
}
