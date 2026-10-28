#!/usr/bin/env python3
"""Fetch pinned model data for remote MOC2 regression tests (no Lua code)."""

from __future__ import annotations

import concurrent.futures
import json
import sys
import time
import urllib.request
from pathlib import Path, PurePosixPath


REVISION = "c46c254c14cffda57bb4eefe0fdca4ae3cc73206"
BASE = f"https://raw.githubusercontent.com/EasyLive2D/Live2D-v2-Lua/{REVISION}/resources/kasumi2/"


def fetch(destination: Path, relative: str) -> bytes:
    relative = relative.replace("\\", "/")
    parts = PurePosixPath(relative)
    if parts.is_absolute() or ".." in parts.parts:
        raise ValueError(f"invalid fixture path: {relative}")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(BASE + relative, timeout=60) as response:
                data = response.read()
            target = destination.joinpath(*parts.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            return data
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
    raise AssertionError("unreachable")


def main() -> None:
    destination = Path(sys.argv[1]).resolve()
    model = json.loads(fetch(destination, "kasumi2.model.json"))
    files = {model["model"], *model["textures"]}
    files.update(model[key] for key in ("physics", "pose") if model.get(key))
    files.update(expression["file"] for expression in model.get("expressions", []))
    files.update(
        motion["file"]
        for motions in model.get("motions", {}).values()
        for motion in motions[:1]
        if motion.get("file")
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        # Consume all results so any missing fixture fails CI immediately.
        list(executor.map(lambda relative: fetch(destination, relative), sorted(files)))
    print(f"Fetched {len(files) + 1} model resources at {REVISION} into {destination}")


if __name__ == "__main__":
    main()
