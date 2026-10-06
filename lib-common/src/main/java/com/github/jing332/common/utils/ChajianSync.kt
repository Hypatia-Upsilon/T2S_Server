package com.github.jing332.common.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * chajian 数据目录与「用户用 SAF 指定的文件夹」之间的**双向同步**。
 *
 * ## 为什么是"同步"而不是"直接把 SAF 目录当数据根"
 * SAF 授权是 **URI 级**的：A11+ 上即便用户用 SAF 授权了 `Download/chajian`，
 * 也无法用 `java.io.File` 去读写该路径；而插件契约 `ttsrv.getFile()` 返回的正是 `File`
 * （脚本里 `File.readText/listFiles/delete` 大量使用）。所以：
 *
 * - **本地目录**（[ChajianDir.root] = `Android/data/<包名>/files/chajian`）始终是工作副本，
 *   app 与插件只操作它，零权限、契约不变；
 * - 用户指定的 SAF 目录是**同步目标**：可随时导入（拉取）或导出（推送），
 *   于是用户依然可以自由地把文件放进 `Download/chajian` 或从那里取走。
 *
 * ## 同步规则
 * - 按 **lastModified 新者胜**：远端更新则拉下来，本地更新则推上去，相同则跳过；
 * - **不传播删除**：两个方向都不删除"对面多出来的文件"，避免误删用户数据；
 * - 只导出非隐藏文件（跳过 `.` 开头的内部标记文件）；
 * - 单次同步文件数上限 [MAX_SYNC_FILES]，超出即截断并在结果里标注；
 * - 目录递归深度上限 [MAX_DEPTH]。
 */
object ChajianSync {
    private const val TAG = "ChajianSync"
    private const val PREFS = "chajian_sync"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_TREE_NAME = "tree_name"
    private const val KEY_GUIDE_SHOWN = "first_run_guide_shown"
    private const val KEY_AUTO_SYNC = "auto_sync"

    private const val MAX_SYNC_FILES = 5000
    private const val MAX_DEPTH = 12

    /**
     * 同步结果。
     * @param copied 实际传输的文件数
     * @param skipped 因对面不更旧而跳过的文件数
     * @param failed 出错的文件数（单个文件失败不影响其余）
     * @param truncated 是否因达到文件数上限而提前结束
     */
    data class Result(
        val copied: Int,
        val skipped: Int,
        val failed: Int,
        val truncated: Boolean,
    )

    // ─────────────────────────── 配置（用户选定的 SAF 目录） ───────────────────────────

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户选定的同步目录（tree URI）；持久授权由 FilePickerActivity / [setTree] 落盘 */
    fun treeUri(context: Context): Uri? =
        sp(context).getString(KEY_TREE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 同步目录展示名（文件管理器里显示的名字） */
    fun treeName(context: Context): String? = sp(context).getString(KEY_TREE_NAME, null)

    fun hasTree(context: Context): Boolean = treeUri(context) != null

    /**
     * 记住用户选定的目录并持久化授权（换目录时可直接覆盖）。
     * @param name 展示名，取自 URI 末段（如 `primary:Download/chajian` → `Download/chajian`）
     */
    fun setTree(context: Context, uri: Uri, name: String? = null) {
        runCatching { uri.grantReadWritePermission(context.contentResolver) }
            .onFailure { Log.w(TAG, "persist uri permission failed: ${it.message}") }

        sp(context).edit()
            .putString(KEY_TREE_URI, uri.toString())
            .putString(KEY_TREE_NAME, name ?: defaultTreeName(uri))
            .apply()
    }

    fun clearTree(context: Context) {
        sp(context).edit().remove(KEY_TREE_URI).remove(KEY_TREE_NAME).apply()
    }

    /** 首次引导是否已展示过（避免反复打扰） */
    fun isFirstRunGuideShown(context: Context): Boolean =
        sp(context).getBoolean(KEY_GUIDE_SHOWN, false)

    fun markFirstRunGuideShown(context: Context) {
        sp(context).edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
    }

    /**
     * 自动同步开关：开启后
     * - **启动时**自动导入一次（把同步目录里更新的文件拉下来）；
     * - **整个应用退到后台时**自动导出一次（把本地较新的文件推上去）。
     *
     * 默认关闭：同步要遍历目录树，交给用户自己决定是否接受这份后台开销。
     */
    fun isAutoSyncEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_SYNC, false)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    /** 自动同步是否真的能跑：开关已开 **且** 已选定同步目录 */
    fun canAutoSync(context: Context): Boolean = isAutoSyncEnabled(context) && hasTree(context)

