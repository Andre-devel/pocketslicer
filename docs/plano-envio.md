# Plano: enviar o G-code para a impressora (Pós-MVP item 1)

Escopo combinado com o usuário (2026-10-24): **só enviar** o arquivo para a impressora; em
2026-10-28 o usuário pediu também "enviar e imprimir" (testar numa impressora real só com OK
explícito, mesa livre e peça pequena). Computador, celular
e impressoras estão na mesma rede; os IPs são pedidos ao usuário quando cada teste precisar.

Impressoras do usuário: Bambu Lab A1, Creality K2 e K3, Snapmaker U1, Qidi Q2.

## Protocolos (conferidos no código do OrcaSlicer 2.4.2)

| Impressora | Perfil no Orca | Envio | Referência no Orca |
|---|---|---|---|
| Qidi Q2 | `Qidi/machine/Qidi Q2 *` (`printer_agent: qidi`) | Moonraker | `src/slic3r/Utils/Moonraker.cpp`; `QidiPrinterAgent` só acrescenta leitura de filamentos (box) |
| Snapmaker U1 | `Snapmaker/machine/Snapmaker U1 *` (`printer_agent: snapmaker`) | Moonraker | idem; `SnapmakerPrinterAgent` só lê filamentos |
| Creality K2 (Plus/Pro) | `Creality/machine/Creality K2 *` (`host_type: crealityprint`) | HTTP próprio da Creality | `src/slic3r/Utils/CrealityPrint.cpp` |
| Creality K3 | **sem perfil no Orca 2.4.2 nem no main** | Moonraker (porta 7125; testado: envio OK) | perfil no Creality Print (AGPL-3.0): `resources/profiles/Creality/{machine,process,filament}/*K3*` |
| Bambu A1 | `BBL/machine/Bambu Lab A1 *` | FTPS (modo LAN) com `.gcode.3mf` | GUI do Orca: `Plater::export_gcode_3mf()`; envio pelo plugin de rede da Bambu (fechado) |

### Moonraker (Q2, U1)
- `POST http://<ip>[:porta]/server/files/upload`, multipart: `file` (o G-code), `root=gcodes`,
  opcional `path` (subpasta) e `print=false`. Resposta: `{"result":{"item":{"path":..,"root":..}}}`.
- Sem autenticação por padrão; se houver, cabeçalho `X-Api-Key`.
- Porta: 80 (nginx do Fluidd/Mainsail) costuma repassar; senão 7125. Testar `GET /server/info`.
- Iniciar (futuro): `POST /printer/print/start` com `{"filename": "<path>"}`.

### Creality K2 (e K3)
- Envio: `POST http://<ip>/upload/<nome url-encoded>`, multipart com `file`
  (e `path` se houver subpasta) — `CrealityPrint::upload()`.
- Iniciar (futuro): WebSocket na porta 9999 (`CrealityPrint::start_print()`), com a lista de
  cores do CFS.
- Primeiro teste: `GET /server/info` na porta 7125/4408 para ver se há Moonraker também; se o
  upload da Creality funcionar, ele basta.

### Creality K3 (2026-10-28)
- Na rede do usuário (192.168.1.3): OpenWrt, Klipper com **4 extrusoras** (`extruder`..`extruder3`),
  Moonraker na 7125 (o envio do app já funcionou), mais as portas 80, 4408, 8000 e 9999 (Creality).
- Perfil: só no Creality Print — `Creality K3.json` (herda `fdm_creality_common` do Creality
  Print, `host_type: octoprint`, 4 bicos 0.4, mesa 260x260x260), 6 processos e 43 filamentos
  "@Creality K3". Tem chaves próprias do Creality Print que o libslic3r do Orca ignora.
- Perfis copiados do Creality Print 7.3 instalado no PC (com os do Bambu Studio, QIDI Studio e
  Snapmaker Orca e os presets do usuário) em `profiles/imported/` (2026-10-29); ainda fora do APK.
- Próximo passo: trazer esses perfis para o app (começando por uma cor, T0), comparar o G-code com
  o do Creality Print desktop no PC que tem a K3 e só então imprimir uma peça pequena.

### Bambu A1 (modo LAN)
- Formato: `.gcode.3mf` (3MF com `Metadata/plate_1.gcode`, miniatura, `slice_info.config`).
  Gerar no motor com `store_bbs_3mf()` e `SaveStrategy::Silence | SplitModel | WithGcode |
  SkipModel`, como `Plater::export_gcode_3mf()`; a `PlateData` precisa de `gcode_file`,
  `is_sliced_valid`, tempo/peso e miniatura. Também serve para imprimir pelo cartão SD.
  Hoje o G-code das Bambu sai sem miniatura (`gcode_thumbnail_sizes()` vazio) — a miniatura vai
  no 3MF.
- Envio: FTPS implícito (TLS) na porta 990, usuário `bblp`, senha = código de acesso da tela da
  impressora; certificado autoassinado. Arquivo na raiz do cartão.
- Conferir no aparelho se o firmware atual exige "modo LAN"/"modo desenvolvedor" para o FTPS.
- Iniciar (futuro): MQTT TLS 8883, tópico `device/<serial>/request`, comando `project_file`.
- **O código de acesso é digitado pelo usuário no app** (nunca pelo Claude).

## Arquitetura no app

Rede toda em Kotlin (sem mexer no libslic3r), num pacote novo `io.github.andredevel.slicer.send`:

```kotlin
data class PrinterHost(val id: String, val name: String, val kind: Kind, val address: String,
                       val apiKey: String? = null, val accessCode: String? = null)
enum class Kind { MOONRAKER, CREALITY, BAMBU_LAN }

interface Uploader { suspend fun upload(host: PrinterHost, file: File, name: String, progress: (Float) -> Unit) }
```

- `MoonrakerUploader`, `CrealityUploader`: `HttpURLConnection` com multipart em streaming
  (`setChunkedStreamingMode`), sem biblioteca nova.
- `BambuUploader`: FTPS implícito. Opções: Apache Commons Net (`FTPSClient(true)`,
  Apache-2.0, ~300 KB) ou um cliente FTP mínimo sobre `SSLSocket` (PASV + STOR). Preferir
  Commons Net; aceitar o certificado autoassinado só para essa conexão (TrustManager próprio no
  socket, não global).
- Impressoras salvas em `filesDir/printers.json`. O código de acesso/API key fica cifrado com
  Android Keystore (EncryptedFile ou AES-GCM com chave do Keystore).
- Manifest: `INTERNET` e `network_security_config` permitindo HTTP em texto puro (as impressoras
  da LAN não têm HTTPS). Atualizar `docs/requisitos.md` (hoje diz "nenhuma dependência de rede").
- Tipo sugerido pelo perfil da impressora selecionada: `host_type`/`printer_agent` do preset
  (`crealityprint` → CREALITY; `qidi`/`snapmaker`/`octoprint` → MOONRAKER; vendor BBL →
  BAMBU_LAN). Endereço salvo por preset de impressora (`print_host` do Orca pode ser
  aproveitado).

### UI
- Depois de fatiar: botão "Enviar para impressora" ao lado de "Salvar G-code".
- Diálogo: escolher impressora salva ou "Adicionar impressora" (nome, tipo, IP; código de acesso
  para Bambu), botão "Testar conexão", barra de progresso do envio, erro claro em pt/en.
- Sem descoberta automática na primeira versão (mDNS/SSDP da Bambu na porta 2021 fica para
  depois).

## Ordem de implementação (cada passo com commit)

1. **Base + Moonraker** (feito; testado no emulador com `scripts/fake-moonraker.py`, falta a Q2 e a U1 reais): permissão/config de rede, `PrinterHost`, armazenamento, `MoonrakerUploader`,
   diálogo de envio. Teste: Qidi Q2 e Snapmaker U1 (pedir os IPs), arquivo aparece na lista
   de arquivos da impressora. Teste local antes: Moonraker falso com `python3 -m http.server` não
   serve; usar um servidor Flask/aiohttp mínimo em `scripts/` que aceite o multipart.
2. **Creality**: `CrealityUploader`; testar K2 e K3 (pedir IPs). Se a K3 não tiver perfil,
   avisar o usuário.
3. **`.gcode.3mf` no motor** (feito): `Engine::export_gcode_3mf()`; "Salvar G-code" e "Enviar"
   usam esse formato quando a impressora pede (Bambu, ou `use_3mf` no perfil, como o Qidi Q2) e o
   Moonraker recebe `plateindex=1`, como no Orca. Testado no Qidi Q2 real: a Moonraker dele só
   extrai a miniatura (512x512) de um .gcode.3mf. Falta validar na A1.
4. **Bambu LAN (FTPS)** (feito): `BambuUploader`, cliente FTPS mínimo (sem biblioteca), canal de
   dados com `PROT P` retomando a sessão TLS do controle; certificado conferido contra as CAs da
   Bambu (`resources/cert/printer.cer`, empacotado como asset), sem conferir o nome do host (o
   certificado só tem o número de série). Testado na A1 real **sem** o modo somente LAN: o arquivo
   chega ao cartão. "Enviar e imprimir" manda `project_file` pelo MQTT (TLS 8883; série = CN do
   certificado; filamento = `ams.tray_now`). Na A1 na nuvem (firmware 2025+) o comando é recusado
   com `err_code` 84033543 (comando sem assinatura): só funciona com modo somente LAN + modo
   desenvolvedor. Não assinar com chaves extraídas do software da Bambu. O app mostra a recusa e o
   arquivo fica no cartão para iniciar pela tela.
5. **Enviar e imprimir (Moonraker)** (feito): botão no diálogo com confirmação ("mesa livre e
   limpa, filamento certo"); antes do envio confere `print_stats.state` e recusa se a impressora
   estiver imprimindo ou pausada; depois `POST /printer/print/start` com o caminho devolvido pelo
   upload, como o Orca. O diálogo só pré-seleciona a impressora cadastrada com o perfil do
   fatiamento e avisa quando a escolhida foi cadastrada com outro. Testado com
   `scripts/fake-moonraker.py` (e `--state printing`); na impressora real, só com OK do usuário.
6. Futuro: iniciar pela Creality (WebSocket) e Bambu (MQTT), descoberta automática, status da
   impressão.

## Riscos
- K3 sem perfil no Orca 2.4.2: o fatiamento para ela depende de perfil importado.
- Firmware Bambu: mudanças de autorização (2025) podem bloquear FTPS fora do modo LAN.
- Arquivos grandes (centenas de MB): upload em streaming, nunca carregar o arquivo inteiro na memória.
- Rede: o roteador da casa isolava clientes no Wi-Fi; se o celular não alcançar a impressora,
  o erro deve dizer isso.
