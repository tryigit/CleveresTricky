from pathlib import Path

path = Path("module/template/webroot/index.html")
text = path.read_text()
old = """    <script src=\"policy.js?revision=1\"></script>\n    <script src=\"policy.js?revision=1\"></script>\n    <script src=\"policy.js?revision=1\"></script>\n    <script src=\"policy.js?revision=1\"></script>\n"""
new = """    <script src=\"policy.js?revision=1\"></script>\n"""
if old not in text:
    raise SystemExit("duplicate policy.js block not found")
path.write_text(text.replace(old, new, 1))
