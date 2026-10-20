// Run with: node --test tests/http-server-page.test.cjs (no Android build required).
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const assets = readFileSync(join(__dirname,
    '../app/src/main/java/me/zhanghai/android/files/httpserver/HttpServerPageAssets.kt'), 'utf8');
const source = assets.match(/val script = """\n([\s\S]*?)\n    """\.trimIndent\(\)/)[1];

// A small DOM/transport harness exercises the shipped script, without duplicating its logic.
// Browser rendering and native dialog behavior remain part of the device smoke test.
class Element {
    constructor(tag = 'div') {
        this.tagName = tag;
        this.dataset = {};
        this.children = [];
        this.listeners = {};
        this.attributes = {};
        this.value = '';
        this.hidden = false;
        this.disabled = false;
        this.checked = false;
        this.indeterminate = false;
        this.open = false;
        this._text = '';
        const classes = this.classes = new Set();
        this.classList = {
            add: name => classes.add(name), remove: name => classes.delete(name),
            contains: name => classes.has(name),
            toggle: (name, on) => on ? classes.add(name) : classes.delete(name)
        };
    }
    set textContent(value) { this._text = String(value); this.children = []; }
    get textContent() { return this._text; }
    set innerHTML(_) { throw new Error('Untrusted content must not be rendered through innerHTML'); }
    set className(value) { this.classes.clear(); for (const name of value.split(/\s+/)) if (name) this.classes.add(name); }
    get className() { return [...this.classes].join(' '); }
    matches(selector) {
        return selector.split(',').some(part => {
            part = part.trim();
            if (part.startsWith('.')) return this.classList.contains(part.slice(1));
            const match = part.match(/^([a-z]+)?(?:\[([a-z-]+)\])?$/);
            if (!match) throw new Error('Unsupported selector: ' + part);
            const [, tag, attr] = match;
            const dataKey = attr?.startsWith('data-') ? attr.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()) : null;
            return (!tag || this.tagName === tag) && (!attr || attr in this.attributes || (dataKey && dataKey in this.dataset));
        });
    }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector) || null; }
    querySelector(selector) {
        for (const child of this.children) {
            if (child.matches(selector)) return child;
            const match = child.querySelector(selector);
            if (match) return match;
        }
        return null;
    }
    addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
    dispatch(name, event = {}) {
        event.target ||= this;
        event.preventDefault ||= () => { event.prevented = true; };
        for (const handler of this.listeners[name] || []) handler(event);
        return event;
    }
    append(...nodes) {
        for (const node of nodes) { node.remove(); node.parent = this; this.children.push(node); }
    }
    remove() {
        if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this);
        this.parent = null;
    }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    removeAttribute(name) { delete this.attributes[name]; }
    getAttribute(name) { return this.attributes[name]; }
    showModal() { this.open = true; }
    close() { this.open = false; this.dispatch('close'); }
    focus() {}
    select() {}
    click() { if (!this.disabled) this.dispatch('click'); }
}

