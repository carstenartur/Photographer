"""Fit and validate the shared display curve from the regional CameraToneSamples TSV."""
import argparse
import csv
from pathlib import Path

import numpy as np
from scipy.optimize import least_squares
from scipy.special import expit, logit

TRAINING = {
    "g5xm2-asis-iso200-ss130-ff2p5-01.cr3",
    "sample-wide.cr3",
    "4515.cr3",
}


def load(path):
    scenes = {}
    with path.open() as stream:
        for row in csv.DictReader(stream, delimiter="\t"):
            scene = scenes.setdefault(row["file"], {})
            key = int(row["tile"])
            bins = scene.setdefault(key, [])
            bins.append([float(row[k]) for k in ("count", "mean_peak", "mean_Y", "jpeg_Y")])
    return {name: [np.array(bins) for bins in tiles.values()] for name, tiles in scenes.items()}


def compare(scene, slope, offset):
    predicted, target = [], []
    for bins in scene:
        count, peak, luminance, reference = bins.T
        # The identity contribution keeps a positive slope at black and at white.
        safe_peak = np.clip(peak, 1e-12, 1 - 1e-12)
        adjusted = .1 * peak + .9 * expit(slope * logit(safe_peak) + offset)
        gain = adjusted / safe_peak
        gain[(peak <= 0) | (peak >= 1)] = 1
        predicted.append(np.average(luminance * gain, weights=count))
        target.append(reference[0])
    return np.array(predicted), np.array(target)


def lightness(y):
    return np.where(y <= 216 / 24389, y * 24389 / 27, 116 * np.cbrt(y) - 16)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("samples", type=Path)
    scenes = load(parser.parse_args().samples)
    missing = TRAINING.difference(scenes)
    if missing:
        parser.error("missing training captures: " + ", ".join(sorted(missing)))

    def residual(parameters):
        errors = []
        for name in sorted(TRAINING):
            predicted, target = compare(scenes[name], *parameters)
            # Compare ratios across the dark and bright scenes, with a toe floor
            # so small near-black fluctuations do not dominate the fit.
            errors.extend(np.log2((predicted + .002) / (target + .002)))
        return np.array(errors)

    result = least_squares(residual, [1.4, 1.5], bounds=([1, 0], [2, 2]),
                           loss="soft_l1", f_scale=.25)
    slope, offset = np.round(result.x, 3)
    print(f"slope={slope:.3f}, offset={offset:.3f}")
    print("capture\trole\tmedian_absolute_dL_before\tmedian_absolute_dL_after")
    for name, scene in scenes.items():
        before, target = compare(scene, 1, 0)
        after, _ = compare(scene, slope, offset)
        baseline = np.median(abs(lightness(before) - lightness(target)))
        adjusted = np.median(abs(lightness(after) - lightness(target)))
        role = "training" if name in TRAINING else "evaluation"
        print(f"{name}\t{role}\t{baseline:.2f}\t{adjusted:.2f}")


if __name__ == "__main__":
    main()
