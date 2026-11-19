# Bundled YOLOX-Nano model

YOLOX by Megvii, Inc. and its affiliates. The bundled `yolox_nano.onnx` is the official 416 × 416 COCO detector from release `0.1.1rc0`, published on 18 August 2021. This is the original 3,659,407-byte release asset, GitHub asset ID `42724905`. [Upstream release](https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0) · [Official download](https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.onnx)

SHA-256 `c789161ed43c8269fcd4e67c67eeeb4e80c622da2eb296a20bc6007bd18a0b7d`.

The upstream YOLOX source repository publishes the Apache License 2.0. Its tagged license text accompanies this resource as [YOLOX-LICENSE](YOLOX-LICENSE). [Upstream source license](https://github.com/Megvii-BaseDetection/YOLOX/blob/0.1.1rc0/LICENSE)

The Apache-2.0 source license is confirmed by that tagged file. Explicit upstream confirmation of the license terms for the official pretrained weights remains unresolved: [upstream issue #1915](https://github.com/Megvii-BaseDetection/YOLOX/issues/1915) asks whether the checkpoints share the source license or have separate terms, including redistribution. The issue had no maintainer reply when checked on 19 November 2026. Update this notice when upstream clarification becomes available.

The binary is ONNX IR version 6, opset 11. Input `images` is float32 `[1,3,416,416]` and follows the current upstream BGR preprocessing with values from 0 to 255 and top-left placement on 114 padding. Output `output` is float32 `[1,3549,85]` with raw center and size offsets on the stride-8, stride-16 and stride-32 grids. The last 81 elements of each prediction are objectness and class probabilities. COCO bird is zero-based class 14. [Official ONNX demo](https://github.com/Megvii-BaseDetection/YOLOX/tree/main/demo/ONNXRuntime) · [COCO class list](https://github.com/Megvii-BaseDetection/YOLOX/blob/main/yolox/data/datasets/coco_classes.py)
