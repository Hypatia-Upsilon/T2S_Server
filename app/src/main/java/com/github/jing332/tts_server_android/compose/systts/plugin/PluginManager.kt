package com.github.jing332.tts_server_android.compose.systts.plugin

import com.github.jing332.common.utils.ChajianDir
import com.github.jing332.database.entities.plugin.Plugin
import com.github.jing332.tts_server_android.constant.AppConst
import splitties.init.appCtx
import java.io.File

class PluginManager(private val plugin: Plugin) {
    companion object {
        /** 插件缓存根目录：Android/data/<包名>/files/chajian（由 ChajianDir 统一解析，零权限且用户可见） */
        val CACHE_BASE_DIR: String get() = ChajianDir.rootPath
    }

    // 存储路径：<chajian>/<pluginId>
    private val cacheDir = File(CACHE_BASE_DIR, plugin.pluginId)

    // 旧的存储路径：ExternalCacheDir (用于清理残留)
    private val legacyCacheDir = File(appCtx.getExternalFilesDir("plugin_cache"), plugin.pluginId)

    fun hasCache(): Boolean {
        return try {
            (cacheDir.list()?.isNotEmpty() == true) || (legacyCacheDir.list()?.isNotEmpty() == true)
        } catch (e: Exception) {
            false
        }
    }

    fun clearCache() {
        try {
            cacheDir.deleteRecursively()
            legacyCacheDir.deleteRecursively()
        } catch (_: Exception) {
        }
    }
}
