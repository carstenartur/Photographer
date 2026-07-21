#!/usr/bin/env python3
"""Generate the deterministic D14 CR3 diagnostic pattern PDFs and manifest."""
from __future__ import annotations

from pathlib import Path
from typing import Any
import argparse
import hashlib
import json
import textwrap

from reportlab.lib.colors import Color, black, white
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.units import inch
from reportlab.pdfgen import canvas

ACTIVE = {"x": 0.04, "y": 0.06, "width": 0.92, "height": 0.88}
BORDER_GRAY = 0.50
VERSION = "1.0.0"
ISSUE = 249


def gray(value: float) -> Color:
    return Color(value, value, value)


def pattern_manifest() -> list[dict[str, Any]]:
    patterns: list[dict[str, Any]] = []

    def add(pattern_id: str, family: str, title: str, renderer: str,
            parameters: dict[str, Any] | None = None,
            discriminator: str = "") -> None:
        patterns.append({"id": pattern_id, "family": family, "title": title,
                         "renderer": renderer, "parameters": parameters or {},
                         "discriminator": discriminator})

    for index, level in enumerate((0.0, 0.0625, 0.125, 0.25, 0.5, 0.75, 0.9375, 1.0)):
        add(f"U{index:02d}", "uniform", f"Uniform luminance {level:.4f}", "uniform",
            {"level": level}, "Constant residual distribution and signal-level dependence.")
    for axis, prefix in (("x", "EX"), ("y", "EY")):
        for percent in (25, 50, 75):
            add(f"{prefix}{percent}", "edge",
                f"{'Vertical' if axis == 'x' else 'Horizontal'} edge at {percent}%", "edge",
                {"axis": axis, "position": percent / 100.0, "low": 0.125, "high": 0.875},
                "Spatial locality, scan direction, predictor boundary and tile/reset alignment.")
    for cycles in (4, 8, 16, 32):
        add(f"VX{cycles:02d}", "directional-frequency", f"Vertical binary bars, {cycles} cycles", "bars",
            {"axis": "x", "cycles": cycles, "low": 0.125, "high": 0.875},
            "Horizontal scan/predictor response and periodic payload structure.")
        add(f"HY{cycles:02d}", "directional-frequency", f"Horizontal binary bars, {cycles} cycles", "bars",
            {"axis": "y", "cycles": cycles, "low": 0.125, "high": 0.875},
            "Vertical scan/predictor response and periodic payload structure.")
    for columns, rows, pattern_id in ((8, 4, "C08"), (32, 18, "C32")):
        add(pattern_id, "checkerboard", f"Checkerboard {columns} x {rows}", "checker",
            {"columns": columns, "rows": rows, "low": 0.125, "high": 0.875},
            "Two-dimensional high-frequency response and block/subband sensitivity.")
    for pattern_id, (center_x, center_y) in {
        "B_C": (0.50, 0.50), "B_TL": (0.20, 0.80), "B_TR": (0.80, 0.80),
        "B_BL": (0.20, 0.20), "B_BR": (0.80, 0.20),
    }.items():
        add(pattern_id, "localized-block", f"Bright block at ({center_x:.2f}, {center_y:.2f})", "block",
            {"center_x": center_x, "center_y": center_y, "width": 0.18, "height": 0.18,
             "background": 0.125, "foreground": 0.875},
            "Movement of one local stimulus against changed payload intervals.")
    add("GX", "ramp", "Horizontal linear gradient", "gradient",
        {"axis": "x", "low": 0.0625, "high": 0.9375},
        "Predictor residual ramp and horizontal causal neighbourhood.")
    add("GY", "ramp", "Vertical linear gradient", "gradient",
        {"axis": "y", "low": 0.0625, "high": 0.9375},
        "Predictor residual ramp and vertical causal neighbourhood.")
    add("STEP16", "ramp", "Horizontal 16-level staircase", "staircase",
        {"axis": "x", "steps": 16, "low": 0.0625, "high": 0.9375},
        "Discrete residual magnitudes and K-adaptation transitions.")
    for bit in range(6):
        add(f"XG{bit}", "gray-code-x", f"X Gray-code plane bit {bit} over 64 columns", "graycode",
            {"axis": "x", "cells": 64, "bit": bit, "low": 0.125, "high": 0.875},
            "Unique coarse X-coordinate correlation across the complete XG sequence.")
    for bit in range(5):
        add(f"YG{bit}", "gray-code-y", f"Y Gray-code plane bit {bit} over 32 rows", "graycode",
            {"axis": "y", "cells": 32, "bit": bit, "low": 0.125, "high": 0.875},
            "Unique coarse Y-coordinate correlation across the complete YG sequence.")
    for pattern_id, inverse in (("PRBS32", False), ("PRBS32I", True)):
        add(pattern_id, "prbs", f"{'Inverse ' if inverse else ''}deterministic 32 x 18 binary field", "prbs",
            {"columns": 32, "rows": 18, "seed": "CR3-D14-PRBS32-v1",
             "low": 0.125, "high": 0.875, "inverse": inverse},
            "High-information fixed spatial signature and polarity control.")
    add("ORI", "orientation", "Asymmetric orientation field", "orientation", {},
        "Confirms rotation, mirroring and active-region registration.")
    add("D45A", "diagonal", "Diagonal split rising", "diagonal",
        {"direction": "rising", "low": 0.125, "high": 0.875},
        "Separates row/column ordering from two-dimensional locality.")
    add("D45B", "diagonal", "Diagonal split falling", "diagonal",
        {"direction": "falling", "low": 0.125, "high": 0.875},
        "Mirror control for diagonal locality.")
    for page, pattern in enumerate(patterns, start=2):
        pattern["page"] = page
    return patterns


