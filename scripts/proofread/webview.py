"""The app's settings screen, driven the way the extension's is.

It is the same screen: one set of Svelte components, built into the app's assets and drawn in
a web view. So it is asked the same questions, over the same protocol, rather than scraped out
of an accessibility dump - which sees a web view as one blank view and would report every row
of it missing.

Debug builds only: the app turns web contents debugging on for them and never for a release.
"""
import json
import socket
import subprocess
import time
import urllib.request

from android_harness import SERIAL, adb, shell

PORT = 9333


class View:
    """One open page inside the app, asked and answered over DevTools."""

    def __init__(self, port=PORT):
        self.port = port
        self.ws = None
        self.next = 1

    def __enter__(self):
        self.open()
        return self

    def __exit__(self, *_):
        self.close()

    def open(self):
        pid = shell("pidof", "io.github.nanbiz.phonetix").strip().split()
        if not pid:
            raise RuntimeError("the app is not running")
        socket_name = f"webview_devtools_remote_{pid[0]}"
        adb("forward", f"tcp:{self.port}", f"localabstract:{socket_name}")
        # The page takes a moment to register itself after the view is built.
        for _ in range(20):
            pages = self._pages()
            ours = [p for p in pages if p.get("type") == "page" and "index.html" in p.get("url", "")]
            if ours:
                self._connect(ours[0]["webSocketDebuggerUrl"])
                return
            time.sleep(0.5)
        raise RuntimeError(f"no settings page among {[p.get('url') for p in self._pages()]}")

    def _pages(self):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/json/list", timeout=5) as r:
                return json.loads(r.read().decode())
        except Exception:
            return []

    def _connect(self, url):
        # A websocket by hand rather than a dependency: one frame out, one frame back, no
        # extensions and no fragmentation, which is all this protocol needs here.
        import base64
        import os

        rest = url.split("://", 1)[1]
        host, path = rest.split("/", 1)
        hostname, port = host.split(":")
        self.ws = socket.create_connection((hostname, int(port)), timeout=20)
        key = base64.b64encode(os.urandom(16)).decode()
        self.ws.sendall(
            f"GET /{path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n".encode()
        )
        answered = b""
        while b"\r\n\r\n" not in answered:
            answered += self.ws.recv(4096)
        if b"101" not in answered.split(b"\r\n")[0]:
            raise RuntimeError(f"the view refused the connection: {answered[:120]!r}")
        self.rest = answered.split(b"\r\n\r\n", 1)[1]

    def _send(self, text):
        import os

        payload = text.encode()
        header = bytearray([0x81])
        mask = os.urandom(4)
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 1 << 16:
            header.append(0x80 | 126)
            header += length.to_bytes(2, "big")
        else:
            header.append(0x80 | 127)
            header += length.to_bytes(8, "big")
        header += mask
        self.ws.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def _read(self):
        def take(n):
            while len(self.rest) < n:
                more = self.ws.recv(65536)
                if not more:
                    raise RuntimeError("the view closed the connection")
                self.rest += more
            out, self.rest = self.rest[:n], self.rest[n:]
            return out

        first = take(2)
        length = first[1] & 0x7F
        if length == 126:
            length = int.from_bytes(take(2), "big")
        elif length == 127:
            length = int.from_bytes(take(8), "big")
        return take(length).decode()

    def ask(self, method, params=None, timeout=30):
        self.next += 1
        want = self.next
        self._send(json.dumps({"id": want, "method": method, "params": params or {}}))
        until = time.time() + timeout
        while time.time() < until:
            self.ws.settimeout(max(1, until - time.time()))
            said = json.loads(self._read())
            if said.get("id") == want:
                return said.get("result", {})
        raise RuntimeError(f"{method} was not answered")

    def evaluate(self, expression):
        """One expression in the page, answered as a string or None."""
        said = self.ask(
            "Runtime.evaluate",
            {"expression": expression, "returnByValue": True, "awaitPromise": True},
        )
        return said.get("result", {}).get("value")

    def close(self):
        if self.ws:
            try:
                self.ws.close()
            except OSError:
                pass
            self.ws = None
        subprocess.run(["adb", "-s", SERIAL, "forward", "--remove", f"tcp:{self.port}"],
                       capture_output=True)
