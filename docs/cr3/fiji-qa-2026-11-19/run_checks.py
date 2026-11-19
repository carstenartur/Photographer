"""Replay the installed ImageJ plugin checks from matrix.json, one headless import per JVM."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def checked_process(command, stem):
    result = subprocess.run(command, capture_output=True, text=True, timeout=240)
    write_json(stem.with_suffix(".command.json"), command)
    stem.with_suffix(".stdout").write_text(result.stdout, encoding="utf-8")
    stem.with_suffix(".stderr").write_text(result.stderr, encoding="utf-8")
    write_json(stem.with_suffix(".exit.json"), {"exit_code": result.returncode})
    result.check_returncode()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True, help="Photographer checkout with fixtures and reference")
    parser.add_argument("--fiji", type=Path, required=True, help="Fiji directory with the plugin installed")
    parser.add_argument("--install-zip", type=Path, required=True, help="Distribution ZIP used for this installation")
    parser.add_argument("--m50", type=Path, required=True, help="Public EOS M50 input, entry 4656")
    parser.add_argument("--javac", type=Path, required=True, help="JDK 25 compiler executable")
    parser.add_argument("--java", type=Path, required=True, help="Java 21 runtime executable")
    parser.add_argument("--output", type=Path, required=True, help="Fresh output directory")
    args = parser.parse_args()

    qa = Path(__file__).resolve().parent
    matrix = json.loads((qa / "matrix.json").read_text(encoding="utf-8"))
    probe = qa / "Cr3ImageJProbe.java"
    if digest(probe) != matrix["tools"]["probe_source_sha256"]:
        raise ValueError("Probe source differs from the recorded checks")
    jars = matrix["candidate_install_zip"]["jars"]
    with zipfile.ZipFile(args.install_zip) as bundle:
        if sorted(bundle.namelist()) != sorted(j["path"] for j in jars):
            raise ValueError("Expected an install ZIP containing the seven listed CR3 JARs")
        for jar in jars:
            installed = args.fiji / jar["path"]
            if installed.read_bytes() != bundle.read(jar["path"]):
                raise ValueError(f"Installed JAR differs from supplied install ZIP: {installed}")
    jar_paths = [(args.fiji / p).resolve() for p in matrix["commands"]["classpath_order"]]
    jar_hashes = {p.name: digest(p) for p in jar_paths}
    classpath = os.pathsep.join(map(str, jar_paths))
    inputs = []
    for item in matrix["inputs"]:
        path = ((args.repo / item["repository_path"]) if "repository_path" in item else args.m50).resolve()
        if path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]:
            raise ValueError(f"Input differs from recorded fixture: {path}")
        inputs.append(path)
    reference_file = args.repo / matrix["reference"]["repository_path"]
    if digest(reference_file) != matrix["reference"]["sha256"]:
        raise ValueError("The independent reference file differs from the pinned version")
    panels = json.loads(reference_file.read_bytes())["panels"]

    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out / "classes"
    classes.mkdir()
    compile_command = [str(args.javac.resolve()), "--release", "21", "-proc:none", "-cp", classpath,
                       "-d", str(classes), str(probe)]
    checked_process(compile_command, out / "compile")
    write_json(out / "runtime.json", {"install_zip_sha256": digest(args.install_zip),
               "jars_sha256": jar_hashes, "probe_class_sha256": digest(classes / "Cr3ImageJProbe.class")})
    for case in matrix["cases"]:
        path = inputs[case["input_index"]]
        command = [str(args.java.resolve()), "-Djava.awt.headless=true", "-Xms24m",
                   f"-Xmx{case['heap_mib']}m", f"-XX:+Use{case['collector']}GC",
                   "-XX:ActiveProcessorCount=4", "-cp", str(classes) + os.pathsep + classpath,
                   "Cr3ImageJProbe", case["mode"], str(path), "--checksum"]
        result = checked_process(command, out / case["id"])
        actual = json.loads(result.stdout)
        pair, = [p for p in panels[case["reference_panel"]]["pairs"]
                 if p["input_index"] == case["input_index"] and p["mode"] == case["reference_mode"]]
        if not pair["complete_match"]:
            raise ValueError(f"Reference panel does not agree: {case['id']}")
        expected = next(iter(pair["variants"].values()))
        mismatches = [k for k, v in expected.items() if actual.get(k) != v]
        if mismatches:
            raise ValueError(f"{case['id']}: differing output fields {mismatches}")
        if (actual["input"] != str(path) or actual["mode"] != case["reference_mode"]
                or actual["imagej_mode"] != case["mode"] or actual["requested_memory_mode"] != "AUTO"
                or actual["java_version"].split(".")[0] != "21" or actual["available_processors"] != 4
                or actual["window_open"] or not actual["headless"]):
            raise ValueError(f"{case['id']}: unexpected import or runtime configuration")
        warnings = [w for w in result.stderr.splitlines() if w.startswith("CR3 low-memory:")]
        limits = [int(s) for w in warnings for s in re.findall(r"worker limit (\d+)", w)]
        if (len(warnings) != len(case["compact_messages"]) or len(limits) != len(warnings)
                or any(n < 1 or n > 4 for n in limits)):
            raise ValueError(f"{case['id']}: unexpected compact messages {warnings}")
        print(f"{case['id']}: {actual['fnv1a64']}, compact worker limits {limits}", flush=True)


if __name__ == "__main__":
    main()