def active_rect(page_width: float, page_height: float) -> tuple[float, float, float, float]:
    return (ACTIVE["x"] * page_width, ACTIVE["y"] * page_height,
            ACTIVE["width"] * page_width, ACTIVE["height"] * page_height)


def draw_base(pdf: canvas.Canvas, page_width: float, page_height: float,
              pattern: dict[str, Any]) -> tuple[float, float, float, float]:
    pdf.setFillColor(gray(BORDER_GRAY)); pdf.rect(0, 0, page_width, page_height, fill=1, stroke=0)
    x, y, width, height = active_rect(page_width, page_height)
    pdf.setStrokeColor(black); pdf.setLineWidth(max(0.5, page_width / 1600)); pdf.rect(x, y, width, height, fill=0, stroke=1)
    pdf.setFillColor(black); pdf.setFont("Helvetica-Bold", max(8, page_height * 0.018))
    pdf.drawString(page_width * 0.01, page_height * 0.975, pattern["id"])
    pdf.setFont("Helvetica", max(6, page_height * 0.012))
    pdf.drawRightString(page_width * 0.99, page_height * 0.975, pattern["title"][:90])
    pdf.drawCentredString(page_width * 0.5, page_height * 0.018,
                          "D14 active field is inside the thin border; exclude the outer label border.")
    return x, y, width, height


