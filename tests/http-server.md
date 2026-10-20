# HTTP server verification

The server screen shares login, root directory and write access settings between
FTP and HTTP. Each protocol keeps its own port (FTP: 2121, HTTP: 8080). Stop the
server before switching protocol. With HTTP running, open the displayed URL from
a browser on the same network. The responsive page supports light/dark mode,
breadcrumb navigation, searching and sorting the current directory, folder
creation, copying, moving, renaming, deletion, and text/image preview. The compact
list has a checkbox per row and a fixed bottom bar for selection and actions.
Select multiple files/folders to copy, cut or delete; rename, preview and download
act on one selected item. Copy/cut retains the selection in a page-local clipboard;
browse to another directory and choose Paste. A failed item does not stop a batch,
and failed moves remain in the clipboard for retry. A partial copy batch retains
only failed items for retry; a fully successful copy can be pasted elsewhere again.
Filtering removes hidden selections, refreshing retains existing visible
selections, and navigating clears selection while retaining the clipboard.
Use the Download action to save the original file. Choosing upload files or
dropping files starts a sequential upload
queue; each task retains the directory in which it was added. Existing files and
folders are never overwritten. Deleting a folder recursively deletes its contents
after confirmation; an interrupted deletion may leave only some of its contents.

The release workflow runs `:app:testReleaseUnitTest`. `HttpServerProtocolTest`
covers URL encoding, traversal rejection, byte ranges (including large files),
HTML escaping, preview types, parent links and read-only listings.
`HttpServerTransportTest` exercises NanoHTTPD's request parser and response writer,
including HEAD responses, ranged downloads, concurrent uploads to a provider with
delayed file visibility, cleanup/retry after an interrupted upload, folder creation,
rename/delete/copy/move conflicts, write authorization, recursive deletion without
following links, recursive copying and cleanup after failure, encoded transfer
destinations, and source/parent/destination reservations during uploads and copies.

Run `node --test tests/http-server-page.test.cjs` for the embedded JavaScript tests.
They use DOM and transport doubles to verify queue behavior, navigation, filtering,
visible-only selection, clipboard behavior, sequential batch requests and failure
retries, management requests and bounded previews without compiling the Android app. The
release workflow runs these tests too. Visual layout, native dialogs, real browser
uploads and storage-provider integration still need the device checks below.

On an Android device, verify the transport and storage providers as follows:

1. Select an empty test directory, HTTP, anonymous login, and write access.
   Start the server and open its URL. Upload a file named `中文 空格+%?#&.txt`,
   an empty file, and a large file. Download each and compare its contents.
   Retry an upload with the same name: expect HTTP 409 and unchanged contents.
2. Create a subdirectory from the webpage, including names containing Chinese,
   spaces, `+%?#&`, and the literal `%2e%2e`. Browse into it and back via breadcrumbs
   and browser Back/Forward. A URL without the final directory slash should redirect
   to one with a slash. Rename both files and folders, including a nonempty folder.
   Attempt duplicate names, empty names, `.`, `..`, separators and control characters:
   they must fail without replacing anything. Cancel a deletion, then confirm it;
   verify a nonempty folder and its contents are deleted, while siblings remain.
3. For a file with known contents, check GET, HEAD, `Range: bytes=2-5`,
   `Range: bytes=2-`, and `Range: bytes=-3`. Expect matching Content-Length and
   Content-Range headers; HEAD must have no response body. A range starting past
   the file length should return 416. HEAD ignores Range.
   Also send HEAD to a directory, a missing file, an invalid path and a protected
   URL without credentials. Each must keep the corresponding GET status and
   Content-Length but send no body (check with a raw socket, since HTTP clients
   commonly hide unexpected HEAD response bodies).
4. Stop the server, disable anonymous login, set a username and password, then
   restart. A fresh browser session should ask for credentials. Missing, invalid
   or malformed Basic credentials must return 401 for downloads and all write methods.
5. Stop the server, disable write access, then restart. Upload, create, copy, cut, paste, rename and
   delete controls must disappear. Direct PUT, POST and DELETE requests must return
   403. Browsing, downloading and previewing still work. OPTIONS returns 405 with
   `Allow: GET, HEAD` (`GET, HEAD, PUT, POST, DELETE` in writable mode).
6. With write access enabled, PUT, POST and DELETE with a foreign Origin must
   return 403. POST/DELETE without `X-Material-Files: 1` must also return 403.
   Requests for `/../secret`, `/%2e%2e/secret`, and any nested symbolic link must
   not expose files outside the configured root. Use `curl --path-as-is` when
   testing traversal. Symlinks inside the root should be omitted from listings.
   Direct operations on links must fail; deleting their enclosing folder removes
   the link without traversing it. Renaming or deleting the shared root must fail.
7. Interrupt a large upload: the incomplete file should be removed. Stop the
   server during a transfer and confirm the connection closes, the notification
   disappears, and the port can be reused. Repeat start/stop and test an occupied
   port: failure should release locks and return the switch to the stopped state.
