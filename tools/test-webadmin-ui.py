"""Playwright UI checks with mocked APIs; no live DrFTPD or srrDB writes."""
import functools
import http.server
import json
import pathlib
import threading
import time
from playwright.sync_api import sync_playwright, expect

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/plugins/webadmin/src/main/resources/webadmin"
OUTPUT = ROOT / "src/plugins/webadmin/target"


class Preview(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] in ("/", "/login", "/home"):
            self.path = "/index.html"
        super().do_GET()

    def log_message(self, *args):
        pass


def check(browser, base, width, height):
    context = browser.new_context(viewport={"width": width, "height": height})
    page = context.new_page()
    page.clock.install()
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    signed_in = False
    decisions = []
    file = {
        "id": "review-1",
        "releasePath": "/ANiMES-FR-HD/Akuma-Kun.2023.E11.SUBFRENCH.1080p.WEB.x264-GROUP",
        "file": "akuma-kun.2023.e11.subfrench.1080p.web.x264-group.sfv",
        "size": 2200, "crc": "AABBCCDD", "state": "pending", "message": "",
        "requestedBy": "siteop", "decidedBy": "",
        "source": "https://www.srrdb.com/details/Release-GROUP",
    }
    review = {
        "files": [], "scanState": "idle", "scanMessage": "", "scanned": 0,
        "total": 0, "errors": 0, "scanErrors": [], "scanLimit": 100,
    }

    def api(route):
        nonlocal signed_in
        path = route.request.url.split("/api/", 1)[1]
        payload = route.request.post_data_json if route.request.post_data else {}
        now = int(time.time() * 1000)
        if path == "login":
            signed_in = payload.get("username") == "siteop" and payload.get("password") == "test"
            response = {"username": "siteop", "csrf": "test", "serverTime": now, "expiresAt": now + 86400000}
            route.fulfill(status=200 if signed_in else 401, json=response if signed_in else {"error": "Invalid username or password"})
            return
        if not signed_in:
            route.fulfill(status=401, json={"error": "Authentication required"})
            return
        if path == "session":
            response = {"username": "siteop", "csrf": "test", "serverTime": now, "expiresAt": now + 86400000}
        elif path == "logout":
            signed_in = False
            response = {"ok": True}
        elif path == "overview":
            response = {
                "onlineSlaves": 1, "totalSlaves": 1, "remergingSlaves": 0,
                "freeBytes": 12345678900, "heapUsedBytes": 23456789, "heapMaximumBytes": 45678900,
                "processors": 16, "uptimeMillis": 65000,
                "slaves": [{"name": "TestSlave", "online": True, "remerging": False,
                            "uploadTransfers": 1, "downloadTransfers": 0, "uploadBytesPerSecond": 50000000,
                            "downloadBytesPerSecond": 0, "freeBytes": 12345678900, "capacityBytes": 34567890000,
                            "renameQueue": 0, "remergeQueue": 0, "crcQueue": 0}],
            }
        elif path == "srrdb":
            action = payload.get("action")
            if action == "scan":
                review.update(files=[dict(file)], scanState="completed", scanned=1, total=1, scanMessage="Scan finished")
            elif action in ("accept", "reject"):
                decisions.append(action)
                review["files"][0]["state"] = "installed" if action == "accept" else "rejected"
                review["files"][0]["decidedBy"] = "siteop"
                review["files"][0]["message"] = "Verified and imported on TestSlave" if action == "accept" else ""
            response = review
        else:
            response = {}
        route.fulfill(json=response)

    page.route("**/api/**", api)
    page.goto(base)
    expect(page.locator("#loginView")).to_be_visible()
    expect(page.locator("#appView")).to_be_hidden()
    page.screenshot(path=str(OUTPUT / f"webadmin-login-{width}.png"), full_page=True)
    page.locator("#username").fill("siteop")
    page.locator("#password").fill("wrong")
    page.locator("#loginForm button").click()
    expect(page.locator("#loginError")).to_have_text("Invalid username or password")
    page.locator("#password").fill("test")
    page.locator("#loginForm button").click()
    expect(page).to_have_url(base + "home")
    expect(page.locator("#loginView")).to_be_hidden()
    expect(page.locator("#appView")).to_be_visible()
    expect(page.locator("#metricSlaves")).to_have_text("1 / 1")
    page.screenshot(path=str(OUTPUT / f"webadmin-home-{width}.png"), full_page=True)
    if width < 760:
        page.locator("#menuButton").click()
    page.locator('[data-view="srrdb"]').click()
    page.locator("#recoveryPath").fill(file["releasePath"])
    page.locator("#scanRecovery").click()
    page.locator("#confirmAccept").click()
    expect(page.locator("#recoveryRows")).to_contain_text(file["file"])
    assert decisions == [], "A scan must not approve any download"
    page.screenshot(path=str(OUTPUT / f"webadmin-srrdb-{width}.png"), full_page=True)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Page overflows viewport"
    page.get_by_role("button", name="Accept", exact=True).click()
    page.locator('#confirmDialog button[value="cancel"]').click()
    assert decisions == [], "Cancelling confirmation must not download"
    page.get_by_role("button", name="Accept", exact=True).click()
    page.locator("#confirmAccept").click()
    expect(page.locator("#recoveryRows")).to_contain_text("Verified and imported")
    assert decisions == ["accept"]
    if width < 760:
        page.locator("#menuButton").click()
    page.locator('[data-view="overview"]').click()
    signed_in = False
    page.clock.fast_forward(86400001)
    expect(page.locator("#loginView")).to_be_visible()
    expect(page.locator("#appView")).to_be_hidden()
    expect(page.locator("#loginError")).to_contain_text("Session expired")
    assert not errors, errors
    context.close()
    print(f"PASS login, home, approval, cancellation, expiry and layout at {width}x{height}")


if __name__ == "__main__":
    OUTPUT.mkdir(parents=True, exist_ok=True)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Preview, directory=str(ASSETS)))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel="chrome", headless=True)
            try:
                for viewport in ((1440, 1000), (390, 844)):
                    check(browser, f"http://127.0.0.1:{server.server_port}/", *viewport)
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