function harness(writable = true) {
    const ids = new Map();
    const elements = [
        'browser', 'search', 'sort', 'refresh', 'entries', 'empty', 'no-results', 'filter-count',
        'notice', 'preview-dialog', 'preview-close', 'preview-title', 'preview-meta',
        'preview-download', 'preview-image', 'preview-text', 'preview-status', 'preview-note',
        'select-all', 'selection-count', 'selection-clear', 'selection-download', 'selection-preview'
    ];
    if (writable) elements.push('queue', 'queue-count', 'queue-summary', 'queue-empty', 'clear-queue',
        'mkdir', 'manage-dialog', 'manage-title', 'manage-description', 'manage-form', 'manage-cancel',
        'manage-confirm', 'manage-error', 'name-label', 'entry-name', 'upload', 'files', 'dropzone',
        'selection-copy', 'selection-cut', 'selection-paste', 'selection-rename', 'selection-delete',
        'clipboard-panel', 'clipboard-status', 'clipboard-clear');
    for (const id of elements) ids.set(id, new Element());
    ids.get('browser').dataset.path = '/';
    ids.get('sort').value = 'name';
    const document = new Element();
    document.body = new Element('body');
    document.body.dataset.writable = String(writable);
    document.getElementById = id => ids.get(id) || null;
    document.createElement = tag => new Element(tag);
    document.querySelectorAll = selector => selector === '.file-row' ? ids.get('entries').children : [];
    const requests = [];
    const fetches = [];
    const blobs = [];
    const revoked = [];
    const history = [];
    const directories = new Map();
    const window = new Element();
    const context = vm.createContext({
        document, window, Intl, Date, AbortController, DOMException, TextDecoder, Uint8Array, Blob,
        setTimeout: () => 1, clearTimeout: () => {}, location: {pathname: '/'},
        history: {
            pushState: (_state, _unused, path) => history.push(path),
            replaceState: (_state, _unused, path) => history.push(path)
        },
        URL: {
            createObjectURL: blob => { blobs.push(blob); return 'blob:test-' + blobs.length; },
            revokeObjectURL: url => revoked.push(url)
        },
        fetch: async (url, options) => { fetches.push({url, options}); return response(String(url), 200, {'Content-Type': 'text/html; charset=utf-8'}); },
        DOMParser: class {
            parseFromString(path) {
                const browser = new Element();
                browser.dataset.path = path;
                browser.rows = directories.get(path) || [];
                return {title: path + ' · Material Files', getElementById: () => browser};
            }
        },
        XMLHttpRequest: class {
            constructor() { this.upload = {}; }
            open(method, url) { this.method = method; this.url = url; }
            send(file) { this.file = file; requests.push(this); }
            abort() { this.onabort(); }
            complete(status = 201, text = '') { this.status = status; this.responseText = text; this.onload(); }
        }
    });
    function wireBrowser(browser) {
        browser.replaceWith = next => {
            wireBrowser(next); ids.set('browser', next);
            ids.get('entries').textContent = '';
            ids.get('entries').append(...next.rows);
        };
    }
    wireBrowser(ids.get('browser'));
    vm.runInContext(source, context);
    return {
        ids, context, requests, fetches, blobs, revoked, history, window, document, directories,
        run: script => vm.runInContext(script, context),
        async settle() { await new Promise(resolve => setImmediate(resolve)); }
    };
}

function response(content, status = 200, headers = {}) {
    const bytes = typeof content === 'string' ? Buffer.from(content) : content;
    return {
        ok: status >= 200 && status < 300, status,
        text: async () => bytes.toString(),
        headers: {get: name => headers[name] || (name === 'Content-Length' ? String(bytes.length) : null)},
        arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
    };
}

function file(name, size = 12) { return {name, size}; }

function row(name, directory = false, parent = '/', preview = '') {
    const element = new Element('tr');
    element.className = 'file-row';
    element.dataset = {name, directory: String(directory), url: parent + encodeURIComponent(name) + (directory ? '/' : ''), size: '12', modified: '0', preview};
    const cell = new Element('td');
    const checkbox = new Element('input');
    checkbox.className = 'entry-select';
    cell.append(checkbox); element.append(cell);
    return element;
}

function select(h, element, checked = true) {
    const checkbox = element.querySelector('.entry-select');
    checkbox.checked = checked;
    h.document.dispatch('change', {target: checkbox});
}

test('read-only page supports browsing and preview without requiring write controls', async () => {
    const h = harness(false);
    assert.equal(h.ids.has('upload'), false);
    h.context.fetch = async () => response('<script>alert(1)</script>');
    await h.context.openPreview({name: 'page.html', url: '/page.html', preview: 'text', size: 25});
    assert.equal(h.ids.get('preview-text').textContent, '<script>alert(1)</script>');
    assert.equal(h.ids.get('preview-text').hidden, false);
});

test('name validation rejects traversal and controls but preserves Unicode and percent literals', () => {
    const h = harness();
    for (const name of ['', ' ', '.', '..', '/a', 'a/b', 'a\\b', 'a\0b', 'a\nb']) {
        assert.equal(h.context.validName(name), false, JSON.stringify(name));
    }
    for (const name of ['中文 空格+%?#&.txt', '%2e%2e', ' leading space', '<script>.txt']) {
        assert.equal(h.context.validName(name), true, name);
    }
});