def draw_pattern(pdf: canvas.Canvas, page_width: float, page_height: float,
                 pattern: dict[str, Any]) -> None:
    x, y, width, height = draw_base(pdf, page_width, page_height, pattern)
    renderer, parameters = pattern["renderer"], pattern["parameters"]
    def fill(level: float, rx: float, ry: float, rw: float, rh: float) -> None:
        pdf.setFillColor(gray(level)); pdf.rect(rx, ry, rw, rh, fill=1, stroke=0)
    if renderer == "uniform":
        fill(parameters["level"], x, y, width, height)
    elif renderer == "edge":
        position, low, high = parameters["position"], parameters["low"], parameters["high"]
        if parameters["axis"] == "x":
            fill(low, x, y, width * position, height); fill(high, x + width * position, y, width * (1 - position), height)
        else:
            fill(low, x, y, width, height * position); fill(high, x, y + height * position, width, height * (1 - position))
    elif renderer == "bars":
        count = parameters["cycles"] * 2
        for index in range(count):
            level = parameters["high"] if index % 2 else parameters["low"]
            if parameters["axis"] == "x": fill(level, x + width * index / count, y, width / count + 0.2, height)
            else: fill(level, x, y + height * index / count, width, height / count + 0.2)
    elif renderer == "checker":
        columns, rows = parameters["columns"], parameters["rows"]
        for row in range(rows):
            for column in range(columns):
                fill(parameters["high"] if (row + column) % 2 else parameters["low"],
                     x + width * column / columns, y + height * row / rows,
                     width / columns + 0.2, height / rows + 0.2)
    elif renderer == "block":
        fill(parameters["background"], x, y, width, height)
        bw, bh = width * parameters["width"], height * parameters["height"]
        fill(parameters["foreground"], x + width * parameters["center_x"] - bw / 2,
             y + height * parameters["center_y"] - bh / 2, bw, bh)
    elif renderer == "gradient":
        strips = 512
        for index in range(strips):
            ratio = index / (strips - 1); level = parameters["low"] + (parameters["high"] - parameters["low"]) * ratio
            if parameters["axis"] == "x": fill(level, x + width * index / strips, y, width / strips + 0.2, height)
            else: fill(level, x, y + height * index / strips, width, height / strips + 0.2)
    elif renderer == "staircase":
        for index in range(parameters["steps"]):
            ratio = index / (parameters["steps"] - 1); level = parameters["low"] + (parameters["high"] - parameters["low"]) * ratio
            fill(level, x + width * index / parameters["steps"], y, width / parameters["steps"] + 0.2, height)
    elif renderer == "graycode":
        for index in range(parameters["cells"]):
            code = index ^ (index >> 1); level = parameters["high"] if ((code >> parameters["bit"]) & 1) else parameters["low"]
            if parameters["axis"] == "x": fill(level, x + width * index / parameters["cells"], y, width / parameters["cells"] + 0.2, height)
            else: fill(level, x, y + height * index / parameters["cells"], width, height / parameters["cells"] + 0.2)
    elif renderer == "prbs":
        seed = parameters["seed"].encode("ascii")
        for row in range(parameters["rows"]):
            for column in range(parameters["columns"]):
                bit = hashlib.sha256(seed + f":{row}:{column}".encode("ascii")).digest()[0] & 1
                if parameters["inverse"]: bit ^= 1
                fill(parameters["high"] if bit else parameters["low"],
                     x + width * column / parameters["columns"], y + height * row / parameters["rows"],
                     width / parameters["columns"] + 0.2, height / parameters["rows"] + 0.2)
    elif renderer == "orientation":
        values = {(0, 0): 0.10, (1, 0): 0.35, (0, 1): 0.65, (1, 1): 0.90}
        for column in range(2):
            for row in range(2): fill(values[(column, row)], x + column * width / 2, y + row * height / 2, width / 2, height / 2)
        fill(0.0, x + 0.07 * width, y + 0.72 * height, 0.12 * width, 0.08 * height)
        pdf.setFillColor(white); pdf.circle(x + 0.84 * width, y + 0.18 * height, min(width, height) * 0.055, fill=1, stroke=0)
    elif renderer == "diagonal":
        fill(parameters["low"], x, y, width, height); pdf.setFillColor(gray(parameters["high"])); path = pdf.beginPath()
        if parameters["direction"] == "rising": path.moveTo(x, y); path.lineTo(x + width, y + height); path.lineTo(x + width, y)
        else: path.moveTo(x, y + height); path.lineTo(x + width, y); path.lineTo(x + width, y + height)
        path.close(); pdf.drawPath(path, fill=1, stroke=0)
    else:
        raise ValueError(f"Unsupported renderer: {renderer}")
    pdf.setStrokeColor(black); pdf.setLineWidth(max(0.5, page_width / 1600)); pdf.rect(x, y, width, height, fill=0, stroke=1)


