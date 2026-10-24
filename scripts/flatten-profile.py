#!/usr/bin/env python3
"""Resolve a cadeia "inherits" de um perfil do OrcaSlicer e grava um JSON completo.

O CLI do Orca desktop (--load-settings / --load-filaments) não resolve "inherits":
usa só as chaves do próprio arquivo e os defaults. Este script faz o que o
PresetBundle faz (pai primeiro, filho sobrescreve) para gerar referências fiéis.

Uso: flatten-profile.py <profiles_dir> <fabricante> <machine|process|filament> <nome do preset> <saida.json>
Os pais são procurados pelo campo "name" no fabricante e em OrcaFilamentLibrary.
"""
import json
import sys
from pathlib import Path


def index(profiles: Path, vendor: str, kind: str) -> dict:
    by_name = {}
    for v in ("OrcaFilamentLibrary", vendor):  # o fabricante tem prioridade
        for path in (profiles / v / kind).glob("**/*.json"):
            data = json.loads(path.read_text(encoding="utf-8"))
            if "name" in data:
                by_name[data["name"]] = data
    return by_name


def flatten(by_name: dict, name: str) -> dict:
    if name not in by_name:
        sys.exit(f"perfil não encontrado: {name}")
    data = dict(by_name[name])
    parent = data.pop("inherits", "")
    if not parent:
        return data
    merged = flatten(by_name, parent)
    merged.update(data)
    return merged


def main() -> None:
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    profiles, vendor, kind, name, out = Path(sys.argv[1]), *sys.argv[2:]
    data = flatten(index(profiles, vendor, kind), name)
    data["name"] = name
    Path(out).write_text(json.dumps(data, indent=4, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