test('filter and sort keep folders first and compare numeric names naturally', () => {
    const h = harness();
    for (const [name, directory, size] of [['10.txt', false, 10], ['2.txt', false, 20], ['folder', true, 0]]) {
        const entry = row(name, directory);
        entry.dataset.size = String(size);
        h.ids.get('entries').append(entry);
    }
    h.context.applyFilter();
    assert.deepEqual(h.ids.get('entries').children.map(row => row.dataset.name), ['folder', '2.txt', '10.txt']);
    h.ids.get('search').value = 'TXT';
    h.context.applyFilter();
    assert.equal(h.ids.get('entries').children[0].hidden, true);
    assert.equal(h.ids.get('filter-count').textContent, '找到 2 项');
    h.ids.get('search').value = 'missing';
    h.context.applyFilter();
    assert.equal(h.ids.get('no-results').hidden, false);
});

test('row and checkbox selection update the bottom bar, and select-all only selects visible rows', () => {
    const h = harness();
    const folder = row('folder', true);
    const text = row('readme.txt', false, '/', 'text');
    const other = row('other.txt');
    h.ids.get('entries').append(folder, text, other);
    h.context.applyFilter();
    assert.equal(h.ids.get('selection-copy').disabled, true);
    h.document.dispatch('click', {button: 0, target: text});
    assert.equal(text.querySelector('.entry-select').checked, true);
    assert.equal(text.classList.contains('is-selected'), true);
    assert.equal(h.ids.get('select-all').indeterminate, true);
    assert.equal(h.ids.get('selection-preview').disabled, false);
    assert.equal(h.ids.get('selection-rename').disabled, false);
    select(h, folder);
    assert.equal(h.ids.get('selection-count').textContent, '已选 2 项');
    assert.equal(h.ids.get('selection-rename').disabled, true);
    assert.equal(h.ids.get('selection-download').disabled, true);
    h.ids.get('search').value = '.txt';
    h.context.applyFilter();
    assert.equal(h.run('selection.size'), 1);
    assert.equal(folder.querySelector('.entry-select').checked, false);
    h.ids.get('select-all').checked = true;
    h.ids.get('select-all').dispatch('change');
    assert.equal(h.run('selection.size'), 2);
    assert.equal(h.ids.get('select-all').checked, true);
    assert.equal(h.ids.get('select-all').indeterminate, false);
    assert.equal(folder.querySelector('.entry-select').checked, false);
    h.ids.get('selection-clear').click();
    assert.equal(h.run('selection.size'), 0);
    assert.equal(text.classList.contains('is-selected'), false);
});

test('read-only selection enables preview and download without write actions', () => {
    const h = harness(false);
    const entry = row('photo.jpg', false, '/', 'image');
    h.ids.get('entries').append(entry);
    select(h, entry);
    assert.equal(h.ids.get('selection-preview').disabled, false);
    assert.equal(h.ids.get('selection-download').disabled, false);
    assert.equal(h.ids.has('selection-copy'), false);
    assert.equal(h.ids.has('selection-delete'), false);
});

test('refresh retains existing selections and navigation clears them while preserving the clipboard', async () => {
    const h = harness();
    const folder = row('folder', true);
    h.ids.get('entries').append(folder);
    select(h, folder);
    const refreshed = row('folder', true);
    h.directories.set('/', [refreshed]);
    await h.context.loadDirectory('/');
    assert.equal(refreshed.querySelector('.entry-select').checked, true);
    h.ids.get('selection-cut').click();
    assert.equal(h.run('selection.size'), 0);
    assert.equal(refreshed.classList.contains('is-cut'), true);
    assert.equal(h.ids.get('selection-paste').disabled, true);
    await h.context.loadDirectory('/folder/child/', 'push');
    assert.equal(h.ids.get('selection-paste').disabled, true);
    const target = row('target.txt', false, '/target/');
    h.directories.set('/target/', [target]);
    await h.context.loadDirectory('/target/', 'push');
    assert.equal(h.ids.get('selection-paste').disabled, false);
    assert.equal(h.run('clipboard.entries[0].url'), '/folder/');
    select(h, target);
    await h.context.loadDirectory('/', 'pop');
    assert.equal(h.run('selection.size'), 0);
    h.ids.get('clipboard-clear').click();
    assert.equal(refreshed.classList.contains('is-cut'), false);
    assert.equal(h.ids.get('clipboard-panel').hidden, true);
});

