window.BENCHMARK_DATA = {
  "lastUpdate": 1789277921457,
  "repoUrl": "https://github.com/carstenartur/Photographer",
  "entries": {
    "Canon CR3 JMH Benchmarks": [
      {
        "commit": {
          "author": {
            "name": "dependabot[bot]",
            "username": "dependabot[bot]",
            "email": "49699333+dependabot[bot]@users.noreply.github.com"
          },
          "committer": {
            "name": "GitHub",
            "username": "web-flow",
            "email": "noreply@github.com"
          },
          "id": "295d3e0d0f157306cb452883b433b9dac43270a2",
          "message": "build(deps): update frontend dependencies and align Node for Vitest 5 (#318)\n\nUpdate frontend dependencies including Vitest 5. Align CI and Maven on Node 24.21.0 LTS, enforce supported Node engines, and use npm ci for reproducible Maven and Docker builds.\n\nValidated with full product verification (1,498 tests, no failures or errors, one existing skip), all 8 frontend tests, lint, production build, and the Docker build.",
          "timestamp": "2026-09-09T06:24:50Z",
          "url": "https://github.com/carstenartur/Photographer/commit/295d3e0d0f157306cb452883b433b9dac43270a2"
        },
        "date": 1789277920039,
        "tool": "jmh",
        "benches": [
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 1",
            "value": 1.4881207327607082,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600",
            "value": 0.7164777291786997,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.7272327262545414,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 3",
            "value": 1.6362305785534232,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 2",
            "value": 1.6862004392536254,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 1",
            "value": 2.7824046939540388,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 2",
            "value": 2.836617813276574,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 3",
            "value": 2.8701588456310736,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — G5 X Mark II · ISO 125 · frame 1",
            "value": 14.409094599424131,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — EOS R · ISO 12800 · frame 1",
            "value": 16.662622811225894,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 1144.59962725,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — EOS R · ISO 12800 · frame 1",
            "value": 1715.769681375,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.01844971776000543,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600",
            "value": 0.018118869911192815,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.01815947225428132,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 3",
            "value": 0.01830449006002449,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 2",
            "value": 0.01814493912765989,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 1",
            "value": 0.04636911821446542,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 2",
            "value": 0.04636971280805241,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 3",
            "value": 0.04639767196038299,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 1",
            "value": 13.170227568412452,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600",
            "value": 9.067665771424394,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 9.014223571486285,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 3",
            "value": 16.808338137016808,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 2",
            "value": 16.91241488787922,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 1",
            "value": 14.867761611851853,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 2",
            "value": 14.474723248597645,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 3",
            "value": 14.551278588969131,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 351.22565312000006,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600",
            "value": 350.38440496000004,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 346.85782369333333,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 377.7997611857143,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 373.0149431428571,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 1",
            "value": 582.4953970666668,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 2",
            "value": 587.4133556444446,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 3",
            "value": 586.5250784222222,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 1100.11536896,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600",
            "value": 1083.75512772,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 1088.4249629199999,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 1104.9442556,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 1112.9511476399998,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 1",
            "value": 1671.8930290833334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 2",
            "value": 1663.8472703666666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 3",
            "value": 1666.6929772666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 1080.2834788799998,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 1600",
            "value": 1081.33654608,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 1072.0966579199999,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 1106.6149882,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 1101.66871272,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — EOS R · ISO 12800 · frame 1",
            "value": 1665.0379998833334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — EOS R · ISO 12800 · frame 2",
            "value": 1662.2595390000001,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — EOS R · ISO 12800 · frame 3",
            "value": 1691.2670457333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — G5 X Mark II · ISO 125 · frame 1",
            "value": 349.01605058333337,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — EOS R · ISO 12800 · frame 1",
            "value": 527.40857525,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — G5 X Mark II · ISO 125 · frame 1",
            "value": 24.44755886428571,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — EOS R · ISO 12800 · frame 1",
            "value": 36.738399619565214,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — G5 X Mark II · ISO 125 · frame 1",
            "value": 18.543336220269556,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — EOS R · ISO 12800 · frame 1",
            "value": 27.967127125615765,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — G5 X Mark II · ISO 125 · frame 1",
            "value": 366.1675485,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — EOS R · ISO 12800 · frame 1",
            "value": 560.6117602500001,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — G5 X Mark II · ISO 125 · frame 1",
            "value": 2.62028409944669,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — EOS R · ISO 12800 · frame 1",
            "value": 3.9076754441292922,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 319.9247933428572,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600",
            "value": 299.01949617142856,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 298.11296077142856,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 309.7005746285714,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 325.23982294285713,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 1",
            "value": 490.633326,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 2",
            "value": 494.60979164,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 3",
            "value": 488.7131072,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Decode Rice symbol",
            "value": 6.949143531678222,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Map residual sign",
            "value": 0.6404104828027324,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Update Rice parameter",
            "value": 1.2761907348582082,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.24883041768800684,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 0.6841189972575187,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 54.862528276315786,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 87.05990258333333,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — G5 X Mark II · ISO 125 · frame 1",
            "value": 8.380374952083333,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — EOS R · ISO 12800 · frame 1",
            "value": 12.483820135030863,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.00042574779457400563,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — EOS R · ISO 12800 · frame 1",
            "value": 0.0004553063108068402,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 15.04584274875622,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — EOS R · ISO 12800 · frame 1",
            "value": 22.792163875,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}