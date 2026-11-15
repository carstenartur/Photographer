window.BENCHMARK_DATA = {
  "lastUpdate": 1794718197831,
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
          "id": "e2dc46454c2338fd6769202b01d44834903ef00c",
          "message": "Target Java 21 for the standalone CR3 libraries and verify the packaged consumer on JDK 21 (#350)",
          "timestamp": "2026-11-15T05:13:03+01:00",
          "tree_id": "7257f81d3eda9ea577cb15c39c7edba291ee9b03",
          "url": "https://github.com/carstenartur/Photographer/commit/e2dc46454c2338fd6769202b01d44834903ef00c"
        },
        "date": 1794718196529,
        "tool": "jmh",
        "benches": [
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 1",
            "value": 2.5136201968463494,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600",
            "value": 1.3138012920158695,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 1.3069024020142606,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 3",
            "value": 2.764821722156517,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 2",
            "value": 2.897462512301853,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 1",
            "value": 4.8742997805979185,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 2",
            "value": 4.7620198985478766,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 3",
            "value": 4.915182041190668,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — G5 X Mark II · ISO 125 · frame 1",
            "value": 17.74574376624944,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — EOS R · ISO 12800 · frame 1",
            "value": 20.3364056179857,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 522.5751495000001,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — EOS R · ISO 12800 · frame 1",
            "value": 792.2567525,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — G5 X Mark II · ISO 125 · frame 1",
            "value": 286.6593182008928,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — EOS R · ISO 12800 · frame 1",
            "value": 408.93340939999996,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.024112347226780646,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600",
            "value": 0.02486018182365414,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.024397075818088122,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 3",
            "value": 0.02404677867010573,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 2",
            "value": 0.02429302184229539,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 1",
            "value": 0.05668273087112334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 2",
            "value": 0.05671199242491788,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 3",
            "value": 0.056985922744642536,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 1",
            "value": 15.880394348868894,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600",
            "value": 11.265338654950124,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 11.23253939254504,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 3",
            "value": 20.18753998581818,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 2",
            "value": 20.25297474895073,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 1",
            "value": 17.564110836430206,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 2",
            "value": 17.666104339122203,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 3",
            "value": 17.67307935635771,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 279.4644179666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600",
            "value": 167.54088364666669,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 164.68473670322584,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 319.386166175,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 323.3093573875,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 1",
            "value": 425.6331147833333,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 2",
            "value": 419.8131433333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 3",
            "value": 422.16553919999996,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 528.9431634599999,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600",
            "value": 392.59581593846156,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 411.4784738153846,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 543.1586018999999,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 545.7637477999999,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 1",
            "value": 773.6063157428571,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 2",
            "value": 794.7089918571429,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 3",
            "value": 752.5703586857143,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 545.52026226,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 1600",
            "value": 393.7272645076923,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 410.71223955384613,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 562.4401370666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 540.7408466200001,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 1",
            "value": 749.5944749428571,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 2",
            "value": 748.1617054571428,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 3",
            "value": 746.2787378857143,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — G5 X Mark II · ISO 125 · frame 1",
            "value": 63.13264553333333,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — EOS R · ISO 12800 · frame 1",
            "value": 94.3619396,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — G5 X Mark II · ISO 125 · frame 1",
            "value": 31.496382486823364,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — EOS R · ISO 12800 · frame 1",
            "value": 44.44040668421053,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — G5 X Mark II · ISO 125 · frame 1",
            "value": 22.29242807142857,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — EOS R · ISO 12800 · frame 1",
            "value": 33.3845575,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — G5 X Mark II · ISO 125 · frame 1",
            "value": 140.93669694791666,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — EOS R · ISO 12800 · frame 1",
            "value": 215.8321471041667,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — G5 X Mark II · ISO 125 · frame 1",
            "value": 4.186759077196646,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — EOS R · ISO 12800 · frame 1",
            "value": 6.024714645316986,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 364.5006912,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600",
            "value": 181.60872488333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 179.97301898333333,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 425.65276928000003,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 424.73133372,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 1",
            "value": 672.1750707666666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 2",
            "value": 679.0631877166667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 3",
            "value": 678.3580289166666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Decode Rice symbol",
            "value": 11.410736385176325,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Map residual sign",
            "value": 0.722077075399959,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Update Rice parameter",
            "value": 1.643924505968742,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.46165595907668444,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 0.8217995006206095,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 43.66513329528985,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 77.66599696153847,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — G5 X Mark II · ISO 125 · frame 1",
            "value": 9.769013475728155,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — EOS R · ISO 12800 · frame 1",
            "value": 14.581842039855072,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.0005722663070723637,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — EOS R · ISO 12800 · frame 1",
            "value": 0.0006195726173258686,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 20.249636595,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — EOS R · ISO 12800 · frame 1",
            "value": 30.088809698529413,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
};