test('copy paste encodes destination paths once and continues after a conflict', async () => {
    const h = harness();
    const entries = [row('中文 +%.txt'), row('%2e%2e', true)];
    h.ids.get('entries').append(...entries);
    for (const entry of entries) select(h, entry);
    h.ids.get('selection-copy').click();
    const destination = '/' + encodeURIComponent('目标 +%2e%2e') + '/';
    await h.context.loadDirectory(destination, 'push');
    const requests = [];
    h.context.fetch = async (url, options) => {
        if (options?.method === 'POST') {
            requests.push({url, options});
            return requests.length === 1 ? response('同名文件已存在', 409) : response('复制完成');
        }
        return response(destination, 200, {'Content-Type': 'text/html'});
    };
    await h.run('runBatch(clipboard.action, [...clipboard.entries])');
    assert.equal(requests.length, 2);
    assert.equal(requests[0].url, entries[0].dataset.url + '?action=copy&destination=' + encodeURIComponent('/目标 +%2e%2e/'));
    assert.equal(requests[1].options.headers['X-Material-Files'], '1');
    assert.equal(h.run('clipboard.entries.length'), 1);
    assert.equal(h.run('clipboard.entries[0].name'), '中文 +%.txt');
    assert.match(h.ids.get('notice').textContent, /复制完成 1 项，1 项失败/);
    assert.match(h.ids.get('notice').textContent, /同名文件已存在/);
    assert.equal(h.ids.get('selection-paste').disabled, false);
});

test('cut paste is sequential, blocks repeated operations and retains only failed entries for retry', async () => {
    const h = harness();
    const entries = [row('first'), row('second')];
    h.ids.get('entries').append(...entries);
    for (const entry of entries) select(h, entry);
    h.ids.get('selection-cut').click();
    await h.context.loadDirectory('/target/', 'push');
    let finish;
    const requests = [];
    h.context.fetch = async (url, options) => {
        if (options?.method === 'POST') {
            requests.push(url);
            if (requests.length === 1) return new Promise(resolve => { finish = resolve; });
            return response('正在修改', 409);
        }
        return response(String(url), 200, {'Content-Type': 'text/html'});
    };
    const batch = h.run('runBatch(clipboard.action, [...clipboard.entries])');
    assert.equal(requests.length, 1);
    assert.equal(h.ids.get('selection-paste').disabled, true);
    assert.equal(h.window.dispatch('beforeunload').prevented, true);
    await h.run('runBatch(clipboard.action, [...clipboard.entries])');
    assert.equal(requests.length, 1);
    await h.context.loadDirectory('/elsewhere/', 'push');
    assert.equal(h.ids.get('browser').dataset.path, '/elsewhere/');
    assert.equal(h.ids.get('selection-paste').disabled, true);
    finish(response('移动完成'));
    await batch;
    assert.equal(requests.length, 2);
    assert.match(requests[0], /action=move/);
    assert.match(requests[1], /destination=%2Ftarget%2F/);
    assert.equal(h.run('clipboard.entries.length'), 1);
    assert.equal(h.run('clipboard.entries[0].name'), 'second');
    h.context.fetch = async (url, options) => options?.method === 'POST' ? response('移动完成') : response('/target/', 200, {'Content-Type': 'text/html'});
    await h.run('runBatch(clipboard.action, [...clipboard.entries])');
    assert.equal(h.run('clipboard'), null);
    assert.equal(h.ids.get('clipboard-panel').hidden, true);
    assert.equal(h.window.dispatch('beforeunload').prevented, undefined);
});

