#!/usr/bin/env python3
"""Fail when API-coupled Rust dependencies drift back to per-crate versions."""

from __future__ import annotations

import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANAGED = ("aes-gcm", "getrandom", "pbkdf2")


def load(path: Path) -> dict:
    with path.open("rb") as handle:
        return tomllib.load(handle)


def main() -> int:
    root_manifest = load(ROOT / "Cargo.toml")
    workspace = root_manifest.get("workspace", {})
    workspace_dependencies = workspace.get("dependencies", {})
    errors: list[str] = []

    for dependency in MANAGED:
        spec = workspace_dependencies.get(dependency)
        if spec is None:
            errors.append(f"rust/Cargo.toml: missing [workspace.dependencies].{dependency}")
        elif isinstance(spec, dict) and spec.get("workspace"):
            errors.append(
                f"rust/Cargo.toml: workspace dependency {dependency} cannot inherit itself"
            )

    for member in workspace.get("members", []):
        manifest_path = ROOT / member / "Cargo.toml"
        manifest = load(manifest_path)
        for table_name in ("dependencies", "dev-dependencies", "build-dependencies"):
            table = manifest.get(table_name, {})
            for dependency in MANAGED:
                if dependency not in table:
                    continue
                spec = table[dependency]
                if not isinstance(spec, dict) or spec.get("workspace") is not True:
                    errors.append(
                        f"{manifest_path.relative_to(ROOT.parent)}: {dependency} must use "
                        "{ workspace = true } so the compatibility lane has one version owner"
                    )
                elif "version" in spec:
                    errors.append(
                        f"{manifest_path.relative_to(ROOT.parent)}: {dependency} must not add "
                        "a member-level version when workspace = true"
                    )

    if errors:
        print("Rust dependency policy violations:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    print(
        "Rust dependency policy OK: aes-gcm, getrandom and pbkdf2 are owned by "
        "[workspace.dependencies]."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
