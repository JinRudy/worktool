package org.yameida.worktool.utils

import com.blankj.utilcode.util.LogUtils
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.yameida.worktool.Constant
import org.yameida.worktool.model.WeworkMessageBean
import org.yameida.worktool.service.WeworkController
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bot WebSocket 客户端
 * 连接 Python 服务器，双向通信：
 * - 上行：发送企微消息 + 日志到 Python 服务器
 * - 下行：接收 Python 服务器的回复消息
 */
object BotWebSocketClient {

    private var socket: WebSocket? = null
    private var connected = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 日志队列 (异步缓冲)
    private val logQueue = LinkedBlockingQueue<String>(500)
    private var flushThread: Thread? = null

    /**
     * 连接 Python 服务器
     */
    fun connect() {
        if (connected) {
            LogUtils.i("BotWebSocket 已连接，跳过")
            return
        }

        val wsUrl = Constant.localCallbackUrl
        if (wsUrl.isBlank()) {
            LogUtils.e("BotWebSocket: 回调地址为空，跳过连接")
            return
        }

        val url = wsUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
            .let { if (!it.endsWith("/ws")) "$it/ws" else it }
            .let { "$it/${Constant.robotId}" }

        LogUtils.i("BotWebSocket 连接: $url")

        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                LogUtils.i("BotWebSocket 连接成功")
                startFlushThread()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleIncomingMessage(text)
                } catch (e: Exception) {
                    LogUtils.e("BotWebSocket 处理消息失败", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                LogUtils.i("BotWebSocket 连接关闭: $code $reason")
                flushThread = null
                // 3秒后重连
                thread {
                    Thread.sleep(3000)
                    if (!connected) connect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                LogUtils.e("BotWebSocket 连接失败", t)
                flushThread = null
                // 3秒后重连
                thread {
                    Thread.sleep(3000)
                    if (!connected) connect()
                }
            }
        })
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        connected = false
        flushThread = null
        try {
            socket?.close(1000, "disconnect")
        } catch (e: Exception) {
            LogUtils.e("BotWebSocket 关闭失败", e)
        }
        socket = null
    }

    /**
     * 发送消息到 Python 服务器
     */
    fun sendMessage(roomType: Int, titleList: ArrayList<String>, messageList: ArrayList<WeworkMessageBean.SubMessageBean>) {
        if (!connected) {
            LogUtils.w("BotWebSocket 未连接，跳过发送")
            return
        }

        try {
            val lastMessage = messageList.lastOrNull() ?: return
            val spoken = lastMessage.itemMessageList.lastOrNull()?.text ?: ""
            val rawSpoken = lastMessage.itemMessageList.joinToString(" ") { it.text }
            val receivedName = lastMessage.nameList.lastOrNull() ?: ""
            val groupName = titleList.lastOrNull() ?: ""
            val groupRemark = if (titleList.size > 1) titleList.first() else ""
            val atMe = if (roomType == WeworkMessageBean.ROOM_TYPE_EXTERNAL_GROUP || roomType == WeworkMessageBean.ROOM_TYPE_INTERNAL_GROUP) {
                rawSpoken.contains("@" + Constant.myName)
            } else false
            val textType = lastMessage.textType

            val json = JSONObject()
            json.put("type", "message")
            json.put("robotId", Constant.robotId)
            json.put("spoken", spoken)
            json.put("rawSpoken", rawSpoken)
            json.put("receivedName", receivedName)
            json.put("groupName", groupName)
            json.put("groupRemark", groupRemark)
            json.put("roomType", roomType)
            json.put("atMe", atMe)
            json.put("textType", textType)
            json.put("fileBase64", "")
            json.put("messageId", "")

            val success = socket?.send(json.toString())
            if (success == true) {
                LogUtils.i("BotWebSocket 发送消息成功: $receivedName - $spoken")
            } else {
                LogUtils.e("BotWebSocket 发送消息失败")
            }
        } catch (e: Exception) {
            LogUtils.e("BotWebSocket 构建消息失败", e)
        }
    }

    /**
     * 发送日志到服务端（异步）
     */
    fun log(tag: String, message: String, level: String = "INFO") {
        val json = JSONObject()
        json.put("type", "log")
        json.put("timestamp", System.currentTimeMillis())
        json.put("log_type", "phone")
        json.put("robot_id", Constant.robotId)
        json.put("tag", tag)
        json.put("message", message)
        json.put("level", level)

        logQueue.offer(json.toString())
    }

    /**
     * 发送错误日志
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val json = JSONObject()
        json.put("type", "log")
        json.put("timestamp", System.currentTimeMillis())
        json.put("log_type", "phone")
        json.put("robot_id", Constant.robotId)
        json.put("tag", tag)
        json.put("message", message)
        json.put("level", "ERROR")
        throwable?.let { json.put("stack_trace", it.stackTraceToString()) }

        logQueue.offer(json.toString())
    }

    /**
     * 启动后台 flush 线程，批量上报日志
     */
    private fun startFlushThread() {
        if (flushThread?.isAlive == true) return

        flushThread = thread(start = true) {
            while (connected) {
                try {
                    val first = logQueue.poll(5, TimeUnit.SECONDS)
                    if (first == null) continue

                    val batch = JSONArray()
                    batch.put(first)
                    while (batch.length() < 50 && logQueue.poll(100, TimeUnit.MILLISECONDS)?.let { batch.put(it); true } == true) {
                        // collect more
                    }

                    if (batch.length() > 0) {
                        val wrapper = JSONObject()
                        wrapper.put("type", "logs")
                        wrapper.put("logs", batch)
                        socket?.send(wrapper.toString())
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    LogUtils.e("BotWebSocket flush 异常", e)
                }
            }
        }
    }

    /**
     * 处理来自 Python 服务器的消息
     */
    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "reply" -> {
                    val name = json.optString("name", "")
                    val msg = json.optString("msg", "")
                    val msgType = json.optInt("msgType", 1)

                    LogUtils.i("BotWebSocket 收到回复: $name - $msg")
                    log("BotWebSocket", "收到回复: $name - $msg")

                    if (name.isNotBlank() && msg.isNotBlank()) {
                        val messageBean = WeworkMessageBean()
                        messageBean.type = WeworkMessageBean.SEND_MESSAGE
                        messageBean.titleList = arrayListOf(name)
                        messageBean.receivedContent = msg
                        messageBean.textType = msgType

                        WeworkController.sendMessage(messageBean)
                    }
                }
                "ping" -> {
                    socket?.send("{\"type\": \"pong\"}")
                }
                else -> {
                    LogUtils.w("BotWebSocket 未知消息类型: $type")
                }
            }
        } catch (e: Exception) {
            LogUtils.e("BotWebSocket 解析消息失败: $text", e)
            error("BotWebSocket", "解析消息失败: $text", e)
        }
    }
}