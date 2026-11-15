#!/usr/bin/env python3
"""Build a portable CR3 update site with Fiji's own updater."""

import argparse
import datetime
import filecmp
import gzip
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "cr3-imagej"
LIBRARIES = ("container", "metadata", "codec", "processing", "core", "imageio")
PLUGIN = "plugins/CR3_Sensor_Import.jar"
SITE_NAME = "Canon CR3"
SITE_ARCHIVE = "cr3-fiji-update-site.zip"
INSTALL_ARCHIVE = "cr3-fiji-install.zip"


def write_db(directory, records):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "db.xml.gz").write_bytes(gzip.compress(ET.tostring(records), mtime=0))


def configure_client(directory, base, site, *, writable=False):
    records = ET.Element("pluginRecords")
    # Replace the default site with a local mirror of the two host APIs.
    # Both the upload and the clean installation run without network access.
    ET.SubElement(records, "update-site", name="ImageJ", timestamp="0", url=base.as_uri() + "/")
    attrs = {"ssh-host": "file:localhost", "upload-directory": str(site)} if writable else {}
    ET.SubElement(records, "update-site", name=SITE_NAME, timestamp="0", url=site.as_uri() + "/", **attrs)
    write_db(directory, records)


def stage_host_apis(fiji, base, publisher, consumer):
    with gzip.open(fiji / "db.xml.gz") as stream:
        records = ET.parse(stream)
    base_records = ET.Element("pluginRecords")
    for prefix in ("ij", "slf4j-api"):
        pattern = re.compile(r"jars/" + prefix + r"-\d[^/]*\.jar")
        candidates = [p for p in records.findall("plugin") if pattern.fullmatch(p.get("filename", ""))
                      and p.find("version") is not None and (fiji / p.get("filename")).is_file()]
        if len(candidates) != 1:
            raise ValueError(f"Expected one installed {prefix} JAR from the Fiji database: {fiji}")
        plugin = candidates[0]
        name = plugin.get("filename")
        version = plugin.find("version")
        timestamp = version.get("timestamp")
        entry = ET.SubElement(base_records, "plugin", filename=name)
        ET.SubElement(entry, "version", version.attrib)
        epoch = datetime.datetime.strptime(timestamp, "%Y%m%d%H%M%S").replace(tzinfo=datetime.timezone.utc).timestamp()
        for directory in (publisher, consumer):
            target = directory / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(fiji / name, target)
            os.utime(target, (epoch, epoch))
    write_db(base, base_records)


def run_updater(fiji, client, *args):
    command = ["java", "-Djava.awt.headless=true", "-Duser.timezone=UTC",
               f"-Dimagej.dir={client}", "-cp", str(fiji / "jars" / "*"),
               "net.imagej.updater.CommandLine", *args]
    proc = subprocess.run(command, cwd=client, text=True, stdout=subprocess.PIPE)
    if proc.returncode:
        raise ValueError(f"Fiji updater {args[0]} exited with status {proc.returncode}:\n{proc.stdout}")
    return proc.stdout


def restore_previous(archive, site, filenames):
    with zipfile.ZipFile(archive) as previous:
        names = previous.namelist()
        if "db.xml.gz" not in names or len(names) != len(set(names)):
            raise ValueError(f"Invalid previous site archive: {archive}")
        for name in names:
            if name != "db.xml.gz":
                path, separator, stamp = name.rpartition("-")
                if not separator or path not in filenames or not re.fullmatch(r"[0-9]{14}", stamp):
                    raise ValueError(f"Unexpected path in previous CR3 site: {name}")
            previous.extract(name, site)
    published = current_payloads(site, filenames)
    latest = max(path.name.rsplit("-", 1)[1] for path in published.values())
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S")
    if latest > now:
        raise ValueError(f"Previous release timestamp {latest} is in the future")
    if latest == now:
        time.sleep(1.1)  # Changed payloads need distinct updater timestamps.


def current_payloads(site, filenames):
    with gzip.open(site / "db.xml.gz") as stream:
        records = ET.parse(stream)
    payloads = {}
    for plugin in records.findall("plugin"):
        name = plugin.get("filename")
        if name not in filenames or name in payloads:
            raise ValueError(f"Unexpected Fiji database entry: {name}")
        current = plugin.find("version")
        if current is None or not re.fullmatch(r"[0-9]{14}", current.get("timestamp", "")):
            raise ValueError(f"Missing current version: {name}")
        payload = site / (name + "-" + current.get("timestamp"))
        if payload.stat().st_size != int(current.get("filesize")):
            raise ValueError(f"Incorrect payload size: {payload}")
        payloads[name] = payload
    if set(payloads) != set(filenames):
        raise ValueError("Fiji database does not include all seven CR3 JARs")
    return payloads


