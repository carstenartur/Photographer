"""Fresh-process native import plus full PGM serialization, sequential engines."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import time

from public_measurements import file_record, utc_now, write_json

q = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path, default=q / 'final16-benchmarks.jar')
parser.add_argument('--forks', type=int, default=3)
parser.add_argument('--output', type=Path, default=q / 'cli-final')
parser.add_argument('--input-manifest', type=Path, default=q / 'inputs.json')
parser.add_argument('--extra-input', type=Path, default=q / 'extra-input.json')
parser.add_argument('--java', type=Path, default=q.parent / 'rgb16-tools/jdk-25.0.5.1+2/bin/java')
parser.add_argument('--probe-classes', type=Path, default=q / 'public-probe-classes')
parser.add_argument('--libraw', type=Path, default=q.parent / 'qa-tools/libraw-bench')
parser.add_argument('--dnglab', type=Path, default=q.parent / 'qa-tools/dnglab-0.8.0')
parser.add_argument('--cpus', nargs='+', type=int, choices=(1, 4), default=[1, 4])
args = parser.parse_args()
if args.forks < 1:
    parser.error('--forks must be positive')
args.output.mkdir(parents=True, exist_ok=False)
inputs = json.loads(args.input_manifest.read_text()) + [json.loads(args.extra_input.read_text())]
java = args.java
main_class = 'com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe'
manifest = dict(created_utc=utc_now(), runner=file_record(Path(__file__)), inputs=inputs,
                artifacts={name: file_record(path) for name, path in {
                    'jar': args.jar, 'java': java, 'libraw': args.libraw, 'dnglab': args.dnglab,
                    'probe': args.probe_classes / (main_class.replace('.', '/') + '.class')}.items()},
                forks=args.forks, cpus=args.cpus, planned_processes=len(inputs) * len(args.cpus) * 3 * args.forks,
                environment_policy=dict(OMP_NUM_THREADS='cpus', OMP_THREAD_LIMIT='cpus',
                                        OMP_DYNAMIC='FALSE', RAYON_NUM_THREADS='cpus'),
                notes=['Fresh process; full native sensor decode and PGM serialization to /dev/null.',
                       'Whole-process wall time includes startup, sampled Java/C diagnostics and shutdown.',
                       'Each child peak RSS is ru_maxrss in KiB on this Linux host.',
                       'Engine order rotates each fork; input SHA-256 reads warm the filesystem cache.'])
write_json(args.output / 'manifest.json', manifest)
records = []
for cpus in args.cpus:
    for index, sample in enumerate(inputs):
        with open(sample['path'], 'rb') as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == sample['sha256']
        for fork in range(args.forks):
            engines = ['Photographer', 'LibRaw', 'DNGLab']
            engines = engines[fork % len(engines):] + engines[:fork % len(engines)]
            for engine in engines:
                env = os.environ.copy()
                for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
                    env.pop(key, None)
                env.update(OMP_NUM_THREADS=str(cpus), OMP_THREAD_LIMIT=str(cpus),
                           OMP_DYNAMIC='FALSE', RAYON_NUM_THREADS=str(cpus))
                command = ['taskset', '-c', f'0-{cpus - 1}']
                if engine == 'Photographer':
                    command += [str(java), '-Xms24m', '-Xmx768m', '-XX:+UseSerialGC',
                                f'-XX:ActiveProcessorCount={cpus}', '-cp',
                                str(args.probe_classes) + os.pathsep + str(args.jar), main_class,
                                '--input', sample['path'], '--mode', 'sensor', '--dump', '/dev/null']
                elif engine == 'LibRaw':
                    command += [str(args.libraw), '--input', sample['path'],
                                '--mode', 'sensor', '--dump', '/dev/null']
                else:
                    command += [str(args.dnglab), 'analyze', '--raw-pixel', sample['path']]
                name = f"{Path(sample['sample']).stem}-{engine}-cpu{cpus}-fork{fork}"
                start_utc = datetime.datetime.now(datetime.timezone.utc).isoformat()
                with open(os.devnull, 'wb') as out, (args.output / (name + '.stderr')).open('wb') as err:
                    start = time.perf_counter()
                    process = subprocess.Popen(command, stdout=out, stderr=err, env=env)
                    _, status, usage = os.wait4(process.pid, 0)
                    process.returncode = os.waitstatus_to_exitcode(status)
                    elapsed = (time.perf_counter() - start) * 1000
                row = dict(engine=engine, input_index=index, sample=sample['sample'], input_sha256=sample['sha256'],
                           cpus=cpus, fork=fork, start_utc=start_utc, command=command,
                           exit_code=process.returncode, process_wall_ms=elapsed,
                           process_cpu_ms=(usage.ru_utime + usage.ru_stime) * 1000,
                           process_maxrss_kb=usage.ru_maxrss)
                records.append(row)
                (args.output / 'records.json').write_text(json.dumps(records, indent=2))
                print(name, 'exit', process.returncode, 'wall ms', round(elapsed, 2),
                      'peak MiB', round(usage.ru_maxrss / 1024, 2), flush=True)
summary = []
for index, sample in enumerate(inputs):
    for cpus in args.cpus:
        for engine in ('Photographer', 'LibRaw', 'DNGLab'):
            rows = [r for r in records if r['sample'] == sample['sample'] and r['cpus'] == cpus and r['engine'] == engine]
            good = [r for r in rows if r['exit_code'] == 0]
            summary.append(dict(input_index=index, sample=sample['sample'], cpus=cpus, engine=engine,
                                successful_forks=len(good), attempted_forks=len(rows),
                                median_wall_ms=statistics.median(r['process_wall_ms'] for r in good) if good else None,
                                median_rss_kb=statistics.median(r['process_maxrss_kb'] for r in good) if good else None,
                                max_rss_kb=max((r['process_maxrss_kb'] for r in good), default=None)))
(args.output / 'summary.json').write_text(json.dumps(summary, indent=2))
raise SystemExit(int(any(r['exit_code'] for r in records)))
