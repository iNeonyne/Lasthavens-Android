#!/usr/bin/env python3
"""Validate the versioned APK, legacy updater alias and update manifest assets."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

CANONICAL_MANIFEST = "lasthavenplus-update.json"
COMPATIBILITY_APK = "lasthavenplus-arm64.apk"
LEGACY_ASSETS = {"lasthavenlauncher-arm64.apk", "lasthavenlauncher-update.json"}


def validate_release_assets(release: dict[str, Any], expected_tag: str) -> None:
    if not isinstance(release, dict):
        raise ValueError("release metadata must be a JSON object")
    if not isinstance(expected_tag, str) or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", expected_tag):
        raise ValueError("release tag must use vMAJOR.MINOR.PATCH")
    if release.get("tag_name") != expected_tag:
        raise ValueError(f"release tag must be {expected_tag}")
    version = expected_tag[1:]
    expected_apk = f"LastHavenPlus-{version}-stable-arm64-v8a.apk"

    assets = release.get("assets")
    if not isinstance(assets, list):
        raise ValueError("release assets must be a list")

    names = []
    assets_by_name = {}
    for asset in assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str):
            raise ValueError("every release asset must have a string name")
        names.append(asset["name"])
        assets_by_name[asset["name"]] = asset

    apk_names = [name for name in names if name.lower().endswith(".apk")]
    if len(apk_names) != 2 or sorted(apk_names) != sorted([expected_apk, COMPATIBILITY_APK]):
        raise ValueError(
            "release must contain exactly one versioned APK named "
            f"{expected_apk} and one legacy updater alias named "
            f"{COMPATIBILITY_APK}; found {apk_names}"
        )

    versioned_size = assets_by_name[expected_apk].get("size")
    compatibility_size = assets_by_name[COMPATIBILITY_APK].get("size")
    if (
        type(versioned_size) is not int
        or versioned_size <= 0
        or type(compatibility_size) is not int
        or compatibility_size != versioned_size
    ):
        raise ValueError("legacy updater APK alias size must match the versioned APK")

    if names.count(CANONICAL_MANIFEST) != 1:
        raise ValueError(f"release must contain exactly one {CANONICAL_MANIFEST}")
    legacy = sorted(LEGACY_ASSETS.intersection(names))
    if legacy:
        raise ValueError(f"obsolete release assets must be absent: {legacy}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("release_json", type=Path)
    parser.add_argument("expected_tag")
    args = parser.parse_args()

    try:
        with args.release_json.open(encoding="utf-8") as source:
            release = json.load(source)
        validate_release_assets(release, args.expected_tag)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"release asset validation failed: {error}", file=sys.stderr)
        return 1

    print(
        f"Verified {args.expected_tag}: one versioned APK, "
        f"one legacy updater alias and one update manifest."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
