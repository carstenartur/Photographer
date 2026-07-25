'use strict';

(function() {
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
      'RAW pipeline · Decode display-ready sRGB (memory-mapped input)',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.parseHeader':
      'CRX stage · Parse codec header',
    'com.photographer.focusstack.cr3.raw.codec.CrxDecoderStagesBenchmark.decodeFirstPlane':
      'CRX stage · Decode first tile plane',
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
    'com.photographer.focusstack.cr3.benchmark.Cr3ImageImportBenchmark.importPreviewViaImageIo':
      'Complete import · Open embedded preview'
  };

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
      return name;
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

  window.CR3_BENCHMARK_LABELS = Object.freeze({ format });
})();