def draw_cover(pdf: canvas.Canvas, page_width: float, page_height: float, edition: str,
               pattern_count: int) -> None:
    pdf.setFillColor(white); pdf.rect(0, 0, page_width, page_height, fill=1, stroke=0); pdf.setFillColor(black)
    pdf.setFont("Helvetica-Bold", page_height * 0.055); pdf.drawString(page_width * 0.06, page_height * 0.88, "CR3 D14 Diagnostic Pattern Pack")
    pdf.setFont("Helvetica", page_height * 0.025); pdf.drawString(page_width * 0.06, page_height * 0.82, edition)
    paragraphs = (
        "Purpose: controlled clean-room spatial stimuli for CR3/CRX differential analysis.",
        "Each following page contains one pattern. Photograph the active field inside the thin border.",
        "Keep the complete gray label border visible, but exclude it from payload-to-spatial analysis.",
        "Use fixed manual camera settings and capture each selected pattern twice consecutively.",
        "Recommended first subset: U00, U03, U04, U07; all EX/EY; VX08, VX32, HY08, HY32; B_C, B_TL, B_TR, B_BL, B_BR; GX, GY, STEP16; all XG/YG; PRBS32 and PRBS32I.",
        "Do not infer decoder semantics from best-looking output. Use duplicate noise baselines and pre-registered gates.",
        f"Issue: #{ISSUE}. Pattern specification version: {VERSION}.",)
    cursor_y = page_height * 0.73; pdf.setFont("Helvetica", page_height * 0.020)
    for paragraph in paragraphs:
        for line in textwrap.wrap(paragraph, width=100):
            pdf.drawString(page_width * 0.06, cursor_y, line)
            cursor_y -= page_height * 0.035
        cursor_y -= page_height * 0.015
    pdf.setFont("Helvetica-Bold", page_height * 0.024)
    pdf.drawString(page_width * 0.06, page_height * 0.16, f"Patterns: {pattern_count}")
    pdf.setFont("Helvetica", page_height * 0.016)
    pdf.drawString(
        page_width * 0.06,
        page_height * 0.11,
        "Both editions share identical normalized active fields.",
    )


def write_pdf(path: Path, page_size: tuple[float, float], edition: str,
              patterns: list[dict[str, Any]]) -> None:
    page_width, page_height = page_size
    pdf = canvas.Canvas(str(path), pagesize=page_size, pageCompression=1, invariant=1)
    pdf.setTitle("CR3 D14 Diagnostic Pattern Pack")
    pdf.setAuthor("Photographer project clean-room research")
    pdf.setSubject("Controlled spatial patterns for CR3/CRX differential analysis")
    draw_cover(pdf, page_width, page_height, edition, len(patterns))
    pdf.showPage()
    for pattern in patterns:
        draw_pattern(pdf, page_width, page_height, pattern)
        pdf.showPage()
    pdf.save()


def write_manifest(path: Path, patterns: list[dict[str, Any]]) -> None:
    manifest = {"schema": "pattern-manifest-v1", "version": VERSION, "issue": ISSUE,
        "activeRegionNormalizedBottomLeft": ACTIVE, "borderLuminance": BORDER_GRAY,
        "captureRepeatsRequired": 2,
        "grayCode": {"xCells": 64, "xBits": 6, "yCells": 32, "yBits": 5,
                     "encoding": "binary-reflected Gray code: g = n XOR (n >> 1)",
                     "bitOrder": "least-significant plane first"},
        "patterns": [{key: value for key, value in pattern.items() if key != "renderer"} for pattern in patterns]}
    path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path(__file__).resolve().parent,
    )
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    patterns = pattern_manifest()
    write_pdf(args.out_dir / "cr3-diagnostic-patterns-screen-16x9.pdf", (13.333 * inch, 7.5 * inch), "Screen edition - 16:9 landscape", patterns)
    write_pdf(args.out_dir / "cr3-diagnostic-patterns-print-a4-landscape.pdf", landscape(A4), "Print edition - A4 landscape", patterns)
    write_manifest(args.out_dir / "pattern-manifest.json", patterns)


if __name__ == "__main__":
    main()
