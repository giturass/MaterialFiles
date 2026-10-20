package me.zhanghai.android.files.httpserver

/** Self-contained assets: the management page works without an Internet connection. */
internal object HttpServerPageAssets {
    val style = """
        :root {
          color-scheme: light dark; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
          --bg: #f4f6fa; --surface: #fff; --soft: #f7f9fc; --text: #253049; --muted: #6b768c;
          --line: #e5eaf2; --accent: #3865db; --accent-soft: #edf2ff; --danger: #c43e4c;
          --green: #23856c; --shadow: 0 8px 32px #23365c06;
          background: var(--bg); color: var(--text); font-size: 14px;
        }
        * { box-sizing: border-box; }
        [hidden] { display: none !important; }
        body { margin: 0; }
        button, input, select { font: inherit; }
        button, .button, select { min-height: 38px; border: 1px solid var(--line); border-radius: 9px; background: var(--surface); color: var(--text); }
        button, .button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding: 8px 13px; cursor: pointer; text-decoration: none; white-space: nowrap; }
        button:hover, .button:hover { background: var(--accent-soft); color: var(--accent); border-color: var(--accent); }
        button:disabled { opacity: .5; cursor: default; }
        a { color: var(--accent); text-decoration: none; }
        a:hover { text-decoration: underline; }
        button:focus-visible, a:focus-visible, input:focus-visible, select:focus-visible, pre:focus-visible { outline: 3px solid var(--accent); outline-offset: 3px; }
        svg { width: 20px; height: 20px; flex: none; vertical-align: middle; }
        .primary { background: var(--accent); color: white; border-color: var(--accent); }
        .primary:hover { background: var(--accent); color: white; filter: brightness(1.08); }
        .danger { background: var(--danger); border-color: var(--danger); color: white; }
        .danger:hover { background: var(--danger); color: white; }
        .danger-quiet, .error { color: var(--danger); }
        .topbar { height: 84px; padding: 0 max(28px, calc((100vw - 1440px) / 2)); display: flex; align-items: center; justify-content: space-between; gap: 20px; background: var(--surface); border-bottom: 1px solid var(--line); }
        .brand { display: flex; align-items: center; gap: 12px; font-size: 18px; font-weight: 700; letter-spacing: -.3px; }
        .brand-icon { display: grid; place-items: center; width: 42px; height: 42px; background: var(--accent); color: white; border-radius: 13px; }
        .brand-icon svg { width: 27px; height: 27px; }
        .brand small { display: block; margin-top: 3px; font-size: 11px; color: var(--muted); font-weight: 400; letter-spacing: 1px; }
        .connection { display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: 12px; }
        .connection-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--green); }
        .mode, .badge { border: 1px solid var(--line); border-radius: 6px; padding: 3px 7px; font-size: 12px; }
        .mode { margin-left: 12px; }
        main { max-width: 1496px; padding: 38px 28px 20px; margin: auto; }
        h1, h2, p { margin-top: 0; }
        h1 { font-size: 30px; margin-bottom: 10px; letter-spacing: -.7px; }
        h2 { font-size: 16px; margin-bottom: 0; }
        .intro { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 30px; }
        .eyebrow { color: var(--accent); font-size: 12px; letter-spacing: 2px; margin-bottom: 9px; }
        .subtitle { color: var(--muted); margin-bottom: 0; line-height: 1.7; }
        .main-actions { display: flex; gap: 10px; }
        .main-actions button { padding: 11px 17px; }
        .workspace { display: grid; grid-template-columns: minmax(0, 1fr) 310px; gap: 22px; align-items: start; }
        .panel { border: 1px solid var(--line); border-radius: 16px; background: var(--surface); box-shadow: var(--shadow); overflow: hidden; }
        .files-panel { min-height: 430px; }
        .toolbar { display: flex; align-items: center; gap: 10px; padding: 20px; border-bottom: 1px solid var(--line); }
        .search { flex: 1; min-width: 0; display: flex; align-items: center; gap: 9px; border-radius: 9px; padding: 0 12px; border: 1px solid var(--line); background: var(--soft); color: var(--muted); }
        .search input { width: 100%; min-width: 0; padding: 10px 0; border: 0; background: transparent; color: var(--text); outline-offset: 0; }
        input::placeholder { color: var(--muted); }
        select { padding: 8px; max-width: 150px; }
        .location-bar { display: flex; align-items: start; justify-content: space-between; gap: 12px; padding: 20px 20px 12px; }
        .breadcrumbs { display: flex; flex-wrap: wrap; gap: 7px; min-width: 0; line-height: 1.7; }
        .breadcrumbs a { color: var(--muted); overflow-wrap: anywhere; }
        .breadcrumbs a:last-child { color: var(--text); font-weight: 600; }
        .breadcrumbs span { color: var(--muted); opacity: .5; }
        .parent-link { flex: none; font-size: 12px; padding: 3px 0; }
        .list-summary { color: var(--muted); font-size: 12px; display: flex; gap: 12px; padding: 0 20px 18px; }
        .table-wrap { overflow-x: auto; }
        table { width: 100%; border-collapse: collapse; text-align: left; table-layout: fixed; }
        th { padding: 11px 12px; background: var(--soft); border-block: 1px solid var(--line); color: var(--muted); font-size: 11px; font-weight: 500; }
        th:first-child { padding-left: 20px; }
        th:nth-child(2) { width: 90px; }
        th:nth-child(3) { width: 130px; }
        th:last-child { width: 228px; }
        td { padding: 14px 12px; border-bottom: 1px solid var(--line); }
        tr:last-child td { border-bottom: 0; }
        .file-row:hover { background: var(--soft); }
        .file-cell { padding-left: 20px; }
        .file-icon { display: inline-grid; place-items: center; width: 38px; height: 42px; border-radius: 10px; background: var(--soft); color: var(--muted); vertical-align: middle; }
        .file-icon.folder { background: #fff5db; color: #b78a24; }
        .file-icon.image { background: #e7f6f0; color: #288769; }
        .file-icon.text { background: var(--accent-soft); color: var(--accent); }
        .file-label { display: inline-block; vertical-align: middle; width: calc(100% - 51px); margin-left: 9px; }
        .entry-link { color: var(--text); font-weight: 500; overflow-wrap: anywhere; line-height: 1.6; }
        .file-label small { display: block; color: var(--muted); font-size: 11px; margin-top: 2px; }
        .size-cell, .date-cell { font-size: 11px; color: var(--muted); font-variant-numeric: tabular-nums; }
        .actions { padding-right: 15px; }
        .actions > div { display: flex; justify-content: flex-end; gap: 3px; }
        .actions button, .actions .button { font-size: 11px; padding: 7px 6px; border-color: transparent; background: transparent; min-height: 34px; }
        .actions button:hover, .actions .button:hover { background: var(--accent-soft); }
        body[data-writable="false"] th:last-child { width: 112px; }
        .actions-heading { text-align: right; padding-right: 22px; }
        .empty { padding: 65px 20px; text-align: center; color: var(--muted); display: grid; justify-items: center; gap: 10px; }
        .empty svg { width: 46px; height: 46px; margin-bottom: 6px; color: var(--accent); opacity: .5; }
        .empty strong { color: var(--text); font-weight: 500; }
        .empty span { font-size: 12px; }
        .queue-panel { padding: 20px; }
        .panel-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
        .badge { background: var(--accent-soft); color: var(--accent); border: 0; }
        .dropzone { width: 100%; min-height: 168px; display: flex; flex-direction: column; border: 1px dashed #a8b9df; border-radius: 12px; background: var(--soft); gap: 8px; white-space: normal; }
        .dropzone > span:last-child { color: var(--muted); font-size: 12px; }
        .dropzone.dragging { background: var(--accent-soft); border-color: var(--accent); box-shadow: inset 0 0 0 1px var(--accent); }
        .drop-icon { display: inline-grid; place-items: center; width: 42px; height: 42px; background: var(--accent-soft); color: var(--accent); border-radius: 12px; margin-bottom: 4px; }
        .hint { color: var(--muted); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
        .queue-panel > .hint { margin: 12px 0 18px; }
        .queue-toolbar { display: flex; justify-content: space-between; gap: 5px; align-items: center; border-top: 1px solid var(--line); padding-top: 14px; color: var(--muted); font-size: 11px; }
        .queue-toolbar button { font-size: 11px; padding: 5px; border: 0; min-height: 30px; }
        .queue-list { list-style: none; padding: 0; margin: 0; max-height: 510px; overflow-y: auto; }
        .queue-item { padding: 16px 0; border-bottom: 1px solid var(--line); }
        .queue-item:last-child { border-bottom: 0; }
        .queue-name { font-size: 12px; font-weight: 600; overflow-wrap: anywhere; margin: 0 0 5px; }
        .queue-detail { font-size: 11px; color: var(--muted); overflow-wrap: anywhere; line-height: 1.6; }
        .queue-bottom { display: flex; gap: 8px; align-items: start; justify-content: space-between; margin-top: 6px; font-size: 11px; }
        .queue-status { overflow-wrap: anywhere; line-height: 1.7; }
        .queue-item[data-state="success"] .queue-status { color: var(--green); }
        .queue-item[data-state="error"] .queue-status { color: var(--danger); }
        .queue-bottom button { border: 0; font-size: 11px; padding: 0 4px; min-height: 24px; }
        progress { display: block; width: 100%; height: 5px; margin-top: 10px; border: 0; border-radius: 4px; overflow: hidden; accent-color: var(--accent); background: var(--line); }
        progress::-webkit-progress-bar { background: var(--line); }
        progress::-webkit-progress-value { background: var(--accent); }
        progress::-moz-progress-bar { background: var(--accent); }
        .queue-empty { padding: 30px 0 12px; text-align: center; color: var(--muted); font-size: 12px; line-height: 1.9; }
        .read-only-panel { padding: 24px; line-height: 1.8; }
        .read-only-panel h2 { margin: 12px 0; }
        .read-only-panel p { color: var(--muted); }
        .notice { padding: 13px 16px; margin-bottom: 20px; border-radius: 10px; background: var(--accent-soft); color: var(--accent); overflow-wrap: anywhere; }
        .notice.error { color: var(--danger); }
        footer { padding-top: 28px; color: var(--muted); font-size: 11px; text-align: center; }
        footer span { padding: 0 8px; }
        dialog { width: min(440px, calc(100% - 32px)); max-height: calc(100dvh - 32px); padding: 26px; border: 1px solid var(--line); border-radius: 18px; background: var(--surface); color: var(--text); box-shadow: 0 24px 100px #0003; }
        dialog::backdrop { background: #13213d80; backdrop-filter: blur(3px); }
        dialog h2 { font-size: 19px; margin-bottom: 14px; overflow-wrap: anywhere; }
        dialog label { display: block; margin-bottom: 8px; }
        #entry-name { width: 100%; padding: 11px; border: 1px solid var(--line); border-radius: 8px; background: var(--soft); color: var(--text); }
        #manage-error { min-height: 18px; font-size: 12px; margin: 12px 0; overflow-wrap: anywhere; }
        .dialog-actions { display: flex; justify-content: flex-end; gap: 10px; }
        .preview-dialog { width: min(1000px, calc(100% - 32px)); padding: 0; }
        .preview-heading { display: flex; align-items: start; justify-content: space-between; gap: 18px; padding: 20px 24px; border-bottom: 1px solid var(--line); }
        .preview-heading > div { min-width: 0; }
        .preview-heading h2 { margin-bottom: 5px; font-size: 16px; }
        .preview-heading p { margin: 0; }
        .preview-content { background: var(--soft); padding: 22px; overflow: auto; min-height: 200px; max-height: 65dvh; text-align: center; }
        #preview-image { max-width: 100%; max-height: 60dvh; object-fit: contain; vertical-align: middle; }
        #preview-text { margin: 0; text-align: left; white-space: pre-wrap; overflow-wrap: anywhere; tab-size: 4; font: 13px/1.75 ui-monospace, "SFMono-Regular", Consolas, monospace; }
        #preview-status { margin: 50px 0; color: var(--muted); }
        .preview-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 24px; }
        .preview-footer .hint { margin: 0; }
        @media (prefers-color-scheme: dark) {
          :root { --bg: #141923; --surface: #1d2431; --soft: #232c3c; --text: #e1e8f6; --muted: #9ba9c2; --line: #303c51; --accent: #9ab7ff; --accent-soft: #2a3958; --danger: #ff9da7; --green: #74d1b1; --shadow: none; }
          .primary, .primary:hover { color: #12264c; }
          .danger, .danger:hover { color: #481c22; }
          .brand-icon { color: #12264c; }
          .file-icon.folder { background: #423a27; color: #e4bd67; }
          .file-icon.image { background: #233e37; color: #78ccb0; }
          .dropzone { border-color: #52678f; }
        }
        @media (max-width: 1250px) { .date-cell { display: none; } th:last-child { width: 225px; } }
        @media (max-width: 1000px) { .workspace { grid-template-columns: minmax(0, 1fr); } .queue-list { max-height: 320px; } .dropzone { min-height: 135px; } .date-cell { display: table-cell; } }
        @media (max-width: 680px) {
          .topbar { height: 70px; padding: 0 18px; } .brand { font-size: 16px; } .brand-icon { width: 37px; height: 37px; } .connection { gap: 5px; } .mode { margin-left: 4px; }
          main { padding: 26px 14px 20px; } h1 { font-size: 26px; } .intro { align-items: stretch; flex-direction: column; gap: 20px; margin-bottom: 22px; } .main-actions button { flex: 1; } .subtitle { font-size: 12px; }
          .toolbar { padding: 14px; flex-wrap: wrap; gap: 8px; } .search { flex-basis: calc(100% - 50px); } #sort { order: 3; max-width: none; width: 100%; } #refresh { padding: 8px; }
          .location-bar { padding: 16px 14px 10px; } .list-summary { padding: 0 14px 14px; } .table-wrap { overflow: visible; }
          table, tbody { display: block; } thead { display: none; } .file-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; border-top: 1px solid var(--line); padding: 12px 14px 8px; }
          td { padding: 0; border: 0; } .file-cell { padding-left: 0; } .size-cell { padding: 12px 0 0 8px; } .date-cell { display: none; } .actions { grid-column: 1 / -1; padding: 6px 0 0 46px; } .actions > div { justify-content: flex-start; flex-wrap: wrap; gap: 8px; } .actions button, .actions .button { min-height: 36px; font-size: 12px; }
          .preview-heading, .preview-footer { padding: 16px; } .preview-content { padding: 15px; } .preview-footer { align-items: start; } .empty { padding: 45px 18px; }
        }
        @media (prefers-reduced-motion: no-preference) { button, .button { transition: background .15s, border-color .15s; } }
    """.trimIndent()

