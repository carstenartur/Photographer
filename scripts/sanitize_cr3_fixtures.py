#!/usr/bin/env python3
"""Remove identifying metadata from committed CR3 fixtures without changing layout.

The sanitizer deliberately preserves every file size and every BMFF/TIFF offset. It
neutralizes complete TIFF IFD entries that may contain camera, lens or owner identity,
zeros their backing bytes, and removes XMP dc:creator text in place. Raw image payloads
and unrelated technical metadata are not rewritten.
"""

from __future__ import annotations

import argparse
import os
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

CMT_TYPES = (b"CMT1", b"CMT2", b"CMT3")

# Standard TIFF/EXIF identity fields.
CMT_SENSITIVE_TAGS: dict[bytes, frozenset[int]] = {
    b"CMT1": frozenset({0x013B}),  # Artist
    b"CMT2": frozenset(
        {
            0xA430,  # CameraOwnerName / OwnerName
            0xA431,  # BodySerialNumber / SerialNumber
            0xA435,  # LensSerialNumber
        }
    ),
    b"CMT3": frozenset(
        {
            0x0009,  # Canon OwnerName
            0x000C,  # Canon camera serial
            0x000D,  # Canon CameraInfo; model-specific owner/lens serial slots
            0x0096,  # Canon SerialInfo / InternalSerialNumber
            0x4019,  # Canon LensInfo / internal lens serial
        }
    ),
}

TIFF_TYPE_SIZES = {
    1: 1,   # BYTE
    2: 1,   # ASCII
    3: 2,   # SHORT
    4: 4,   # LONG
    5: 8,   # RATIONAL
    6: 1,   # SBYTE
    7: 1,   # UNDEFINED
    8: 2,   # SSHORT
    9: 4,   # SLONG
    10: 8,  # SRATIONAL
    11: 4,  # FLOAT
    12: 8,  # DOUBLE
    13: 4,  # IFD
}

MAX_IFD_ENTRIES = 4096
MAX_VALUE_BYTES = 16 * 1024 * 1024
REDACTED_TAG_ID = 0xFFFF


class SanitizationError(RuntimeError):
    """Raised when a fixture cannot be sanitized without guessing."""


@dataclass(frozen=True)
class BoxLocation:
    box_type: bytes
    start: int
    data_start: int
    end: int


@dataclass(frozen=True)
class SanitizationResult:
    path: Path
    tiff_entries: int
    xmp_packets: int
    changed: bool


def _u32_be(data: bytes | bytearray, offset: int) -> int:
    return struct.unpack_from(">I", data, offset)[0]


def _u64_be(data: bytes | bytearray, offset: int) -> int:
    return struct.unpack_from(">Q", data, offset)[0]


def locate_cmt_boxes(data: bytes | bytearray) -> list[BoxLocation]:
    """Locate CMT boxes by validated BMFF headers.

    CR3 CMT payloads start directly with a TIFF header. Searching for the four-byte
    box type and validating size, bounds and TIFF magic avoids dependence on one
    particular nesting arrangement while rejecting payload coincidences.
    """
    locations: list[BoxLocation] = []
    for box_type in CMT_TYPES:
        cursor = 0
        while True:
            type_offset = data.find(box_type, cursor)
            if type_offset < 0:
                break
            cursor = type_offset + 1
            start = type_offset - 4
            if start < 0 or start + 8 > len(data):
                continue
            size32 = _u32_be(data, start)
            if size32 == 1:
                if start + 16 > len(data):
                    continue
                size = _u64_be(data, start + 8)
                data_start = start + 16
            elif size32 == 0:
                size = len(data) - start
                data_start = start + 8
            else:
                size = size32
                data_start = start + 8
            end = start + size
            if size < data_start - start + 8 or end > len(data):
                continue
            if not _looks_like_tiff(data, data_start, end):
                continue
            locations.append(BoxLocation(box_type, start, data_start, end))
    locations.sort(key=lambda item: item.start)
    return locations


