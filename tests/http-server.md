# HTTP server verification

The server screen shares login, root directory and write access settings between
FTP and HTTP. Each protocol keeps its own port (FTP: 2121, HTTP: 8080). Stop the
server before switching protocol. With HTTP running, open the displayed URL from
a browser on the same network. Click a file to download it; choose files and click
Upload to upload into the current directory. Existing files are never overwritten.

The release workflow runs `:app:testReleaseUnitTest`. `HttpServerProtocolTest`
covers URL encoding, traversal rejection, byte ranges (including large files),
HTML escaping, parent links and read-only listings.

On an Android device, verify the transport and storage providers as follows:

1. Select an empty test directory, HTTP, anonymous login, and write access.
   Start the server and open its URL. Upload a file named `中文 空格+%?#&.txt`,
   an empty file, and a large file. Download each and compare its contents.
   Retry an upload with the same name: expect HTTP 409 and unchanged contents.
2. Create a subdirectory in the app. Browse into it and back to its parent.
   A URL without the final directory slash should redirect to one with a slash.
3. For a file with known contents, check GET, HEAD, `Range: bytes=2-5`,
   `Range: bytes=2-`, and `Range: bytes=-3`. Expect matching Content-Length and
   Content-Range headers; HEAD must have no response body. A range starting past
   the file length should return 416. HEAD ignores Range.
4. Stop the server, disable anonymous login, set a username and password, then
   restart. A fresh browser session should ask for credentials. Missing, invalid
   or malformed Basic credentials must return 401 for both downloads and uploads.
5. Stop the server, disable write access, then restart. The upload controls should
   disappear, and a direct PUT must return 403. POST, DELETE and OPTIONS are not
   supported and must not modify files.
6. With write access enabled, a PUT with a foreign Origin must return 403.
   Requests for `/../secret`, `/%2e%2e/secret`, and any nested symbolic link must
   not expose files outside the configured root. Use `curl --path-as-is` when
   testing traversal. Symlinks inside the root should be omitted from listings.
7. Interrupt a large upload: the incomplete file should be removed. Stop the
   server during a transfer and confirm the connection closes, the notification
   disappears, and the port can be reused. Repeat start/stop and test an occupied
   port: failure should release locks and return the switch to the stopped state.
8. Repeat browsing, download and upload with a Storage Access Framework root.
   Switch back to FTP and confirm the previous FTP port, login and file operations
   still work. The quick settings tile and notification should control whichever
   protocol is selected.

HTTP uses unencrypted HTTP Basic authentication when anonymous login is disabled.
Use it on a trusted local network.
