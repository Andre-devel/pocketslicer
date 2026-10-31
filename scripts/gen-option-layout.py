#!/usr/bin/env python3
"""Extrai de src/slic3r/GUI/Tab.cpp do OrcaSlicer onde cada opção aparece nas abas
Processo, Filamento e Impressora (página, grupo e, para linhas com mais de uma opção,
o rótulo da linha) e grava native/src/option_layout.inc, usado por Engine::options_json.

Uso: scripts/gen-option-layout.py [Tab.cpp] > native/src/option_layout.inc
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
tab = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "third_party/OrcaSlicer/src/slic3r/GUI/Tab.cpp"

# Funções que montam as páginas de cada aba (as demais, como TabPrintModel, são ignoradas).
TABS = {"TabPrint": "PROCESS", "TabFilament": "FILAMENT", "TabPrinter": "PRINTER"}
FUNC = re.compile(r"^\S.*\b(Tab\w+)::(\w+)\(")
PAGE = re.compile(r"add_options_page\((?:L\(\"([^\"]*)\"\)|(\w+))")
GROUP = re.compile(r"new_optgroup\((?:L\(\"([^\"]*)\"\)|\"([^\"]*)\")")
LINE = re.compile(r"(?:Line\s+\w+\s*=?\s*|\bline\s*=\s*|Line\s*)\{\s*L\(\"([^\"]*)\"\)")
SINGLE = re.compile(r"(?:append_single_option_line|append_option_line\(\s*optgroup\s*,|create_line_with_widget\(\s*optgroup\.get\(\)\s*,)\s*\(?\s*\"(\w+)\"")
IN_LINE = re.compile(r"get_option\(\"(\w+)\"")
# Laços como "for (const std::string opt_key : { "a", "b", ... })" da página Setting Overrides.
LOOP = re.compile(r"\bfor\s*\(.*:\s*\{")
QUOTED = re.compile(r"\"(\w+)\"")
# Página Motion ability: listas de eixos e append_option_line(optgroup, "prefixo_" + axis).
VECTOR = re.compile(r"std::vector<std::string>\s*(\w+)\s*\{")
RANGE_FOR = re.compile(r"\bfor\s*\(.*?(\w+)\s*:\s*(\w+)\s*\)")
APPEND_VAR = re.compile(r"append_option_line\(\s*optgroup\s*,\s*(?:\"(\w+)\"\s*\+\s*)?(\w+)\s*,")
ASSIGN_OPTION = re.compile(r"Option\s+option\s*=\s*optgroup->get_option\(\"(\w+)\"")
# Chamada sem argumentos de outra função da aba, como add_filament_overrides_page(): a página
# dela entra nesse ponto da ordem de build(), não onde a função aparece no arquivo.
CALL = re.compile(r"^\s*(\w+)\(\);")

layout = {kind: [] for kind in TABS.values()}
seen = {kind: set() for kind in TABS.values()}
kind = page = group = line = func = None
# Ordem das páginas em cada aba e a primeira página de cada função auxiliar.
order = {kind: [] for kind in TABS.values()}
helper_page = {}
skip = 0
in_loop = False
vectors = {}
vector = None
loop_var = loop_values = None
for raw in tab.read_text(encoding="utf-8").splitlines():
    text = raw.split("//", 1)[0]
    stripped = raw.strip()
    if stripped.startswith("#if 0"):
        skip += 1
        continue
    if skip:
        if stripped.startswith("#if"):
            skip += 1
        elif stripped.startswith("#endif"):
            skip -= 1
        continue
    if m := FUNC.match(raw):
        kind, func = TABS.get(m.group(1)), m.group(2)
        line = None
        continue
    if kind is None:
        continue
    if m := PAGE.search(text):
        # Páginas dos extrusores: o nome vem de uma variável ("Extruder N").
        page, group, line = m.group(1) or "Extruder", "", None
        helper_page.setdefault(func, page)
        if page not in order[kind]:
            order[kind].append(page)
        continue
    if func == "build" and (m := CALL.match(text)) and m.group(1) in helper_page:
        order[kind].remove(helper_page[m.group(1)])
        order[kind].append(helper_page[m.group(1)])
        continue
    if m := GROUP.search(text):
        group, line = m.group(1) or m.group(2) or "", None
        continue
    if m := LINE.search(text):
        line = m.group(1)
        continue

    def add(key, line_label):
        if page is None or key in seen[kind]:
            return
        seen[kind].add(key)
        layout[kind].append((key, page, group, line_label or ""))

    if vector is not None or (m := VECTOR.search(text)):
        if vector is None:
            vector, text = m.group(1), text.split("{", 1)[1]
            vectors[vector] = []
        vectors[vector] += QUOTED.findall(text)
        if "}" in text:
            vector = None
        continue
    if (m := RANGE_FOR.search(text)) and m.group(2) in vectors:
        loop_var, loop_values = m.group(1), vectors[m.group(2)]
        continue
    if (m := APPEND_VAR.search(text)) and m.group(2) == loop_var:
        for value in loop_values:
            add((m.group(1) or "") + value, None)
        continue
    if in_loop or LOOP.search(text):
        in_loop = "})" not in text
        for key in QUOTED.findall(text.split("{", 1)[-1] if LOOP.search(text) else text):
            add(key, None)
        continue
    if m := ASSIGN_OPTION.search(text):
        add(m.group(1), None)
        continue
    if m := SINGLE.search(text):
        line = None
        add(m.group(1), None)
        continue
    for key in IN_LINE.findall(text):
        add(key, line)

for kind, entries in layout.items():
    entries.sort(key=lambda e: order[kind].index(e[1]))
out = ["// Gerado por scripts/gen-option-layout.py a partir do Tab.cpp do OrcaSlicer. Não editar."]
for kind, entries in layout.items():
    out.append(f"static const OptionPlace {kind.lower()}_layout[] = {{")
    out += [f'    {{"{k}", "{p}", "{g}", "{l}"}},' for k, p, g, l in entries]
    out.append("};")
print("\n".join(out))
