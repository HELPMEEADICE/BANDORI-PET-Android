#!/usr/bin/env python3
"""Validate checked-in JSON/XML assets and their cross-file references."""

from __future__ import annotations

import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]


class DuplicateJsonKeyError(ValueError):
    pass


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKeyError(f"duplicate key: {key!r}")
        result[key] = value
    return result


def tracked_files(pattern: str) -> list[Path]:
    output = subprocess.run(
        ["git", "ls-files", "-z", pattern],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    ).stdout
    return [ROOT / item.decode("utf-8") for item in output.split(b"\0") if item]


def require_object(value: Any, label: str, errors: list[str]) -> dict[str, Any] | None:
    if isinstance(value, dict):
        return value
    errors.append(f"{label}: expected a JSON object")
    return None


def main() -> int:
    errors: list[str] = []
    parsed_json: dict[str, Any] = {}

    json_files = tracked_files("*.json")
    for path in json_files:
        relative = path.relative_to(ROOT).as_posix()
        try:
            parsed_json[relative] = json.loads(
                path.read_text(encoding="utf-8"),
                object_pairs_hook=reject_duplicate_keys,
            )
        except (OSError, UnicodeError, json.JSONDecodeError, DuplicateJsonKeyError) as error:
            errors.append(f"{relative}: {error}")

    xml_files = tracked_files("*.xml")
    for path in xml_files:
        relative = path.relative_to(ROOT).as_posix()
        try:
            ET.parse(path)
        except (OSError, ET.ParseError) as error:
            errors.append(f"{relative}: {error}")

    band_data = require_object(parsed_json.get("band.json"), "band.json", errors)
    outfit_data = require_object(parsed_json.get("outfit.json"), "outfit.json", errors)
    prompt_data = require_object(parsed_json.get("prompt.json"), "prompt.json", errors)

    band_ids: set[str] = set()
    band_character_ids: set[str] = set()
    if band_data is not None:
        bands = band_data.get("bands")
        if not isinstance(bands, list):
            errors.append("band.json: 'bands' must be an array")
        else:
            for index, band in enumerate(bands):
                label = f"band.json: bands[{index}]"
                if not isinstance(band, dict):
                    errors.append(f"{label} must be an object")
                    continue

                band_id = band.get("id")
                if not isinstance(band_id, str) or not band_id:
                    errors.append(f"{label}.id must be a non-empty string")
                elif band_id in band_ids:
                    errors.append(f"{label}.id duplicates {band_id!r}")
                else:
                    band_ids.add(band_id)

                character_ids = band.get("characters")
                if not isinstance(character_ids, list) or not all(
                    isinstance(character_id, str) and character_id
                    for character_id in character_ids
                ):
                    errors.append(f"{label}.characters must be an array of non-empty strings")
                else:
                    if len(character_ids) != len(set(character_ids)):
                        errors.append(f"{label}.characters contains duplicate IDs")
                    band_character_ids.update(character_ids)

                display = band.get("display")
                if not isinstance(display, str) or not display:
                    errors.append(f"{label}.display must be a non-empty string")

                logo = band.get("logo")
                if logo is not None:
                    if not isinstance(logo, str):
                        errors.append(f"{label}.logo must be a relative path string")
                    else:
                        logo_path = (ROOT / logo).resolve()
                        if not logo_path.is_relative_to(ROOT) or not logo_path.is_file():
                            errors.append(f"{label}.logo points to a missing file: {logo!r}")

    outfit_characters: set[str] = set()
    if outfit_data is not None:
        characters = require_object(outfit_data.get("characters"), "outfit.json.characters", errors)
        if characters is not None:
            outfit_characters = set(characters)
            for character_id, character in characters.items():
                label = f"outfit.json.characters.{character_id}"
                if not isinstance(character, dict):
                    errors.append(f"{label} must be an object")
                    continue
                display = character.get("display", character_id)
                if not isinstance(display, str) or not display:
                    errors.append(f"{label}.display must be a non-empty string")
                costumes = character.get("costumes", {})
                if not isinstance(costumes, dict) or not all(
                    isinstance(costume_name, str) for costume_name in costumes.values()
                ):
                    errors.append(f"{label}.costumes must map IDs to string names")

    if band_character_ids != outfit_characters:
        missing = sorted(band_character_ids - outfit_characters)
        ungrouped = sorted(outfit_characters - band_character_ids)
        if missing:
            errors.append(f"band.json references IDs missing from outfit.json: {', '.join(missing)}")
        if ungrouped:
            errors.append(f"outfit.json IDs missing from band.json: {', '.join(ungrouped)}")

    if prompt_data is not None:
        prompt_characters = require_object(
            prompt_data.get("characters"), "prompt.json.characters", errors
        )
        if prompt_characters is not None and outfit_characters:
            missing = sorted(outfit_characters - set(prompt_characters))
            extra = sorted(set(prompt_characters) - outfit_characters)
            if missing:
                errors.append(f"outfit.json IDs missing from prompt.json: {', '.join(missing)}")
            if extra:
                errors.append(f"prompt.json IDs missing from outfit.json: {', '.join(extra)}")
            for character_id, character in prompt_characters.items():
                label = f"prompt.json.characters.{character_id}"
                if not isinstance(character, dict):
                    errors.append(f"{label} must be an object")
                    continue
                if not isinstance(character.get("identity", ""), str):
                    errors.append(f"{label}.identity must be a string")
                tags = character.get("tags", [])
                if not isinstance(tags, list) or not all(isinstance(tag, str) for tag in tags):
                    errors.append(f"{label}.tags must be an array of strings")

    language_files = {
        path.stem: parsed_json[path.relative_to(ROOT).as_posix()]
        for path in tracked_files("lang/*.json")
        if path.relative_to(ROOT).as_posix() in parsed_json
    }
    missing_languages = sorted({"en", "ja", "zh"} - set(language_files))
    if missing_languages:
        expected = ", ".join(f"lang/{language}.json" for language in missing_languages)
        errors.append(f"Missing translation files: {expected}")

    english = language_files.get("en")
    if isinstance(english, dict):
        english_keys = set(english)
        for language, translations in sorted(language_files.items()):
            if not isinstance(translations, dict):
                errors.append(f"lang/{language}.json: expected a JSON object")
                continue
            if not all(isinstance(value, str) for value in translations.values()):
                errors.append(f"lang/{language}.json: all translation values must be strings")
            missing = sorted(english_keys - set(translations))
            extra = sorted(set(translations) - english_keys)
            if missing:
                errors.append(f"lang/{language}.json is missing keys: {', '.join(missing)}")
            if extra:
                errors.append(f"lang/{language}.json has extra keys: {', '.join(extra)}")

    if errors:
        print("Project data checks failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"Validated {len(json_files)} JSON and {len(xml_files)} XML files.")
    print("Band, outfit, prompt, and translation references are consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
