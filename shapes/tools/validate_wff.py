#!/usr/bin/env python3
"""
Validate a Watch Face Format XML against Google's official WFF XSD.

    pip install xmlschema
    python shapes/tools/validate_wff.py shapes/src/main/res/raw/watchface.xml
    python shapes/tools/validate_wff.py app/src/main/res/raw/watchface.xml --version 2

The XSD (from github.com/google/watchface, Apache-2.0) is downloaded once into
shapes/tools/.wff_xsd/<version>/ (git-ignored). It uses XSD 1.1, so xmllint
can't read it - this uses the `xmlschema` package instead.
"""
import argparse
import os
import posixpath
import re
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/google/watchface/main/third_party/wff/specification/documents/{v}/"
HERE = os.path.dirname(os.path.abspath(__file__))


def fetch_schema(version):
    root = os.path.join(HERE, ".wff_xsd", str(version))
    base = BASE.format(v=version)
    seen = set()

    def fetch(rel):
        rel = posixpath.normpath(rel)
        if rel in seen:
            return
        seen.add(rel)
        path = os.path.join(root, *rel.split("/"))
        if not os.path.exists(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with urllib.request.urlopen(base + rel, timeout=30) as r:
                data = r.read()
            with open(path, "wb") as f:
                f.write(data)
        with open(path, encoding="utf-8") as f:
            text = f.read()
        for inc in re.findall(r'schemaLocation="([^"]+)"', text):
            fetch(posixpath.join(posixpath.dirname(rel), inc))

    fetch("watchface.xsd")
    return os.path.join(root, "watchface.xsd")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("xml")
    ap.add_argument("--version", default="2", help="WFF version (manifest format.version)")
    args = ap.parse_args()
    try:
        import xmlschema
    except ImportError:
        sys.exit("pip install xmlschema")
    schema = xmlschema.XMLSchema11(fetch_schema(args.version))
    errors = list(schema.iter_errors(args.xml))
    for e in errors[:30]:
        print(f"ERR: {e.reason}\n     at {e.path}")
    if errors:
        print(f"{len(errors)} error(s)")
        sys.exit(1)
    print("VALID")


if __name__ == "__main__":
    main()
