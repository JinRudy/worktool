package org.yameida.worktool.model

data class MyConfigBean(
    //QA类型 0未配置 1第三方QA 2微信对话平台
    var openCallback: Int? = null,

    //第三方QA回调地址
    var callbackUrl: String? = null,

    //回复策略 -1只读消息不回调 0仅私聊和群聊@机器人回调 1私聊群聊全部回调
    var replyAll: Int? = null,

    //key校验 0未开启 1开启
    var robotKeyCheck: Int? = null,

    //通讯加密 0不加密 1加密
    var encryptType: Int? = null,

    //创建时间
    var createTime: String? = null,

    //本地模式 0关闭 1开启
    var useLocalMode: Int? = null,

    //本地HTTP端口
    var localHttpPort: Int? = null,

    //本地回调地址
    var localCallbackUrl: String? = null
)