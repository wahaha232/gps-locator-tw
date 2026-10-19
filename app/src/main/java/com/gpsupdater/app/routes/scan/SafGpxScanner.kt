package com.gpsupdater.app.routes.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 沒有「所有檔案存取權」時的備案：使用者用系統的資料夾選擇器選一個資料夾，
 * 我們只掃描那個資料夾（含子資料夾）。不需要任何額外權限。
 */
object SafGpxScanner {
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private data class Node(val documentId: String, val path: String, val depth: Int)

    fun scan(
        context: Context,
        treeUri: Uri,
        isCancelled: () -> Boolean = { false },
        onProgress: (ScanProgress) -> Unit = {},
        onFound: (GpxFileEntry) -> Unit,
    ): ScanProgress {
        val resolver = context.contentResolver
        val stack = ArrayDeque<Node>()
        stack.addLast(Node(DocumentsContract.getTreeDocumentId(treeUri), "", 0))
        var directories = 0
        var found = 0
        var limitReached = false

        while (stack.isNotEmpty() && !limitReached && !isCancelled()) {
            val node = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
            val cursor = runCatching { resolver.query(children, PROJECTION, null, null, null) }.getOrNull() ?: continue
            directories++
            if (directories % 10 == 0) onProgress(ScanProgress(directories, found, false))
            cursor.use {
                while (it.moveToNext() && !isCancelled()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1).orEmpty()
                    val mime = it.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (node.depth < GpxFileScanner.MAX_DEPTH && !name.startsWith(".")) {
                            stack.addLast(Node(id, if (node.path.isEmpty()) name else node.path + "/" + name, node.depth + 1))
                        }
                    } else if (name.endsWith(".gpx", ignoreCase = true)) {
                        val size = if (it.isNull(3)) 0L else it.getLong(3)
                        if (size <= 0L) continue
                        if (found >= GpxFileScanner.MAX_RESULTS) {
                            limitReached = true
                            break
                        }
                        found++
                        onFound(
                            GpxFileEntry(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                                name = name,
                                location = node.path.ifEmpty { "/" },
                                sizeBytes = size,
                                lastModifiedMs = if (it.isNull(4)) 0L else it.getLong(4),
                            ),
                        )
                    }
                }
            }
        }
        val summary = ScanProgress(directories, found, limitReached)
        onProgress(summary)
        return summary
    }
}
