#!/usr/bin/env python3
"""Copia, dos fatiadores dos fabricantes instalados no PC, os perfis das impressoras do usuário.

Para cada fatiador (Bambu Studio, Creality Print, QIDI Studio, Snapmaker Orca) pega da pasta
system/ (os perfis de sistema já atualizados pelo próprio app) só as máquinas escolhidas, os
processos e filamentos compatíveis com elas e tudo de que dependem por "inherits"/"include",
inclusive em outro fabricante (OrcaFilamentLibrary). Gera um <Fabricante>.json com as listas
reduzidas, na ordem do original. Copia também os presets do usuário (user/<conta>/*) para
user/<tipo>/, sem a pasta da conta, os .info e o sync_data (têm a conta do usuário). Do Creality
Print entram também os presets da versão 7.0 que não existem na 7.3.

Uso: import-vendor-profiles.py [<appdata>] [<saida>]
     (padrões: %APPDATA% e profiles/imported)
"""
import json
import os
import re
import shutil
import sys
from pathlib import Path

# fatiador -> (pasta em %APPDATA%, {fabricante: regex dos nomes de máquina/modelo})
# Presets do usuário: além de <pasta>/user, as pastas de OLD_USER (na ordem, sem sobrescrever).
SOURCES = {
    "BambuStudio": ("BambuStudio", {"BBL": r"^Bambu Lab A1( |$)"}),  # A1 e A1 mini
    "CrealityPrint": ("Creality/Creality Print/7.3", {"Creality": r"^Creality (K2|K3)( \d|_model|$)"}),
    "QIDIStudio": ("QIDIStudio", {"Q Series": r"^(Qidi Q2|Q2)( \d|$)"}),
    "SnapmakerOrca": ("Snapmaker_Orca", {"Snapmaker": r"^Snapmaker U1( |$)"}),
}
OLD_USER = {"CrealityPrint": ["Creality/Creality Print/7.0/user"]}
KINDS = {"machine_model_list": "machine_model", "machine_list": "machine",
         "process_list": "process", "filament_list": "filament"}


def load(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def as_list(value) -> list:
    """Os perfis guardam listas ora como lista JSON, ora como texto "['a', 'b']" ou "a;b"."""
    if value is None or value == "":
        return []
    if isinstance(value, list):
        return value
    text = value.strip()
    if text.startswith("["):
        return [s.strip().strip("'\"") for s in text[1:-1].split(",") if s.strip()]
    return [s for s in text.split(";") if s]


class Vendor:
    def __init__(self, system: Path, name: str):
        self.name = name
        self.index = load(system / f"{name}.json")
        self.dir = system / name
        # (lista, nome) -> sub_path
        self.entries = {(k, e["name"]): e["sub_path"] for k in KINDS for e in self.index.get(k, [])}
        self.data = {key: load(self.dir / sub) for key, sub in self.entries.items()}


def deps(profile: dict) -> list:
    return as_list(profile.get("inherits")) + as_list(profile.get("include"))


def import_app(app: str, appdata: Path, out: Path) -> None:
    folder, wanted = SOURCES[app]
    root = appdata / folder
    system = root / "system"
    vendors = {p.stem: Vendor(system, p.stem) for p in system.glob("*.json")}
    picked = {v: set() for v in vendors}  # fabricante -> {(lista, nome)}

    def find(kind_list: str, name: str, prefer: str):
        for v in [prefer] + [v for v in vendors if v != prefer]:
            if (kind_list, name) in vendors[v].entries:
                return v
        raise SystemExit(f"{app}: '{name}' ({kind_list}) não encontrado")

    def take(vendor: str, key):
        if key in picked[vendor]:
            return
        picked[vendor].add(key)
        for dep in deps(vendors[vendor].data[key]):
            take(find(key[0], dep, vendor), (key[0], dep))

    for vendor, pattern in wanted.items():
        v = vendors[vendor]
        rx = re.compile(pattern)
        machines = {n for (k, n) in v.entries if k in ("machine_list", "machine_model_list") and rx.search(n)}
        for (k, n) in v.entries:
            if k in ("machine_list", "machine_model_list") and n in machines:
                take(vendor, (k, n))
        printers = {n for (k, n) in picked[vendor] if k == "machine_list"}
        for (k, n), prof in v.data.items():
            if k in ("process_list", "filament_list") and printers & set(as_list(prof.get("compatible_printers"))):
                take(vendor, (k, n))

    dest = out / app
    if dest.exists():
        shutil.rmtree(dest)
    for vendor, keys in picked.items():
        if not keys:
            continue
        v = vendors[vendor]
        # O Bambu Studio 2.x tira o G-code das máquinas para perfis "... template <opção>",
        # trazidos por "include", que o Orca não conhece: as opções vão para o próprio perfil
        # (nenhuma está nos dois) e os modelos saem.
        merged = {}
        for key in keys:
            profile = v.data[key]
            if "include" not in profile:
                continue
            profile = {k: val for k, val in profile.items() if k != "include"}
            for name in as_list(v.data[key]["include"]):
                for opt, val in v.data[(key[0], name)].items():
                    if opt not in ("name", "instantiation", "type", "from", "setting_id"):
                        if opt in profile:
                            raise SystemExit(f"{app}: '{opt}' em '{key[1]}' e no include '{name}'")
                        profile[opt] = val
            merged[key] = profile
        templates = {(k, n) for key in merged for k, n in [(key[0], i) for i in as_list(v.data[key]["include"])]}
        keys = keys - templates
        index = dict(v.index)
        for k in KINDS:
            if k in index:
                index[k] = [e for e in v.index[k] if (k, e["name"]) in keys]
        for key in keys:
            target = dest / vendor / v.entries[key]
            target.parent.mkdir(parents=True, exist_ok=True)
            if key in merged:
                with target.open("w", encoding="utf-8", newline="\n") as f:
                    json.dump(merged[key], f, ensure_ascii=False, indent=4)
                    f.write("\n")
            else:
                shutil.copyfile(v.dir / v.entries[key], target)
        with (dest / f"{vendor}.json").open("w", encoding="utf-8", newline="\n") as f:
            json.dump(index, f, ensure_ascii=False, indent=4)
            f.write("\n")
        counts = ", ".join(f"{KINDS[k]} {sum(1 for (kk, _) in keys if kk == k)}" for k in KINDS)
        print(f"{app}/{vendor} {v.index.get('version')}: {counts}")

    # Presets do usuário: só os .json de user/<conta>/{machine,process,filament}.
    n = 0
    for user in [root / "user"] + [appdata / d for d in OLD_USER.get(app, [])]:
        for path in sorted(user.glob("*/*/**/*.json")):
            rel = path.relative_to(user)
            if rel.parts[1] not in ("machine", "process", "filament"):
                continue
            target = dest / "user" / Path(*rel.parts[1:])
            if target.exists():
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            n += 1
    print(f"{app}/user: {n} presets")


def main() -> None:
    appdata = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(os.environ["APPDATA"])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parent.parent / "profiles" / "imported"
    for app in SOURCES:
        import_app(app, appdata, out)


if __name__ == "__main__":
    main()