    val script = """
        'use strict';
        const byId = id => document.getElementById(id);
        const writable = document.body.dataset.writable === 'true';
        const queue = [];
        let uploading = false;
        let navigation = 0;
        let navigationController;
        let noticeTimer;
        const collator = new Intl.Collator('zh-CN', {numeric: true, sensitivity: 'base'});
        const currentPath = () => byId('browser').dataset.path;
        const activeTasks = () => queue.some(item => item.state === 'waiting' || item.state === 'uploading');

        function notify(message, error = false) {
          clearTimeout(noticeTimer);
          byId('notice').textContent = message;
          byId('notice').classList.toggle('error', error);
          byId('notice').hidden = false;
          if (!error) noticeTimer = setTimeout(() => { byId('notice').hidden = true; }, 6000);
        }
        function sizeLabel(size) {
          const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
          let unit = 0;
          while (size >= 1024 && unit < units.length - 1) { size /= 1024; ++unit; }
          return (unit ? size.toFixed(1) : size) + ' ' + units[unit];
        }
        function applyFilter() {
          const query = byId('search').value.toLocaleLowerCase();
          const sort = byId('sort').value;
          const rows = Array.from(document.querySelectorAll('.file-row'));
          rows.sort((a, b) => {
            const directory = Number(b.dataset.directory === 'true') - Number(a.dataset.directory === 'true');
            if (directory) return directory;
            const name = collator.compare(a.dataset.name, b.dataset.name);
            if (sort === 'size' || sort === 'modified') return Number(b.dataset[sort]) - Number(a.dataset[sort]) || name;
            return sort === 'name-desc' ? -name : name;
          });
          let visible = 0;
          for (const row of rows) {
            row.hidden = !row.dataset.name.toLocaleLowerCase().includes(query);
            if (!row.hidden) ++visible;
            byId('entries').append(row);
          }
          byId('empty').hidden = rows.length !== 0;
          byId('no-results').hidden = !rows.length || visible !== 0;
          byId('filter-count').textContent = query ? '找到 ' + visible + ' 项' : '';
          for (const time of document.querySelectorAll('[data-time]')) {
            const date = new Date(Number(time.dataset.time));
            if (Number(time.dataset.time) > 0 && !isNaN(date.getTime())) {
              time.textContent = date.toLocaleString('zh-CN', {year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'});
              time.dateTime = date.toISOString();
              time.title = date.toLocaleString();
            }
          }
        }
        async function loadDirectory(path, mode = 'refresh') {
          const ticket = ++navigation;
          if (navigationController) navigationController.abort();
          navigationController = new AbortController();
          byId('refresh').disabled = true;
          byId('browser').setAttribute('aria-busy', 'true');
          try {
            const response = await fetch(path, {signal: navigationController.signal, cache: 'no-store'});
            if (!response.ok) throw new Error(await response.text() || '无法读取文件夹');
            // A directory may have been replaced by an uploaded file since it was listed.
            if (!(response.headers.get('Content-Type') || '').toLowerCase().startsWith('text/html')) {
              throw new Error('目标已不是文件夹，请刷新上一级目录');
            }
            const page = new DOMParser().parseFromString(await response.text(), 'text/html');
            const browser = page.getElementById('browser');
            if (!browser) throw new Error('文件夹已不可用，请刷新页面重试');
            if (ticket !== navigation) return;
            const changed = currentPath() !== browser.dataset.path;
            byId('browser').replaceWith(browser);
            if (changed) byId('search').value = '';
            if (mode === 'push') history.pushState(null, '', browser.dataset.path);
            if (mode === 'pop') history.replaceState(null, '', browser.dataset.path);
            document.title = page.title;
            applyFilter();
          } catch (error) {
            if (ticket !== navigation || error.name === 'AbortError') return;
            if (mode === 'pop') history.replaceState(null, '', currentPath());
            notify('刷新失败：' + error.message, true);
          } finally {
            if (ticket === navigation) {
              byId('refresh').disabled = false;
              byId('browser').removeAttribute('aria-busy');
            }
          }
        }
        byId('search').addEventListener('input', applyFilter);
        byId('sort').addEventListener('change', applyFilter);
        byId('refresh').addEventListener('click', () => loadDirectory(currentPath()));
        window.addEventListener('popstate', () => loadDirectory(location.pathname, 'pop'));
        window.addEventListener('beforeunload', event => {
          if (activeTasks()) { event.preventDefault(); event.returnValue = ''; }
        });
        document.addEventListener('click', event => {
          if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
          const directory = event.target.closest('a[data-directory-link]');
          if (directory) { event.preventDefault(); loadDirectory(directory.getAttribute('href'), 'push'); return; }
          const control = event.target.closest('[data-action], [data-preview-link]');
          if (!control) return;
          const row = control.closest('.file-row');
          if (!row) return;
          event.preventDefault();
          const action = control.dataset.action || 'preview';
          if (action === 'preview') openPreview(row.dataset);
          else if (writable) openManagement(action, row.dataset);
        });

        let management;
        let managing = false;
        function openManagement(action, entry = {}) {
          management = {action, url: entry.url || currentPath(), name: entry.name || '', directory: entry.directory === 'true'};
          const deleting = action === 'delete';
          byId('manage-title').textContent = deleting ? '删除' + (management.directory ? '文件夹' : '文件') : action === 'rename' ? '重命名' : '新建文件夹';
          byId('manage-description').textContent = deleting ? '确定删除「' + entry.name + '」' + (management.directory ? '及其中的全部内容' : '') + '？此操作无法撤销。' : '名称不能包含 /、\\，也不能是 . 或 ..。同名项目不会被覆盖。';
          byId('name-label').hidden = deleting;
          byId('entry-name').hidden = byId('entry-name').disabled = deleting;
          byId('entry-name').value = action === 'rename' ? entry.name : '';
          byId('manage-error').textContent = '';
          byId('manage-confirm').textContent = deleting ? '确认删除' : action === 'rename' ? '保存' : '创建';
          byId('manage-confirm').className = deleting ? 'danger' : 'primary';
          byId('manage-dialog').showModal();
          if (deleting) byId('manage-cancel').focus();
          else { byId('entry-name').focus(); byId('entry-name').select(); }
        }
        function validName(name) { return name.trim().length > 0 && name !== '.' && name !== '..' && !/[\/\\\u0000-\u001f]/.test(name); }
        async function submitManagement(event) {
          event.preventDefault();
          if (managing) return;
          const item = management;
          const name = byId('entry-name').value;
          if (item.action !== 'delete' && !validName(name)) {
            byId('manage-error').textContent = '请输入有效名称，不要包含路径分隔符或控制字符。';
            return;
          }
          managing = true;
          byId('manage-confirm').disabled = byId('manage-cancel').disabled = true;
          byId('manage-error').textContent = '正在处理…';
          try {
            const query = item.action === 'delete' ? '' : '?action=' + item.action + '&name=' + encodeURIComponent(name);
            const response = await fetch(item.url + query, {method: item.action === 'delete' ? 'DELETE' : 'POST', headers: {'X-Material-Files': '1'}});
            const message = await response.text();
            if (!response.ok) throw new Error(message || '操作失败（' + response.status + '）');
            byId('manage-dialog').close();
            notify(message);
            await loadDirectory(currentPath());
          } catch (error) {
            byId('manage-error').textContent = error.message + (item.action === 'delete' ? ' 文件夹可能已部分删除，请刷新查看。' : '');
          } finally {
            managing = false;
            byId('manage-confirm').disabled = byId('manage-cancel').disabled = false;
          }
        }

        function updateQueueSummary() {
          const done = queue.filter(item => item.state === 'success').length;
          const pending = queue.filter(item => item.state === 'waiting' || item.state === 'uploading').length;
          byId('queue-count').textContent = queue.length;
          const failed = queue.filter(item => item.state === 'error').length;
          const cancelled = queue.filter(item => item.state === 'cancelled').length;
          byId('queue-summary').textContent = queue.length ? done + ' 完成 · ' + pending + ' 进行中' + (failed ? ' · ' + failed + ' 失败' : '') + (cancelled ? ' · ' + cancelled + ' 取消' : '') : '还没有上传任务';
          byId('queue-empty').hidden = queue.length !== 0;
          byId('clear-queue').disabled = !queue.some(item => item.state !== 'waiting' && item.state !== 'uploading');
        }
        function updateTask(item) {
          item.element.dataset.state = item.state;
          item.progress.value = item.percent;
          item.status.textContent = item.state === 'uploading' ? (item.percent >= 100 ? '服务器正在保存…' : '正在上传 · ' + item.percent + '%') : ({waiting: '等待上传', success: '上传完成', cancelled: item.started ? '已取消；若文件已保存，请刷新确认' : '已取消', error: item.error})[item.state];
          item.button.hidden = item.state === 'success';
          item.button.textContent = item.state === 'error' || item.state === 'cancelled' ? '重试' : '取消';
          updateQueueSummary();
        }
        function addFiles(files) {
          const destination = currentPath();
          for (const file of files) {
            const item = {file, name: file.name, destination, state: 'waiting', percent: 0, xhr: null};
            item.element = document.createElement('li');
            item.element.className = 'queue-item';
            const name = document.createElement('p');
            name.className = 'queue-name'; name.textContent = file.name;
            const detail = document.createElement('div');
            detail.className = 'queue-detail'; detail.textContent = sizeLabel(file.size) + ' → ' + decodeURIComponent(destination);
            item.progress = document.createElement('progress');
            item.progress.max = 100; item.progress.value = 0;
            item.progress.setAttribute('aria-label', file.name + ' 上传进度');
            const bottom = document.createElement('div'); bottom.className = 'queue-bottom';
            item.status = document.createElement('span'); item.status.className = 'queue-status';
            item.button = document.createElement('button'); item.button.type = 'button';
            item.button.addEventListener('click', () => {
              if (item.state === 'uploading') { if (item.xhr) item.xhr.abort(); }
              else if (item.state === 'waiting') { item.state = 'cancelled'; updateTask(item); }
              else {
                item.state = 'waiting'; item.percent = 0; item.error = ''; updateTask(item); runQueue();
              }
            });
            bottom.append(item.status, item.button);
            item.element.append(name, detail, item.progress, bottom);
            byId('queue').append(item.element);
            queue.push(item); updateTask(item);
          }
          runQueue();
        }
        function uploadFile(item) {
          return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            item.xhr = xhr; item.started = true;
            xhr.open('PUT', item.destination + encodeURIComponent(item.file.name));
            xhr.upload.onprogress = event => {
              if (event.lengthComputable) { item.percent = event.total > 0 ? Math.round(event.loaded / event.total * 100) : 0; updateTask(item); }
            };
            xhr.onload = () => xhr.status === 201 ? resolve() : reject(new Error(xhr.responseText || '上传失败（' + xhr.status + '）'));
            xhr.onerror = () => reject(new Error('连接中断，请检查服务器后重试'));
            xhr.onabort = () => reject(new DOMException('上传已取消', 'AbortError'));
            xhr.send(item.file);
          });
        }
        async function runQueue() {
          if (uploading) return;
          uploading = true;
          let changed = false;
          try {
            let item;
            while ((item = queue.find(task => task.state === 'waiting'))) {
              item.state = 'uploading'; updateTask(item);
              try {
                await uploadFile(item);
                item.state = 'success'; item.percent = 100;
                // Successful entries no longer need to retain potentially large File objects.
                item.file = null;
              } catch (error) {
                item.state = error.name === 'AbortError' ? 'cancelled' : 'error';
                item.error = error.message;
              } finally {
                item.xhr = null; changed = true; updateTask(item);
              }
            }
          } finally {
            uploading = false;
            if (changed && !byId('refresh').disabled) loadDirectory(currentPath());
          }
        }
        if (writable) {
          byId('mkdir').addEventListener('click', () => openManagement('mkdir'));
          byId('manage-form').addEventListener('submit', submitManagement);
          byId('manage-cancel').addEventListener('click', () => byId('manage-dialog').close());
          byId('manage-dialog').addEventListener('cancel', event => { if (managing) event.preventDefault(); });
          byId('upload').addEventListener('click', () => byId('files').click());
          byId('dropzone').addEventListener('click', () => byId('files').click());
          byId('files').addEventListener('change', event => { addFiles(event.target.files); event.target.value = ''; });
          byId('clear-queue').addEventListener('click', () => {
            for (let index = queue.length - 1; index >= 0; --index) {
              if (queue[index].state !== 'waiting' && queue[index].state !== 'uploading') {
                queue[index].element.remove(); queue.splice(index, 1);
              }
            }
            updateQueueSummary();
          });
          // Prevent the browser from opening a dropped file in place of the running queue.
          document.addEventListener('dragover', event => { event.preventDefault(); });
          document.addEventListener('drop', event => { event.preventDefault(); });
          const dropzone = byId('dropzone');
          dropzone.addEventListener('dragover', event => { event.preventDefault(); dropzone.classList.add('dragging'); });
          dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragging'));
          dropzone.addEventListener('drop', event => {
            event.preventDefault(); dropzone.classList.remove('dragging');
            const items = Array.from(event.dataTransfer.items || []);
            const containsDirectory = items.some(item => {
              const entry = item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
              return entry && entry.isDirectory;
            });
            if (containsDirectory) { notify('请拖入文件；如需上传文件夹，请先在页面中新建文件夹。', true); return; }
            addFiles(event.dataTransfer.files);
          });
        }

        let previewController;
        let previewUrl;
        function cleanPreview() {
          if (previewController) previewController.abort();
          previewController = null;
          byId('preview-image').removeAttribute('src');
          if (previewUrl) URL.revokeObjectURL(previewUrl);
          previewUrl = null;
          byId('preview-text').textContent = '';
        }
        async function openPreview(entry) {
          cleanPreview();
          const controller = new AbortController();
          previewController = controller;
          const isImage = entry.preview === 'image';
          const limit = isImage ? 20 * 1024 * 1024 : 256 * 1024;
          byId('preview-title').textContent = entry.name;
          byId('preview-meta').textContent = (isImage ? '图片预览' : '文本预览') + ' · ' + sizeLabel(Number(entry.size));
          byId('preview-download').href = entry.url;
          byId('preview-image').hidden = byId('preview-text').hidden = true;
          byId('preview-image').alt = entry.name;
          byId('preview-status').hidden = false;
          byId('preview-status').textContent = '正在加载…';
          byId('preview-note').textContent = isImage ? '最大支持 20 MiB 的图片' : '文本最多预览前 256 KiB';
          if (!byId('preview-dialog').open) byId('preview-dialog').showModal();
          try {
            if (isImage && Number(entry.size) > limit) throw new Error('图片超过 20 MiB，请下载后查看');
            if (Number(entry.size) === 0) {
              if (isImage) throw new Error('图片文件为空');
              byId('preview-status').textContent = '这是一个空文本文件';
              return;
            }
            const response = await fetch(entry.url, {signal: controller.signal, headers: {Range: 'bytes=0-' + limit}, cache: 'no-store'});
            if (!response.ok) throw new Error(await response.text() || '预览失败');
            const length = Number(response.headers.get('Content-Length'));
            if (length > limit + 1) throw new Error('文件超过预览读取上限，请下载后查看');
            const buffer = await response.arrayBuffer();
            if (controller.signal.aborted) return;
            if (isImage) {
              if (buffer.byteLength > limit) throw new Error('图片超过 20 MiB，请下载后查看');
              previewUrl = URL.createObjectURL(new Blob([buffer], {type: entry.mime}));
              const img = byId('preview-image');
              img.onload = () => { if (!controller.signal.aborted) { byId('preview-status').hidden = true; img.hidden = false; } };
              img.onerror = () => { if (!controller.signal.aborted) byId('preview-status').textContent = '无法解码图片，请下载原文件查看'; };
              img.src = previewUrl;
            } else {
              const bytes = new Uint8Array(buffer, 0, Math.min(buffer.byteLength, limit));
              const encoding = bytes[0] === 255 && bytes[1] === 254 ? 'utf-16le' : bytes[0] === 254 && bytes[1] === 255 ? 'utf-16be' : 'utf-8';
              const truncated = buffer.byteLength > limit || Number(entry.size) > limit;
              byId('preview-text').textContent = new TextDecoder(encoding).decode(bytes, {stream: truncated});
              byId('preview-text').hidden = false;
              byId('preview-status').hidden = true;
              byId('preview-note').textContent = (truncated ? '仅显示前 256 KiB · ' : '') + encoding.toUpperCase() + ' · 只读预览';
            }
          } catch (error) {
            if (!controller.signal.aborted) {
              controller.abort();
              byId('preview-status').textContent = error.message;
            }
          }
        }
        byId('preview-close').addEventListener('click', () => byId('preview-dialog').close());
        byId('preview-dialog').addEventListener('close', cleanPreview);
        applyFilter();
    """.trimIndent()
}