def _looks_like_tiff(data: bytes | bytearray, start: int, end: int) -> bool:
    if start + 8 > end:
        return False
    marker = bytes(data[start : start + 2])
    if marker == b"II":
        return struct.unpack_from("<H", data, start + 2)[0] == 42
    if marker == b"MM":
        return struct.unpack_from(">H", data, start + 2)[0] == 42
    return False


def _read_u16(data: bytes | bytearray, offset: int, endian: str) -> int:
    return struct.unpack_from(endian + "H", data, offset)[0]


def _read_u32(data: bytes | bytearray, offset: int, endian: str) -> int:
    return struct.unpack_from(endian + "I", data, offset)[0]


def _write_u16(data: bytearray, offset: int, endian: str, value: int) -> None:
    struct.pack_into(endian + "H", data, offset, value)


def neutralize_ifd_tags(data: bytearray, box: BoxLocation) -> int:
    """Neutralize configured identity tags in one TIFF-based CMT box."""
    marker = bytes(data[box.data_start : box.data_start + 2])
    endian = "<" if marker == b"II" else ">"
    ifd_relative = _read_u32(data, box.data_start + 4, endian)
    ifd_start = box.data_start + ifd_relative
    if ifd_relative == 0 or ifd_start + 2 > box.end:
        raise SanitizationError(
            f"{box.box_type.decode()}: invalid first IFD offset {ifd_relative}"
        )
    entry_count = _read_u16(data, ifd_start, endian)
    if entry_count > MAX_IFD_ENTRIES:
        raise SanitizationError(
            f"{box.box_type.decode()}: unreasonable IFD entry count {entry_count}"
        )

    targets = CMT_SENSITIVE_TAGS[box.box_type]
    neutralized = 0
    for index in range(entry_count):
        entry = ifd_start + 2 + index * 12
        if entry + 12 > box.end:
            raise SanitizationError(
                f"{box.box_type.decode()}: truncated IFD entry {index}"
            )
        tag = _read_u16(data, entry, endian)
        if tag not in targets:
            continue
        data_type = _read_u16(data, entry + 2, endian)
        count = _read_u32(data, entry + 4, endian)
        type_size = TIFF_TYPE_SIZES.get(data_type)
        if type_size is None:
            raise SanitizationError(
                f"{box.box_type.decode()}: unsupported TIFF type {data_type} "
                f"for sensitive tag 0x{tag:04x}"
            )
        value_size = count * type_size
        if value_size > MAX_VALUE_BYTES:
            raise SanitizationError(
                f"{box.box_type.decode()}: unreasonable sensitive value size "
                f"{value_size} for tag 0x{tag:04x}"
            )
        if value_size <= 4:
            value_start = entry + 8
            value_end = entry + 12
        else:
            relative = _read_u32(data, entry + 8, endian)
            value_start = box.data_start + relative
            value_end = value_start + value_size
            if value_start < box.data_start or value_end > box.end:
                raise SanitizationError(
                    f"{box.box_type.decode()}: sensitive tag 0x{tag:04x} "
                    "points outside its box"
                )
        data[value_start:value_end] = b"\x00" * (value_end - value_start)
        _write_u16(data, entry, endian, REDACTED_TAG_ID)
        neutralized += 1
    return neutralized