    // ─────────────────────────── 自动同步入口（供 App 生命周期调用） ───────────────────────────

    /** 同一时刻只允许一个自动同步在跑（导入/导出共用），避免生命周期抖动叠加 */
    private val autoSyncRunning = AtomicBoolean(false)

    /**
     * 启动时自动导入；未开启自动同步或未选目录时返回 null（调用方无需判断）。
     * **需在 IO 线程调用。**
     */
    fun autoImport(context: Context): Result? {
        if (!canAutoSync(context)) return null
        val uri = treeUri(context) ?: return null
        if (!autoSyncRunning.compareAndSet(false, true)) return null
        return try {
            importFromTree(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "auto import failed: ${e.message}")
            null
        } finally {
            autoSyncRunning.set(false)
        }
    }

    /**
     * 退到后台时自动导出；未开启自动同步或未选目录时返回 null。
     * **需在 IO 线程调用。**
     */
    fun autoExport(context: Context): Result? {
        if (!canAutoSync(context)) return null
        val uri = treeUri(context) ?: return null
        if (!autoSyncRunning.compareAndSet(false, true)) return null
        return try {
            exportToTree(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "auto export failed: ${e.message}")
            null
        } finally {
            autoSyncRunning.set(false)
        }
    }

    /**
     * 是否需要弹「首次引导」：还没设过同步目录、没弹过、且本地副本是空的
     * （本地已有数据说明用户已经在用了，没必要打扰）。
     */
    fun shouldShowFirstRunGuide(context: Context): Boolean {
        if (hasTree(context)) return false
        if (isFirstRunGuideShown(context)) return false
        return ChajianDir.root.listFiles()?.none { !it.name.startsWith(".") } != false
    }

    /** `content://…/tree/primary%3ADownload%2Fchajian` → `Download/chajian` */
    fun defaultTreeName(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.lastPathSegment.orEmpty()
        // 去掉卷前缀（primary: / 0000-0000:）
        return docId.substringAfter(':').ifBlank { docId }
    }

    // ─────────────────────────── 导入（SAF 目录 → 本地工作副本） ───────────────────────────

    /**
     * 把同步目录里的文件拉进本地工作副本。**需在 IO 线程调用。**
     * 远端比本地新（或本地不存在）才复制；不动本地多出来的文件。
     */
    fun importFromTree(context: Context, treeUri: Uri): Result {
        val resolver = context.contentResolver
        val target = ChajianDir.root
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return Result(0, 0, 0, false)

        var copied = 0
        var skipped = 0
        var failed = 0
        val counter = Counter()

        fun walk(parentDocId: String, relDir: String, depth: Int) {
            if (depth > MAX_DEPTH || counter.truncated) return
            for (child in queryChildren(resolver, treeUri, parentDocId)) {
                if (counter.value >= MAX_SYNC_FILES) {
                    counter.truncated = true
                    return
                }
                val rel = if (relDir.isEmpty()) child.name else "$relDir/${child.name}"
                if (child.isDir) {
                    walk(child.docId, rel, depth + 1)
                    continue
                }
                if (child.name.isBlank()) continue

                counter.value++
                val local = File(target, rel)
                if (local.exists() && local.lastModified() >= child.lastModified) {
                    skipped++
                    continue
                }
                val ok = runCatching {
                    local.parentFile?.mkdirs()
                    resolver.openInputStream(child.uri)?.use { ins ->
                        local.outputStream().use { outs -> ins.copyTo(outs) }
                    } ?: return@runCatching false
                    if (child.lastModified > 0L) local.setLastModified(child.lastModified)
                    true
                }.onFailure {
                    Log.w(TAG, "import failed: $rel (${it.message})")
                }.getOrDefault(false)

                if (ok) copied++ else failed++
            }
        }

        walk(rootId, "", 0)
        Log.i(TAG, "import done: copied=$copied skipped=$skipped failed=$failed truncated=${counter.truncated}")
        return Result(copied, skipped, failed, counter.truncated)
    }

