#!/usr/bin/env python3
"""Print JaCoCo instruction coverage as a percentage with 2 decimals."""
import sys
import xml.etree.ElementTree as ET


def main(path: str) -> int:
    tree = ET.parse(path)
    root = tree.getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "INSTRUCTION":
            covered = int(counter.get("covered", "0"))
            missed = int(counter.get("missed", "0"))
            total = covered + missed
            pct = 100.0 * covered / total if total else 0.0
            print(f"{pct:.2f}")
            return 0
    print("0.00")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: jacoco_coverage.py <jacocoTestReport.xml>", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
