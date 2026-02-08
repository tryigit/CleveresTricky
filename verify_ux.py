import sys
import subprocess
import time
from playwright.sync_api import sync_playwright

# Start HTTP server
server = subprocess.Popen(["python3", "-m", "http.server", "8000"])
time.sleep(2)

def verify_ux():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()

        # Mock APIs
        def handle_config(route):
            route.fulfill(json={
                "global_mode": False,
                "tee_broken_mode": False,
                "rkp_bypass": False,
                "auto_beta_fetch": False,
                "auto_keybox_check": False,
                "random_on_boot": False,
                "drm_fix": False,
                "random_drm_on_boot": False,
                "keybox_count": 5
            })
        page.route("**/api/config**", handle_config)

        page.route("**/api/stats**", lambda route: route.fulfill(json={"members": "1234"}))

        page.route("**/api/templates**", lambda route: route.fulfill(json=[
            {"id": "tmpl1", "model": "Pixel 7", "manufacturer": "Google", "fingerprint": "fp1", "securityPatch": "2023-01-01"}
        ]))

        page.route("**/api/packages**", lambda route: route.fulfill(json=["com.android.vending", "com.google.android.gms"]))

        page.route("**/api/keyboxes**", lambda route: route.fulfill(json=["keybox1.xml", "keybox2.xml"]))

        page.route("**/api/app_config_structured**", lambda route: route.fulfill(json=[
            {"package": "com.example.app", "template": "tmpl1", "keybox": "keybox1.xml"}
        ]))

        # Navigate
        page.goto("http://localhost:8000/index.html?token=test-token")

        # Verify UI loaded
        page.wait_for_selector("h1:has-text('CleveresTricky')")

        # Click on Apps tab
        page.click("#tab_apps")

        # Verify Keybox Selector exists
        page.wait_for_selector("#appKeybox")

        # Verify it is an input element
        element = page.locator("#appKeybox")
        tag_name = element.evaluate("el => el.tagName")
        print(f"Keybox element tag name: {tag_name}")

        if tag_name.lower() != "input":
             print("ERROR: expected input, got", tag_name)
             sys.exit(1)

        # Take screenshot
        page.screenshot(path="verification_apps.png")

        browser.close()

try:
    verify_ux()
finally:
    server.terminate()
