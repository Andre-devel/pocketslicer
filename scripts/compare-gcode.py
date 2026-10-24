#!/usr/bin/env python3
"""Compara dois G-codes do OrcaSlicer camada a camada.

Igualdade byte a byte não é esperada entre o desktop (GCC/libstdc++) e o
Android (clang/libc++): desempates de ordenação e arredondamentos mudam a ordem
e o sentido das linhas. Então compara o que importa para a peça: número de
camadas e filamento extrudado por camada e no total.

Paredes e preenchimento sólido saem iguais; o preenchimento esparso varia até
~1,5% por camada (a ligação das linhas depende de arredondamento), mas a soma
fica estável. Daí uma tolerância folgada por camada e uma apertada no total.

Uso: compare-gcode.py <referencia.gcode> <teste.gcode>
Sai com 1 se o número de camadas diferir ou alguma tolerância for excedida.
"""
import re
import sys

LAYER_TOL = 0.02
TOTAL_TOL = 0.001
MOVE = re.compile(r'([XYE])(-?[\d.]+)')


def extrusion_per_layer(path):
    layers = []
    with open(path) as f:
        for line in f:
            # Bambu: "; CHANGE_LAYER"; demais firmwares: ";LAYER_CHANGE".
            if line.startswith(('; CHANGE_LAYER', ';LAYER_CHANGE')):
                layers.append(0.0)
                continue
            if not layers or not line.startswith('G1'):
                continue
            words = dict(MOVE.findall(line.split(';')[0]))
            # Só extrusão com movimento em XY (ignora retração/despriming).
            if 'E' in words and ('X' in words or 'Y' in words):
                e = float(words['E'])
                if e > 0:
                    layers[-1] += e
    return layers


def main():
    ref_path, test_path = sys.argv[1], sys.argv[2]
    ref, test = extrusion_per_layer(ref_path), extrusion_per_layer(test_path)

    ok = True
    if len(ref) != len(test):
        print(f'camadas: referência {len(ref)}, teste {len(test)}')
        ok = False
    worst = (0.0, -1)
    for i, (a, b) in enumerate(zip(ref, test)):
        rel = abs(a - b) / a if a else abs(b)
        worst = max(worst, (rel, i))
        if rel > LAYER_TOL:
            print(f'camada {i}: extrusão {a:.3f} vs {b:.3f} mm ({rel:.2%})')
            ok = False
    total_ref, total_test = sum(ref), sum(test)
    total_rel = abs(total_ref - total_test) / total_ref if total_ref else 1.0
    if total_rel > TOTAL_TOL:
        ok = False
    print(f'camadas: {len(test)}; extrusão total {total_ref:.2f} vs {total_test:.2f} mm '
          f'({total_rel:.3%}); pior camada {worst[1]} ({worst[0]:.3%})')
    print('OK: equivalente à referência' if ok else
          f'FALHOU (tolerância {LAYER_TOL:.0%} por camada, {TOTAL_TOL:.1%} no total)')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
