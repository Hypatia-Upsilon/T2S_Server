package com.github.jing332.tts.synthesizer

interface IBgmPlayer {
    fun play()
    fun stop()
    fun init()
    fun destroy()
    fun setPlayList(
        list: List<BgmSource>
    )
}

/**
 * @param uri BGM 条目：SAF `content://` URI 字符串，或历史数据遗留的绝对路径
 *            （统一交给 SafUtils 解析，见 [com.github.jing332.common.utils.SafUtils]）
 */
data class BgmSource(val uri: String, val volume: Float)