def neutralize_xmp_creator(data: bytearray) -> int:
    """Remove XMP dc:creator values in place and make the property unrecognized."""
    opening = b"<dc:creator"
    closing = b"</dc:creator>"
    replacement_name = b"dc:creat0r"  # same byte length; valid but nonstandard XML name
    packets = 0
    cursor = 0
    while True:
        start = data.find(opening, cursor)
        if start < 0:
            break
        open_end = data.find(b">", start + len(opening))
        close_start = data.find(closing, open_end + 1 if open_end >= 0 else start)
        if open_end < 0 or close_start < 0:
            raise SanitizationError("unterminated XMP dc:creator element")
        close_end = close_start + len(closing)

        # Remove all character data while retaining XML markup and exact byte length.
        inside_tag = False
        for position in range(open_end + 1, close_start):
            value = data[position]
            if value == ord("<"):
                inside_tag = True
            elif value == ord(">"):
                inside_tag = False
            elif not inside_tag and value not in (ord("\r"), ord("\n"), ord("\t")):
                data[position] = ord(" ")

        data[start + 1 : start + 1 + len(replacement_name)] = replacement_name
        closing_name_start = close_start + 2
        data[
            closing_name_start : closing_name_start + len(replacement_name)
        ] = replacement_name
        packets += 1
        cursor = close_end
    return packets


def sanitize_bytes(original: bytes) -> tuple[bytes, int, int]:
    data = bytearray(original)
    boxes = locate_cmt_boxes(data)
    if not boxes:
        raise SanitizationError("no TIFF-based CMT boxes found")
    entries = sum(neutralize_ifd_tags(data, box) for box in boxes)
    xmp_packets = neutralize_xmp_creator(data)
    if len(data) != len(original):
        raise AssertionError("sanitization changed file size")
    return bytes(data), entries, xmp_packets


def sanitize_file(path: Path, *, write: bool) -> SanitizationResult:
    original = path.read_bytes()
    sanitized, entries, xmp_packets = sanitize_bytes(original)
    changed = sanitized != original
    if write and changed:
        mode = path.stat().st_mode
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=path.parent, prefix=path.name + ".", delete=False
        ) as handle:
            temporary = Path(handle.name)
            handle.write(sanitized)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        temporary.replace(path)
    return SanitizationResult(path, entries, xmp_packets, changed)


def tracked_cr3_files(root: Path) -> list[Path]:
    completed = subprocess.run(
        ["git", "-C", str(root), "ls-files", "-z", "--", "*.cr3", "*.CR3"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", errors="replace").strip()
        raise SanitizationError(f"git ls-files failed: {message}")
    return [
        root / item.decode("utf-8", errors="surrogateescape")
        for item in completed.stdout.split(b"\x00")
        if item
    ]


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Neutralize identifying metadata in CR3 fixtures while preserving "
            "file size and all container offsets."
        )
    )
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        help="CR3 files. Defaults to every tracked CR3 file.",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Repository root used when paths are omitted.",
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="Replace files atomically. Without this flag the command is a dry run.",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    root = args.root.resolve()
    paths = [path.resolve() for path in args.paths]
    if not paths:
        try:
            paths = tracked_cr3_files(root)
        except SanitizationError as exc:
            print(f"CR3 sanitization error: {exc}", file=sys.stderr)
            return 2
    if not paths:
        print("No tracked CR3 fixtures found.")
        return 0

    failures = 0
    changed = 0
    total_entries = 0
    total_xmp = 0
    for path in paths:
        try:
            result = sanitize_file(path, write=args.write)
        except (OSError, SanitizationError) as exc:
            failures += 1
            print(f"CR3 sanitization error: {path}: {exc}", file=sys.stderr)
            continue
        changed += int(result.changed)
        total_entries += result.tiff_entries
        total_xmp += result.xmp_packets
        action = "sanitized" if args.write and result.changed else "would sanitize"
        if not result.changed:
            action = "already sanitized"
        try:
            display = path.relative_to(root)
        except ValueError:
            display = path
        print(
            f"{action}: {display} "
            f"(IFD entries={result.tiff_entries}, XMP creator packets={result.xmp_packets})"
        )

    if failures:
        return 1
    mode = "written" if args.write else "dry-run"
    print(
        f"CR3 sanitization {mode}: files={len(paths)}, changed={changed}, "
        f"IFD entries={total_entries}, XMP creator packets={total_xmp}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