8. Repeat browsing, upload, download, create, rename and delete with a Storage Access Framework root.
   On a remote FTP/WebDAV root, start a slow upload and upload the same name from
   a second client before the first finishes. The second request must return 409
   without replacing the first upload. Uploads to different names can proceed
   concurrently. Repeat with an interrupted first upload and retry its name after
   cleanup; the retry must succeed. During an upload, renaming/deleting its parent,
   deleting the destination, or renaming a sibling onto it must return 409. An
   unrelated folder may still be created, renamed or deleted.
   Switch back to FTP and confirm the previous FTP port, login and file operations
   still work. The quick settings tile and notification should control whichever
   protocol is selected.

9. Queue several files, including a duplicate, an empty file and a large file. Check
   per-file progress, failure text, continued uploading after a failure, cancellation
   of waiting/active tasks, retry, and clearing finished tasks. Navigate while a task
   is uploading, then queue another file: each must go to its original destination.
   Refreshing the list must retain the queue; leaving/reloading the page should warn
   while tasks remain. Cancel near completion: if the server has already committed
   the file, it may remain, and retry must report a conflict instead of overwriting.
10. Preview UTF-8 and BOM-marked UTF-16 text, an empty text file, and text larger than
    256 KiB. Large text must be truncated with a notice. HTML/SVG and filenames with
    markup must appear as text without executing scripts. Preview PNG, JPEG, GIF,
    WebP, BMP, AVIF and ICO files supported by the browser; a corrupt image should
    report a decoding error, and an image over 20 MiB should offer downloading.
    Close a slow preview and open another: cancelled/stale data must not appear.
    Original downloads must keep the attachment disposition and full file contents.
11. Check desktop and 360 px mobile layouts in light and dark mode. Long filenames
    should truncate with the full name available on hover and in the checkbox's
    accessible label; deep breadcrumbs should wrap. Search is case insensitive; sorting always
    keeps folders first. Confirm empty/no-match states, keyboard focus, Escape to
    close dialogs, and error feedback after stopping the server. Disable external
    Internet access while keeping the local connection: the page must still work.
12. Check that rows stay compact on desktop and mobile, without per-row action
    buttons. Select rows using their checkboxes or empty row space. The bottom
    bar must show the selected count and enable only valid actions. Select all
    with a search active: only visible results should be selected. Hiding a
    selected item by searching must deselect it. Sorting and refreshing should
    retain visible selections; entering another directory must clear selection.
    Rename should enable for one item, and download/preview for one supported file.
    Check that the bar does not cover the final row and respects the mobile safe area.
13. Select several files and nonempty/empty folders, copy, enter another directory,
    and paste. Compare bytes and folder structure; the originals must remain.
    Repeat with cut: originals must disappear only after a successful move. Try
    Chinese names, `+%?#&`, literal `%2e%2e`, same-name destinations, the source
    directory, and a source folder's descendant. Existing destinations must remain
    untouched and self/descendant pastes must be unavailable. A conflict must not
    stop the remaining items; only failed items should remain for retry after a
    partial batch. Cancel the clipboard and confirm cut rows regain their normal
    appearance. Navigate during a slow paste: all items must retain the destination
    captured when Paste was clicked, and repeated Paste must remain disabled.
14. Select several files/folders and delete. Cancel once and verify all survive;
    confirm once and verify the selected items are removed. If one item fails,
    the remaining items should continue, with failures reported as text and kept
    selected if still visible in the same directory. Repeat copies and moves on
    a Storage Access Framework root and an FTP/WebDAV root. Copying a folder with
    a symbolic link must fail without following it or leaving a partial copy.

Management API (requires the same authentication and write permission as uploads):

- `POST /parent/?action=mkdir&name=<percent-encoded single name>` creates a directory
  and returns 201 with its `Location`.
- `POST /path?action=rename&name=<percent-encoded single name>` renames within the
  same parent and returns 200 with the new `Location`.
- `POST /path?action=copy&destination=<percent-encoded root-relative directory>`
  recursively copies a file/folder into that directory under its original name.
  The destination parameter contains a logical path, encoded once as a query
  value (for example `/目标 %2e%2e/` becomes `%2F%E7%9B%AE%E6%A0%87%20%252e%252e%2F`).
  It returns 200 with the new `Location`. Failed copies attempt to clean up their
  newly created destination; if cleanup fails, a partial copy may remain.
- `POST /path?action=move&destination=<percent-encoded root-relative directory>`
  moves into that directory with the same response. Copy/move reject the shared
  root, existing targets, identical source/target and a folder's own descendants.
  Source and destination trees are reserved until the operation and cleanup finish.
- `DELETE /path` deletes a file or recursively deletes a folder and returns 200.
- POST/DELETE require `X-Material-Files: 1`. Parameters are in the URL; no request
  body is needed. Foreign origins, root mutations, duplicate parameters and names
  containing path separators are rejected. These endpoints never replace targets.
- Preview reuses GET with a bounded byte Range. Downloads remain
  `application/octet-stream` attachments. The page displays text through
  `textContent` and raster images through Blob URLs; SVG is previewed as text.

HTTP uses unencrypted HTTP Basic authentication when anonymous login is disabled.
Use it on a trusted local network.
