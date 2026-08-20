#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "service/src/main/java/cleveres/tricky/cleverestech/ServerManager.kt"
text = path.read_text(encoding="utf-8")
old = 'throw SecurityException("Server response exceeds ${maxResponseSize / 1024 / 1024}MB limit")'
new = 'throw SecurityException("Server response exceeds 10MB limit")'
if text.count(old) != 1:
    raise SystemExit(f"expected exactly one compile-error expression, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
