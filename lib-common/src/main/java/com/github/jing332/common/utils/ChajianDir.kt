package com.github.jing332.common.utils

import android.content.Context
import android.util.Log
import splitties.init.appCtx
import java.io.File

/**
 * 「chajian」数据根目录的**唯一解析入口**（插件缓存 / 密钥 / 角色记录 / 语音标记 / 脚本沙箱共用）。
 *
 * ## 为什么搬家
 * 原来固定在公共 `/storage/emulated/0/Download/chajian`，读写都需要
 * `MANAGE_EXTERNAL_STORAGE`（「所有文件访问权限」）。该权限已从本应用移除，
 * 于是 A11+ 上写入必然 EACCES 失败（日志表现为各种 `... failed`）。
 *
 * ## 现在的位置
 * `Android/data/<包名>/files/chajian`（即 [Context.getExternalFilesDir]，应用专属外部目录）
 * - 本应用读写**不需要任何权限**；
 * - 用的是**非弃用**的官方 API（`getExternalMediaDirs()` 已 deprecated），
 *   且与响度统计等其余应用数据同处 `Android/data/<包名>/files/` 下，结构统一；
 * - 代价：A11+ 上其他应用与系统「文件」App **都看不到 `Android/data`**，
 *   所以对外交换文件一律走「数据目录（SAF 同步）」——见 [ChajianSync]：
 *   用户可指定 `Download/chajian` 等任意文件夹，通过手动导入/导出或自动同步互通；
 * - 与所有应用专属目录一样：**随应用卸载一并删除**，换机/重装前请先导出备份。
 *
 * 无外部存储时退回应用内部 `filesDir/chajian`（功能可用，但更不可见）。
 *
 * ## 为什么不能用 SAF 直接当根目录
 * SAF 授权是 URI 级的，A11+ 上即便用户用 SAF 授权了某个目录，也无法用
 * `java.io.File` 去读写该路径；而插件契约 `ttsrv.getFile()` 返回的正是 `File`。
 * 因此 SAF 只能作为**导入/导出**的通道，实时数据根必须是真实路径。
 */
object ChajianDir {
    private const val TAG = "ChajianDir"
    const val DIR_NAME = "chajian"

    /** 一次性迁移标记（位于新根目录内），避免每次启动重复尝试 */
    private const val MIGRATED_MARKER = ".migrated_from_public"

    /** 递归深度上限，防御异常目录结构 */
    private const val MAX_DEPTH = 12

    /**
     * 历史位置，**只作为一次性迁移的来源**：
     * `/storage/emulated/0/Download/chajian` —— 最初版本的公共目录
     * （读写需要「所有文件访问权限」，该权限已移除，A11+ 上 `exists()`/`canRead()` 直接为 false）。
     *
     * 注：开发过程中曾短暂把根目录放在 `Android/media/<包名>/chajian`（`getExternalMediaDirs()`
     * 已 deprecated），但该版本从未发布，故不提供从它迁移的来源。
     */
    val legacyRoots: List<File> = listOf(File("/storage/emulated/0/Download/chajian"))

    @Volatile
    private var cached: File? = null

    /** 当前数据根目录（惰性解析并缓存）。所有模块都不应再硬编码路径。 */
    val root: File
        get() = cached ?: resolve(appCtx).also { cached = it }

    val rootPath: String
        get() = root.absolutePath

    /** 根目录下的子目录/文件 */
    fun of(name: String): File = File(root, name)

    /**
     * 解析数据根目录：
     * 1. 应用专属外部目录 `Android/data/<包名>/files/chajian`（零权限，官方规范 API）
     * 2. 无外部存储时退回应用内部 `filesDir/chajian`
     *
     * 该目录**随应用卸载一并删除**（官方文档明确），对外交换文件请配合 [ChajianSync]。
     */
    fun resolve(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR_NAME).apply { runCatching { mkdirs() } }
    }

    /**
     * 把历史位置的数据一次性迁移进新根目录（幂等，**请在 IO 线程调用**）。
     *
     * - 历史目录不存在或不可读（A11+ 上的常态）→ 只落标记，之后不再重试；
     * - 可读（A10 及以下，或仍持有存储权限）→ 递归复制，**已存在的文件不覆盖**，
     *   全部走完才落标记；中途异常则下次启动重试（[copyTree] 对已存在文件会跳过，重试是安全的）。
     *
     * 只复制、不删除历史目录：数据多一份更安全，用户可自行核对后再清理。
     */
    fun migrateFromLegacyIfNeeded() {
        val target = root
        val marker = File(target, MIGRATED_MARKER)
        if (marker.exists()) return

        val source = legacyRoots.firstOrNull { it.exists() && it.canRead() }
        if (source == null) {
            // 无可迁移内容：落标记，避免每次启动都白跑一遍 exists/canRead
            writeMarker(marker, "nothing to migrate")
            return
        }

        val migrated = runCatching {
            copyTree(source, target, depth = 0)
            true
        }.onFailure {
            Log.w(TAG, "chajian migrate failed from ${source.absolutePath}", it)
        }.getOrDefault(false)

        if (migrated) {
            writeMarker(marker, "migrated")
            Log.i(TAG, "chajian migrated: ${source.absolutePath} -> ${target.absolutePath}")
        } else {
            Log.w(TAG, "chajian migrate failed; will retry next launch")
        }
    }

    /** 递归复制目录内容，逐文件容错；目标已存在则跳过（不覆盖新数据），内部点文件不搬 */
    private fun copyTree(src: File, dst: File, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = src.listFiles() ?: return
        dst.mkdirs()
        for (child in children) {
            val out = File(dst, child.name)
            if (child.isDirectory) {
                copyTree(child, out, depth + 1)
            } else if (child.isFile && !child.name.startsWith(".") && !out.exists()) {
                runCatching { child.copyTo(out, overwrite = false) }
                    .onFailure { Log.w(TAG, "chajian migrate skip ${child.absolutePath}: ${it.message}") }
            }
        }
    }

    private fun writeMarker(marker: File, note: String) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText("$note\n")
        }.onFailure { Log.w(TAG, "write migrate marker failed", it) }
    }
}
