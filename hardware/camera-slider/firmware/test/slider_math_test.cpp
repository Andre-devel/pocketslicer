// Teste da conta do carrinho no PC (os mesmos valores do SliderPlanTest do app):
//   g++ -std=c++17 -I src test/slider_math_test.cpp -o /tmp/slider_math_test && /tmp/slider_math_test
#include <cmath>
#include <cstdio>

#include "slider_math.h"

int failures = 0;

void expect(int layer, int count, int passes, int dwell, float want) {
  float got = sliderFraction(layer, count, passes, dwell);
  if (std::fabs(got - want) > 1e-4f) {
    std::printf("FALHOU camada %d/%d passes %d parada %d: %f (esperado %f)\n", layer, count, passes, dwell, got, want);
    failures++;
  }
}

void expectResume(int layer, int count, float planFrom, int realFrom, float want) {
  float got = resumedLayer(layer, count, planFrom, realFrom);
  if (std::fabs(got - want) > 1e-3f) {
    std::printf("FALHOU retomada camada %d/%d de %.1f em %d: %f (esperado %f)\n", layer, count, planFrom, realFrom, got, want);
    failures++;
  }
}

int main() {
  expect(25, 101, 0, 0, 0.25f);
  expect(0, 101, 1, 0, 0);
  expect(25, 101, 1, 0, 0.5f);
  expect(50, 101, 1, 0, 1);
  expect(75, 101, 1, 0, 0.5f);
  expect(100, 101, 1, 0, 0);
  expect(45, 101, 1, 10, 1);
  expect(50, 101, 1, 10, 1);
  expect(55, 101, 1, 10, 1);
  expect(100, 101, 1, 10, 0);
  expect(25, 101, 2, 0, 1);
  // Sem retomada: a própria camada.
  expectResume(0, 101, 0, 0, 0);
  expectResume(37, 101, 0, 0, 37);
  expectResume(100, 101, 0, 0, 100);
  // Câmera parada na 40 com a impressão na 70: antes igual, na 70 fica na 40, termina na 100.
  expectResume(20, 101, 40, 70, 20);
  expectResume(70, 101, 40, 70, 40);
  expectResume(85, 101, 40, 70, 70);
  expectResume(100, 101, 40, 70, 100);
  // A queda da Q2 (815 camadas, câmera na 442, impressora na 585).
  expectResume(585, 815, 442, 585, 442);
  expectResume(814, 815, 442, 585, 814);
  // Retomada na última camada: fica no fim.
  expectResume(100, 101, 40, 100, 40);
  std::printf(failures ? "%d falha(s)\n" : "ok\n", failures);
  return failures ? 1 : 0;
}
