window.BENCHMARK_DATA = {
  "lastUpdate": 1794824573227,
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
          "id": "4a816c8ebd34082e0a11e5b32cfc6f72c7e620d5",
          "message": "Decode CR3 regions directly and improve native tile throughput (#382)",
          "timestamp": "2026-11-16T10:49:45+01:00",
          "tree_id": "52ed18b6629e2b5eec8f4f8055a479ea20097dbd",
          "url": "https://github.com/carstenartur/Photographer/commit/4a816c8ebd34082e0a11e5b32cfc6f72c7e620d5"
        },
        "date": 1794824571665,
        "tool": "jmh",
        "benches": [
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 1",
            "value": 1.4729268569790839,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600",
            "value": 0.6918739129217163,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.6756112268332639,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 3",
            "value": 1.6599112142046029,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — G5 X Mark II · ISO 125 · frame 2",
            "value": 1.6287137941531107,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 1",
            "value": 2.8366726455467997,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 2",
            "value": 2.7775376472618687,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Container · Parse ISO-BMFF boxes — EOS R · ISO 12800 · frame 3",
            "value": 2.9654805207126933,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — G5 X Mark II · ISO 125 · frame 1",
            "value": 14.86072705267517,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open embedded preview — EOS R · ISO 12800 · frame 1",
            "value": 17.252203905072935,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 471.59855715,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Complete import · Open CR3 as display-ready RAW — EOS R · ISO 12800 · frame 1",
            "value": 670.0399326666667,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Cr3 Image Import · Import Processed Region Via Image Io — G5 X Mark II · ISO 125 · frame 1",
            "value": 114.53357816666667,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Cr3 Image Import · Import Processed Region Via Image Io — EOS R · ISO 12800 · frame 1",
            "value": 200.5947861090909,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Cr3 Image Import · Import Sensor Region Via Image Io — G5 X Mark II · ISO 125 · frame 1",
            "value": 4.471043159814626,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Cr3 Image Import · Import Sensor Region Via Image Io — EOS R · ISO 12800 · frame 1",
            "value": 6.257249715067125,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — G5 X Mark II · ISO 125 · frame 1",
            "value": 236.80108677777778,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "ImageIO import · Decode native Bayer samples — EOS R · ISO 12800 · frame 1",
            "value": 364.335454125,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.018857972021999837,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600",
            "value": 0.01881162582631558,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 0.018855256797363044,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 3",
            "value": 0.01880893644016603,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — G5 X Mark II · ISO 125 · frame 2",
            "value": 0.018896020866597585,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 1",
            "value": 0.047305528728911535,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 2",
            "value": 0.04737592981018522,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Metadata · Read EXIF and MakerNote — EOS R · ISO 12800 · frame 3",
            "value": 0.04731228242142929,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 1",
            "value": 13.47705388989939,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600",
            "value": 9.29594935088286,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 9.329630384241788,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 3",
            "value": 17.284936091897254,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — G5 X Mark II · ISO 125 · frame 2",
            "value": 17.26242282835249,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 1",
            "value": 14.74258517265493,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 2",
            "value": 14.725877911972791,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "Preview · Extract embedded JPEG — EOS R · ISO 12800 · frame 3",
            "value": 14.839120560082147,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 254.79477767000003,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600",
            "value": 125.4673701402439,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 135.32608364509247,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 241.4894497047619,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 243.0840301238095,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 1",
            "value": 361.68249767142856,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 2",
            "value": 387.4146329538461,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode linear RAW — EOS R · ISO 12800 · frame 3",
            "value": 391.1877032923077,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 433.70454351666666,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600",
            "value": 334.3408053425,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 338.93520047999993,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 471.3587039454545,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 476.5954629818181,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 1",
            "value": 659.551768175,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 2",
            "value": 705.0776314750001,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (stream input) — EOS R · ISO 12800 · frame 3",
            "value": 701.5301212500001,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 1",
            "value": 456.7286635272727,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 1600",
            "value": 332.9538806141667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 340.34519654666667,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 3",
            "value": 483.03578569090905,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — G5 X Mark II · ISO 125 · frame 2",
            "value": 435.55830861666664,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 1",
            "value": 700.7853991750001,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 2",
            "value": 702.08400735,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW pipeline · Decode display-ready sRGB (path input) — EOS R · ISO 12800 · frame 3",
            "value": 701.256168325,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — G5 X Mark II · ISO 125 · frame 1",
            "value": 59.45392015625001,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply camera colour matrix — EOS R · ISO 12800 · frame 1",
            "value": 89.05943727272727,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — G5 X Mark II · ISO 125 · frame 1",
            "value": 24.74965750111408,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Apply white balance — EOS R · ISO 12800 · frame 1",
            "value": 36.84447120652174,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — G5 X Mark II · ISO 125 · frame 1",
            "value": 18.41757456976744,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Convert linear RGB to sRGB — EOS R · ISO 12800 · frame 1",
            "value": 28.011257183805416,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — G5 X Mark II · ISO 125 · frame 1",
            "value": 115.015340725,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Demosaic Bayer data — EOS R · ISO 12800 · frame 1",
            "value": 169.57719042857144,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — G5 X Mark II · ISO 125 · frame 1",
            "value": 2.945948296016484,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "RAW processing · Subtract black level — EOS R · ISO 12800 · frame 1",
            "value": 4.448239048155215,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 1",
            "value": 329.56218245714285,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600",
            "value": 143.85297469999998,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 1600 · tungsten WB",
            "value": 156.19711935384618,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 3",
            "value": 369.25961963333333,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — G5 X Mark II · ISO 125 · frame 2",
            "value": 344.13784689999994,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 1",
            "value": 538.13417005,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 2",
            "value": 547.4799654999999,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX decoder · Decode complete lossless RAW — EOS R · ISO 12800 · frame 3",
            "value": 553.317648,
            "unit": "ms/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Decode Rice symbol",
            "value": 6.351916712783179,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Map residual sign",
            "value": 0.6456907246542812,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX inner loop · Update Rice parameter",
            "value": 1.2826949054321397,
            "unit": "ns/op",
            "extra": "iterations: 5\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.3696924374761899,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Copy first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 0.8818792900634062,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 39.60503153846154,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Decode native first tile plane — EOS R · ISO 12800 · frame 1",
            "value": 66.99735293333333,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — G5 X Mark II · ISO 125 · frame 1",
            "value": 8.865139692477875,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Interleave Bayer planes — EOS R · ISO 12800 · frame 1",
            "value": 13.12085702922078,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — G5 X Mark II · ISO 125 · frame 1",
            "value": 0.00043494527208811544,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Parse codec header — EOS R · ISO 12800 · frame 1",
            "value": 0.0004755532897345087,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — G5 X Mark II · ISO 125 · frame 1",
            "value": 17.730569697368423,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          },
          {
            "name": "CRX stage · Apply MED prediction to first plane — EOS R · ISO 12800 · frame 1",
            "value": 26.34819292088394,
            "unit": "ms/op",
            "extra": "iterations: 4\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}