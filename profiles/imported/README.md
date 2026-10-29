# Perfis dos fatiadores dos fabricantes

Copiados em 2026-10-29 do PC do usuário por `scripts/import-vendor-profiles.py`, a partir da
pasta `system/` de cada app em `%APPDATA%` (os perfis já atualizados pelo próprio app, mais novos
que os do instalador). Só entram as impressoras do usuário, os processos e filamentos compatíveis
com elas e tudo de que dependem. Para atualizar, rode o script de novo neste PC.

**Ainda não entram no APK**: o `zipOrcaResources` (`profiles/build.gradle.kts`) só empacota os
perfis do OrcaSlicer. Antes de empacotar, cada conjunto precisa ser testado com o libslic3r do Orca
(ver "Diferenças" abaixo).

| Pasta | App | Fabricante (versão dos perfis) | Impressoras | Máquinas / processos / filamentos |
|---|---|---|---|---|
| `BambuStudio` | Bambu Studio | `BBL` 02.08.00.06 | A1 e A1 mini | 20 / 81 / 358 |
| `CrealityPrint` | Creality Print 7.3 | `Creality` 26.10.10.09 | K2 (0.2–0.8) e K3 | 7 / 20 / 106 |
| `QIDIStudio` | QIDI Studio | `Q Series` 02.07.02.73 | Q2 (0.2–0.8) | 5 / 30 / 248 |
| `SnapmakerOrca` | Snapmaker Orca | `Snapmaker` 02.03.03.03 | U1 (0.2–0.8) | 7 / 35 / 195 |

As contagens incluem os perfis-base (`instantiation: false`). Todos esses apps são AGPL-3.0, como
este projeto.

## Presets do usuário (`<app>/user/{machine,process,filament}`)

Os presets que o usuário criou em cada app, sem a pasta da conta, os `.info` e o `sync_data`.
No formato do Orca: só a diferença para o preset pai (`inherits`), que está em `system/`. A
exceção são os de `BambuStudio/user/filament/base/`, presets completos sem pai, que são o pai de
`VOOLT3D PETG TRANSLUCIDO` e `Voolt3D PLA PLA outletCalibrated`.

- Bambu Studio: 13 filamentos (VOOLT3D, master print, etc.), 4 máquinas (A1 com retração,
  timelapse e time-brick; uma cópia da A1 mini) e 7 processos.
- Creality Print: 9 filamentos, 1 máquina (K2) e 4 processos. Inclui os da versão 7.0 (outra
  conta) que não existem na 7.3: `ABS-padrao`, `abs`, `Generic PETG - padrao`, `PLA - padrao`,
  a máquina K2 e o processo 0.20mm da K2.
- Snapmaker Orca: a máquina U1 0.4 com timelapse.
- QIDI Studio: nenhum.

## Diferenças em relação aos perfis do Orca 2.4.2

- **Bambu Studio** usa `include` (o G-code inicial, final, de troca de filamento, etc. da A1 vem de
  perfis `... template machine_start_gcode`), que o Orca não conhece. É preciso juntar os
  `include` no perfil antes de usar (como o `scripts/flatten-profile.py` já junta o `inherits`).
  Também traz chaves dos bicos múltiplos (H2D), em listas por variante de extrusora.
- **Creality Print** é o único com perfil da K3 (o Orca não tem). Tem chaves próprias, que o
  libslic3r ignora. Os nomes da K2 batem com os do Orca (`Creality K2 0.4 nozzle`).
- **QIDI Studio** chama o fabricante de `Q Series` e as máquinas de `Q2 0.4 nozzle` (no Orca:
  `Qidi` e `Qidi Q2 0.4 nozzle`). Os nomes não colidem com os do Orca.
- **Snapmaker Orca**: o fabricante e os nomes (`Snapmaker U1 (0.4 nozzle)`) são iguais aos do
  Orca, então não pode entrar ao lado do `Snapmaker` do Orca: um substitui o outro. O OrcaSlicer
  instalado neste PC já tem perfis Snapmaker mais novos (02.04.00.07, com a U1 0.4+0.6) que os do
  Snapmaker Orca (02.03.03.03).
- `BBL` e `Creality` também colidem com os do Orca (conferido no OrcaSlicer instalado:
  `Bambu Lab A1 0.4 nozzle`, `Creality K2 0.4 nozzle`). Não dá para carregar os dois de uma vez.
