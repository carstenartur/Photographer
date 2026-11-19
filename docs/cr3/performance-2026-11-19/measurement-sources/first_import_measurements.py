#!/usr/bin/env python3
"""Fresh JVM RGB16 sRGB imports; first API call and whole probe process separately."""
import argparse
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import time

from public_measurements import file_record, sha256, utc_now, write_json


def main():
    work = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=work / 'first-import-final')
    parser.add_argument('--forks', type=int, default=3)
    parser.add_argument('--input-manifest', type=Path, default=work / 'inputs.json')
    parser.add_argument('--baseline-jar', type=Path, default=work / 'baseline-83a687e-benchmarks.jar')
    parser.add_argument('--final-jar', type=Path, default=work / 'final16-benchmarks.jar')
    parser.add_argument('--java', type=Path, default=work.parent / 'rgb16-tools/jdk-25.0.5.1+2/bin/java')
    parser.add_argument('--probe-classes', type=Path, default=work / 'public-probe-classes')
    args = parser.parse_args()
    if args.forks < 1:
        parser.error('--forks must be positive')
    args.output.mkdir(parents=True, exist_ok=False)
    inputs = json.loads(args.input_manifest.read_text())[:2]
    for sample in inputs:
        if sha256(Path(sample['path'])) != sample['sha256']:
            raise ValueError('Input checksum mismatch: ' + sample['path'])
    jars = {'baseline': args.baseline_jar, 'optimized': args.final_jar}
    java = args.java
    probe = args.probe_classes
    main_class = 'com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe'
    environment = os.environ.copy()
    for name in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        environment.pop(name, None)
    manifest = {'created_utc': utc_now(), 'inputs': inputs, 'forks': args.forks,
                'artifacts': {name: file_record(path) for name, path in jars.items()},
                'java': file_record(java), 'runner': file_record(Path(__file__)),
                'probe': file_record(probe / (main_class.replace('.', '/') + '.class')),
                'planned_processes': len(inputs) * 2 * 2 * args.forks,
                'notes': ['No warmup, no explicit GC and one import per fresh JVM.',
                          'Input hash reads warm the filesystem cache; this is not cold-storage latency.',
                          'First API time excludes plugin scan and output/statistics.',
                          'Whole probe process includes JVM/plugin startup, instrumentation, sampled hash and JSON.',
                          'Peak RSS is each child process ru_maxrss, in KiB on this Linux host.',
                          'AB/BA/AB variant order within each input/CPU/fork.']}
    write_json(args.output / 'manifest.json', manifest)
    records = []
    for fork in range(1, args.forks + 1):
        variants = list(jars) if fork % 2 else list(reversed(jars))
        for cpus in (1, 4):
            for index, sample in enumerate(inputs):
                for variant in variants:
                    command = ['taskset', '-c', f'0-{cpus - 1}', str(java),
                               '-Xms24m', '-Xmx768m', '-XX:+UseSerialGC',
                               f'-XX:ActiveProcessorCount={cpus}', '-cp',
                               str(probe) + os.pathsep + str(jars[variant]), main_class,
                               '--input', sample['path'], '--mode', 'rgb16',
                               '--warmup', '0', '--iterations', '1']
                    name = f'input{index}-{variant}-cpu{cpus}-fork{fork}'
                    start_utc = utc_now()
                    with (args.output / (name + '.stdout')).open('wb') as out, \
                            (args.output / (name + '.stderr')).open('wb') as err:
                        start = time.perf_counter()
                        process = subprocess.Popen(command, stdout=out, stderr=err, env=environment)
                        _, status, usage = os.wait4(process.pid, 0)
                        process.returncode = os.waitstatus_to_exitcode(status)
                        wall_ms = (time.perf_counter() - start) * 1000
                    rows = [json.loads(line) for line in (args.output / (name + '.stdout')).read_text().splitlines()
                            if line.lstrip().startswith('{')]
                    valid = process.returncode == 0 and len(rows) == 1 and rows[0].get('iteration') == 0 \
                        and math.isfinite(rows[0].get('total_ms', math.nan))
                    record = {'variant': variant, 'input_index': index, 'sample': sample['sample'],
                              'input_sha256': sample['sha256'], 'cpus': cpus, 'fork': fork,
                              'start_utc': start_utc, 'finish_utc': utc_now(), 'command': command,
                              'exit_code': process.returncode, 'status': 'ok' if valid else 'failed',
                              'process_wall_ms': wall_ms,
                              'process_cpu_ms': (usage.ru_utime + usage.ru_stime) * 1000,
                              'process_peak_rss_kb': usage.ru_maxrss, 'observations': rows}
                    records.append(record)
                    write_json(args.output / 'records.json', records)
                    print(len(records), '/', manifest['planned_processes'], name, record['status'],
                          'first API ms', rows[0].get('total_ms') if rows else None,
                          'process ms', round(wall_ms, 2), flush=True)
    groups = []
    for index, sample in enumerate(inputs):
        for cpus in (1, 4):
            for variant in jars:
                selected = [r for r in records if r['input_index'] == index and r['cpus'] == cpus
                            and r['variant'] == variant]
                good = [r for r in selected if r['status'] == 'ok']
                groups.append({'input_index': index, 'sample': sample['sample'], 'cpus': cpus,
                               'variant': variant, 'attempted_forks': len(selected), 'successful_forks': len(good),
                               'median_first_api_ms': statistics.median(r['observations'][0]['total_ms'] for r in good) if good else None,
                               'median_process_wall_ms': statistics.median(r['process_wall_ms'] for r in good) if good else None,
                               'median_process_peak_rss_kb': statistics.median(r['process_peak_rss_kb'] for r in good) if good else None})
    write_json(args.output / 'summary.json', groups)
    return int(any(r['status'] != 'ok' for r in records))


if __name__ == '__main__':
    raise SystemExit(main())