test('bulk deletion requires one confirmation and retains failures selected after refreshing', async () => {
    const h = harness();
    const entries = [row('folder', true), row('<script>.txt')];
    h.ids.get('entries').append(...entries);
    for (const entry of entries) select(h, entry);
    const failed = row('<script>.txt');
    h.directories.set('/', [failed]);
    const requests = [];
    h.context.fetch = async (url, options) => {
        if (options?.method === 'DELETE') {
            requests.push(url);
            return requests.length === 1 ? response('删除完成') : response('<b>无法删除</b>', 403);
        }
        return response('/', 200, {'Content-Type': 'text/html'});
    };
    h.ids.get('selection-delete').click();
    assert.equal(requests.length, 0);
    assert.equal(h.ids.get('manage-dialog').open, true);
    assert.match(h.ids.get('manage-description').textContent, /选中的 2 个项目及文件夹中的全部内容/);
    h.ids.get('manage-cancel').click();
    assert.equal(requests.length, 0);
    h.ids.get('selection-delete').click();
    await h.context.submitManagement({preventDefault() {}});
    assert.deepEqual(requests, ['/folder/', '/%3Cscript%3E.txt']);
    assert.equal(h.run('selection.size'), 1);
    assert.equal(failed.querySelector('.entry-select').checked, true);
    assert.match(h.ids.get('notice').textContent, /<b>无法删除<\/b>/);
    assert.equal(h.ids.get('manage-dialog').open, false);
});

test('queue is sequential, retains destinations through navigation and continues after failure', async () => {
    const h = harness();
    h.context.addFiles([file('中文 +%.txt'), file('second.txt')]);
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].url, '/' + encodeURIComponent('中文 +%.txt'));
    assert.equal(h.window.dispatch('beforeunload').prevented, true);
    await h.context.loadDirectory('/next/', 'push');
    h.context.addFiles([file('third.txt')]);
    h.requests[0].upload.onprogress({lengthComputable: true, loaded: 6, total: 12});
    assert.equal(h.run('queue[0].percent'), 50);
    h.requests[0].complete(409, '同名文件已存在');
    await h.settle();
    assert.equal(h.requests[1].url, '/second.txt');
    h.requests[1].complete();
    await h.settle();
    assert.equal(h.requests[2].url, '/next/third.txt');
    h.requests[2].complete();
    await h.settle();
    assert.deepEqual(Array.from(h.run('queue.map(item => item.state)')), ['error', 'success', 'success']);
    assert.equal(h.run('queue[1].file'), null);
    assert.match(h.ids.get('queue-summary').textContent, /1 失败/);
    assert.equal(h.ids.get('browser').dataset.path, '/next/');
    assert.equal(h.window.dispatch('beforeunload').prevented, undefined);
});

test('waiting and active uploads can be cancelled, retried and cleared', async () => {
    const h = harness();
    h.context.addFiles([file('a'), file('b')]);
    h.run('queue[1].button.click()');
    assert.equal(h.run('queue[1].state'), 'cancelled');
    h.run('queue[0].button.click()');
    await h.settle();
    assert.equal(h.run('queue[0].state'), 'cancelled');
    assert.equal(h.requests.length, 1);
    h.run('queue[1].button.click()');
    assert.equal(h.requests.length, 2);
    h.requests[1].complete();
    await h.settle();
    h.ids.get('clear-queue').click();
    assert.equal(h.run('queue.length'), 0);
    assert.equal(h.ids.get('queue-empty').hidden, false);
});

test('empty file progress remains finite and queue errors are rendered as text', async () => {
    const h = harness();
    h.context.addFiles([file('<img onerror=alert(1)>', 0)]);
    h.requests[0].upload.onprogress({lengthComputable: true, loaded: 0, total: 0});
    assert.equal(h.run('queue[0].percent'), 0);
    assert.equal(h.run('queue[0].element.children[0].textContent'), '<img onerror=alert(1)>');
    h.requests[0].complete(500, '<script>untrusted</script>');
    await h.settle();
    assert.equal(h.run('queue[0].status.textContent'), '<script>untrusted</script>');
});

