# Bot Sobrevivente

App Android (Kotlin) que joga sozinho o modo "Sobrevivente" de um jogo de cartas/combate em **paisagem**. O bot **lê a tela** e age por toques e arrastos, sem marcar botões na mão e sem calibração:

- **Texto** (OCR offline, Google ML Kit): reconhece botões e telas (SOBREVIVENTE, LUTAR/REPETIR, AVANÇAR, CONTINUAR, FECHAR, PARAR E RECEBER, EQUIPE, CRIE SUA EQUIPE, GIRE A RODA etc.) e os textos dos mini-eventos.
- **Forma/cor** (OpenCV 4.9, sem template, exceto a arte das cartas): detecta combate (barras azuis + slots roxos), brilho/rosa/X vermelho dos slots de poder, círculo da bolinha, círculo da barra de encher, seta de arrastar, barra do ping pong, caveira/X do time morto.
- **Arte das cartas** (OpenCV, *template matching*): ajuda o OCR a separar versões da mesma carta na montagem de times.
- **Mãos**: um Serviço de Acessibilidade executa os gestos (toque, rajada de toques, arrasto, lote de arrastos, rolagem, 2 dedos).

> Esta documentação foi escrita a partir do código-fonte em 27/11/2026. Valores entre parênteses (coordenadas, tempos, limites) são os que estão no código hoje. Onde esta documentação divergir do código, vale o código.
> O plano de melhorias e o estado de validação de cada fase estão em `PLANO_PERFORMANCE.md`. O contexto fixo para IA está em `CONTEXTO.md`.

---

## 1. Ficha técnica

| Item | Valor |
|---|---|
| `applicationId` / namespace | `com.matheus.bot` |
| `versionName` / `versionCode` | `0.1` / `1` |
| `minSdk` / `targetSdk` / `compileSdk` | 30 / 35 / 35 |
| ABI | somente `arm64-v8a` |
| Linguagem | Kotlin 2.0.21, JVM/Java 17 |
| Android Gradle Plugin | 8.7.3 |
| Gradle (wrapper) | 8.9 (`-Xmx2g`, AndroidX ligado) |
| Dependências | `androidx.core:core-ktx 1.15.0`, `androidx.activity:activity-ktx 1.9.3`, `kotlinx-coroutines-android 1.9.0`, `org.opencv:opencv 4.9.0`, `com.google.mlkit:text-recognition 16.0.1` |
| Módulos | `:app` (único) |
| Testes automatizados | nenhum (a fase de testes offline foi removida do plano) |
| Nome exibido | "Bot Sobrevivente" |

