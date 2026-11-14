window.BENCHMARK_DATA = {
  "lastUpdate": 1794657516786,
  "repoUrl": "https://github.com/carstenartur/Photographer",
  "entries": {
    "Canon CR3 JMH Benchmarks": [
      {
        "commit": {
          "author": {
            "email": "carsten.hammer@t-online.de",
            "name": "Carsten Hammer",
            "username": "carstenartur"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "0448da0f5565dbf7ca816267c3ab21cdada69a6a",
          "message": "Prepare independently named CR3 libraries for signed Maven Central deployment (#341)\n\n* Test Canon CRX run-mode hypothesis and pin full-sensor references\n\n* Locate and verify the first two native lossless CRX samples (#334)\n\n* Verify the CMP1 byte origin and restrict opening reads to measured sites\n\n* Decode native lossless Canon CRX mosaics and verify all sensor sites\n\n* Decode and verify full three-level Canon C-RAW sensor mosaics\n\n* Stream CRX reconstruction into native and linear ImageIO rasters\n\n* Prepare signed six-module CR3 releases using the Central Maven plugin\n\n* Handle the full legal range of ImageIO subsampling and destination offsets\n\n* Document how offline Maven handles Central bundle generation\n\n* Describe the ImageIO service as an independent CR3 reader",
          "timestamp": "2026-11-14T12:25:13+01:00",
          "tree_id": "0eb2a9e6d32eb069e9bf069b874038c8270cf130",
          "url": "https://github.com/carstenartur/Photographer/commit/0448da0f5565dbf7ca816267c3ab21cdada69a6a"
        },
        "date": 1794657515003,
        "tool": "jmh",
        "benches": [
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 1",
            "value": 1.4328946920905856,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600",
            "value": 0.6999049303805684,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.7177243709627797,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 3",
            "value": 1.7454110368581452,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 2",
            "value": 1.7237032818189308,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 1",
            "value": 2.9500329460038253,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 2",
            "value": 3.1691138134340724,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 3",
            "value": 3.1447909785469346,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — G5 X Mark II · ISO 125 · frame 1",
            "value": 14.980255238643885,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — EOS R · ISO 12800 · frame 1",
            "value": 17.230531129579386,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 1200.285047125,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — EOS R · ISO 12800 · frame 1",
            "value": 1863.414825,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — G5 X Mark II · ISO 125 · frame 1",
            "value": 374.454486,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — EOS R · ISO 12800 · frame 1",
            "value": 618.78351275,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.019342420505571438,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600",
            "value": 0.018927555849889185,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.01877863566886192,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 3",
            "value": 0.018868991418448007,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 2",
            "value": 0.018770204642883613,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 1",
            "value": 0.0476642336272773,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 2",
            "value": 0.047249966612603944,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 3",
            "value": 0.04758586351532566,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 1",
            "value": 13.446614994892679,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600",
            "value": 9.391053954588338,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 9.437452378346713,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 3",
            "value": 17.156681370700895,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 2",
            "value": 17.33267395694332,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 1",
            "value": 14.812777013319666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 2",
            "value": 14.700288374377417,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 3",
            "value": 14.78477919458579,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 378.79244075714286,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600",
            "value": 186.08178404444442,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 183.97339282857143,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 401.52027653846153,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 401.2906302923077,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 1",
            "value": 612.2777047333333,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 2",
            "value": 627.7722076,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 3",
            "value": 611.8516906666666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 1163.1764864800002,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600",
            "value": 965.7815309,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 964.5155100666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 1214.6489907199998,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 1214.30800736,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 1",
            "value": 1778.704553,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 2",
            "value": 1827.7739066666666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 3",
            "value": 1815.0451561333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 1163.3751051599997,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 1600",
            "value": 968.3612998666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 967.4903133,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 1183.46405008,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 1199.34375576,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — EOS R · ISO 12800 · frame 1",
            "value": 1790.4071061999998,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — EOS R · ISO 12800 · frame 2",
            "value": 1795.6169126666664,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (memory-mapped input) — EOS R · ISO 12800 · frame 3",
            "value": 1793.7567998666666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — G5 X Mark II · ISO 125 · frame 1",
            "value": 353.3763441666667,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — EOS R · ISO 12800 · frame 1",
            "value": 538.5545903750001,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — G5 X Mark II · ISO 125 · frame 1",
            "value": 25.16859955213904,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — EOS R · ISO 12800 · frame 1",
            "value": 36.68646613043478,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — G5 X Mark II · ISO 125 · frame 1",
            "value": 18.71603707087486,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — EOS R · ISO 12800 · frame 1",
            "value": 28.331869895628078,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — G5 X Mark II · ISO 125 · frame 1",
            "value": 372.2191264166667,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — EOS R · ISO 12800 · frame 1",
            "value": 617.16147675,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — G5 X Mark II · ISO 125 · frame 1",
            "value": 2.858936958951545,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — EOS R · ISO 12800 · frame 1",
            "value": 4.250870085126605,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 371.3905991666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600",
            "value": 182.23003417727276,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 179.51478111666665,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 398.0495167933333,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 395.87764243333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 1",
            "value": 594.83669465,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 2",
            "value": 615.73311505,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 3",
            "value": 604.74476015,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Decode Rice symbol",
            "value": 7.370789038281797,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Map residual sign",
            "value": 0.6381404942563748,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Update Rice parameter",
            "value": 1.2937495581381273,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.32215152121216734,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 0.8186179589872196,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 48.770068488095234,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 80.15508328846154,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — G5 X Mark II · ISO 125 · frame 1",
            "value": 8.91670011903445,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — EOS R · ISO 12800 · frame 1",
            "value": 13.354708034869827,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.00042577197577337933,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — EOS R · ISO 12800 · frame 1",
            "value": 0.00046214603587673635,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 18.40993444090909,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — EOS R · ISO 12800 · frame 1",
            "value": 26.342547177462887,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}