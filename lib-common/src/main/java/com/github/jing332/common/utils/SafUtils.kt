package com.github.jing332.common.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.github.jing332.common.utils.FileUtils.getAllFilesInFolder
import com.github.jing332.common.utils.FileUtils.mimeType
import java.io.File
import java.net.URLConnection

/**
 * SAF（Storage Access Framework）音频条目工具 —— BGM 曲目的统一读取入口。
 *
 * 背景：自 Android 11 起应用不再持有「所有文件访问权限(MANAGE_EXTERNAL_STORAGE)」，
 * `/storage/emulated/0/...` 这类绝对路径无法直接读。因此 BGM 曲目现在统一落库为
 * SAF `content://` URI（持久读授权由 FilePickerActivity 在选取时调用
 * takePersistableUriPermission 落盘，重启后依然有效）。
 *
 * 历史上已落库的**绝对路径**继续按 `java.io.File` 处理，旧配置在 Android 10 及以下仍可用。
 *
 * 目录列举直接用 [DocumentsContract] 查询子文档（每个目录 1 次 query），
 * 不用 DocumentFile 逐个查询，避免大目录下 N 次跨进程往返。
 */
object SafUtils {
    private const val SCHEME_CONTENT = "content://"

    /** 目录树 URI 的路径段：content://.../tree/<docId> */
    private const val SEGMENT_TREE = "tree"

    /** 递归下钻深度上限：异常 Provider 返回环状目录时不至于无限查下去 */
    private const val MAX_DEPTH = 12

    /** 兜底音频扩展名（Provider 无 MIME、系统扩展名表也查不到时使用） */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "mp2", "mpga", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus",
        "wav", "wave", "wma", "amr", "awb", "mid", "midi", "ape", "aif", "aiff",
        "3gp", "mka", "ac3", "dts"
    )

    /** 是否为 SAF URI（历史数据里的绝对路径返回 false） */
    fun isSafUri(entry: String?): Boolean = entry?.startsWith(SCHEME_CONTENT) == true

    /**
     * 是否为「目录树」URI（OpenDocumentTree 的返回值）。
     * 用 pathSegments 判断而不调用 `DocumentsContract.isTreeUri`（API 24+），
     * 这样 minSdk 21 也能用。
     */
    fun isTreeUri(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == SEGMENT_TREE

    /**
     * 展示用短名称（列表项 / 通知 / 播放器标题）。
     *
     * - 目录树 URI：由 docId 直接推导（无 IO）
     * - 单文件 URI：查询 [OpenableColumns.DISPLAY_NAME]（MediaStore 的 documentId 是无意义数字，只能查）
     * - 历史绝对路径：原样返回（保持旧 UI 行为）
     */
    fun displayName(context: Context, entry: String): String {
        if (!isSafUri(entry)) return normalizeLegacyPath(entry)
        val uri = runCatching { Uri.parse(entry) }.getOrNull() ?: return entry
        if (isTreeUri(uri)) return treeDisplayName(entry)

        val queried = runCatching { queryDisplayName(context.contentResolver, uri) }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: lastSegmentFallback(entry)
    }

    /**
     * 解析一个 BGM 条目为可播放音频列表（**在 IO 线程调用**：含跨进程查询）。
     *
     * - `content://` 目录树：递归列举其下所有音频
     * - `content://` 单文件：本身是音频则返回自身
     * - 绝对路径：沿用旧的 File 语义
     */
    fun listAudio(context: Context, entry: String): List<SafAudio> {
        val resolver = context.contentResolver

        if (!isSafUri(entry)) {
            // ── 历史绝对路径（Android 10 及以下 / 应用私有目录仍可读）
            // 个别文件管理器/Provider 会回 file:// URI，先归一成绝对路径
            return File(normalizeLegacyPath(entry)).let { file ->
                val files = if (file.isFile) listOf(file) else getAllFilesInFolder(file)
                files.mapNotNull { f ->
                    val mime = f.mimeType
                    if (!isAudio(f.name, mime)) return@mapNotNull null
                    SafAudio(uriString = f.absolutePath, name = f.name, mime = mime)
                }
            }
        }

        val uri = runCatching { Uri.parse(entry) }.getOrNull() ?: return emptyList()

        if (isTreeUri(uri)) {
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return emptyList()
            val result = mutableListOf<SafAudio>()
            collectTreeAudio(resolver, uri, rootId, 0, result)
            return result
        }

        // 单文件：本身是音频才可播。若 MIME 与扩展名都判不出来（个别 Provider 的 docId 完全无信息），
        // 因为是用户明确选中的单个文件，这里放行交给 ExoPlayer 尝试，失败会被 onPlayerError 跳过。
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val name = runCatching { queryDisplayName(resolver, uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: lastSegmentFallback(entry)
        if (isAudio(name, mime)) return listOf(SafAudio(uriString = entry, name = name, mime = mime))
        val noInfo = mime == null && name.substringAfterLast('.', "").isEmpty()
        return if (noInfo) listOf(SafAudio(uriString = entry, name = name, mime = null))
        else emptyList()
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private fun collectTreeAudio(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        depth: Int,
        out: MutableList<SafAudio>,
    ) {
        if (depth > MAX_DEPTH) return
        for (child in queryChildren(resolver, treeUri, parentDocId)) {
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                collectTreeAudio(resolver, treeUri, child.docId, depth + 1, out)
            } else if (isAudio(child.name, child.mime)) {
                out.add(
                    SafAudio(
                        // 子文档 URI 由树 URI + docId 派生，权限随树的持久授权一起生效
                        uriString = DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, child.docId).toString(),
                        name = child.name,
                        mime = child.mime,
                    )
                )
            }
        }
    }

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
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2)
                    out.add(DocEntry(docId, name, mime))
                }
            }
        }.onFailure { it.printStackTrace() }
        return out
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return null
    }

    /** 目录树 URI → 去掉卷前缀的目录路径，如 `content://.../tree/primary%3AMusic%2FSub` → `Music/Sub` */
    private fun treeDisplayName(entry: String): String {
        val decoded = runCatching { Uri.decode(entry) }.getOrDefault(entry)
        return decoded.substringAfterLast("/$SEGMENT_TREE/").substringAfter(':')
    }

    /** `file:///storage/x.mp3` → `/storage/x.mp3`；非 file:// 字符串原样返回 */
    private fun normalizeLegacyPath(entry: String): String =
        if (entry.startsWith("file://"))
            runCatching { Uri.parse(entry).path }.getOrNull()?.takeIf { it.isNotBlank() } ?: entry
        else entry

    /** 查询失败时的最后兜底：取 URI 末段（如 `.../document/audio%3A123` → `123`） */
    private fun lastSegmentFallback(entry: String): String {
        val decoded = runCatching { Uri.decode(entry) }.getOrDefault(entry)
        return decoded.substringAfterLast('/').substringAfterLast(':')
    }

    private fun isAudio(name: String, mime: String?): Boolean {
        if (mime != null) {
            if (mime.startsWith("audio")) return true
            if (mime == "application/ogg") return true // 部分 Provider 对 ogg/opus 如此上报
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext in AUDIO_EXTENSIONS) return true
        val byMap = runCatching { URLConnection.getFileNameMap().getContentTypeFor(name) }
            .getOrNull()
        return byMap?.startsWith("audio") == true
    }

    private data class DocEntry(val docId: String, val name: String, val mime: String?)
}

/**
 * 一条可播放的 BGM 音频。
 *
 * @param uriString SAF `content://` URI 字符串，或历史数据的绝对路径
 */
data class SafAudio(val uriString: String, val name: String, val mime: String?)
