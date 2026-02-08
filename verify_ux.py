from playwright.sync_api import sync_playwright, expect

def run(playwright):
    browser = playwright.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto("http://localhost:8000/index.html")

    # verify title
    expect(page).to_have_title("CleveresTricky")

    # verify focus style on Global Mode toggle
    # Click on the tab to ensure focus is near
    page.click("#tab_dashboard")
    # Tab to the first toggle
    # The structure is: div.row > label, input.toggle
    # Input is after label.
    # We can just focus it directly using locator
    toggle = page.locator("#global_mode")
    toggle.focus()

    # Take screenshot of dashboard with focus
    page.screenshot(path="verification_dashboard_focus.png")

    # Go to Apps tab
    page.click("#tab_apps")

    # Verify Blank Permissions section
    expect(page.get_by_text("Blank Permissions (Privacy)")).to_be_visible()

    contacts_cb = page.locator("#permContacts")
    expect(contacts_cb).to_be_disabled()
    expect(contacts_cb).to_have_attribute("title", "Coming Soon")

    media_cb = page.locator("#permMedia")
    expect(media_cb).to_be_disabled()
    expect(media_cb).to_have_attribute("title", "Coming Soon")

    # Take screenshot of Apps tab
    page.screenshot(path="verification_apps_disabled.png")

    browser.close()

with sync_playwright() as playwright:
    run(playwright)