### Permissões (`AndroidManifest.xml`)
- `SYSTEM_ALERT_WINDOW`: painel flutuante.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`: serviço de captura de tela.
- `POST_NOTIFICATIONS`: notificação do serviço em primeiro plano (pedida ao abrir o app).
- `WRITE_SECURE_SETTINGS`: **não é concedida na instalação**; só via adb (`conceder_permissao_uma_vez.bat`). Permite ao app ligar a própria acessibilidade ao abrir.
- `allowBackup="false"`.

### Componentes declarados
- `MainActivity` (launcher, tema `Theme.DeviceDefault.NoActionBar`).
- `BotService` (não exportado, `foregroundServiceType="mediaProjection"`).
- `BotAccessibilityService` (protegido por `BIND_ACCESSIBILITY_SERVICE`; `canPerformGestures=true`, evento `typeWindowStateChanged`, `notificationTimeout=100`, descrição "Executa toques e arrastos para o bot jogar por você.").

### Recursos
- `res/values/strings.xml` (`app_name`, `acc_desc`) e `res/xml/accessibility_service_config.xml`. Não há layout XML: as telas são montadas por código.
- `assets/cartas/equipe1.jpg` e `equipe2.jpg`: fotos de referência (tela cheia) para o reconhecimento por arte (`CardArt`).

---

## 2. Estrutura do projeto

```
BotSobrevivente/
├─ app/src/main/java/com/matheus/bot/
│  ├─ MainActivity.kt            tela de configuração, passos de preparação, botão de pausa
│  ├─ BotService.kt              serviço em primeiro plano: captura + painel flutuante + notificação + Brain
│  ├─ Brain.kt                   máquina de estados: menus, combate, roleta, minigames, vigia, coleta de quadros
│  ├─ TeamSwap.kt                montagem do time 1, troca para o time 2 e equipamentos
│  ├─ CardArt.kt                 reconhecimento do personagem pela arte da carta (OpenCV)
│  ├─ Heur.kt                    análise de imagem com OpenCV (Snap e Heur)
│  ├─ TextVision.kt              OCR (ML Kit) + busca de palavras na tela (Screen/Word)
│  ├─ Capture.kt                 captura de tela via MediaProjection (com horário real do quadro)
│  ├─ BotAccessibilityService.kt gestos (toque, rajada, arrasto, lote, rolagem, 2 dedos)
│  ├─ BotConfig.kt               configurações (SharedPreferences "bot")
│  ├─ Perf.kt                    medição de tempos (debug/perf.txt)
│  ├─ Session.kt                 contadores da sessão (debug/sessao_*.txt)
│  ├─ DebugStore.kt              limpeza da pasta debug/
│  └─ Ui.kt                      miniButton() dos painéis flutuantes
├─ app/src/main/assets/cartas/   equipe1.jpg, equipe2.jpg (referências de arte)
├─ app/src/main/res/             strings.xml e accessibility_service_config.xml
├─ ativar_acessibilidade.bat     liga a acessibilidade via adb
├─ conceder_permissao_uma_vez.bat concede WRITE_SECURE_SETTINGS via adb
├─ CONTEXTO.md                   contexto fixo do projeto para IA
├─ PLANO_PERFORMANCE.md          plano de melhorias por fases e estado de cada uma
└─ README.md                     este arquivo
```

---

## 3. Como rodar

1. Abra o projeto no Android Studio, faça o Sync e rode (Run) no celular (arm64).
2. No app, na ordem dos botões:
   1. **"1) Permitir sobreposição de tela"** (abre `ACTION_MANAGE_OVERLAY_PERMISSION`).
   2. **"2) Abrir acessibilidade"**: tenta abrir direto a página do serviço "Bot Sobrevivente" (SubSettings do Settings); se o Settings recusar, abre a lista geral e mostra um aviso com o caminho ("Apps baixados" > "Bot Sobrevivente" > "Usar Bot Sobrevivente").
   3. **"3) Iniciar captura"**: pede a permissão de captura de tela (MediaProjection) e inicia o `BotService`.
3. O texto no topo do app mostra `Sobreposição`, `Acessibilidade` e `Permissão p/ ligar sozinho` (OK/FALTA) e, quando a acessibilidade não liga, o motivo. Atualiza ao voltar para o app, 1,5 s e 3 s depois.
4. Com a captura ativa aparece o painel no canto superior esquerdo: status, **▶ Play / ⏸ Pausa** (começa **pausado**) e **■ Sair**. O mesmo controle existe no botão da tela principal ("▶ Continuar o bot" / "⏸ Pausar o bot") e na **notificação** ("▶ Continuar" / "⏸ Pausar"). Os três acompanham o estado real do bot, inclusive quando ele pausa sozinho.
5. Deixe o jogo na tela inicial ou no lobby e aperte ▶ Play.
6. O painel usa `FLAG_KEEP_SCREEN_ON`: a tela não apaga enquanto o painel existe (sem isso a captura para em ciclos longos).

### Atalhos via adb (celular por USB)
Os dois `.bat` usam o adb em `C:\Users\teteu\AppData\Local\Android\Sdk\platform-tools\adb.exe` (caminho fixo no arquivo; ajuste se mudar).

- `conceder_permissao_uma_vez.bat`: executa `pm grant com.matheus.bot android.permission.WRITE_SECURE_SETTINGS`. Rodar **uma vez** (de novo só se desinstalar o app). Se der `SecurityException`, ligar "Depuração USB (configurações de segurança)" nas opções do desenvolvedor. Depois disso o app liga a própria acessibilidade em todo `onResume` (se ela estiver desligada).
- `ativar_acessibilidade.bat`: acrescenta o serviço em `enabled_accessibility_services` (preservando os outros) e põe `accessibility_enabled=1`. Útil quando não se concedeu a permissão acima.

---

## 4. Configurações (`BotConfig`)

Salvas em `SharedPreferences("bot")`, editadas na `MainActivity` (botão **Salvar configurações**). Cada leitura vai direto às preferências, então os valores salvos valem sem reiniciar o bot. **Exceção:** `Tamanho da captura` só vale ao iniciar a captura.

| Campo | Padrão | Faixa ao salvar | Significado |
|---|---|---|---|
| `fightTarget` | 0 | — | Depois de N lutas, usa "Parar e receber" no lobby. `0` = só para quando o time morrer. |
| `power1AfterSec` | 0 | — | Usa o poder 1 se ficar N s sem usar o poder 2 (e ele estiver carregado). `0` = nunca por tempo. |
| `comboSwipes` | 6 | — | Arrastos seguidos por rodada do combo. |
| `comboBatch` | 2 | 1 a 4 | Arrastos enviados juntos num único gesto. `1` = um por vez. |
| `comboGapMs` | 0 | — | Pausa entre arrastos. `0` = automática, 30% da duração do gesto (aprox. 12–18 ms); valor manual é ampliado em 30%. |
| `powerPct` | 55 | — | Brilho mínimo (%) do slot para considerar o poder carregado (rótulo na tela: "Brilho da ult", mas vale para os 3 slots). |
| `captureScalePct` | 100 | 30 a 100 | Tamanho da captura em % da tela. Menor = quadros mais baratos, mas o OCR enxerga letra menor. Vale ao iniciar a captura. |
| `artMode` | 2 | 0 a 2 | Personagem pela arte na montagem de times: `0` só texto; `1` a arte só ajuda quando o texto falha; `2` também rejeita versão errada. |
| `artAccept` | 60 | 10 a 99 | Pontuação mínima (%) da arte para **aceitar** uma carta que o OCR não leu. |
| `artVeto` | 30 | 0 a 90 | Pontuação (%) abaixo da qual a arte **rejeita** a carta que o OCR achou (modo 2). |
| `phaseSec` | 17 | 3 a 120 | Montagem de times: segundos arrastando a lista para um lado antes de inverter a direção. |
| `block` | `false` | — | Bloqueio com 2 dedos ao fim de cada rodada do combo. |
| `ult` | `false` | — | Usa o poder 3 (ult). |
| `barGame` | `true` | — | Liga o minigame de encher a barra. Desligue se ele disparar em falso e atrapalhar o combo. |
| `record` | `false` | — | Modo de coleta: grava quadros e `.json` do combate em `debug/dataset/`. |

Campos numéricos vazios ou inválidos voltam ao padrão ao salvar.

---

## 5. Arquitetura em tempo de execução

```
MainActivity ──(MediaProjection)──► BotService
                                      ├─ Capture (VirtualDisplay + ImageReader, paisagem, RGBA_8888)
                                      ├─ TextVision (ML Kit)
                                      ├─ Painel flutuante (status, Play/Pausa, Sair) + notificação com Pausar/Continuar
                                      └─ Brain ──► BotAccessibilityService.instance (gestos)
                                           ├─ laço principal (loop): decide combate x menu e age
                                           ├─ vigia (watch): lê poderes, texto do evento e barra em paralelo
                                           ├─ Heur / Snap (OpenCV)
                                           ├─ TeamSwap ──► CardArt (arte das cartas)
                                           └─ Perf / Session / DebugStore (medição e logs)
