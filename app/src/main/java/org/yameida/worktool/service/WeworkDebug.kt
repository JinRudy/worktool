package org.yameida.worktool.service

import com.blankj.utilcode.util.LogUtils
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import org.yameida.worktool.Constant
import org.yameida.worktool.model.WeworkMessageBean
import org.yameida.worktool.utils.AccessibilityUtil
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 页面元素调试工具
 * 抓取当前无障碍窗口的元素树，上报到 Python 服务器
 */
object WeworkDebug {

    fun dumpPage(message: WeworkMessageBean) {
        try {
            val service = WeworkController.weworkService
            val root = try {
                service.rootInActiveWindow
            } catch (e: Exception) {
                LogUtils.e("debugDump getRoot failed", e)
                return
            }

            if (root == null) {
                LogUtils.w("debugDump: root is null")
                return
            }

            val packageName = root.packageName?.toString() ?: "unknown"
            val className = root.className?.toString() ?: "unknown"

            // 检查 isAtHome
            val isAtHome = isAtHome()

            // 收集页面元素摘要
            val elements = collectElements(root, depth = 0, maxDepth = 8, maxTotal = 200)
            val elementsCount = elements.size

            // 元素预览（前 1500 字符）
            val preview = elements.joinToString("\n").take(1500)

            LogUtils.i("debugDump: pkg=$packageName class=$className isAtHome=$isAtHome elements=$elementsCount")
            log("debugDump: pkg=$packageName class=$className isAtHome=$isAtHome elements=$elementsCount")

            // 上报到 Python 服务器
            if (Constant.useLocalMode && Constant.localCallbackUrl.isNotBlank()) {
                sendDumpToServer(
                    isAtHome = isAtHome,
                    packageName = packageName,
                    className = className,
                    elementsCount = elementsCount,
                    preview = preview
                )
            }
        } catch (e: Exception) {
            LogUtils.e("debugDump error", e)
        }
    }

    /**
     * 递归收集页面元素摘要（仅文本信息，不发图片）
     */
    private fun collectElements(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        maxTotal: Int,
        collected: MutableList<String> = mutableListOf()
    ): List<String> {
        if (collected.size >= maxTotal) return collected
        if (depth > maxDepth) return collected

        val prefix = "  ".repeat(depth)

        // 节点基本信息
        val cls = node.className?.toString() ?: ""
        val text = node.text?.toString()?.take(60)
        val contentDesc = node.contentDescription?.toString()?.take(60)
        val isClick = node.isClickable

        val line = buildString {
            append(prefix)
            append("[$cls]")
            if (!text.isNullOrEmpty()) append(" text=\"$text\"")
            if (!contentDesc.isNullOrEmpty()) append(" desc=\"$contentDesc\"")
            if (isClick) append(" ✅click")
        }
        collected.add(line)

        // 递归子节点
        for (i in 0 until node.childCount) {
            if (collected.size >= maxTotal) break
            node.getChild(i)?.let { collectElements(it, depth + 1, maxDepth, maxTotal, collected) }
        }

        return collected
    }

    private fun sendDumpToServer(
        isAtHome: Boolean,
        packageName: String,
        className: String,
        elementsCount: Int,
        preview: String
    ) {
        try {
            val json = JSONObject()
            json.put("is_at_home", isAtHome)
            json.put("package_name", packageName)
            json.put("current_class", className)
            json.put("elements_count", elementsCount)
            json.put("root_text", preview)
            json.put("elements_preview", preview)

            val url = "${Constant.localCallbackUrl}/api/debug/dump"
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    json.toString()
                ))
                .build()

            val response = WeworkDebug.client.newCall(request).execute()
            val body = response.body()?.string() ?: ""
            LogUtils.i("debugDump server response: ${response.code()} $body")
        } catch (e: Exception) {
            LogUtils.e("debugDump send to server failed", e)
        }
    }

    // 复用的 OkHttpClient
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}