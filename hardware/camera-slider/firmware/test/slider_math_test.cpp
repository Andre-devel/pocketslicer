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
  std::printf(failures ? "%d falha(s)\n" : "ok\n", failures);
  return failures ? 1 : 0;
}
