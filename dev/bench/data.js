window.BENCHMARK_DATA = {
  "lastUpdate": 1794722546432,
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
          "id": "74623dd8db5c0e477ab83ef12ff56f101feed026",
          "message": "Import CR3 sensor data in Fiji and release CR3 libraries without Central by default (#351)\n\n* Target Java 21 for the standalone CR3 libraries and verify the packaged consumer on JDK 21\n\n* Document offline and hosted installation of the CR3 Fiji distribution\n\n* Build the thin ImageJ plugin and stage CR3 runtime through a Maven update-site profile\n\n* Build and verify a portable CR3 site using Fiji's updater and retain release history\n\n* Import native CR3 sensor data through the Fiji File menu and attach installable distributions\n\n* Exercise the packaged menu command in the Fiji distribution on Java 21\n\n* Stage CR3 Maven releases on a Git-backed repository and make Central opt-in\n\n* Verify Maven consumer provenance from staged HTTP transfers\n\n* Run the Fiji comparison in batch mode so the application exits cleanly",
          "timestamp": "2026-11-15T06:25:45+01:00",
          "tree_id": "fada32913b4073f15733099c847e0427f319dd52",
          "url": "https://github.com/carstenartur/Photographer/commit/74623dd8db5c0e477ab83ef12ff56f101feed026"
        },
        "date": 1794722544395,
        "tool": "jmh",
        "benches": [
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 1",
            "value": 3.634562057726543,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600",
            "value": 1.9424689916839513,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 1.9100994920499967,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 3",
            "value": 3.943390979079677,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 2",
            "value": 3.9482720030763687,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 1",
            "value": 6.947344866497422,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 2",
            "value": 7.050726764025326,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 3",
            "value": 7.082483410340811,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — G5 X Mark II · ISO 125 · frame 1",
            "value": 16.48670376386913,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — EOS R · ISO 12800 · frame 1",
            "value": 18.414102237143727,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 628.80061325,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — EOS R · ISO 12800 · frame 1",
            "value": 991.9013226666666,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — G5 X Mark II · ISO 125 · frame 1",
            "value": 327.04936964285713,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — EOS R · ISO 12800 · frame 1",
            "value": 484.28288205,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.02681741498624392,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600",
            "value": 0.027074738577865177,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.02628855568044492,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 3",
            "value": 0.02624321047670866,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 2",
            "value": 0.026568897100999106,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 1",
            "value": 0.0676444306258552,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 2",
            "value": 0.0750616860336161,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 3",
            "value": 0.06802776240272765,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 1",
            "value": 15.496625974633275,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600",
            "value": 11.19701142323513,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 11.217840264715754,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 3",
            "value": 19.369238762509337,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 2",
            "value": 19.47316942084047,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 1",
            "value": 16.984484451867008,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 2",
            "value": 16.741849675775285,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 3",
            "value": 16.788730632941174,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 313.6755846625,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600",
            "value": 163.97975749032258,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 166.05359607784945,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 319.174999325,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 344.6403262,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 1",
            "value": 498.70527961818186,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 2",
            "value": 495.21524116363634,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 3",
            "value": 508.99547051999997,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 609.6753402444444,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600",
            "value": 454.5408972969697,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 471.1872213636363,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 619.3221599333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 655.2173516,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 1",
            "value": 946.5250415666666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 2",
            "value": 944.0057128666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 3",
            "value": 957.5941949333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 623.4200727777778,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 1600",
            "value": 470.07512336363635,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 447.0784588666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 620.2769534027777,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 642.5071507,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 1",
            "value": 958.2642112666668,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 2",
            "value": 932.6957040000001,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 3",
            "value": 935.1320395333332,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — G5 X Mark II · ISO 125 · frame 1",
            "value": 105.14364111111111,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — EOS R · ISO 12800 · frame 1",
            "value": 157.19448404166667,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — G5 X Mark II · ISO 125 · frame 1",
            "value": 33.86478742045454,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — EOS R · ISO 12800 · frame 1",
            "value": 51.952558499999995,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — G5 X Mark II · ISO 125 · frame 1",
            "value": 19.185431950757575,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — EOS R · ISO 12800 · frame 1",
            "value": 28.848899477272727,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — G5 X Mark II · ISO 125 · frame 1",
            "value": 149.55898497767856,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — EOS R · ISO 12800 · frame 1",
            "value": 223.42861209999998,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — G5 X Mark II · ISO 125 · frame 1",
            "value": 6.518907564422474,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — EOS R · ISO 12800 · frame 1",
            "value": 9.48447012912913,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 424.43289468,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600",
            "value": 190.55487345454546,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 190.22319112727274,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 458.14033336,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 450.63863976000005,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 1",
            "value": 700.5340368,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 2",
            "value": 736.2200165333334,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 3",
            "value": 715.0196306666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Decode Rice symbol",
            "value": 8.828871936866056,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Map residual sign",
            "value": 0.7882382745460732,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Update Rice parameter",
            "value": 1.6897102601356035,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.7560989552041782,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 1.3043473348527437,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 51.435161349999994,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 86.53801814583333,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — G5 X Mark II · ISO 125 · frame 1",
            "value": 12.416610293209878,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — EOS R · ISO 12800 · frame 1",
            "value": 19.29453008173077,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.000679902638173355,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — EOS R · ISO 12800 · frame 1",
            "value": 0.0006836999285990997,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 20.04485709,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — EOS R · ISO 12800 · frame 1",
            "value": 29.690825036764704,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
};