def zip_files(archive, files):
    # Stable entry metadata permits byte-for-byte repacking of the same site.
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as output:
        for name, path in sorted(files.items()):
            output.writestr(zipfile.ZipInfo(name), path.read_bytes())


def build(fiji, version, libraries, output, previous):
    inputs = {f"jars/cr3-{name}.jar": libraries / f"cr3-{name}-{version}.jar" for name in LIBRARIES}
    inputs[PLUGIN] = MODULE / "target" / f"cr3-imagej-{version}.jar"
    for path in inputs.values():
        if not path.is_file():
            raise ValueError(f"Missing build artifact: {path}")
    if not (fiji / "db.xml.gz").is_file() or not list((fiji / "jars").glob("imagej-updater-*.jar")):
        raise ValueError(f"Expected a Fiji installation with imagej-updater 2.x: {fiji}")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Serializing writers keeps simultaneous builds from branching site history.
    lock = output.with_name(output.name + ".lock")
    try:
        lock.mkdir()
    except FileExistsError:
        raise ValueError(f"An update-site build holds {lock}. Check for a running build before removing a stale lock.")
    try:
        installed_archive = output / SITE_ARCHIVE
        if output.exists():
            if not installed_archive.is_file():
                raise ValueError(f"Existing directory does not contain a CR3 site archive: {output}")
            if previous and not filecmp.cmp(previous, installed_archive, shallow=False):
                raise ValueError("The previous site differs from the existing output. Use a new output directory.")
            previous = installed_archive
        with tempfile.TemporaryDirectory(prefix=".cr3-fiji-", dir=output.parent) as temp:
            assemble(fiji, inputs, output, previous, Path(temp))
    finally:
        lock.rmdir()
    print(f"Verified {len(inputs)} installed JARs. Archives and site: {output}")


def assemble(fiji, inputs, output, previous, work):
    publisher, consumer, base = (work / name for name in ("publisher", "consumer", "host"))
    result = work / "result"
    site = result / "site"
    site.mkdir(parents=True)
    if previous:
        restore_previous(previous, site, inputs)
    else:
        write_db(site, ET.Element("pluginRecords"))
    stage_host_apis(fiji, base, publisher, consumer)
    configure_client(publisher, base, site, writable=True)
    for name, artifact in inputs.items():
        target = publisher / name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(artifact, target)
    # The updater computes its JAR checksums, discovers dependencies and
    # retains prior versions. Only the explicit CR3 files can be uploaded.
    run_updater(fiji, publisher, "upload", "--update-site", SITE_NAME, *inputs)
    (site / "db.xml.gz.old").unlink(missing_ok=True)
    payloads = current_payloads(site, inputs)
    configure_client(consumer, base, site)
    run_updater(fiji, consumer, "update")
    for name, payload in payloads.items():
        if not filecmp.cmp(consumer / name, payload, shallow=False):
            raise ValueError(f"Installed JAR differs from the published payload: {name}")
    for client in (publisher, consumer):
        listing = run_updater(fiji, client, "list-uptodate", *inputs)
        installed = {line.split("\t", 1)[0] for line in listing.splitlines() if "\t(INSTALLED)\t" in line}
        if installed != set(inputs):
            raise ValueError(f"Updater did not recognize the expected installed files:\n{listing}")
    zip_files(result / SITE_ARCHIVE, {p.relative_to(site).as_posix(): p for p in site.rglob("*") if p.is_file()})
    zip_files(result / INSTALL_ARCHIVE, {name: consumer / name for name in inputs})
    if not output.exists():
        result.rename(output)
        return
    # Stage both portable archives first. The site database can then be
    # switched atomically after all corresponding payloads are in place.
    for payload in sorted(site.glob("*/*")):
        destination = output / "site" / payload.relative_to(site)
        destination.parent.mkdir(parents=True, exist_ok=True)
        payload.replace(destination)
    for name in (SITE_ARCHIVE, INSTALL_ARCHIVE, "site/db.xml.gz"):
        (result / name).replace(output / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fiji", type=Path, required=True, help="Fiji application directory containing jars/ and db.xml.gz")
    parser.add_argument("--version", required=True, help="Maven artifact version")
    parser.add_argument("--libraries", type=Path, default=MODULE / "target/fiji-libraries")
    parser.add_argument("--output", type=Path, default=ROOT / "target/cr3-fiji-site")
    parser.add_argument("--previous", type=Path, help="Previous release's cr3-fiji-update-site.zip")
    args = parser.parse_args()
    try:
        build(args.fiji.resolve(), args.version, args.libraries.resolve(), args.output.resolve(),
              args.previous or os.environ.get("FIJI_PREVIOUS_SITE"))
    except (OSError, ValueError, zipfile.BadZipFile, ET.ParseError) as exc:
        parser.exit(1, f"CR3 site build failed: {exc}\n")
