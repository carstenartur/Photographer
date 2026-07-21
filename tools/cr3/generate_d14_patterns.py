#!/usr/bin/env python3
"""Generate the deterministic CR3 D14 diagnostic pattern PDFs from the manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from reportlab.lib.colors import Color, black, white
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.units import inch
from reportlab.pdfgen import canvas


def gray(value: float) -> Color:
    return Color(value, value, value)


def active_rect(page_width: float, page_height: float, active: dict[str, float]):
    return (
        active["x"] * page_width,
        active["y"] * page_height,
        active["width"] * page_width,
        active["height"] * page_height,
    )


def draw_cover(pdf: canvas.Canvas, page_width: float, page_height: float,
               target_name: str, pattern_count: int) -> None:
    pdf.setFillColor(white)
    pdf.rect(0, 0, page_width, page_height, fill=1, stroke=0)
    pdf.setFillColor(black)
    pdf.setFont("Helvetica-Bold", page_height * 0.055)
    pdf.drawString(page_width * 0.06, page_height * 0.88,
                   "CR3 D14 Diagnostic Pattern Pack")
    pdf.setFont("Helvetica", page_height * 0.025)
    pdf.drawString(page_width * 0.06, page_height * 0.82, target_name)
    lines = [
        "Controlled clean-room spatial stimuli for CR3/CRX differential analysis.",
        "Photograph the active field inside the thin border and keep the complete gray label border visible.",
        "Use fixed manual camera settings and capture each selected pattern twice consecutively.",
        "Use duplicate captures as the noise baseline before comparing different stimuli.",
        "Do not select decoder semantics from the best-looking rendered output.",
        "Issue #249 - pattern specification version 1.0.0.",
    ]
    pdf.setFont("Helvetica", page_height * 0.020)
    y = page_height * 0.72
    for line in lines:
        pdf.drawString(page_width * 0.06, y, line)
        y -= page_height * 0.055
    pdf.setFont("Helvetica-Bold", page_height * 0.024)
    pdf.drawString(page_width * 0.06, page_height * 0.16,
                   f"Patterns: {pattern_count}")
    pdf.setFont("Helvetica", page_height * 0.016)
    pdf.drawString(page_width * 0.06, page_height * 0.11,
                   "Screen and print editions share the same normalized active field.")


def draw_base(pdf: canvas.Canvas, page_width: float, page_height: float,
              active: dict[str, float], border_luminance: float,
              pattern: dict) -> tuple[float, float, float, float]:
    pdf.setFillColor(gray(border_luminance))
    pdf.rect(0, 0, page_width, page_height, fill=1, stroke=0)
    x, y, width, height = active_rect(page_width, page_height, active)
    pdf.setStrokeColor(black)
    pdf.setLineWidth(max(0.5, page_width / 1600))
    pdf.rect(x, y, width, height, fill=0, stroke=1)
    pdf.setFillColor(black)
    pdf.setFont("Helvetica-Bold", max(8, page_height * 0.018))
    pdf.drawString(page_width * 0.01, page_height * 0.975, pattern["id"])
    pdf.setFont("Helvetica", max(6, page_height * 0.012))
    pdf.drawRightString(page_width * 0.99, page_height * 0.975,
                        pattern["title"][:90])
    pdf.drawCentredString(
        page_width * 0.5,
        page_height * 0.018,
        "D14 active field is inside the thin border; exclude the outer label border.",
    )
    return x, y, width, height


def draw_pattern(pdf: canvas.Canvas, page_width: float, page_height: float,
                 active: dict[str, float], border_luminance: float,
                 pattern: dict) -> None:
    x, y, width, height = draw_base(
        pdf, page_width, page_height, active, border_luminance, pattern)
    family = pattern["family"]
    params = pattern["parameters"]

    if family == "uniform":
        pdf.setFillColor(gray(params["level"]))
        pdf.rect(x, y, width, height, fill=1, stroke=0)
    elif family == "edge":
        low, high, position = params["low"], params["high"], params["position"]
        if params["axis"] == "x":
            pdf.setFillColor(gray(low))
            pdf.rect(x, y, width * position, height, fill=1, stroke=0)
            pdf.setFillColor(gray(high))
            pdf.rect(x + width * position, y, width * (1 - position), height,
                     fill=1, stroke=0)
        else:
            pdf.setFillColor(gray(low))
            pdf.rect(x, y, width, height * position, fill=1, stroke=0)
            pdf.setFillColor(gray(high))
            pdf.rect(x, y + height * position, width, height * (1 - position),
                     fill=1, stroke=0)
    elif family == "directional-frequency":
        count = params["cycles"] * 2
        for index in range(count):
            pdf.setFillColor(gray(params["high"] if index % 2 else params["low"]))
            if params["axis"] == "x":
                pdf.rect(x + width * index / count, y, width / count + 0.2,
                         height, fill=1, stroke=0)
            else:
                pdf.rect(x, y + height * index / count, width,
                         height / count + 0.2, fill=1, stroke=0)
    elif family == "checkerboard":
        columns, rows = params["columns"], params["rows"]
        for row in range(rows):
            for column in range(columns):
                level = params["high"] if (row + column) % 2 else params["low"]
                pdf.setFillColor(gray(level))
                pdf.rect(x + width * column / columns,
                         y + height * row / rows,
                         width / columns + 0.2,
                         height / rows + 0.2,
                         fill=1, stroke=0)
    elif family == "localized-block":
        pdf.setFillColor(gray(params["background"]))
        pdf.rect(x, y, width, height, fill=1, stroke=0)
        block_width = width * params["width"]
        block_height = height * params["height"]
        pdf.setFillColor(gray(params["foreground"]))
        pdf.rect(x + width * params["center_x"] - block_width / 2,
                 y + height * params["center_y"] - block_height / 2,
                 block_width, block_height, fill=1, stroke=0)
    elif family == "ramp":
        if "steps" in params:
            count = params["steps"]
        else:
            count = 512
        for index in range(count):
            fraction = index / max(1, count - 1)
            level = params["low"] + (params["high"] - params["low"]) * fraction
            pdf.setFillColor(gray(level))
            if params["axis"] == "x":
                pdf.rect(x + width * index / count, y, width / count + 0.2,
                         height, fill=1, stroke=0)
            else:
                pdf.rect(x, y + height * index / count, width,
                         height / count + 0.2, fill=1, stroke=0)
    elif family in {"gray-code-x", "gray-code-y"}:
        cells = params["cells"]
        for index in range(cells):
            code = index ^ (index >> 1)
            level = params["high"] if ((code >> params["bit"]) & 1) else params["low"]
            pdf.setFillColor(gray(level))
            if params["axis"] == "x":
                pdf.rect(x + width * index / cells, y, width / cells + 0.2,
                         height, fill=1, stroke=0)
            else:
                pdf.rect(x, y + height * index / cells, width,
                         height / cells + 0.2, fill=1, stroke=0)
    elif family == "prbs":
        seed = params["seed"].encode("ascii")
        for row in range(params["rows"]):
            for column in range(params["columns"]):
                digest = hashlib.sha256(
                    seed + f":{row}:{column}".encode("ascii")).digest()
                bit = digest[0] & 1
                if params["inverse"]:
                    bit ^= 1
                level = params["high"] if bit else params["low"]
                pdf.setFillColor(gray(level))
                pdf.rect(x + width * column / params["columns"],
                         y + height * row / params["rows"],
                         width / params["columns"] + 0.2,
                         height / params["rows"] + 0.2,
                         fill=1, stroke=0)
    elif family == "orientation":
        levels = {(0, 0): 0.10, (1, 0): 0.35, (0, 1): 0.65, (1, 1): 0.90}
        for column in range(2):
            for row in range(2):
                pdf.setFillColor(gray(levels[(column, row)]))
                pdf.rect(x + column * width / 2, y + row * height / 2,
                         width / 2, height / 2, fill=1, stroke=0)
        pdf.setFillColor(black)
        pdf.rect(x + 0.07 * width, y + 0.72 * height,
                 0.12 * width, 0.08 * height, fill=1, stroke=0)
        pdf.setFillColor(white)
        pdf.circle(x + 0.84 * width, y + 0.18 * height,
                   min(width, height) * 0.055, fill=1, stroke=0)
    elif family == "diagonal":
        pdf.setFillColor(gray(params["low"]))
        pdf.rect(x, y, width, height, fill=1, stroke=0)
        pdf.setFillColor(gray(params["high"]))
        path = pdf.beginPath()
        if params["direction"] == "rising":
            path.moveTo(x, y)
            path.lineTo(x + width, y + height)
            path.lineTo(x + width, y)
        else:
            path.moveTo(x, y + height)
            path.lineTo(x + width, y)
            path.lineTo(x + width, y + height)
        path.close()
        pdf.drawPath(path, fill=1, stroke=0)
    else:
        raise ValueError(f"Unsupported pattern family: {family}")

    pdf.setStrokeColor(black)
    pdf.setLineWidth(max(0.5, page_width / 1600))
    pdf.rect(x, y, width, height, fill=0, stroke=1)


def generate(path: Path, page_size: tuple[float, float], target_name: str,
             manifest: dict) -> None:
    page_width, page_height = page_size
    pdf = canvas.Canvas(str(path), pagesize=page_size, pageCompression=1,
                        invariant=1)
    pdf.setTitle("CR3 D14 Diagnostic Pattern Pack")
    pdf.setAuthor("Photographer project clean-room research")
    pdf.setSubject("Controlled spatial patterns for CR3/CRX differential analysis")
    draw_cover(pdf, page_width, page_height, target_name,
               len(manifest["patterns"]))
    pdf.showPage()
    active = manifest["activeRegionNormalizedBottomLeft"]
    border_luminance = manifest["borderLuminance"]
    for pattern in manifest["patterns"]:
        draw_pattern(pdf, page_width, page_height,
                     active, border_luminance, pattern)
        pdf.showPage()
    pdf.save()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path,
                        default=Path("docs/cr3-patterns/pattern-manifest.json"))
    parser.add_argument("--output-dir", type=Path,
                        default=Path("docs/cr3-patterns"))
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    generate(args.output_dir / "cr3-diagnostic-patterns-screen-16x9.pdf",
             (13.333 * inch, 7.5 * inch),
             "Screen edition - 16:9 landscape", manifest)
    generate(args.output_dir / "cr3-diagnostic-patterns-print-a4-landscape.pdf",
             landscape(A4), "Print edition - A4 landscape", manifest)


if __name__ == "__main__":
    main()
