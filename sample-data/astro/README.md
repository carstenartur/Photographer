# Astro Sample Data

This directory is reserved for synthetic and prepared star field images
used for testing the ASTRO mode.

## Generated at Test Time

The `SyntheticStarImageGenerator` utility class in `astro-core/src/test/`
generates star field images programmatically with:

- Configurable star count, image dimensions, and random seed
- Slightly shifted frames (simulating sky motion)
- Blurry frames (simulating out-of-focus captures)
- Focus scan datasets (different blur levels)

## Frame Types

- **Standard**: Sharp stars with Gaussian profiles on dark background
- **Shifted**: Same stars with sub-pixel translation offsets
- **Blurry**: Larger, dimmer blobs simulating defocused stars

All test data is generated in-memory during test execution for reproducibility.
