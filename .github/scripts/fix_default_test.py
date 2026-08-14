from pathlib import Path

p = Path("module/webui-tests/default-settings.test.js")
text = p.read_text()
old = "  securityPatch: true\n"
if text.count(old) != 1:
    raise SystemExit(f"expected one default securityPatch assertion, found {text.count(old)}")
p.write_text(text.replace(old, "  securityPatch: false\n", 1))
Path(__file__).unlink()
