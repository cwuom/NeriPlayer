"""Select a library's sources from the shared CRAP scope without changing its rules."""

import argparse
import json
from pathlib import Path


def select_scope(definition, source_root):
    patterns = [pattern for pattern in definition.get("source_patterns", ())
                if any(path.is_file() for path in source_root.glob(pattern))]
    methods = [rule for rule in definition.get("method_scopes", ())
               if (source_root / rule["source"]).is_file()]
    if not patterns and not methods:
        raise ValueError(f"No complexity rules select sources in {source_root}")
    return {"source_patterns": patterns, "method_scopes": methods}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    selected = select_scope(json.loads(args.scope.read_text()), args.source_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(selected, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
