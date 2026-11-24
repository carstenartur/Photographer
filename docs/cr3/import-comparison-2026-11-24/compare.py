"""Run the recorded RGB16 or native Bayer comparison with the two public probes."""
import argparse
import filecmp
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess


def run_probe(probe, cpus, env, out, source, stem, engine, channels, verify):
    command = cpus + probe + [
        '--input', str(source), '--warmup', '0' if verify else '3', '--iterations', '1' if verify else '5',
    ]
    if verify:
        command.extend(['--checksum', '--dump', str(out / f'{engine}.pgm')])
    result = subprocess.run(command, capture_output=True, text=True, env=env)
    (out / f'{stem}.jsonl').write_text(result.stdout)
    (out / f'{stem}.err').write_text(result.stderr)
    result.check_returncode()
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
    if len(rows) != (1 if verify else 5) or any(
        row['bits'] != 16 or row['channels'] != channels for row in rows
    ):
        raise ValueError(f'Unexpected output for {stem}')
    print(stem, statistics.median(row['total_ms'] for row in rows), flush=True)


def verify_bayer(out, case):
    java_pgm, native_pgm = out / 'java.pgm', out / 'libraw.pgm'
    if not filecmp.cmp(java_pgm, native_pgm, shallow=False):
        raise ValueError(f'Bayer data differ for {case}')
    with java_pgm.open('rb') as image:
        print(case, hashlib.file_digest(image, 'sha256').hexdigest(), flush=True)
    java_pgm.unlink()
    native_pgm.unlink()


def compare(mode):
    repo = Path(os.environ['PHOTOGRAPHER_REPO']).resolve()
    samples = repo / 'canon-cr3-imageio/src/test/resources/samples'
    external = Path(os.environ['CR3_EXTERNAL']).resolve()
    inputs = {
        'g5-raw': samples / 'sample.cr3',
        'g5-craw': samples / 'research/g5xm2-detail-iso1600-ss0p4s-ff1p6-iq3-20260511-201012-02.cr3',
        'eos-r-raw': samples / 'eosr-iso-low.cr3',
    }
    native = [os.environ['LIBRAW_BENCH'], '--mode', mode]
    if mode == 'rgb16':
        inputs.update({
            'r8-craw': external / '6587.cr3',
            'g7iii-craw': external / '6054.cr3',
            'r5ii-apsc-craw': external / '7884.cr3',
        })
        native.extend(['--transfer', 'srgb', '--quality', '0', '--wb', 'camera',
                       '--auto-bright', '0', '--orientation', 'camera'])
    java = [os.environ.get('CR3_JAVA', 'java'), '-Xms24m', '-Xmx768m', '-XX:+UseSerialGC',
            '-XX:ActiveProcessorCount=4', '-cp', os.environ['CR3_CLASSPATH'],
            'com.photographer.focusstack.cr3.benchmark.Cr3ImportProbe',
            '--mode', 'default' if mode == 'rgb16' else 'sensor', '--gc-between']
    out = Path(os.environ['CR3_RESULT_DIR']).resolve() / mode
    out.mkdir(parents=True, exist_ok=False)
    cpus = ['taskset', '-c', os.environ.get('CR3_CPUS', '4-7')]
    env = dict(os.environ, OMP_NUM_THREADS='4', OMP_THREAD_LIMIT='4', OMP_DYNAMIC='FALSE')
    cases = list(inputs)
    for fork in range(4 if mode == 'sensor' else 3):
        verify = fork == 3
        rotation = fork % len(cases)
        for case in cases[rotation:] + cases[:rotation]:
            java_first = (cases.index(case) + fork) % 2 == (0 if mode == 'rgb16' else 1)
            order = [('java', java), ('libraw', native)]
            for engine, probe in order if java_first else reversed(order):
                run_probe(probe, cpus, env, out, inputs[case], f'{case}-{fork + 1}-{engine}',
                          engine, 3 if mode == 'rgb16' else 1, verify)
            if verify:
                verify_bayer(out, case)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('rgb16', 'sensor'))
    compare(parser.parse_args().mode)
