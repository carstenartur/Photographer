'use strict';

(function() {
  const pathInput = 'RAW pipeline · Decode display-ready sRGB (path input)';
  const headerParse = 'CRX stage · Parse codec header';
  const sensorRegion = 'ImageIO import · Decode Bayer region';
  const processedRegion = 'ImageIO import · Develop RGB region';

  const benchmarkLabels = {
    'com.photographer.focusstack.cr3.benchmark.Cr3BoxParseBenchmark.parse':
      'Container · Parse ISO-BMFF boxes',
    'com.photographer.focusstack.cr3.benchmark.Cr3MetadataBenchmark.readMetadata':
      'Metadata · Read EXIF and MakerNote',
    'com.photographer.focusstack.cr3.benchmark.Cr3PreviewExtractBenchmark.extractPreview':
      'Preview · Extract embedded JPEG',
    'com.photographer.focusstack.cr3.benchmark.CrxLosslessDecodeBenchmark.decode':
      'CRX decoder · Decode complete lossless RAW',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawLinearBenchmark.decodeRawLinear':
      'RAW pipeline · Decode linear RAW',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessedBenchmark.decodeRawProcessed':
      'RAW pipeline · Decode display-ready sRGB (stream input)',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessedBenchmark.decodeRawProcessedMmap':
      pathInput,
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessedBenchmark.decodeRawProcessedFromPath':
      pathInput,
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.parseHeader':
      headerParse,
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.decodeFirstPlane':
      'CRX stage · Decode first tile plane',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.decodeNativeFirstPlane':
      'CRX stage · Decode native first tile plane',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.copyFirstTilePlane':
      'CRX stage · Copy first tile plane',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.interleavePositionalPlanes':
      'CRX stage · Interleave Bayer planes',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.predictFirstPlane':
      'CRX stage · Apply MED prediction to first plane',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderMicroStagesBenchmark.decodeRiceSymbols':
      'CRX inner loop · Decode Rice symbol',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderMicroStagesBenchmark.mapResidualSigns':
      'CRX inner loop · Map residual sign',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderMicroStagesBenchmark.updateRiceAdaptation':
      'CRX inner loop · Update Rice parameter',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.subtractBlackLevel':
      'RAW processing · Subtract black level',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.applyWhiteBalance':
      'RAW processing · Apply white balance',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.demosaicBayer':
      'RAW processing · Demosaic Bayer data',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.applyColorMatrix':
      'RAW processing · Apply camera colour matrix',
    'com.photographer.focusstack.cr3.benchmark.Cr3RawProcessingStagesBenchmark.convertToSrgb':
      'RAW processing · Convert linear RGB to sRGB',
    'com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importProcessedRawViaImageIo':
      'Complete import · Open CR3 as display-ready RAW',
    'com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importSensorViaImageIo':
      'ImageIO import · Decode native Bayer samples',
    'com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importSensorRegionViaImageIo':
      sensorRegion,
    'com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importProcessedRegionViaImageIo':
      processedRegion,
    'com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importPreviewViaImageIo':
      'Complete import · Open embedded preview'
  };

  const publishedLabels = new Map([
    ['RAW pipeline · Decode display-ready sRGB (memory-mapped input)', pathInput],
    ['CRX stage · Decode first tile plane', 'CRX stage · Decode experimental first tile plane'],
    ['Cr3 Image Import · Import Sensor Region Via Image Io', sensorRegion],
    ['Cr3 Image Import · Import Processed Region Via Image Io', processedRegion]
  ]);
  const decoderBenchmarks = new Set([
    'CRX decoder · Decode complete lossless RAW',
    'RAW pipeline · Decode linear RAW',
    'RAW pipeline · Decode display-ready sRGB (stream input)',
    'Complete import · Open CR3 as display-ready RAW'
  ]);

  // These are the main source commits, not the publication dates of their runs.
  const nativeDecoderCommit = Date.parse('2026-11-14T11:25:13Z'); // 0448da0, #341
  const streamedPathCommit = Date.parse('2026-11-14T12:22:25Z'); // b2a4bb3, #342

  const sampleLabels = {
    'sample.cr3': 'G5 X Mark II · ISO 125 · frame 1',
    'sample-tele.cr3': 'G5 X Mark II · ISO 125 · frame 2',
    'sample-wide.cr3': 'G5 X Mark II · ISO 125 · frame 3',
    'sample-iso-high.cr3': 'G5 X Mark II · ISO 1600',
    'sample-wb-tungsten.cr3': 'G5 X Mark II · ISO 1600 · tungsten WB',
    'eosr-iso-low.cr3': 'EOS R · ISO 12800 · frame 1',
    'eosr-iso-mid.cr3': 'EOS R · ISO 12800 · frame 2',
    'eosr-iso-high.cr3': 'EOS R · ISO 12800 · frame 3'
  };

  const publishedSeries = /^(.*?)\s*\(\s*(\{.*\})\s*\)\s*$/;

  function humanize(identifier) {
    const withoutSuffix = identifier.replace(/Benchmark$/, '');
    const words = withoutSuffix
      .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/_/g, ' ')
      .trim();
    return words ? words[0].toUpperCase() + words.slice(1) : words;
  }

  function operationLabel(rawName) {
    if (benchmarkLabels[rawName]) {
      return benchmarkLabels[rawName];
    }
    if (!rawName.includes('Benchmark.')) {
      return rawName;
    }
    const parts = rawName.split('.');
    const method = parts.pop() || rawName;
    const className = parts.pop() || '';
    return humanize(className) + ' · ' + humanize(method);
  }

  function parameterLabels(params) {
    const details = [];
    const keys = Object.keys(params).sort();
    for (const key of keys) {
      const value = params[key];
      if (key === 'sample' && typeof value === 'string') {
        details.push(sampleLabels[value] || value);
        continue;
      }
      const rendered = value === true ? 'yes' : value === false ? 'no' :
        value === null ? 'none' : String(value);
      details.push(humanize(key) + ': ' + rendered);
    }
    return details;
  }

  function format(name) {
    const match = publishedSeries.exec(name);
    if (!match) {
      return operationLabel(name);
    }

    let params;
    try {
      params = JSON.parse(match[2]);
    } catch (_error) {
      return operationLabel(match[1]);
    }
    if (!params || Array.isArray(params) || typeof params !== 'object') {
      return operationLabel(match[1]);
    }

    const operation = operationLabel(match[1]);
    const details = parameterLabels(params);
    return details.length ? operation + ' — ' + details.join(' · ') : operation;
  }

  function implementation(operation, commit) {
    if (operation !== pathInput && operation !== headerParse && !decoderBenchmarks.has(operation)) {
      return '';
    }

    const sourceDate = Date.parse(commit.timestamp);
    if (!Number.isFinite(sourceDate)) {
      return 'Source revision date unavailable';
    }
    const native = sourceDate >= nativeDecoderCommit;
    if (operation === headerParse) {
      return native ? 'CMP1 directory parser' : 'Legacy header parser';
    }
    const decoder = native ? 'Native decoder' : 'Experimental decoder';
    if (operation === pathInput) {
      const input = sourceDate >= streamedPathCommit ? 'streamed path input' : 'memory-mapped input';
      return decoder + ' · ' + input;
    }
    return decoder;
  }

  function describe(name, commit) {
    const formatted = format(name);
    const separator = formatted.indexOf(' — ');
    const operation = separator === -1 ? formatted : formatted.slice(0, separator);
    const label = publishedLabels.get(operation) || operation;
    return {
      label: separator === -1 ? label : label + formatted.slice(separator),
      implementation: implementation(label, commit)
    };
  }

  window.CR3_BENCHMARK_LABELS = Object.freeze({ format, describe });
})();
