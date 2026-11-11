// Posição do carrinho por camada, igual à do app (sliderFraction em CameraSlider.kt). Sem
// Arduino, para testar no PC: test/slider_math_test.cpp.
#pragma once

#include <algorithm>
#include <cmath>

// Onde a camada [layer] (de 0 a [layerCount] - 1) fica entre o início (0) e o fim (1) do curso.
// Sem [passes]: reta do início ao fim. Com [passes] idas e voltas: 2 x passes trechos de mesmo
// número de camadas, alternando o sentido, cada um com aceleração e desaceleração em cosseno, e
// [dwellLayers] camadas paradas em cada virada (no máximo metade da impressão no total).
inline float sliderFraction(int layer, int layerCount, int passes, int dwellLayers) {
  if (layerCount <= 1) return 0;
  float t = std::min(std::max(layer, 0), layerCount - 1);
  float span = layerCount - 1;
  if (passes <= 0) return t / span;
  int legs = 2 * passes;
  int turns = legs - 1;
  float dwell = std::min(std::max(float(dwellLayers), 0.0f), span * 0.5f / turns);
  float legLength = (span - dwell * turns) / legs;
  float cycle = legLength + dwell;
  int leg = int(t / cycle);
  float u;
  if (leg >= legs) {
    leg = legs - 1;
    u = 1;
  } else {
    float r = t - leg * cycle;
    u = r >= legLength ? 1 : r / legLength;
  }
  float eased = float((1 - std::cos(M_PI * u)) / 2);
  return leg % 2 == 0 ? eased : 1 - eased;
}
