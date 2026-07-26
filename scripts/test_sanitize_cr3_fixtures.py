#!/usr/bin/env python3
"""Tests for sanitize_cr3_fixtures.py."""

from __future__ import annotations

import importlib.util
import struct
import sys
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("sanitize_cr3_fixtures.py")
SPEC = importlib.util.spec_from_file_location("sanitize_cr3_fixtures", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
SANITIZER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SANITIZER
SPEC.loader.exec_module(SANITIZER)


def make_cmt_box(
    box_type: bytes,
    entries: list[tuple[int, int, bytes]],
    *,
    endian: str = "<",
) -> bytes:
    """Build a minimal TIFF-based CMT box.

    Entry tuples contain (tag, TIFF type, raw value bytes). Values longer than four
    bytes are stored after the IFD; shorter values are inline and padded.
    """
    marker = b"II" if endian == "<" else b"MM"
    ifd_offset = 8
    entry_count = len(entries)
    table_size = 2 + entry_count * 12 + 4
    payload = bytearray(marker + struct.pack(endian + "H", 42))
    payload += struct.pack(endian + "I", ifd_offset)
    payload += struct.pack(endian + "H", entry_count)

    external = bytearray()
    external_base = ifd_offset + table_size
    for tag, data_type, value in entries:
        type_size = SANITIZER.TIFF_TYPE_SIZES[data_type]
        if len(value) % type_size:
            raise AssertionError("test value does not align to TIFF type size")
        count = len(value) // type_size
        payload += struct.pack(endian + "HHI", tag, data_type, count)
        if len(value) <= 4:
            payload += value.ljust(4, b"\x00")
        else:
            payload += struct.pack(endian + "I", external_base + len(external))
            external += value
    payload += b"\x00\x00\x00\x00"
    payload += external
    return struct.pack(">I4s", len(payload) + 8, box_type) + payload


class Cr3FixtureSanitizerTest(unittest.TestCase):

    def test_neutralizes_sensitive_tag_and_preserves_other_tag_and_size(self) -> None:
        sensitive = b"PRIVATE-CAMERA-ID\x00"
        public = b"Canon\x00"
        box = make_cmt_box(
            b"CMT2",
            [
                (0xA431, 2, sensitive),
                (0x010F, 2, public),
            ],
        )
        original = b"prefix" + box + b"suffix"

        sanitized, count, xmp_count = SANITIZER.sanitize_bytes(original)

        self.assertEqual(len(original), len(sanitized))
        self.assertEqual(1, count)
        self.assertEqual(0, xmp_count)
        self.assertNotIn(sensitive.rstrip(b"\x00"), sanitized)
        self.assertIn(public, sanitized)
        self.assertIn(b"\xff\xff", sanitized)

    def test_neutralizes_inline_maker_note_serial(self) -> None:
        box = make_cmt_box(b"CMT3", [(0x000C, 4, b"\x12\x34\x56\x78")], endian=">")

        sanitized, count, _ = SANITIZER.sanitize_bytes(box)

        self.assertEqual(1, count)
        self.assertNotIn(b"\x12\x34\x56\x78", sanitized)
        locations = SANITIZER.locate_cmt_boxes(sanitized)
        self.assertEqual(1, len(locations))

    def test_removes_xmp_creator_text_without_resizing(self) -> None:
        cmt = make_cmt_box(b"CMT1", [(0x013B, 2, b"Person\x00")])
        xmp = (
            b'<x:xmpmeta xmlns:x="adobe:ns:meta/" '
            b'xmlns:dc="http://purl.org/dc/elements/1.1/">'
            b"<dc:creator><rdf:Seq><rdf:li>Private Person</rdf:li>"
            b"</rdf:Seq></dc:creator></x:xmpmeta>"
        )
        original = cmt + xmp

        sanitized, _, xmp_count = SANITIZER.sanitize_bytes(original)

        self.assertEqual(len(original), len(sanitized))
        self.assertEqual(1, xmp_count)
        self.assertNotIn(b"Private Person", sanitized)
        self.assertNotIn(b"dc:creator", sanitized)
        self.assertIn(b"dc:creat0r", sanitized)
        self.assertIn(b"rdf:Seq", sanitized)

    def test_is_idempotent(self) -> None:
        original = make_cmt_box(b"CMT2", [(0xA435, 2, b"LENS-ID\x00")])
        first, first_count, _ = SANITIZER.sanitize_bytes(original)
        second, second_count, _ = SANITIZER.sanitize_bytes(first)

        self.assertGreater(first_count, 0)
        self.assertEqual(0, second_count)
        self.assertEqual(first, second)

    def test_rejects_out_of_bounds_sensitive_value(self) -> None:
        box = bytearray(make_cmt_box(b"CMT2", [(0xA431, 2, b"SERIAL-ID\x00")]))
        location = SANITIZER.locate_cmt_boxes(box)[0]
        ifd_start = location.data_start + 8
        struct.pack_into("<I", box, ifd_start + 2 + 8, 0xFFFFFF00)

        with self.assertRaises(SANITIZER.SanitizationError):
            SANITIZER.sanitize_bytes(bytes(box))


if __name__ == "__main__":
    unittest.main()
