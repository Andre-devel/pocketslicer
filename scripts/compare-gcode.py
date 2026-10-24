#!/usr/bin/env python3
"""Compara dois G-codes do OrcaSlicer camada a camada.

Igualdade byte a byte não é esperada entre o desktop (GCC/libstdc++) e o
Android (clang/libc++): desempates de ordenação e arredondamentos mudam a ordem
e o sentido das linhas. Então compara o que importa para a peça: número de
camadas e filamento extrudado por camada e no total.

Uso: compare-gcode.py <referencia.gcode> <teste.gcode> [tolerância relativa]
Sai com 1 se alguma camada passar da tolerância (padrão 0,5%).
"""
import re
import sys

MOVE = re.compile(r'([XYE])(-?[\d.]+)')


def extrusion_per_layer(path):
    layers = []
    with open(path) as f:
        for line in f:
            if line.startswith('; CHANGE_LAYER'):
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
    tol = float(sys.argv[3]) if len(sys.argv) > 3 else 0.005
    ref, test = extrusion_per_layer(ref_path), extrusion_per_layer(test_path)

    ok = True
    if len(ref) != len(test):
        print(f'camadas: referência {len(ref)}, teste {len(test)}')
        ok = False
    worst = (0.0, -1)
    for i, (a, b) in enumerate(zip(ref, test)):
        rel = abs(a - b) / a if a else abs(b)
        worst = max(worst, (rel, i))
        if rel > tol:
            print(f'camada {i}: extrusão {a:.3f} vs {b:.3f} mm ({rel:.2%})')
            ok = False
    total_ref, total_test = sum(ref), sum(test)
    print(f'camadas: {len(test)}; extrusão total {total_ref:.2f} vs {total_test:.2f} mm '
          f'({abs(total_ref - total_test) / total_ref:.3%}); '
          f'pior camada {worst[1]} ({worst[0]:.3%})')
    print('OK: equivalente à referência' if ok else f'FALHOU (tolerância {tol:.1%})')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
