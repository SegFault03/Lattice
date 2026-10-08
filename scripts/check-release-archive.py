#!/usr/bin/env python3
import argparse
import hashlib
import io
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET
import zipfile


def check(archive, version):
    with zipfile.ZipFile(archive) as distribution:
        files = distribution.namelist()
        allowed = {"Lattice/", "Lattice/lib/", "Lattice/lib/Lattice.jar", f"Lattice/lib/Lattice-{version}.jar",
                   f"Lattice/lib/Lattice-{version}-searchableOptions.jar", "Lattice/lib/mysql-connector-j-26.7.0.jar",
                   "Lattice/lib/hsqldb-2.7.4.jar", "Lattice/lib/protobuf-java-4.36.2.jar"}
        for name in files:
            if "\\" in name or PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
                raise ValueError("Archive contains a non-portable or unsafe path")
            if not name.startswith("Lattice/"):
                raise ValueError("Unexpected distribution root")
            if name not in allowed:
                raise ValueError(f"Unexpected release file: {name}")
        required = {"Lattice/lib/mysql-connector-j-26.7.0.jar", "Lattice/lib/hsqldb-2.7.4.jar",
                    "Lattice/lib/protobuf-java-4.36.2.jar"}
        if not required.issubset(files):
            raise ValueError("Missing production JDBC drivers")
        plugins = [name for name in files if name.endswith(".jar") and PurePosixPath(name).name.startswith("Lattice") and "searchableOptions" not in name]
        if len(plugins) != 1:
            raise ValueError("Expected one Lattice implementation JAR")
        with zipfile.ZipFile(io.BytesIO(distribution.read(plugins[0]))) as plugin:
            descriptor = ET.fromstring(plugin.read("META-INF/plugin.xml"))
            if descriptor.findtext("version") != version or descriptor.find("idea-version").get("since-build") != "251":
                raise ValueError("Plugin version or IntelliJ baseline mismatch")
            for notice in ("LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/mysql-connector-j-26.7.0-LICENSE.txt", "licenses/hsqldb-LICENSE.txt", "licenses/protobuf-LICENSE.txt"):
                if not plugin.read(notice):
                    raise ValueError("Empty bundled license notice")
            for name in plugin.namelist():
                if name.endswith(".class"):
                    data = plugin.read(name)
                    if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 65:
                        raise ValueError(f"Unexpected Java bytecode version: {name}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--checksums", type=Path)
    args = parser.parse_args()
    check(args.archive, args.version)
    if args.checksums:
        assets = [args.archive] + ([args.source] if args.source else [])
        lines = []
        for asset in assets:
            with asset.open("rb") as stream:
                lines.append(f"{hashlib.file_digest(stream, 'sha256').hexdigest()}  {asset.name}")
        args.checksums.parent.mkdir(parents=True, exist_ok=True)
        args.checksums.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Validated release archive {args.archive}")


if __name__ == "__main__":
    main()