```

### BotService
- Em `onStartCommand`: se a ação for `TOGGLE_PAUSE` (botão da notificação), pausa/continua o bot (ou encerra o serviço se o bot ainda não foi montado). Só inicia a captura se ainda não houver projeção.
- Cria o canal "Bot" (importância baixa) e a notificação "Bot Sobrevivente pausado/rodando", entra em **primeiro plano antes** de obter o `MediaProjection` (exigência do Android 14+), registra callback (`onStop` → `shutdown()`), inicia o OpenCV (`OpenCVLoader.initLocal()`; se falhar, encerra o serviço) e carrega as referências de arte (`CardArt.init`; se falhar, o bot segue só com o OCR).
- Captura sempre em **paisagem**: `w = max(largura, altura)`, `h = min(...)` (ex.: 2400x1080). Com `captureScalePct < 100`, a captura é reduzida (dimensões pares) e `BotAccessibilityService.gestureScale` guarda a relação tela real / captura para converter todos os gestos.
- Cria o `Brain` com a pasta de debug `getExternalFilesDir(null)/debug`. O painel é **escondido (INVISIBLE)** durante a montagem de times, porque aparece na captura e cobriria o quadrado 1.
- `START_NOT_STICKY`. `shutdown()` para o Brain (grava a sessão), remove o painel, volta `gestureScale` a 1, libera a captura e a projeção e encerra o serviço.
- Expõe `BotService.instance`, `paused` e `toggle()` (usados pela tela principal) e `pauseListener`.

### Capture
`VirtualDisplay` com `ImageReader` (2 buffers). `latestFrame()` devolve o último quadro junto com o **horário real da captura** (carimbo do `Image`, convertido para `currentTimeMillis`), lidos juntos para dois leitores nunca receberem quadro de um e horário do outro. O carimbo só é usado se parecer estar na base de `System.nanoTime()` (idade entre 0 e 1 s; propriedade `tsValid`); senão devolve o horário em que o bot pegou o quadro. Se não há quadro novo, repete o anterior. Cada quadro novo é copiado para um `Bitmap` já recortado do preenchimento de linha (`rowStride`). `latest()` devolve só o `Bitmap`.

### TextVision / Screen / Word
- OCR do ML Kit (modelo embutido, offline). Imagens com mais de 1280 px de largura são reduzidas para o OCR; as posições voltam em **proporção da tela (0..1)**.
- Texto normalizado: **MAIÚSCULAS, sem acento, sem símbolos** (`"Avançar!"` → `AVANCAR`). Guarda linhas e também cada elemento (palavra) separadamente.
- `read(bmp, l, t, r, b)` lê a tela toda ou só um recorte (bem mais rápido).
- `Screen.find/has/hasAny`: acham texto por "contém"; para chaves com 6+ letras aceitam 1 letra errada (distância de Levenshtein ≤ 1). Podem filtrar por região (`xMin..yMax`). `find` devolve a palavra de maior área.

### BotAccessibilityService (as "mãos")
Singleton em `BotAccessibilityService.instance` (nulo = acessibilidade desligada). Todas as coordenadas recebidas estão no espaço da **captura**; `gestureScale` as converte para a tela real. `lastGestureAt` guarda o horário do último gesto (o vigia de inatividade do Brain usa isso).

| Função | O que faz |
|---|---|
| `tap` | Toque (40 ms por padrão). |
| `tapBurst` | Até 10 toques no mesmo ponto em **um único gesto** (padrão 6 toques, período 25 ms, 8 ms cada, sem sobreposição). Bem mais rápido que vários `tap`. |
| `swipe` | Arrasto simples. |
| `swipeBatch` | Vários arrastos curtos num único gesto, um depois do outro (intervalo mínimo 2 ms). Respeita `GestureDescription.getMaxStrokeCount()`. |
| `drag` | Segura parado (`holdMs`), move (`moveMs`) e **fica parado no destino** (`endHoldMs`, padrão 400 ms) antes de soltar; usa gestos encadeados (`continueStroke`). O "segurar" evita que o jogo leia como rolagem; a parada final evita que a carta caia no meio do caminho. |
| `dragVia` | Igual ao `drag`, passando por um ponto intermediário (ex.: subir para sair da faixa da lista). |
| `scrollStop` | Rolagem sem inércia: arrasta e fica parado no fim antes de soltar (a lista anda exatamente a distância pedida). |
| `block` | Dois toques simultâneos em (60%, 45%) e (72%, 55%) da tela. |

Cada gesto mede o atraso do Android além da própria duração (`gesture_overhead`, `_burst`, `_batch` no `perf.txt`); só toques e arrastos curtos entram na mediana usada na antecipação do ping pong.

### MainActivity
Interface montada por código: botões dos 3 passos, botão de pausa, campos numéricos de configuração e caixas de seleção ("Bloqueio (2 dedos)", "Usar poder 3 (ult)", "Minigame da barra", "Coletar quadros do combate"). A antecipação do ping pong é calibrada pelo próprio bot. Pede `POST_NOTIFICATIONS` ao abrir.

Em `onResume` tenta `autoEnableAccessibility()` (precisa de `WRITE_SECURE_SETTINGS`): se o serviço não está na lista, adiciona; se ~1,5 s depois ele continua desconectado, **força o religamento** (tira da lista e põe de volta após 400 ms). Em `onResume`/`onPause` também liga/desliga o `pauseListener` para o botão de pausa acompanhar o bot.

---

## 6. Brain: o ciclo do bot

O `Brain` roda um laço (`loop`) em coroutine (`Dispatchers.Default`). A cada volta grava as medições (`Perf.flush`), limpa a pasta de debug a cada 5 min (`trimMaybe`), grava a sessão (`Session.flush`); se pausado, espera 200 ms; se a acessibilidade caiu, avisa e **pausa**; pega o quadro, gera um `Snap` e decide **combate** ou **menu**.

### Início e pausa
`start()` tira o bot da pausa e zera o vigia de inatividade. Só **na primeira vez** (quando o laço ainda não existe) zera `fights`, `unknown`, `deadVotes`, o último estado e `stopAfter`. Pausar e continuar **não zera** as lutas, e `team` (1 ou 2) **nunca** é reiniciado enquanto o serviço está vivo: depois de uma troca para o time 2, um novo Play na mesma sessão continua considerando o time 2.

### Estados (`Sc`)
`COMBATE, AVANCAR, CONTINUAR, FECHAR, CONFIRMA, LOBBY_MORTO, LOBBY_VIVO, ROLETA, HOME, EQUIPE_VAZIA, DETALHE, DESCONHECIDA`.

### Fluxo geral
```
HOME (SOBREVIVENTE) → lobby (LUTAR) → roleta → COMBATE → AVANÇAR → CONTINUAR → lobby (LUTAR) → ...
```
- **Contagem de lutas** (`fights`) sobe ao **entrar** no estado AVANÇAR.
- **Meta de lutas**: no lobby com o botão de lutar disponível, se `fightTarget > 0` e `fights >= fightTarget`, toca em **PARAR E RECEBER** em vez de LUTAR.
- **Confirmação do "Parar e receber"**: só age se o texto tiver `RECEBER` sem `EQUIPE` e algum aviso (`OPTAR`, `SAIR DO MODO` ou `TODAS AS RECOMPENSAS`). Toca em RECEBER e marca `stopAfter`.
- **Depois de `stopAfter`**: telas desconhecidas são tratadas como o "Coringa/Última Risada" (procura `ACEITAR`, `COLETAR`, `RESGATAR`, `CONTINUAR`, `RECEBER`; se não achar, 1 toque no centro a cada 3 s). Ao voltar para a HOME, mostra "Ciclo terminado." e **pausa**.
- **Tela desconhecida**: salva `debug/desconhecida_*` na primeira leitura e continua lendo a tela (OCR geral e faixa de cima) a cada volta, agindo normalmente assim que reconhecer. **Não gira a roleta às cegas.** Com mais de 120 leituras seguidas, **pausa**.

### Proteção de montagem de times (`guard`)
Liga por até 90 s quando o bot clica em SOBREVIVENTE (HOME), quando o time 1 morre e quando vai montar o time. Desliga ao entrar em LOBBY_VIVO, COMBATE, AVANÇAR, CONTINUAR, CONFIRMA ou ROLETA, e ao fim da montagem. Enquanto ligada o Brain **não gira a roleta** (o arrasto rápido para a esquerda lançaria a lista de personagens), **ignora a evidência fraca de combate** e **não aplica o vigia de inatividade**.

### Vigia de inatividade (`unstick`)
Regra geral, fora da proteção de montagem: **8 s sem nenhum gesto** (a contar do último gesto ou do início/última ação do vigia) significa que o ciclo travou. O bot esquece o estado (tela anterior, votos, bloqueios, leituras do evento), guarda `debug/parado_8s_*` (até 3 vezes), relê a tela do zero e decide de novo (combate ou menu). Se mesmo assim não houve gesto, escala: (1) toca em um botão genérico de seguir (`CONTINUAR`, `AVANCAR`, `FECHAR`, `ACEITAR`, `COLETAR`, `RESGATAR`) de 40% da altura da tela para baixo; (2) toca no centro da tela, da 2ª à 6ª tentativa seguida. **Nunca** toca em RECEBER (no lobby seria PARAR E RECEBER) nem em LUTAR (respeita a meta de lutas). Conta `watchdog` na sessão.

### Ordem de decisão nos menus (`menuTick`)
1. Confirmação de "Parar e receber" (acima) → `CONFIRMA`
2. `FECHAR` (popup "NOVA CARTA", embaixo, y ≥ 0,75) → `FECHAR`
3. `AVANCAR` (y ≥ 0,70) → `AVANCAR`
4. `CONTINUAR` (y ≥ 0,75) → `CONTINUAR`
5. **Detalhes do personagem** (abriu sem querer): `EQUIPAMENTO` + (`SUPORTE`, `MELHORIAS`, `NIVEL ATUAL` ou `CRITICO`) → `DETALHE`: toca no meio do modelo 3d (16% da largura, 40% da altura) para fechar, a cada ~0,9 s; após 10 toques sem fechar, salva `debug/detalhe_nao_fechou_*` e **pausa**. A montagem de times trata essa tela por conta própria.
6. `RECEBER` + `EQUIPE` → lobby (ver abaixo)
7. Fora da proteção e sem `RECEBER`: `GIRE`, `SORTEAR`, `RODA` ou `RECOMPENSAS` (os dois últimos só na parte de cima, y ≤ 0,35) → roleta
8. `SOBREVIVENTE` + (`BATALHA`/`COLECAO`/`LOJA`) → `HOME` (liga a proteção e toca em SOBREVIVENTE; com `stopAfter` encerra o ciclo)
9. `CRIE SUA EQUIPE` → `EQUIPE_VAZIA`: esconde o painel, monta o time 1 (seção 8)
10. Qualquer outra coisa → desconhecida (com segunda leitura da faixa de cima, `0,15..0,85 × 0..0,32`, para achar o texto cortado da roleta; ali `GIRE`/`RODA`/`SORTEAR`/`RECOMPENSAS`, ou `PARTIDA` no topo (y ≤ 0,25), contam como roleta)

### Lobby (`lobby`)
O botão grande do lobby se chama LUTAR e, depois da troca de time ou de uma run repetida, **REPETIR** (mesmo lugar, mesma função; região 0,45–0,78 × 0,30–0,65). Dois validadores de **time morto**, independentes do texto:

| Validador | Região | Critério |
|---|---|---|
| X vermelho no botão central (`deadX`) | x 0,575–0,64, y 0,38–0,56 | ≥ 6% de vermelho vivo e < 2% de branco (LUTAR/REPETIR tem letras brancas) |
| Caveira branca nas 3 cartas (`deadCards`) | miolo de cada carta, y 0,396–0,535 | ≥ 20% de branco dentro e faixas logo acima/abaixo (y 0,28–0,375 e 0,549–0,597) com ≤ 5%; **as três** cartas têm que bater |

Reserva `ringVisible`: se o texto do botão não foi lido mas o anel vermelho está lá (x 0,57–0,70, y 0,33–0,60, ≥ 2%) e nenhum validador mostra morte, trata como LUTAR (toca em x 0,61, y 0,47). Se **os dois** validadores concordam (`strongDead`), um "LUTAR" lido pelo OCR é descartado como engano.

Com o botão de lutar disponível: `LOBBY_VIVO`, zera os votos e toca em LUTAR (ou PARAR E RECEBER, pela meta).
Sem ele: soma **votos** de morte (X do botão = +2, caveira = +2, nenhum validador = +1 por leitura); com **≥ 3** é `LOBBY_MORTO`. No time 1 chama a troca para o time 2 (seção 8) e, se der certo, `team = 2`; se falhar, pausa com a mensagem do erro. No time 2: "Fim do ciclo. Pausado.".

### Roleta
Arrasto rápido para a **esquerda** de (74%, 62%) a (20%, 62%) em 200 ms. Depois de 700 ms, se o combate ainda não apareceu, repete **uma vez** por segurança. Espera até 4,5 s (1,2 s se os dois gestos falharam), saindo assim que o combate aparecer. Guarda `debug/roleta_disparou_*` nas 5 primeiras vezes para conferir se disparou em tela errada.

### Detecção de combate
- `isCombat()` (estrito): barras de vida azuis no topo (`hud > 0,02`) **e** slots roxos embaixo (`purple > 0,10`).
- Evidência fraca (só fora da proteção de montagem): já estando em combate, basta `isCombatLoose()` (`hud > 0,008` **ou** `purple > 0,04`); ou, em tela desconhecida, `purple > 0,25`.
- Com evidência fraca, de 1 em 1 s o OCR confere se não é um menu de verdade (AVANÇAR, FECHAR...).
- Sem HUD no meio da luta (animações/efeitos), por até 25 voltas continua batendo (combo) e só pergunta ao OCR de ~1 em 1 s se surgiu um botão.

### Combate (`combatTick`)
Ordem de prioridade a cada volta:
1. **Mini-evento** por texto (seção 7).
2. **Barra de encher por forma**: já confirmada pelo vigia (seção 7), só na janela de 8 s depois de um poder.
3. **Poderes**, tocando no centro de cada slot (y = 90,5% da altura): poder 3 em x = 13% (só se `ult`), poder 2 em x = 23,6%, poder 1 em x = 34,3%.
   - Slot "carregado" = **sem X vermelho** e (rosa do número > 4% **ou** brilho > `powerPct`).
   - Ordem: ult (se ligada) → poder 2 → poder 1 se o poder 2 está com X vermelho → poder 1 por tempo (`power1AfterSec`).
   - **Trava anti-loop** (`usePower`): tocar no mesmo poder mais de 3 vezes em 1,5 s faz esse poder ficar de lado por 2,5 s (evita ficar só tocando num poder que não dispara).
4. **Combo** em spam.

Usar qualquer poder abre a **janela de minigame de 8 s** (`markPower`).

### Combo
`comboSwipes` arrastos horizontais, enviados em lotes de `comboBatch` (padrão: 6 arrastos em 3 lotes de 2): y aleatório entre 38% e 65% da altura, início em x 56–64%, comprimento 14–20% da largura (máx. ≈ 84%, longe dos botões do Android), duração 40–59 ms. A pausa automática entre arrastos é 30% da duração do gesto; um valor configurado manualmente recebe aumento de 30%. Entre os lotes o combo só **consulta** o vigia (custo zero) e sai na hora se há minigame pronto, barra confirmada ou poder carregado (usa a leitura do vigia se tiver até 300 ms; senão lê o quadro mais recente). Com o poder 2 com X, usa o poder 1 se carregado. Dentro de um lote o combo não pode ser interrompido. Ao fim do ciclo, aguarda até 450 ms antes de recomeçar, mas interrompe a pausa se um poder ou minigame ficar pronto; se `block` estiver ligado, executa primeiro o bloqueio de 2 dedos (350 ms).

### Vigia (`watch`)
Coroutine própria que, **em combate**, lê cada quadro novo só com recortes pequenos:
- poderes (`Heur.power`) → `perceived`, que o combo e o `freshPower` consultam;
- texto do evento (OCR em segundo plano, via `scanEvent`);
- forma da barra de encher, só na janela de 8 s pós-poder (`watchBar`);
- coleta de quadros, se `record` estiver ligado.

Ele só **percebe**; quem age é o Brain. Enquanto um minigame roda (`watcherHold`) o vigia não lê poder/texto/barra (só grava, se a coleta estiver ligada). Ritmo: ~25 ms entre leituras fora da janela de minigame, 8 ms dentro dela. Fora de combate ou pausado, só espera (80 ms).

---

## 7. Mini-eventos (minigames)

### Detecção
O texto do canto superior direito (região `0,50..0,95 × 0,15..0,33`) é lido por OCR **em segundo plano** (no máximo uma leitura por vez; intervalo de 90 ms na janela de 8 s após um poder, 300 ms no resto). O resultado vale por 1,5 s. `Brain.miniOf` é o **único** lugar que decide qual minigame é, nesta ordem:

1. contém `ARREMESS` (o jogo mostra "TOQUE PARA ARREMESSAR") → **PING PONG**
2. contém `PASSE` ou `DEDO` → **SETA**
3. contém `TOQUE` e `RAPIDO` → **TOQUE**
4. só na janela pós-poder: `ENCHER` **e** `BARRA` juntas (e `barGame` ligado) → **BARRA**. Palavras soltas (`CARREG`, `MARTEL`, `TOQUE`...) não disparam, por dar falso positivo no meio da luta.

Os textos específicos valem sempre (podem vir por ícone); os genéricos só na janela.

Bloqueio após um evento (`evBlockUntil`): se o minigame **não existia** (a forma não apareceu), 3 s; se encadeou outro ou foi PING PONG, 0; senão 400 ms. A barra confirmada por forma bloqueia 1,5 s depois.

### Cada minigame
| Minigame | Ação |
|---|---|
| **PING PONG** ("TOQUE PARA ARREMESSAR") | **Acompanha o marcador** (ver seção 7.1). Um único toque em (56,5%, 48%), no instante em que o marcador cruza o quadrado branco do centro. Espera 0,6 s depois (1,2 s se não chegou a tocar pelo rastreio). |
| **SETA** ("PASSE O DEDO RÁPIDO") | Por até 8 s: acha a seta azul (`arrowFast`), pega o centro e faz 3 arrastos rápidos (27 ms) ao longo dela (meio comprimento = 60% do comprimento da seta, entre 12% e 45% da altura). Se não achou a seta na 2ª tentativa seguida, arrasta para baixo no meio (x 58,4%; 80 ms). Desiste se nunca achou (12 leituras) ou se achou e ela sumiu (20 leituras). Sai se aparecer o texto de ARREMESSAR (encadeia com o PING PONG). Só começa por **texto** (o gatilho por forma foi removido por falso positivo com brilhos azuis do cenário). |
| **TOQUE** (bolinha) | Espera até 2,2 s a forma aparecer: se for o círculo da barra → BARRA; se for um círculo solto → bolinha. Na bolinha: rajadas de 6 toques no centro (2 se perdeu a bolinha), com intervalo de 12 ms, até ela sumir por 5 leituras (máx. 9 s; se nunca viu a bola, desiste em 3,2 s). |
| **BARRA** (encher a barra) | Dispara por texto (`ENCHER`+`BARRA`) ou pela **forma** (círculo + barra vertical clara), só na janela de 8 s depois de um poder. Por texto, espera até 0,6 s a forma; **sem forma não toca em nada**. Com a forma: rajadas de 6 toques no círculo (5 ms cada, período 12 ms) até ele sumir (conferência a cada 150 ms, máx. 9 s; sai após 5 leituras sem o círculo, ou em 4 s se nunca o viu). Pode ser desligado em `barGame`. |

**Confirmação da barra por forma:** o vigia só a considera minigame se ela aparecer em 2 leituras seguidas (< 0,7 s) no mesmo ponto (±3%), no máximo a cada 250 ms; senão o combo segue normal. A barra confirmada fica válida por 0,8 s.

### 7.1 Ping pong: como o bot acompanha o marcador
1. A cada quadro novo lê a faixa da barra (x 54–62,5%, y 26–74%, em 100 faixas horizontais) e guarda, por linha, o **menor** brilho já visto (o fundo parado). O marcador é a maior mancha que ficou ≥ 0,15 mais brilhante que esse fundo (massa ≥ 0,35); funciona para qualquer linha clara que se mova.
2. Com 3 posições seguidas (em até 300 ms) na mesma direção, calcula a velocidade (proporção da altura por segundo; precisa de ≥ 0,05) e o tempo que falta para chegar ao centro (y = 50%).
3. Se ele vai **para** o centro e falta ≤ 0,12 s (descontada a antecipação aprendida), espera o resto e toca. Se está se afastando, espera ele voltar. Se nunca achar o marcador em 2,5 s, ou em 7 s no total, toca uma vez mesmo assim.
4. **Calibração automática:** começa usando a mediana do atraso dos gestos curtos medidos (`gesture_overhead`), ou 90 ms até haver medições. Depois de cada toque, compara a previsão com o cruzamento real do ponto branco e corrige metade do erro (máximo 40 ms por rodada). O valor aprendido fica salvo no aparelho; não há ajustes manuais na tela.
5. **Registro:** o toque sai em paralelo e o bot lê quadros por mais 600 ms para ver quando o marcador cruzou o centro de verdade. Cada ping pong escreve uma linha em `debug/pingpong_acertos.txt` com antecipação usada, atraso do toque, velocidade, posição, `cruzouApos` e `erroPrevisao`. `erroPrevisao > 0` = o marcador chegou depois do previsto (toque cedo); `< 0` = chegou antes (toque tarde). Isso mede se a **previsão** está certa; o acerto final é o resultado na tela do jogo.
6. Nas 2 primeiras vezes grava recortes da barra e um log (`pp{0,1}_NN_*`, `pp{0,1}_TAP_*` e o log `pingpong{0,1}_log_<horário>.txt`).

---

## 8. TeamSwap: times e equipamentos

Tudo é guiado por texto (e, nos personagens, também pela arte): cada passo confere a tela antes de agir; em qualquer divergência salva um print em `debug/` e devolve a mensagem de erro (o Brain **pausa** e a mostra no painel).

### Fluxos
- **`createTeam1`** (tela "CRIE SUA EQUIPE", time vazio): arrasta os 3 personagens do time 1 → toca no botão "CRIE SUA EQUIPE" (x fixo 65%, y pela palavra CRIE/EQUIPE ou 33,5%; até 3 tentativas de 4 s cada, esperando o lobby) → equipa os 3 personagens.
- **`run`** (time 1 morto → time 2): toca em EQUIPE → espera a tela de escolha (até 6 s) → arrasta os 3 personagens do time 2 → toca em CONFIRMAR ALTERAÇÕES (até 3 s) → espera voltar ao lobby (até 10 s) → equipa os 3.

### Times configurados no código
Posição na lista = quadrado de destino (1, 2, 3). Há regras para distinguir versões da mesma carta (subtítulo certo/errado e nível mínimo lido no canto da carta).

**Time 1**
| Quadrado | Personagem (chave de busca) | Versão / regra | Equipamentos (quadrados 1, 2, 3) |
|---|---|---|---|
| 1 | Flash Reverso (`REVERSO`) | nível ≥ 30 | Rifle Personalizado; Armadura Peitoral LexCorp V1; Traje de Hipervelocidade Revestido (`REVESTIDO`) |
| 2 | Apocalipse (`APOCALIPSE`) | subtítulo "Contenção", nível ≥ 40 | Bastões de Escrima Energizados; Estrela da Manhã de Metal Enésimo; Kama do Reino Inferior |
| 3 | Nevasca (`NEVASCA`) | rejeita subtítulo "Regime", nível ≥ 40 | Placa Peitoral Divina do Quarto Mundo; Maça Divina do Quarto Mundo; Capacete Divino do Quarto Mundo (`DIVINO`) |

**Time 2**
| Quadrado | Personagem (chave de busca) | Versão / regra | Equipamentos (quadrados 1, 2, 3) |
|---|---|---|---|
| 1 | Batman (`BATMAN`, exato) | subtítulo "Ninja", nível ≥ 50, estrito (nunca aceita subtítulo ilegível), rejeita subtítulo "Foice"/"Mart" | Placa Peitoral Divina do Quarto Mundo; Maça Divina do Quarto Mundo; Facas de Adepto da Liga dos Assassinos |
| 2 | Cavaleiro Arkham (`CAVALEIRO`) | nível ≥ 50 | Rifle Personalizado; Kama do Reino Inferior; Estrela da Manhã de Metal Enésimo |
| 3 | Caçador de Marte (`DE MARTE`, para não casar com "martelo" nem com o subtítulo do Lobo) | subtítulo "Noite"/"Densa", nível ≥ 50, estrito | Bastões de Escrima Energizados; Armadura Peitoral LexCorp V1; Traje do Caçador de Marte (`CACADOR`) |

Chaves dos equipamentos: `RIFLE`, `ARMADURA`, `REVESTIDO`, `BASTOES`, `MANHA`, `KAMA`, `PLACA`, `MACA`, `DIVINO`, `FACAS`, `CACADOR`. Para itens que o OCR lê mal há palavras alternativas (`gearAlt`: LEXCORP, PERSONALIZADO, REINO, ESCRIMA, ENESIMO, ADEPTO, CAPACETE) e, na lista, `ESCRIMA|ENERGIZADOS` nos Bastões e `REINO|INFERIOR` na Kama.

### Reconhecimento pela arte (`CardArt`)
Separa as **versões** da mesma carta sem depender do OCR ler o subtítulo sobre arte brilhante.
- **Referências:** recortes só da arte (sem nível, subtítulo, nome nem números) de `assets/cartas/equipe1.jpg` e `equipe2.jpg`. Time 1 (Flash Reverso, Apocalipse, Nevasca) tem uma referência cada (o quadrado); Batman, Cavaleiro Arkham e Caçador de Marte têm duas (o quadrado e a lista de baixo).
- **Comparação:** *template matching* (`TM_CCOEFF_NORMED`, cor) em 3 escalas (0,88 / 1,0 / 1,14), com foto e quadro reduzidos a 640 px de largura, na faixa da lista (y 0,43–0,80). Guarda a melhor pontuação (0 a 1) em 100 faixas horizontais, com cache por quadro.
- **Uso** (`TeamSwap.findCard`, só personagens; equipamentos seguem só com texto):
  - o texto achou a carta e a arte nessa posição pontua abaixo de `artVeto`: **rejeita** (versão errada), só no modo 2 e só nas 2 primeiras rodadas (a 3ª rodada volta a valer só o texto, como válvula de segurança);
  - o texto não achou e a melhor carta pela arte pontua pelo menos `artAccept`: **aceita** (o nível lido e o subtítulo errado ainda valem) e guarda até 3 prints por personagem em `debug/arte_aceito_*`.
- **Diagnóstico:** `debug/arte_scores.txt` (até 600 linhas por sessão, nunca apagado pelo `DebugStore`).
- **Limites conhecidos:** não há foto das versões erradas (compara só com a certa); o recorte depende de a tela ter a mesma proporção da foto (mesmo aparelho); os limites 60 e 30 são um palpite, ainda não medido.

### Arrastar personagens/equipamentos (`pickAll`)
1. **Personagens:** antes de começar, espera a lista parar (2 comparações seguidas iguais, no máximo 2 s). Em seguida **analisa parado** a tela; se um item que falta está visível na lista de baixo, arrasta para o quadrado.
2. Se não achou, rola a lista por até `phaseSec` segundos (padrão 17) em cada direção; equipamentos alternam esquerda/direita continuamente e invertem antes se a lista parecer parada (3 leituras iguais). Personagens percorrem as rodadas configuradas e, se ainda faltar alguém, reiniciam a varredura. Cada passo usa `scrollStop`: personagens movem 190 ms, equipamentos 600 ms; ambos ficam parados 110 ms no fim. Antes de ler após cada rolagem, espera 125 ms para personagens e 120 ms para equipamentos. Personagens fazem uma pausa de 0,5 s ao fim de cada rodada. A partir da 2ª rodada aceita carta com subtítulo ilegível (exceto alvos `strict`: Batman e Caçador de Marte). O `notSub` de um alvo aceita alternativas separadas por `|`.
3. **Personagens, antes de arrastar:** confirma o nome e a posição num quadro **novo** (a lista pode ter mexido). Se não confere, nunca usa a posição antiga: volta a ler e tenta novamente; após 3 falhas, informa que segue verificando.
4. **Arrasto do personagem:** segura 0,9 s parado na carta, sobe reto até 40% da altura (350 ms, saindo da faixa da lista para não ler como rolagem), vai ao quadrado (450 ms) e fica parado 0,4 s antes de soltar.
5. Depois de arrastar confere se a carta saiu da lista. Se ainda houver outro alvo à vista, tenta esse também; senão continua rolando de onde parou. Tentativas não confirmadas são repetidas enquanto o processo estiver ativo.
6. Se o alvo não aparecer após as rodadas, **personagens**: se o quadrado tem algo lido que não é o nome de outro personagem conhecido, assume que já está certo (quem está no time some da lista) e guarda `debug/personagem_N_assumido_*`; se não, reinicia a varredura e continua tentando.

**Equipamentos** têm particularidades:
- Aguarda 2 s ao entrar na aba. Lê as duas metades da lista e procura a chave, alternativas e palavras do nome completo; aceita um erro isolado de OCR na chave somente quando uma palavra de contexto do mesmo cartão também bate. Se a leitura dividida não localizar o item, tenta a faixa inteira da lista. Antes de arrastar, confirma o nome em um quadro recente e relê o quadrado de destino; se o item já estiver equipado, pula o gesto. As durações de segurar, mover e soltar do gesto de equipagem são reduzidas a um terço; a rolagem da lista e a pausa para o jogo registrar cada tentativa não mudam. Após o arrasto, também confere a faixa de estado/modal. Durante a busca, a cada 5 s verifica em quadros diferentes os três quadrados; se todos estiverem válidos, encerra a busca e toca em `CANCELAR`/`VOLTAR` para sair. Também faz essa conferência ao terminar a busca normal.
- Item que o próprio personagem já usa **some da lista**; se aparece no quadrado, considera equipado.
- A cada tentativa alterna 4 gestos: arrastar direto; arrastar em dois trechos; tocar no item e depois no quadrado; arrastar em dois trechos por outra altura. O contato e o movimento aumentam gradualmente até um limite após várias voltas. Guarda `debug/equip_tentativa*`.
- Se o jogo abrir uma janela de confirmação, toca em `SIM`, `CONFIRMAR`, `EQUIPAR`, `TROCAR`, `MOVER`, `SUBSTITUIR` ou `OK` (`debug/equip_janela_*`). Se a tela não for reconhecida, registra o quadro e tenta reconhecê-la novamente.
- A busca não encerra após um número fixo de gestos: repete enquanto o processo estiver ativo e só sai da aba quando a conferência dos três quadrados confirmar que estão ocupados e sem item conhecido errado. Um quadrado ocupado sem nome legível pode ser aceito se o OCR não identificar um item errado.
- **Item errado no quadrado**: o bot conhece as chaves de todos os equipamentos dos dois times. Se um quadrado mostra o nome de OUTRO equipamento conhecido, isso **nunca** conta como feito; o item certo é arrastado por cima (`debug/equip_item_errado*`).
- **Ponto de pegada**: ao pegar um equipamento na lista, ignora linhas de OCR que juntam o nome de duas cartas vizinhas (o centro delas cai entre as cartas); nesse caso usa o centro da palavra-chave.
- Para entrar na escolha de equipamentos de cada personagem (`openEquip`), toca no nome dele na carta do lobby (se não abrir, varia o ponto/tipo de toque, até 8 voltas) ou, se abrir a tela de detalhes, no 1º quadrado de equipamento (x 66,6%, y 50%). Sai por `CANCELAR`/`VOLTAR` (sem tocar no CANCELAR do próprio lobby).

### Posições (proporção da tela)
| | Quadrados (x) | Quadrado (y) | Faixa do nome no quadrado (y) | Lista de baixo (y mín.) | Rolagem (de → para, x) |
|---|---|---|---|---|---|
| Personagens | 13,75% / 30,6% / 47,6% | 23% | 0,28–0,50 | 0,50 | 78% → 23% |
| Equipamentos | 32,8% / 49,4% / 65,8% | 32% | 0,37–0,47 | 0,62 | 76,5% → 29,3% |

---

## 9. Análise de imagem (`Heur.kt`)

O `Snap` reduz o quadro para **800 px de largura** e guarda HSV e cinza. Regiões em proporção da tela:

| Análise | Região | Critério |
|---|---|---|
| `hud()` | x 30–80%, y 2,5–11% | fração de azul (barras de vida) |
| `purple()` | x 6–40%, y 88–96% | fração de roxo (slots de poder) |
| `slotV(i)` | y 89–95% | brilho médio do slot |
| `slotPink(i)` | y 87–94,5% | rosa/magenta do número (poder carregado); limite fixo 4% |
| `slotBlocked(i)` | y 87–94,5% | **X vermelho** (vermelho nos terços esquerdo e direito > 3%, magenta < 2%) = poder indisponível |
| `ball()` | x 40–95%, y 15–90% | `HoughCircles` (raio 30–70), ignora o círculo da barra |
| `barCircle()` | x 48–67%, y 52–76% | círculo pequeno (raio 16–34) **com barra vertical clara logo acima** (coluna clara ≥ 55%) |
| `arrowFast()` | x 35–85%, y 15–92% | maior mancha azul comprida (proporção ≥ 1,5, área ≥ 600); direção pelo lado mais cheio |
| `pingProfile()` | x 54–62,5%, y 26–74% | brilho por faixa horizontal (limiar de cinza 150) |

Slots (x): poder 3 = 8–18%, poder 2 = 18,5–28,7%, poder 1 = 29,3–39,5%.
`Heur.power()` é a versão rápida da leitura dos 3 slots (recorta só a faixa de baixo, y 86–96%) e devolve 6 valores: carregado e X vermelho de cada slot ([slot 3, slot 2, slot 1]).
`Heur.arrowFast()` e `Heur.pingProfile()` recortam **antes** de converter (bem mais leves que o quadro todo).

Código existente mas **sem uso** hoje: `Heur.ballFast()` (a bolinha usa `Snap.ball()`).

---

## 10. Medição e logs

### `Perf` (`debug/perf.txt`)
Medição de tempo (não muda o comportamento). A cada 15 s acrescenta um bloco com, por etiqueta, quantidade, média e máximo (cada bloco é uma janela de 15 s). O cabeçalho traz `overhead_toque_med` e `idade_quadro_med`. Passou de 1 MB, o arquivo vira `perf_old.txt`.

Etiquetas: `capture_gap`, `capture_copy`, `frame_age`, `ocr WxH`, `snap`, `power`, `watch_power`, `ping_profile`, `ping_gap`, `gesture_overhead`, `gesture_overhead_burst`, `gesture_overhead_batch`. A leitura delas está no Anexo A do `PLANO_PERFORMANCE.md`.

### `Session` (`debug/sessao_<inicio>.txt`)
Contadores desde que o app abriu, gravados no máximo 1 vez por minuto (e ao parar), sobrescrevendo o arquivo. Chaves: `lutas`, `pausas`, `combate_ms`, `poder1`/`poder2`/`poder3` e `poderN_travado`, `mini_<TIPO>_ok`/`_falso` (PING_PONG, SETA, TOQUE, BARRA), `mini_BARRA_forma`, `watchdog`, `tela_desconhecida`, `time_morto` e o `ritmo` (lutas/min).

### `DebugStore`
A cada 5 min mantém a pasta `debug/` com no máximo **500 arquivos / 300 MB**, apagando os mais antigos. Não conta nem apaga arquivos que começam com `perf`, `pingpong_acertos`, `sessao` ou `arte_scores`, e não entra em subpastas.

### Modo de coleta (`record`)
Grava em `debug/dataset/` até 4 quadros por segundo durante o combate: `f_<horario>.jpg` (1280 px de largura) mais `f_<horario>.json` com o estado, o minigame, o texto do evento, `hud`, `roxo`, brilho/rosa/X de cada slot e os poderes lidos pelo vigia. Roda em outra thread; limite de 2000 arquivos / 400 MB.

### Prints (`debug/`)
Salvos em `Android/data/com.matheus.bot/files/debug/` (nome `tag_timestamp.jpg`):

| Arquivo | Quando |
|---|---|
| `desconhecida_*` | tela não reconhecida |
| `parado_8s_*` | vigia de inatividade (até 3) |
| `detalhe_nao_fechou_*` | a tela de detalhes do personagem não fechou após 10 toques |
| `roleta_disparou_*` | os 5 primeiros giros da roleta |
| `time_morto_x_*` | primeira leitura com validador de time morto |
| `evento_barra_*`, `barra_ok_*` | minigame da barra (até 3 de cada) |
| `seta_ok_*`, `seta_nao_achou_*` | minigame da seta (até 3 no total) |
| `pp{0,1}_NN_*`, `pp{0,1}_TAP_*`, `pingpong{0,1}_log_<horário>.txt` | recortes da barra e log do ping pong (2 primeiras vezes) |
| `quadradoN_ja_cheio_*` | quadrado já cheio sem o bot ter arrastado |
| `time1_nao_li_quadrados_*`, `time2_nao_li_quadrados_*` | conferência informativa dos quadrados |
| `troca_falha_*` | qualquer falha da montagem/troca |
| `personagem_N_assumido_*` | personagem assumido como já no quadrado (não achado na lista) |
| `arte_aceito_*` | carta aceita só pela arte (até 3 por personagem) |
| `equip_tentativa{N}_{CHAVE}_*`, `equip_janela_*` | tentativas de equipar e janela de confirmação |
| `equip_N_ja_equipado_*`, `equip_N_assumido_*`, `equip_sem_confirmar_*`, `equip_falhou_*`, `equip_item_errado*` | decisões/falhas na equipagem |
| `equip_lobby_antes{N}_*`, `equip_tap{N}_n{K}_*`, `equip_nao_abriu_*`, `equip_tela_outra_*`, `equip_p{N}_final_*` | entrada na tela de equipamentos e resultado final de cada personagem |

O painel mostra o estado atual (`COMBATE | lutas: N`, tempo desde o último especial, passo da busca etc.).

---

## 11. Limitações e pontos ainda em ajuste (segundo o próprio código)

- **Validação:** as fases 2, 3 e 4 têm implementação no código, mas os limites de arte (60 e 30), a antecipação do ping pong e o ritmo de lotes ainda precisam de medição no aparelho. O último Build Output mostrado falhou porque a conexão com o Kotlin daemon foi perdida; isso não confirma nem descarta erros de compilação no fonte.
- **Ping pong:** a previsão do cruzamento é registrada em `pingpong_acertos.txt`, mas o instante em que o jogo registra o toque não é observável; o acerto final é conferido na tela do jogo.
- **Barra de encher:** calibrada a partir de um único print. Para evitar disparos falsos no meio da luta, exige confirmação em 2 leituras e não toca às cegas; se ainda assim atrapalhar, desligue `barGame`.
- **Seta:** não dispara por forma, só pelo texto do evento; se a direção falhar, assume "para baixo".
- O jogo é assumido em **paisagem**, e as coordenadas de toque/leitura são proporções fixas da tela calibradas em prints do autor (podem exigir ajuste em outros aparelhos/resoluções). A arte das cartas só vale para a mesma proporção de tela das fotos de referência.
- Os times, versões de cartas e equipamentos estão **fixos no código** (`TeamSwap`).
- Nomes de botões dependem do idioma do jogo (português) e da leitura do OCR.
- `team` e `fights` não são reiniciados ao pausar e continuar (ver seção 6); só `fights` é zerado na primeira vez que o bot inicia, e `team` nunca.
- Reduzir `captureScalePct` abaixo de 100 faz o OCR enxergar letra menor; confira a montagem de times antes de usar.
- A montagem repete as varreduras de personagem enquanto faltarem alvos; a busca de equipamentos alterna os sentidos da rolagem até localizar os itens e confirmar os quadrados. A cada 5 s ela faz uma conferência reforçada dos três quadrados e sai da aba se estiverem válidos. Sem pausa/cancelamento do processo e sem confirmar os quadrados, uma busca sem progresso pode continuar indefinidamente.
- A coleta de quadros (`record`) pode custar CPU durante o ping pong.
- Código sem uso: `Heur.ballFast()` (ver seção 9).
- O uso de automação pode contrariar as regras do jogo; é decisão do dono do projeto.
