#!/usr/bin/env python3
"""Manage copyright headers in Java/Kotlin source files.

Modes (default: --branch):
  --branch    Add nbplugins copyright to files changed vs main...HEAD (one-shot fix).
  --all       Add the full canonical header to every .java/.kt file in Nbm/src/ that
              has no copyright at all.  Safe to run repeatedly (idempotent).
  --check     Exit with code 1 if any .java/.kt file in Nbm/src/ has no copyright line
              or has a wrong nbplugins year. Intended for CI.

Run from the repository root:
    python3 build-scripts/update-copyright.py            # default: --branch
    python3 build-scripts/update-copyright.py --all      # fix all new files
    python3 build-scripts/update-copyright.py --check    # CI check
"""

import datetime
import re
import subprocess
import sys
from pathlib import Path

_NBPLUGINS_PATTERN = re.compile(
    r" \* Copyright (\d{4})(?:-(\d{4}))? nbplugins contributors"
)


def get_git_modification_year(path: Path) -> int:
    """Return the year of the last commit touching path since 2026-01-01, or current year."""
    result = subprocess.run(
        ["git", "log", "-1", "--format=%ci", "--after=2025-12-31", "--", str(path)],
        capture_output=True, text=True, check=True,
    )
    if result.stdout.strip():
        return int(result.stdout.strip()[:4])
    return datetime.date.today().year


def nbplugins_line(year: int) -> str:
    return f" * Copyright {year} nbplugins contributors"


def full_header(year: int) -> str:
    return f"""\
/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
 * Copyright {year} nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
"""


def get_branch_files() -> list[Path]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "main...HEAD"],
        capture_output=True, text=True, check=True,
    )
    return [
        Path(p) for p in result.stdout.splitlines()
        if p.endswith((".java", ".kt")) and Path(p).exists()
    ]


_STUB_PACKAGES = (
    "com/intellij/",
    "com/google/",
    "com/jetbrains/",
    "org/netbeans/",
)


def get_all_source_files() -> list[Path]:
    """Return all .java/.kt files in src/main/java, src/main/kotlin and test equivalents, excluding IntelliJ stubs."""
    roots = [
        Path("Nbm/src/main/java"),
        Path("Nbm/src/main/kotlin"),
        Path("Nbm/src/test/java"),
        Path("Nbm/src/test/kotlin"),
    ]
    result = []
    for root in roots:
        for p in root.rglob("*"):
            if p.suffix not in (".java", ".kt") or not p.is_file():
                continue
            rel = p.relative_to(root).as_posix()
            if any(rel.startswith(stub) for stub in _STUB_PACKAGES):
                continue
            result.append(p)
    return result


def has_any_copyright(text: str) -> bool:
    return "Copyright" in text


def process_file(path: Path, only_missing: bool = False) -> str:
    """Return 'updated' or 'no-change'.

    only_missing=True: add header only if no copyright exists (--all mode).
    only_missing=False: also insert nbplugins line into existing JetBrains headers (--branch mode).
    """
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    year = get_git_modification_year(path)
    line = nbplugins_line(year)

    # Already has the nbplugins line → nothing to do
    if _NBPLUGINS_PATTERN.search(text):
        return "no-change"

    if not only_missing:
        # --- Case 1: file has a JetBrains copyright line → insert nbplugins line after it ---
        for i, ln in enumerate(lines):
            if "JetBrains" in ln and "Copyright" in ln:
                lines.insert(i + 1, line + "\n")
                path.write_text("".join(lines), encoding="utf-8")
                return "updated"

    # --- Case 2: no copyright header at all → prepend full canonical header ---
    if has_any_copyright(text):
        # Has some copyright but not nbplugins — only reached in --all mode.
        # Don't touch; the user may want to add the nbplugins line manually or via --branch.
        return "no-change"

    # Find insertion point: after leading @file:OptIn annotation(s), if any
    insert_at = 0
    for i, ln in enumerate(lines):
        stripped = ln.strip()
        if stripped.startswith("@file:"):
            insert_at = i + 1
        elif stripped:
            break

    lines.insert(insert_at, full_header(year))
    path.write_text("".join(lines), encoding="utf-8")
    return "updated"


def cmd_branch() -> None:
    files = get_branch_files()
    counts = {"updated": 0, "no-change": 0}
    for path in files:
        status = process_file(path, only_missing=False)
        counts[status] += 1
        if status == "updated":
            print(f"  updated  {path}")
    print(
        f"\nDone: {counts['updated']} updated, "
        f"{counts['no-change']} already had the line."
    )


def cmd_all() -> None:
    files = get_all_source_files()
    counts = {"updated": 0, "no-change": 0}
    for path in files:
        status = process_file(path, only_missing=True)
        counts[status] += 1
        if status == "updated":
            print(f"  updated  {path}")
    print(
        f"\nDone: {counts['updated']} updated, "
        f"{counts['no-change']} already had a copyright or the nbplugins line."
    )


def cmd_check() -> None:
    files = get_all_source_files()
    errors: list[str] = []

    for path in files:
        text = path.read_text(encoding="utf-8")
        if not has_any_copyright(text):
            errors.append(f"  MISSING header: {path}")
            continue
        m = _NBPLUGINS_PATTERN.search(text)
        if not m:
            continue  # has JetBrains-only copyright, no nbplugins line yet — not an error here
        # end year is group 2 if range (e.g. 2024-2026), else group 1
        header_year = int(m.group(2) or m.group(1))
        expected_year = get_git_modification_year(path)
        if header_year != expected_year:
            errors.append(
                f"  WRONG YEAR ({header_year} ≠ {expected_year}): {path}"
            )

    if errors:
        print("ERROR: copyright header issues found:")
        for e in errors:
            print(e)
        print(
            f"\n{len(errors)} issue(s). "
            "Run: python3 build-scripts/update-copyright.py --all"
        )
        sys.exit(1)
    else:
        print(f"OK: all {len(files)} Java/Kotlin files pass copyright check.")


def main() -> None:
    mode = sys.argv[1] if len(sys.argv) > 1 else "--branch"
    if mode == "--branch":
        cmd_branch()
    elif mode == "--all":
        cmd_all()
    elif mode == "--check":
        cmd_check()
    else:
        print(f"Unknown mode: {mode}. Use --branch, --all, or --check.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
