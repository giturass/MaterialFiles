package me.zhanghai.android.files.httpserver

import java.util.Locale

internal object HttpServerPage {
    data class Entry(
        val name: String, val directory: Boolean, val size: Long, val modified: Long = 0
    )

    fun render(segments: List<String>, entries: List<Entry>, writable: Boolean): String {
        val path = "/" + segments.joinToString("/")
        val breadcrumbs = buildString {
            append("<a href=\"/\" data-directory-link>根目录</a>")
            for (index in segments.indices) {
                append("<span aria-hidden=\"true\">/</span><a data-directory-link href=\"")
                append(HttpServerPaths.url(segments.take(index + 1), true))
                append("\">")
                append(escape(segments[index]))
                append("</a>")
            }
        }
        val rows = entries.joinToString("\n") { entry ->
            val url = HttpServerPaths.url(segments + entry.name, entry.directory)
            val mime = previewMime(entry.name).takeUnless { entry.directory }.orEmpty()
            val preview = when {
                mime.startsWith("image/") -> "image"
                mime.isNotEmpty() -> "text"
                else -> ""
            }
            val linkAttribute = when {
                entry.directory -> "data-directory-link"
                preview.isNotEmpty() -> "data-preview-link"
                else -> "download"
            }
            """
                <tr class="file-row" data-name="${escape(entry.name)}" data-directory="${entry.directory}"
                    data-size="${entry.size}" data-modified="${entry.modified}" data-url="$url"
                    data-preview="$preview" data-mime="$mime">
                  <td class="select-cell"><input class="entry-select" type="checkbox" aria-label="选择 ${escape(entry.name)}"></td>
                  <td class="file-cell">
                    <span class="file-icon ${if (entry.directory) "folder" else preview}" aria-hidden="true">${icon(if (entry.directory) "folder" else preview)}</span>
                    <div class="file-label">
                      <a class="entry-link" href="$url" $linkAttribute title="${escape(entry.name)}">${escape(entry.name)}</a>
                      <time class="entry-modified" data-time="${entry.modified}">—</time>
                    </div>
                  </td>
                  <td class="size-cell">${if (entry.directory) "—" else formatSize(entry.size)}</td>
                </tr>
            """.trimIndent()
        }
        val folderCount = entries.count { it.directory }
        val writeButtons = if (writable) """
            <button id="mkdir" type="button">${icon("new-folder")}新建文件夹</button>
            <button id="upload" class="primary" type="button">${icon("upload")}上传文件</button>
            <input id="files" type="file" multiple hidden>
        """.trimIndent() else ""
        val sidePanel = if (writable) """
            <aside class="queue-panel panel" aria-label="上传队列">
              <div class="panel-heading"><h2>上传队列</h2><span id="queue-count" class="badge">0</span></div>
              <button id="dropzone" class="dropzone" type="button">
                <span class="drop-icon">${icon("upload")}</span>
                <strong>拖放文件到这里</strong><span>或点击选择多个文件</span>
              </button>
              <p class="hint">上传到当前文件夹，同名文件不会被覆盖。</p>
              <div class="queue-toolbar"><span id="queue-summary" role="status">还没有上传任务</span><button id="clear-queue" type="button" disabled>清除已结束</button></div>
              <ol id="queue" class="queue-list" aria-label="文件上传进度"></ol>
              <div id="queue-empty" class="queue-empty">选择文件后自动排队上传<br>可以继续浏览其他文件夹</div>
            </aside>
        """.trimIndent() else """
            <aside class="panel read-only-panel"><span class="drop-icon">${icon("folder")}</span>
              <h2>只读模式</h2><p>可以浏览、下载文件，也可以直接预览文本和图片。</p>
              <p class="hint">如需管理文件，请在手机上开启服务器的写入权限。</p>
            </aside>
        """.trimIndent()
        val managementDialog = if (writable) """
            <dialog id="manage-dialog" aria-labelledby="manage-title">
              <form id="manage-form">
                <h2 id="manage-title">新建文件夹</h2><p id="manage-description" class="hint"></p>
                <label id="name-label" for="entry-name">名称</label>
                <input id="entry-name" name="name" autocomplete="off" spellcheck="false" required>
                <p id="manage-error" class="error" role="alert"></p>
                <div class="dialog-actions"><button id="manage-cancel" type="button">取消</button><button id="manage-confirm" class="primary" type="submit">创建</button></div>
              </form>
            </dialog>
        """.trimIndent() else ""
        val writeSelectionActions = if (writable) """
            <button id="selection-copy" type="button" disabled>${icon("copy")}<span>复制</span></button>
            <button id="selection-cut" type="button" disabled>${icon("cut")}<span>剪切</span></button>
            <button id="selection-paste" type="button" disabled>${icon("paste")}<span>粘贴</span></button>
            <button id="selection-rename" type="button" disabled>${icon("rename")}<span>重命名</span></button>
            <button id="selection-delete" class="danger-quiet" type="button" disabled>${icon("delete")}<span>删除</span></button>
        """.trimIndent() else ""
        val clipboardPanel = if (writable) """
            <div id="clipboard-panel" class="clipboard-panel" hidden><span id="clipboard-status" role="status"></span><button id="clipboard-clear" type="button">取消</button></div>
        """.trimIndent() else ""
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="color-scheme" content="light dark">
              <title>${escape(path)} · Material Files</title>
              <style>${HttpServerPageAssets.style}</style>
            </head>
            <body data-writable="$writable">
              <header class="topbar"><div class="brand"><span class="brand-icon">${icon("folder")}</span><div>Material Files<small>局域网文件管理</small></div></div>
                <div class="connection"><span class="connection-dot"></span>HTTP <span class="mode">${if (writable) "可读写" else "只读模式"}</span></div>
              </header>
              <main>
                <section class="intro"><h1>文件管理</h1><div class="main-actions">$writeButtons</div></section>
                <div id="notice" class="notice" role="status" aria-live="polite" hidden></div>
                <div class="workspace">
                  <section class="panel files-panel" aria-label="文件浏览器">
                    <div class="toolbar"><label class="search">${icon("search")}<input id="search" type="search" placeholder="搜索当前文件夹" aria-label="搜索当前文件夹"></label>
                      <select id="sort" aria-label="排序方式"><option value="name">名称 A–Z</option><option value="name-desc">名称 Z–A</option><option value="modified">最近修改</option><option value="size">大小从大到小</option></select>
                      <button id="refresh" type="button" aria-label="刷新文件列表" title="刷新">${icon("refresh")}</button>
                    </div>
                    <div id="browser" data-path="${HttpServerPaths.url(segments, true)}">
                      <div class="location-bar"><nav class="breadcrumbs" aria-label="当前路径">$breadcrumbs</nav>
                        ${if (segments.isNotEmpty()) "<a class=\"parent-link\" data-directory-link href=\"${HttpServerPaths.url(segments.dropLast(1), true)}\">↑ 上一级</a>" else ""}
                      </div>
                      <div class="list-summary"><span>$folderCount 个文件夹 · ${entries.size - folderCount} 个文件</span><span id="filter-count"></span></div>
                      <div class="table-wrap"><table><thead><tr><th scope="col" class="select-cell"><span class="sr-only">选择</span></th><th scope="col">名称</th><th scope="col" class="size-cell">大小</th></tr></thead><tbody id="entries">$rows</tbody></table></div>
                      <div id="empty" class="empty" ${if (entries.isNotEmpty()) "hidden" else ""}>${icon("folder")}<strong>此文件夹为空</strong><span>${if (writable) "上传文件或新建一个文件夹，开始整理吧" else "这里还没有文件"}</span></div>
                      <div id="no-results" class="empty" hidden><strong>没有匹配的文件</strong><span>试试其他名称</span></div>
                    </div>
                  </section>
                  $sidePanel
                </div>
                <footer>Material Files <span>·</span> 通过局域网连接设备</footer>
              </main>
              <div class="action-bar" role="region" aria-label="文件操作">
                <div class="selection-info"><label class="select-all-label"><input id="select-all" type="checkbox" aria-label="全选当前可见项目"><span>全选</span></label><span id="selection-count" role="status">未选择</span><button id="selection-clear" type="button" disabled>取消选择</button></div>
                <div class="selection-actions" role="group" aria-label="所选项目操作">
                  $writeSelectionActions
                  <button id="selection-download" type="button" disabled>${icon("download")}<span>下载</span></button>
                </div>
                $clipboardPanel
              </div>
              $managementDialog
              <dialog id="preview-dialog" class="preview-dialog" aria-labelledby="preview-title">
                <div class="preview-heading"><div><h2 id="preview-title">文件预览</h2><p id="preview-meta" class="hint"></p></div><button id="preview-close" type="button" aria-label="关闭预览">关闭</button></div>
                <div id="preview-content" class="preview-content"><p id="preview-status" role="status">正在加载…</p><img id="preview-image" alt="" hidden><pre id="preview-text" tabindex="0" hidden></pre></div>
                <div class="preview-footer"><span id="preview-note" class="hint"></span><a id="preview-download" class="button primary" download>下载原文件</a></div>
              </dialog>
              <noscript><p class="notice">启用 JavaScript 后即可管理、上传和预览文件。仍可点击文件链接下载。</p></noscript>
              <script>${HttpServerPageAssets.script}</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun previewMime(name: String): String {
        val lowerName = name.lowercase(Locale.ROOT)
        return when (lowerName.substringAfterLast('.', "")) {
            "png" -> "image/png"
            "jpg", "jpeg", "jfif" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "ico" -> "image/x-icon"
            "txt", "md", "markdown", "log", "json", "jsonl", "xml", "csv", "tsv", "yaml", "yml",
            "ini", "conf", "config", "properties", "toml", "html", "htm", "css", "js", "mjs",
            "ts", "tsx", "jsx", "java", "kt", "kts", "py", "sh", "c", "h", "cpp", "hpp", "rs",
            "go", "sql", "svg", "bat", "ps1", "gradle", "gitignore", "env" -> "text/plain"
            else -> if (lowerName in listOf("readme", "license", "notice", "makefile", "dockerfile")) {
                "text/plain"
            } else ""
        }
    }

    private fun icon(kind: String): String {
        val shape = when (kind) {
            "folder", "new-folder" -> "<path d=\"M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10H3Z\"/>" +
                if (kind == "new-folder") "<path d=\"M9 13h6m-3-3v6\"/>" else ""
            "image" -> "<rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"3\"/><circle cx=\"8\" cy=\"8\" r=\"1\"/><path d=\"m3 17 6-6 4 4 3-3 5 5\"/>"
            "upload" -> "<path d=\"M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5\"/>"
            "search" -> "<circle cx=\"10\" cy=\"10\" r=\"6\"/><path d=\"m15 15 5 5\"/>"
            "refresh" -> "<path d=\"M20 7v5h-5M4 17v-5h5\"/><path d=\"M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1\"/>"
            "copy" -> "<rect x=\"8\" y=\"8\" width=\"12\" height=\"13\" rx=\"2\"/><path d=\"M15 8V3H3v13h5\"/>"
            "cut" -> "<circle cx=\"6\" cy=\"6\" r=\"3\"/><circle cx=\"6\" cy=\"18\" r=\"3\"/><path d=\"m8 8 12 13M8 16 20 3\"/>"
            "paste" -> "<path d=\"M9 5H5v16h14V5h-4\"/><rect x=\"9\" y=\"3\" width=\"6\" height=\"4\" rx=\"1\"/><path d=\"M8 12h8m-8 4h6\"/>"
            "rename" -> "<path d=\"m14 5 5 5M4 20l5-1L21 7l-5-5L4 14Z\"/>"
            "delete" -> "<path d=\"M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7\"/>"
            "download" -> "<path d=\"M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4\"/>"
            else -> "<path d=\"M13 3H5v18h14V9ZM13 3v6h6M8 13h8m-8 4h6\"/>"
        }
        return "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">$shape</svg>"
    }

    private fun escape(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun formatSize(size: Long): String {
        if (size < 1024) return "$size B"
        var value = size.toDouble()
        val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            ++unit
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
