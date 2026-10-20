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
        this.open = false;
        this._text = '';
        const classes = new Set();
        this.classList = {
            add: name => classes.add(name), remove: name => classes.delete(name),
            contains: name => classes.has(name),
            toggle: (name, on) => on ? classes.add(name) : classes.delete(name)
        };
    }
    set textContent(value) { this._text = String(value); this.children = []; }
    get textContent() { return this._text; }
    set innerHTML(_) { throw new Error('Untrusted content must not be rendered through innerHTML'); }
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
    click() { this.dispatch('click'); }
}

function harness(writable = true) {
    const ids = new Map();
    const elements = [
        'browser', 'search', 'sort', 'refresh', 'entries', 'empty', 'no-results', 'filter-count',
        'notice', 'preview-dialog', 'preview-close', 'preview-title', 'preview-meta',
        'preview-download', 'preview-image', 'preview-text', 'preview-status', 'preview-note'
    ];
    if (writable) elements.push('queue', 'queue-count', 'queue-summary', 'queue-empty', 'clear-queue',
        'mkdir', 'manage-dialog', 'manage-title', 'manage-description', 'manage-form', 'manage-cancel',
        'manage-confirm', 'manage-error', 'name-label', 'entry-name', 'upload', 'files', 'dropzone');
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
        browser.replaceWith = next => { wireBrowser(next); ids.set('browser', next); };
    }
    wireBrowser(ids.get('browser'));
    vm.runInContext(source, context);
    return {
        ids, context, requests, fetches, blobs, revoked, history, window,
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
        const row = new Element();
        row.dataset = {name, directory: String(directory), size: String(size), modified: '0'};
        h.ids.get('entries').append(row);
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
    await h.context.submitManagement({preventDefault() {}});
    assert.equal(request.options.method, 'DELETE');
    assert.equal(request.url, '/folder/');
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