    // ─────────────────────────── 导出（本地工作副本 → SAF 目录） ───────────────────────────

    /**
     * 把本地工作副本推送到同步目录（缺失的目录/文件会创建）。
     * **需在 IO 线程调用**，同样按 lastModified 比对新者胜，且不删除远端多余文件。
     */
    fun exportToTree(context: Context, treeUri: Uri): Result {
        val resolver = context.contentResolver
        val source = ChajianDir.root
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return Result(0, 0, 0, false)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)

        var copied = 0
        var skipped = 0
        var failed = 0
        val counter = Counter()

        fun walk(localDir: File, remoteDirUri: Uri, depth: Int) {
            if (depth > MAX_DEPTH || counter.truncated) return
            val remoteDirId = runCatching { DocumentsContract.getDocumentId(remoteDirUri) }
                .getOrNull() ?: return
            val remoteChildren = queryChildren(resolver, treeUri, remoteDirId)

            for (child in localDir.listFiles().orEmpty()) {
                if (counter.value >= MAX_SYNC_FILES) {
                    counter.truncated = true
                    return
                }
                // 跳过内部标记文件（.migrated_from_public 等），不往用户目录里塞垃圾
                if (child.name.startsWith(".")) continue

                val existing = remoteChildren.firstOrNull { it.name == child.name }

                if (child.isDirectory) {
                    val dirUri = when {
                        existing == null -> runCatching {
                            DocumentsContract.createDocument(
                                resolver, remoteDirUri,
                                DocumentsContract.Document.MIME_TYPE_DIR, child.name
                            )
                        }.getOrNull()

                        existing.isDir -> existing.uri
                        else -> null // 同名但类型不同：不覆盖，计失败
                    }
                    if (dirUri == null) {
                        failed++
                        continue
                    }
                    walk(child, dirUri, depth + 1)
                    continue
                }

                if (!child.isFile) continue

                counter.value++
                if (existing != null && !existing.isDir && existing.lastModified >= child.lastModified()) {
                    skipped++
                    continue
                }
                val fileUri = when {
                    existing != null && !existing.isDir -> existing.uri
                    existing == null -> runCatching {
                        DocumentsContract.createDocument(
                            resolver, remoteDirUri, mimeOf(child.name), child.name
                        )
                    }.getOrNull()

                    else -> null
                }
                if (fileUri == null) {
                    failed++
                    continue
                }

                val ok = runCatching {
                    // "wt" = 截断写入，保证覆盖后不留尾部旧内容
                    resolver.openOutputStream(fileUri, "wt")?.use { outs ->
                        child.inputStream().use { ins -> ins.copyTo(outs) }
                    } ?: return@runCatching false
                    true
                }.onFailure {
                    Log.w(TAG, "export failed: ${child.name} (${it.message})")
                }.getOrDefault(false)

                if (ok) copied++ else failed++
            }
        }

        walk(source, rootDocUri, 0)
        Log.i(TAG, "export done: copied=$copied skipped=$skipped failed=$failed truncated=${counter.truncated}")
        return Result(copied, skipped, failed, counter.truncated)
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private class Counter {
        var value = 0
        var truncated = false
    }

    private data class DocEntry(
        val docId: String,
        val name: String,
        val isDir: Boolean,
        val lastModified: Long,
        val uri: Uri,
    )

    private fun queryChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
    ): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = mutableListOf<DocEntry>()
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2)
                    val lastModified = cursor.getLong(3)
                    out.add(
                        DocEntry(
                            docId = docId,
                            name = name,
                            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            lastModified = lastModified,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "query children failed: ${it.message}") }
        return out
    }

    /** 仅用于 createDocument 的 MIME 提示；ExternalStorageProvider 主要按扩展名建文件 */
    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "js" -> "application/javascript"
        "txt", "md", "csv" -> "text/plain"
        "html" -> "text/html"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
