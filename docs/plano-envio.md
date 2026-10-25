# Plano: enviar o G-code para a impressora (Pós-MVP item 1)

Escopo combinado com o usuário (2026-10-24): **só enviar** o arquivo para a impressora; iniciar a
impressão fica para depois (não testar "enviar e imprimir" sem OK explícito). Computador, celular
e impressoras estão na mesma rede; os IPs são pedidos ao usuário quando cada teste precisar.

Impressoras do usuário: Bambu Lab A1, Creality K2 e K3, Snapmaker U1, Qidi Q2.

## Protocolos (conferidos no código do OrcaSlicer 2.4.2)

| Impressora | Perfil no Orca | Envio | Referência no Orca |
|---|---|---|---|
| Qidi Q2 | `Qidi/machine/Qidi Q2 *` (`printer_agent: qidi`) | Moonraker | `src/slic3r/Utils/Moonraker.cpp`; `QidiPrinterAgent` só acrescenta leitura de filamentos (box) |
| Snapmaker U1 | `Snapmaker/machine/Snapmaker U1 *` (`printer_agent: snapmaker`) | Moonraker | idem; `SnapmakerPrinterAgent` só lê filamentos |
| Creality K2 (Plus/Pro) | `Creality/machine/Creality K2 *` (`host_type: crealityprint`) | HTTP próprio da Creality | `src/slic3r/Utils/CrealityPrint.cpp` |
| Creality K3 | **sem perfil no Orca 2.4.2** | provavelmente igual à K2 | confirmar no aparelho; o usuário precisa de um perfil (importar .json) |
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
3. **`.gcode.3mf` no motor**: `Engine::export_gcode_3mf(path, thumbnail)` + "Salvar .gcode.3mf"
   para impressoras Bambu; validar abrindo no Bambu Studio/Orca desktop e pelo cartão na A1.
4. **Bambu LAN (FTPS)**: `BambuUploader`; usuário digita o código de acesso; testar na A1.
5. Futuro (precisa de OK do usuário para testar): iniciar impressão (Moonraker
   `print/start`, WebSocket Creality, MQTT Bambu), descoberta automática, status da impressão.

## Riscos
- K3 sem perfil no Orca 2.4.2: o fatiamento para ela depende de perfil importado.
- Firmware Bambu: mudanças de autorização (2025) podem bloquear FTPS fora do modo LAN.
- Arquivos grandes (centenas de MB): upload em streaming, nunca carregar o arquivo inteiro na memória.
- Rede: o roteador da casa isolava clientes no Wi-Fi; se o celular não alcançar a impressora,
  o erro deve dizer isso.