test('text preview requests a bounded range, truncates safely and recognizes UTF-16 BOM', async () => {
    const h = harness();
    let range;
    h.context.fetch = async (_url, options) => {
        range = options.headers.Range;
        return response(Buffer.alloc(262145, 'a'), 206);
    };
    await h.context.openPreview({name: 'big.txt', url: '/big.txt', preview: 'text', size: 400000});
    assert.equal(range, 'bytes=0-262144');
    assert.equal(h.ids.get('preview-text').textContent.length, 262144);
    assert.match(h.ids.get('preview-note').textContent, /仅显示前 256 KiB/);
    h.context.fetch = async () => response(Buffer.from([255, 254, 0x2d, 0x4e, 0x87, 0x65]));
    await h.context.openPreview({name: 'utf16.txt', url: '/utf16.txt', preview: 'text', size: 6});
    assert.equal(h.ids.get('preview-text').textContent, '中文');
    h.ids.get('preview-dialog').close();
    assert.equal(h.ids.get('preview-text').textContent, '');
});

test('oversized images are rejected before transfer and object URLs are released on close', async () => {
    const h = harness();
    await h.context.openPreview({name: 'large.jpg', url: '/large.jpg', preview: 'image', size: 21 * 1024 * 1024});
    assert.equal(h.fetches.length, 0);
    assert.match(h.ids.get('preview-status').textContent, /20 MiB/);
    h.context.fetch = async () => response(Buffer.from([1, 2, 3]));
    await h.context.openPreview({name: 'small.jpg', url: '/small.jpg', preview: 'image', mime: 'image/jpeg', size: 3});
    assert.equal(h.blobs[0].type, 'image/jpeg');
    h.ids.get('preview-image').onload();
    assert.equal(h.ids.get('preview-image').hidden, false);
    h.ids.get('preview-dialog').close();
    assert.deepEqual(h.revoked, ['blob:test-1']);
});

test('closing a preview aborts pending reads and prevents stale content from appearing', async () => {
    const h = harness();
    let resolve;
    let signal;
    h.context.fetch = async (_url, options) => {
        signal = options.signal;
        return new Promise(done => { resolve = done; });
    };
    const preview = h.context.openPreview({name: 'slow.txt', url: '/slow.txt', preview: 'text', size: 4});
    h.ids.get('preview-dialog').close();
    assert.equal(signal.aborted, true);
    resolve(response('late'));
    await preview;
    assert.equal(h.ids.get('preview-text').textContent, '');
});

test('management submits encoded names, requires confirmation and displays conflicts', async () => {
    const h = harness();
    h.context.openManagement('rename', {url: '/old', name: 'old', directory: 'false'});
    h.ids.get('entry-name').value = '中文 +%?#.txt';
    let request;
    h.context.fetch = async (url, options) => { request = {url, options}; return response('同名文件已存在', 409); };
    await h.context.submitManagement({preventDefault() {}});
    assert.equal(request.url, '/old?action=rename&name=' + encodeURIComponent('中文 +%?#.txt'));
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.headers['X-Material-Files'], '1');
    assert.match(h.ids.get('manage-error').textContent, /同名文件已存在/);
    assert.equal(h.ids.get('manage-dialog').open, true);
    request = null;
    h.context.openManagement('delete', {url: '/folder/', name: 'folder', directory: 'true'});
    assert.equal(request, null);
    assert.match(h.ids.get('manage-description').textContent, /全部内容/);
    h.context.fetch = async (url, options) => { request = {url, options}; return response('无法删除', 409); };
    h.ids.get('refresh').disabled = true;
    await h.context.submitManagement({preventDefault() {}});
    assert.equal(request.options.method, 'DELETE');
    assert.equal(request.url, '/folder/');
    assert.match(h.ids.get('notice').textContent, /1 项失败/);
});


test('navigation refuses uploaded file content masquerading as a directory', async () => {
    const h = harness();
    h.context.fetch = async () => response('<div id="browser"><img src=x onerror=alert(1)></div>',
        200, {'Content-Type': 'application/octet-stream'});
    await h.context.loadDirectory('/replaced/', 'push');
    assert.equal(h.ids.get('browser').dataset.path, '/');
    assert.match(h.ids.get('notice').textContent, /已不是文件夹/);
    assert.equal(h.history.length, 0);
});
