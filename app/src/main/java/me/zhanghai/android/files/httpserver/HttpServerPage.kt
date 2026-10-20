package me.zhanghai.android.files.httpserver

import java.util.Locale

internal object HttpServerPage {
    data class Entry(val name: String, val directory: Boolean, val size: Long)

    fun render(segments: List<String>, entries: List<Entry>, writable: Boolean): String {
        val path = "/" + segments.joinToString("/")
        val rows = buildString {
            if (segments.isNotEmpty()) {
                append("<li><a href=\"")
                append(HttpServerPaths.url(segments.dropLast(1), true))
                append("\">↰ 上一级</a></li>")
            }
            for (entry in entries) {
                append("<li><a href=\"")
                append(HttpServerPaths.url(segments + entry.name, entry.directory))
                append("\">")
                append(escape(entry.name))
                if (entry.directory) {
                    append('/')
                }
                append("</a><span>")
                append(if (entry.directory) "文件夹" else formatSize(entry.size))
                append("</span></li>")
            }
            if (entries.isEmpty()) {
                append("<li class=\"empty\">此文件夹为空</li>")
            }
        }
        val upload = if (writable) """
            <section aria-label="上传文件">
              <p>上传到当前文件夹。同名文件不会被覆盖。</p>
              <input id="files" type="file" multiple aria-label="选择文件">
              <button id="upload" type="button">上传</button>
              <p id="status" role="status" aria-live="polite"></p>
              <progress id="progress" max="100" value="0" hidden></progress>
            </section>
            <script>
            const files = document.getElementById('files');
            const button = document.getElementById('upload');
            const status = document.getElementById('status');
            const progress = document.getElementById('progress');
            button.addEventListener('click', async () => {
              if (!files.files.length) {
                status.textContent = '请先选择文件';
                return;
              }
              button.disabled = files.disabled = true;
              progress.hidden = false;
              try {
                for (const file of files.files) {
                  status.textContent = '正在上传：' + file.name;
                  progress.value = 0;
                  await new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();
                    xhr.open('PUT', location.pathname + encodeURIComponent(file.name));
                    xhr.upload.onprogress = event => {
                      if (event.lengthComputable) progress.value = event.loaded / event.total * 100;
                    };
                    xhr.onload = () => xhr.status === 201 ? resolve() :
                      reject(new Error(xhr.responseText || '上传失败（' + xhr.status + '）'));
                    xhr.onerror = () => reject(new Error('连接中断，请检查服务器状态'));
                    xhr.send(file);
                  });
                }
                location.reload();
              } catch (error) {
                status.textContent = error.message;
              } finally {
                button.disabled = files.disabled = false;
                progress.hidden = true;
              }
            });
            </script>
        """.trimIndent() else "<p class=\"empty\">只读模式</p>"
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${escape(path)} · Material Files</title>
              <style>
                :root { color-scheme: light dark; font-family: system-ui, sans-serif; }
                body { max-width: 900px; margin: auto; padding: 24px; }
                header { color: #607d8b; font-weight: 600; }
                h1 { font-size: 1.5rem; overflow-wrap: anywhere; }
                ul { padding: 0; list-style: none; }
                li { display: flex; gap: 16px; align-items: center; border-bottom: 1px solid #8884; }
                a { color: #0086c3; padding: 14px 0; flex: 1; overflow-wrap: anywhere; }
                span, .empty { opacity: .7; }
                span { white-space: nowrap; font-size: .85rem; }
                .empty { padding: 14px 0; }
                section { border-top: 1px solid #8884; margin-top: 24px; }
                input { max-width: 100%; margin-bottom: 12px; }
                button { padding: 8px 24px; cursor: pointer; }
                progress { width: 100%; }
                #status { overflow-wrap: anywhere; }
              </style>
            </head>
            <body>
              <header>Material Files · HTTP</header>
              <h1>${escape(path)}</h1>
              <ul>$rows</ul>
              $upload
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun formatSize(size: Long): String {
        if (size < 1024) {
            return "$size B"
        }
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
