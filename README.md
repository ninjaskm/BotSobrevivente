# Bot Sobrevivente

App Android (Kotlin) que joga sozinho o modo "Sobrevivente" de um jogo de cartas/combate em **paisagem**. O bot **lê a tela** e age por toques e arrastos, sem marcar botões na mão e sem calibração:

- **Texto** (OCR offline, Google ML Kit): reconhece botões e telas (SOBREVIVENTE, LUTAR, AVANÇAR, CONTINUAR, FECHAR, PARAR E RECEBER, EQUIPE, CRIE SUA EQUIPE, GIRE A RODA etc.) e os textos dos mini-eventos.
- **Forma/cor** (OpenCV 4.9, sem template): detecta combate (barras azuis + slots roxos), brilho/rosa/X vermelho dos slots de poder, círculo da bolinha, círculo da barra de encher e seta de arrastar.
- **Mãos**: um Serviço de Acessibilidade executa os gestos (toque, rajada de toques, arrasto, rolagem, 2 dedos).

> Esta documentação foi escrita a partir do código-fonte. Valores entre parênteses (coordenadas, tempos, limites) são os que estão no código hoje.

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
| Gradle (wrapper) | 8.9 |
| Dependências | `androidx.core:core-ktx 1.15.0`, `androidx.activity:activity-ktx 1.9.3`, `kotlinx-coroutines-android 1.9.0`, `org.opencv:opencv 4.9.0`, `com.google.mlkit:text-recognition 16.0.1` |
| Módulos | `:app` (único) |
| Nome exibido | "Bot Sobrevivente" |

### Permissões (`AndroidManifest.xml`)
- `SYSTEM_ALERT_WINDOW`: painel flutuante.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`: serviço de captura de tela.
- `POST_NOTIFICATIONS`: notificação do serviço em primeiro plano (pedida ao abrir o app).
- `WRITE_SECURE_SETTINGS`: **não é concedida na instalação**; só via adb (`conceder_permissao_uma_vez.bat`). Permite ao app ligar a própria acessibilidade ao abrir.
- `allowBackup="false"`.

### Componentes declarados
- `MainActivity` (launcher).
- `BotService` (não exportado, `foregroundServiceType="mediaProjection"`).
- `BotAccessibilityService` (protegido por `BIND_ACCESSIBILITY_SERVICE`; `canPerformGestures=true`, evento `typeWindowStateChanged`, `notificationTimeout=100`).

---

## 2. Estrutura do projeto

```
BotSobrevivente/
├─ app/src/main/java/com/matheus/bot/
│  ├─ MainActivity.kt            tela de configuração e passos de preparação
│  ├─ BotService.kt              serviço em primeiro plano: captura + painel flutuante + Brain
│  ├─ Brain.kt                   máquina de estados: menus, combate, roleta, minigames
│  ├─ TeamSwap.kt                montagem do time 1, troca para o time 2 e equipamentos
│  ├─ Heur.kt                    análise de imagem com OpenCV (Snap e Heur)
│  ├─ TextVision.kt              OCR (ML Kit) + busca de palavras na tela (Screen/Word)
│  ├─ Capture.kt                 captura de tela via MediaProjection
│  ├─ BotAccessibilityService.kt gestos (toque, rajada, arrasto, rolagem, 2 dedos)
│  ├─ BotConfig.kt               configurações (SharedPreferences "bot")
│  └─ Ui.kt                      miniButton() dos painéis flutuantes
├─ app/src/main/res/             strings.xml e accessibility_service_config.xml
├─ ativar_acessibilidade.bat     liga a acessibilidade via adb
├─ conceder_permissao_uma_vez.bat concede WRITE_SECURE_SETTINGS via adb
└─ CONTEXTO.md                   contexto fixo do projeto para IA
```

---

## 3. Como rodar

1. Abra o projeto no Android Studio, faça o Sync e rode (Run) no celular (arm64).
2. No app, na ordem dos botões:
   1. **"1) Permitir sobreposição de tela"** (abre `ACTION_MANAGE_OVERLAY_PERMISSION`).
   2. **"2) Abrir acessibilidade"**: tenta abrir direto a página do serviço "Bot Sobrevivente" (SubSettings do Settings); se o Settings recusar, abre a lista geral e mostra um aviso com o caminho ("Apps baixados" > "Bot Sobrevivente" > "Usar Bot Sobrevivente").
   3. **"3) Iniciar captura"**: pede a permissão de captura de tela (MediaProjection) e inicia o `BotService`.
3. O texto no topo do app mostra `Sobreposição: OK/FALTA` e `Acessibilidade: OK/FALTA` (atualiza ao voltar para o app e 1,5 s depois).
4. Com a captura ativa aparece o painel no canto superior esquerdo: status, **▶ Play / ⏸ Pausa** (começa **pausado**) e **■ Sair**.
5. Deixe o jogo na tela inicial ou no lobby e aperte ▶ Play.

### Atalhos via adb (celular por USB)
Os dois `.bat` usam o adb em `C:\Users\teteu\AppData\Local\Android\Sdk\platform-tools\adb.exe` (caminho fixo no arquivo; ajuste se mudar).

- `conceder_permissao_uma_vez.bat`: executa `pm grant com.matheus.bot android.permission.WRITE_SECURE_SETTINGS`. Rodar **uma vez** (de novo só se desinstalar o app). Se der `SecurityException`, ligar "Depuração USB (configurações de segurança)" nas opções do desenvolvedor. Depois disso o app liga a própria acessibilidade em todo `onResume` (se ela estiver desligada).
- `ativar_acessibilidade.bat`: acrescenta o serviço em `enabled_accessibility_services` (preservando os outros) e põe `accessibility_enabled=1`. Útil quando não se concedeu a permissão acima.

---

## 4. Configurações (`BotConfig`)

Salvas em `SharedPreferences("bot")`, editadas na `MainActivity` (botão **Salvar configurações**). O `Brain` lê a cada uso, então valores salvos valem sem reiniciar a captura.

| Campo | Padrão | Significado |
|---|---|---|
| `fightTarget` | 0 | Depois de N lutas, usa "Parar e receber" no lobby. `0` = só para quando o time morrer. |
| `power1AfterSec` | 0 | Usa o poder 1 se ficar N s sem usar o poder 2 (e ele estiver carregado). `0` = nunca por tempo. |
| `tossDelayMs` | 600 | Atraso (ms) antes do toque do mini-evento **PING PONG** (texto do jogo: "TOQUE PARA ARREMESSAR"). |
| `comboSwipes` | 8 | Arrastos seguidos por rodada do combo. |
| `comboGapMs` | 0 | Pausa (ms) entre arrastos do combo. `0` = o mais rápido possível. |
| `powerPct` | 55 | Brilho mínimo (%) do slot para considerar o poder carregado (rótulo na tela: "Brilho da ult", mas vale para os 3 slots). |
| `block` | `false` | Bloqueio com 2 dedos ao fim de cada rodada do combo. |
| `ult` | `false` | Usa o poder 3 (ult). |
| `barGame` | `true` | Liga o minigame de encher a barra. Desligue se ele disparar em falso e atrapalhar o combo. |

Campos numéricos vazios/inválidos voltam ao padrão ao salvar.

---

## 5. Arquitetura em tempo de execução

```
MainActivity ──(MediaProjection)──► BotService
                                      ├─ Capture (VirtualDisplay + ImageReader, paisagem, RGBA_8888)
                                      ├─ TextVision (ML Kit)
                                      ├─ Painel flutuante (status, Play/Pausa, Sair)
                                      └─ Brain ──► BotAccessibilityService.instance (gestos)
                                           ├─ Heur / Snap (OpenCV)
                                           └─ TeamSwap (montagem de times / equipamentos)
```

### BotService
- Em `onStartCommand` (só se ainda não houver projeção): cria o canal/notificação "Bot Sobrevivente ativo", entra em **primeiro plano antes** de obter o `MediaProjection` (exigência do Android 14+), registra callback (`onStop` → `shutdown()`), inicia o OpenCV (`OpenCVLoader.initLocal()`).
- Captura sempre em **paisagem**: `w = max(largura, altura)`, `h = min(...)` (ex.: 2400x1080), com a densidade da tela.
- Cria o `Brain` com a pasta de debug `getExternalFilesDir(null)/debug` e o painel. O painel é **escondido (INVISIBLE)** durante a montagem de times, porque aparece na captura e cobriria o quadrado 1.
- `START_NOT_STICKY`. `shutdown()` para o Brain, remove o painel, libera a captura e a projeção e encerra o serviço.

### Capture
`VirtualDisplay` com `ImageReader` (2 buffers). `latest()` devolve o último quadro disponível (se não há novo, repete o anterior) já recortado para remover o preenchimento de linha (`rowStride`).

### TextVision / Screen / Word
- OCR do ML Kit (modelo embutido, offline). Imagens com mais de 1280 px de largura são reduzidas para o OCR; as posições voltam em **proporção da tela (0..1)**.
- Texto normalizado: **MAIÚSCULAS, sem acento, sem símbolos** (`"Avançar!"` → `AVANCAR`). Guarda linhas e também cada elemento (palavra) separadamente.
- `read(bmp, l, t, r, b)` lê a tela toda ou só um recorte.
- `Screen.find/has/hasAny`: acham texto por "contém"; para chaves com 6+ letras aceitam 1 letra errada (distância de Levenshtein ≤ 1). Podem filtrar por região (`xMin..yMax`).

### BotAccessibilityService (as "mãos")
Singleton em `BotAccessibilityService.instance` (nulo = acessibilidade desligada). Gestos:

| Função | O que faz |
|---|---|
| `tap` | Toque (40 ms por padrão). |
| `tapBurst` | Até 10 toques no mesmo ponto em **um único gesto** (período 25 ms, sem sobreposição). Bem mais rápido que vários `tap`. |
| `swipe` | Arrasto simples. |
| `drag` | Segura parado (`holdMs`), move (`moveMs`) e **fica parado no destino** (`endHoldMs`, padrão 400 ms) antes de soltar; usa gestos encadeados (`continueStroke`). O "segurar" evita que o jogo leia como rolagem; a parada final evita que a carta caia no meio do caminho. |
| `dragVia` | Igual ao `drag`, passando por um ponto intermediário (ex.: subir para sair da faixa da lista). |
| `scrollStop` | Rolagem sem inércia: arrasta e fica parado no fim antes de soltar (a lista anda exatamente a distância pedida). |
| `block` | Dois toques simultâneos em (60%, 45%) e (72%, 55%) da tela. |

### MainActivity
Interface montada por código (sem XML de layout): botões dos 3 passos, campos de configuração, caixas de seleção "Bloqueio (2 dedos)", "Usar poder 3 (ult)" e "Minigame da barra". Em `onResume` tenta `autoEnableAccessibility()` (precisa de `WRITE_SECURE_SETTINGS`): se o serviço não está na lista, adiciona; se ~1,5 s depois ele continua desconectado, **força o religamento** (tira da lista e põe de volta após 400 ms). O texto de status mostra `Sobreposição`, `Acessibilidade`, `Permissão p/ ligar sozinho` e, quando a acessibilidade não liga, o motivo (permissão faltando, erro lançado ou aguardando o sistema).

---

## 6. Brain: o ciclo do bot

O `Brain` roda um laço (`loop`) em coroutine (`Dispatchers.Default`). A cada volta: se pausado, espera 200 ms; se a acessibilidade caiu, avisa e **pausa**; pega o quadro, gera um `Snap` e decide **combate** ou **menu**.

### Estados (`Sc`)
`COMBATE, AVANCAR, CONTINUAR, FECHAR, CONFIRMA, LOBBY_MORTO, LOBBY_VIVO, ROLETA, HOME, EQUIPE_VAZIA, DETALHE, DESCONHECIDA`.

### Fluxo geral
```
HOME (SOBREVIVENTE) → lobby (LUTAR) → roleta → COMBATE → AVANÇAR → CONTINUAR → lobby (LUTAR) → ...
```
- **Contagem de lutas** (`fights`) sobe ao **entrar** no estado AVANÇAR. É zerada em `start()`.
- **Meta de lutas**: no lobby com LUTAR visível, se `fightTarget > 0` e `fights >= fightTarget`, toca em **PARAR E RECEBER** em vez de LUTAR.
- **Confirmação do "Parar e receber"**: só age se o texto tiver `RECEBER` sem `EQUIPE` e algum aviso (`OPTAR`, `SAIR DO MODO` ou `TODAS AS RECOMPENSAS`). Toca em RECEBER e marca `stopAfter`.
- **Depois de `stopAfter`**: telas desconhecidas são tratadas como o "Coringa/Última Risada" (procura `ACEITAR`, `COLETAR`, `RESGATAR`, `CONTINUAR`, `RECEBER`; se não achar, 1 toque no centro a cada 3 s). Ao voltar para a HOME, mostra "Ciclo terminado." e **pausa**.
- **Time morto**: sem botão LUTAR no lobby; confirma por **3 leituras seguidas** (`deadVotes`). No time 1 chama a troca para o time 2 (seção 8); se a troca falhar, pausa com a mensagem do erro. Com o time 2 morto, "Fim do ciclo. Pausado.". A variável `team` começa em 1 e vira 2 após a troca.
- **Tela desconhecida**: salva `debug/desconhecida_*` na primeira leitura e continua lendo a tela (OCR geral e faixa de cima) a cada volta, agindo normalmente assim que reconhecer. **Não gira a roleta às cegas.** Com mais de 120 leituras seguidas, **pausa**.

### Ordem de decisão nos menus (`menuTick`)
1. Confirmação de "Parar e receber" (acima) → `CONFIRMA`
2. `FECHAR` (popup "NOVA CARTA", embaixo) → `FECHAR`
3. `AVANCAR` (y ≥ 0,70) → `AVANCAR`
4. `CONTINUAR` (y ≥ 0,75) → `CONTINUAR`
5. **Detalhes do personagem** (abriu sem querer): `EQUIPAMENTO` + (`SUPORTE`, `MELHORIAS`, `NIVEL ATUAL` ou `CRITICO`) → `DETALHE`: toca no meio do modelo 3d (16% da largura, 40% da altura) para fechar, a cada ~0,9 s; após 10 toques sem fechar, salva `debug/detalhe_nao_fechou_*` e **pausa**. A montagem de times trata essa tela por conta própria.
6. `RECEBER` + `EQUIPE` → lobby (LUTAR/morto)
7. `GIRE`, `SORTEAR`, `RODA` ou `RECOMPENSAS` (parte de cima) → roleta
8. `SOBREVIVENTE` + (`BATALHA`/`COLECAO`/`LOJA`) → `HOME` (toca em SOBREVIVENTE)
9. `CRIE SUA EQUIPE` → `EQUIPE_VAZIA`: esconde o painel, monta o time 1 (seção 8)
10. Qualquer outra coisa → desconhecida (com segunda leitura da faixa de cima, `0,15..0,85 × 0..0,32`, para achar o texto cortado da roleta; ali `GIRE`/`RODA`/`SORTEAR`/`RECOMPENSAS`, ou `PARTIDA` no topo (y ≤ 0,25), contam como roleta)

### Roleta
Arrasto rápido para a **esquerda** de (74%, 62%) a (20%, 62%) em 200 ms, feito **2 vezes por segurança** (o 2º ~0,7 s depois do 1º, e só se o combate ainda não apareceu). Espera até 4,5 s (1,2 s se os dois gestos falharam), saindo assim que o combate aparecer.

### Detecção de combate
- `isCombat()` (estrito): barras de vida azuis no topo (`hud > 0,02`) **e** slots roxos embaixo (`purple > 0,10`).
- Evidência fraca também vale: já estando em combate, basta `isCombatLoose()` (`hud > 0,008` **ou** `purple > 0,04`); ou, em tela desconhecida, `purple > 0,25`.
- Com evidência fraca, de 1 em 1 s o OCR confere se não é um menu de verdade (AVANÇAR, FECHAR...).
- Sem HUD no meio da luta (animações/efeitos), por até 25 voltas continua batendo (combo) e só pergunta ao OCR de ~1 em 1 s se surgiu um botão.

### Combate (`combatTick`)
Ordem de prioridade a cada volta:
1. **Mini-evento** por texto (seção 7).
2. **Barra de encher por forma** (só na janela de 8 s depois de usar um poder, a cada ~0,3 s).
3. **Poderes**, tocando no centro de cada slot (y = 90,5% da altura): poder 3 em x = 13% (só se `ult`), poder 2 em x = 23,6%, poder 1 em x = 34,3%.
   - Slot "carregado" = **sem X vermelho** e (rosa do número > 4% **ou** brilho > `powerPct`).
   - Ordem: ult (se ligada) → poder 2 → poder 1 se o poder 2 está com X vermelho → poder 1 por tempo (`power1AfterSec`).
   - **Trava anti-loop**: tocar no mesmo poder mais de 3 vezes em 1,5 s faz esse poder ficar de lado por 2,5 s (evita ficar só tocando num poder que não dispara).
4. **Combo** em spam.

Usar qualquer poder abre a **janela de minigame de 8 s** (`markPower`).

### Combo
`comboSwipes` arrastos horizontais: y aleatório entre 38% e 65% da altura, início em x 56–64%, comprimento 14–20% da largura, duração 40–59 ms. Entre os arrastos: continua lendo o texto do evento (e sai na hora se achar um) e, a cada 2 arrastos, confere os poderes. No fim da rodada, se `block` estiver ligado, faz o bloqueio de 2 dedos (350 ms).

---

## 7. Mini-eventos (minigames)

### Detecção
O texto do canto superior direito (região `0,50..0,95 × 0,15..0,33`) é lido por OCR **em segundo plano** (no máximo uma leitura por vez; intervalo de 90 ms na janela de 8 s após um poder, 300 ms no resto). O resultado vale por 1,5 s. `Brain.miniOf` é o **único** lugar que decide qual minigame é, nesta ordem:

1. contém `ARREMESS` (o jogo mostra "TOQUE PARA ARREMESSAR") → **PING PONG**
2. contém `PASSE` ou `DEDO` → **SETA**
3. contém `TOQUE` e `RAPIDO` → **TOQUE**
4. só na janela pós-poder: `ENCHER` **e** `BARRA` juntas (e `barGame` ligado) → **BARRA**. Palavras soltas (`CARREG`, `MARTEL`, `TOQUE`...) não disparam mais, por dar falso positivo no meio da luta.

Os textos específicos valem sempre (podem vir por ícone); os genéricos só na janela.

Bloqueio após um evento: se o minigame **não existia** (forma não apareceu), 3 s; se encadeou outro ou foi ARREMESSO, 0; senão 400 ms.

### Cada minigame
| Minigame | Ação |
|---|---|
| **PING PONG** (texto do jogo: "TOQUE PARA ARREMESSAR") | Espera `tossDelayMs` e toca em (56,5%, 48%); espera 1,2 s. Nas 2 primeiras vezes grava 24 quadros (2,4 s) em `debug/pingpong*_NN_*`. O momento certo ainda não está mapeado. |
| **SETA** ("PASSE O DEDO RÁPIDO") | Por até 8 s: acha a seta azul (`arrowFast`), pega o centro e faz 3 arrastos rápidos (55 ms) ao longo dela. Se não achou a seta na 2ª tentativa, arrasta para baixo no meio (x 58,4%). Termina quando a seta some (~20 leituras). Sai se aparecer o texto de ARREMESSAR (encadeia com o PING PONG). Só começa por **texto** (o gatilho por forma foi removido por falso positivo com brilhos azuis do cenário). |
| **TOQUE** (bolinha) | Espera até 1,5 s a forma aparecer: se for o círculo da barra → BARRA; se for um círculo solto → bolinha. Na bolinha: rajadas de 6 toques no centro até ela sumir por 5 leituras (máx. 9 s; se nunca viu, desiste em 2,5 s). |
| **BARRA** (encher a barra) | Dispara por texto (`ENCHER`+`BARRA`) ou pela **forma** (círculo + barra vertical clara), só na janela de 8 s depois de um poder. A forma precisa ser **confirmada**: aparecer em 2 leituras seguidas (<0,7 s) no mesmo ponto (±3%), senão o combo segue normal. Por texto, espera só 0,6 s a forma; **sem forma não toca em nada** (antes tocava 4 s às cegas). Com a forma: rajadas de 6 toques no círculo até ele sumir (conferência a cada 150 ms, máx. 9 s). Depois de um minigame, o gatilho por forma fica bloqueado por 1,5 s. Pode ser desligado em `barGame`. |

---

## 8. TeamSwap: times e equipamentos

Tudo é guiado por texto: cada passo confere a tela antes de agir; em qualquer divergência salva um print em `debug/` e devolve a mensagem de erro (o Brain **pausa** e a mostra no painel).

### Fluxos
- **`createTeam1`** (tela "CRIE SUA EQUIPE", time vazio): arrasta os 3 personagens do time 1 → toca no botão "CRIE SUA EQUIPE" (x fixo 65%, até 3 tentativas, espera o lobby) → equipa os 3 personagens.
- **`run`** (time 1 morto → time 2): toca em EQUIPE → tela de escolha → arrasta os 3 personagens do time 2 → toca em CONFIRMAR ALTERAÇÕES → espera voltar ao lobby → equipa os 3.

### Times configurados no código
Posição na lista = quadrado de destino (1, 2, 3). Há regras para distinguir versões da mesma carta (subtítulo certo/errado e nível mínimo lido no canto da carta).

**Time 1**
| Quadrado | Personagem (chave de busca) | Versão / regra | Equipamentos (quadrados 1, 2, 3) |
|---|---|---|---|
| 1 | Flash Reverso (`REVERSO`) | nível ≥ 30 | Rifle Personalizado; Armadura Peitoral LexCorp V1; Traje de Hipervelocidade Revestido |
| 2 | Apocalipse (`APOCALIPSE`) | subtítulo "Contenção", nível ≥ 40 | Bastões de Escrima Energizados; Estrela da Manhã de Metal Enésimo; Kama do Reino Inferior |
| 3 | Nevasca (`NEVASCA`) | rejeita subtítulo "Regime", nível ≥ 40 | Placa Peitoral Divina do Quarto Mundo; Maça Divina do Quarto Mundo; Capacete Divino do Quarto Mundo |

**Time 2**
| Quadrado | Personagem (chave de busca) | Versão / regra | Equipamentos (quadrados 1, 2, 3) |
|---|---|---|---|
| 1 | Batman (`BATMAN`, exato) | subtítulo "Ninja", nível ≥ 50, estrito (nunca aceita subtítulo ilegível), rejeita subtítulo "Foice"/"Mart" | Placa Peitoral Divina do Quarto Mundo; Maça Divina do Quarto Mundo; Facas de Adepto da Liga dos Assassinos |
| 2 | Cavaleiro Arkham (`CAVALEIRO`) | nível ≥ 50 | Rifle Personalizado; Kama do Reino Inferior; Estrela da Manhã de Metal Enésimo |
| 3 | Caçador de Marte (`DE MARTE`) | subtítulo "Noite"/"Densa", nível ≥ 50, estrito | Bastões de Escrima Energizados; Armadura Peitoral LexCorp V1; Traje do Caçador de Marte |

### Arrastar personagens/equipamentos (`pickAll`)
1. **Analisa parado** a tela; se um item que falta está visível na lista de baixo, arrasta para o quadrado (segura 600 ms, move 700 ms, para 400 ms).
2. Se não achou, rola a lista em **rodadas**: esquerda → direita → esquerda (`phaseDirs`), cada uma por até 50 s (`phaseMs`) ou até a lista parar de andar. Cada passo rola ~1,3 carta e solta parado. No fim de cada rodada para 1,2 s e analisa de novo. A partir da 2ª rodada aceita carta com subtítulo ilegível (exceto alvos `strict`: Batman e Caçador de Marte). O `notSub` de um alvo aceita alternativas separadas por `|`.
3. Depois de arrastar confere se a carta saiu da lista. Personagens: até 3 tentativas. Se ainda houver outro à vista arrasta também; senão **continua rolando de onde parou**.
4. Sem achar após as 3 rodadas, desiste com mensagem listando o que faltou.

**Equipamentos** têm particularidades:
- Usa leitura reforçada (tela inteira + duas metades da lista, para o OCR ler texto pequeno sobre arte brilhante) e palavras alternativas por item.
- Item que o próprio personagem já usa **some da lista**; se aparece no quadrado, considera equipado.
- A cada tentativa usa um **gesto diferente** (4 variantes): arrastar direto; subir e depois ir ao quadrado; tocar no item e depois no quadrado; tocar no quadrado e depois no item. Guarda `debug/equip_tentativa*`.
- Se o jogo abrir uma janela de confirmação, toca em `SIM`, `CONFIRMAR`, `EQUIPAR`, `TROCAR`, `MOVER`, `SUBSTITUIR` ou `OK` (`debug/equip_janela_*`).
- Com o quadrado ainda vazio após 4 gestos, falha com aviso; com o quadrado ocupado e o item ainda na lista após 2 arrastos, aceita e salva `equip_sem_confirmar`.
- **Item errado no quadrado**: o bot conhece as chaves de todos os equipamentos dos dois times (`allGearKeys`). Se um quadrado mostra o nome de OUTRO equipamento conhecido (ex.: o Traje de Hipervelocidade do Flash no quadrado do Kama do Cavaleiro Arkham), isso **nunca** conta como feito: não vale o "quadrado ocupado" nem o "assumido"; o item certo é arrastado por cima (`debug/equip_item_errado*`). Após 8 tentativas no mesmo quadrado, falha com aviso.
- **Ponto de pegada**: ao pegar um equipamento na lista, ignora linhas de OCR que juntam o nome de duas cartas vizinhas (o centro delas cai entre as cartas e pegava a carta errada); nesse caso usa o centro da palavra-chave.
- Para entrar na escolha de equipamentos de cada personagem, toca na carta dele no lobby (pelo nome; se não abrir, varia o ponto/tipo de toque, até 8 voltas) ou, se abrir a tela de detalhes, no 1º quadrado de equipamento (x 66,6%, y 50%). Sai por `CANCELAR`/`VOLTAR` (sem tocar no CANCELAR do próprio lobby).

---

## 9. Análise de imagem (`Heur.kt`)

O `Snap` reduz o quadro para **800 px de largura** e guarda HSV e cinza. Regiões em proporção da tela:

| Análise | Região | Critério |
|---|---|---|
| `hud()` | x 30–80%, y 2,5–11% | fração de azul (barras de vida) |
| `purple()` | x 6–40%, y 88–96% | fração de roxo (slots de poder) |
| `slotV(i)` | y 89–95% | brilho médio do slot |
| `slotPink(i)` | y 87–94,5% | rosa/magenta do número (poder carregado) |
| `slotBlocked(i)` | y 87–94,5% | **X vermelho** (vermelho nos terços esquerdo e direito, sem magenta) = poder indisponível |
| `ball()` | x 40–95%, y 15–90% | `HoughCircles` (raio 30–70), ignora o círculo da barra |
| `barCircle()` | x 48–67%, y 52–76% | círculo pequeno (raio 16–34) **com barra vertical clara logo acima** |
| `arrow()` / `arrowFast()` | x 35–85%, y 15–92% | maior mancha azul comprida (proporção ≥ 1,5, área ≥ 600); direção pelo lado mais cheio |

Slots (x): poder 3 = 8–18%, poder 2 = 18,5–28,7%, poder 1 = 29,3–39,5%.
`Heur.power()` é a versão rápida da leitura dos 3 slots (recorta só a faixa de baixo) e devolve 6 valores: carregado e X vermelho de cada slot.

---

## 10. Depuração

Prints salvos em `Android/data/com.matheus.bot/files/debug/` (nome `tag_timestamp.jpg`):

| Arquivo | Quando |
|---|---|
| `desconhecida_*` | tela não reconhecida |
| `detalhe_nao_fechou_*` | a tela de detalhes do personagem não fechou após 10 toques |
| `evento_barra_*`, `barra_ok_*` | minigame da barra (até 3 de cada) |
| `seta_ok_*`, `seta_nao_achou_*` | minigame da seta (até 3 no total) |
| `pingpong{0,1}_NN_*` | 24 quadros do ping pong (2 primeiras vezes) |
| `quadradoN_ja_cheio_*` | quadrado já cheio sem o bot ter arrastado |
| `time1_nao_li_quadrados_*`, `time2_nao_li_quadrados_*` | conferência informativa dos quadrados |
| `troca_falha_*` | qualquer falha da montagem/troca |
| `equip_tentativa{N}_{CHAVE}_*`, `equip_janela_*` | tentativas de equipar e janela de confirmação |
| `equip_N_ja_equipado_*`, `equip_N_assumido_*`, `equip_sem_confirmar_*`, `equip_falhou_*`, `equip_item_errado*` | decisões/falhas na equipagem (`equip_item_errado*` = quadrado com item de outro personagem) |
| `equip_lobby_antes{N}_*`, `equip_tap{N}_n{K}_*`, `equip_nao_abriu_*`, `equip_tela_outra_*`, `equip_p{N}_final_*` | entrada na tela de equipamentos e resultado final de cada personagem |

O painel mostra o estado atual (`COMBATE | lutas: N`, tempo desde o último especial, passo da busca etc.).

---

## 11. Limitações e pontos ainda em ajuste (segundo o próprio código)

- **Ping pong**: o marcador ainda não foi mapeado; o bot toca após um atraso fixo.
- **Barra de encher**: calibrada a partir de um único print. Para evitar disparos falsos no meio da luta, exige confirmação em 2 leituras e não toca às cegas; se ainda assim atrapalhar, desligue `barGame`.
- **Seta**: não dispara por forma, só pelo texto do evento; se a direção falhar, assume "para baixo".
- O jogo é assumido em **paisagem**, e as coordenadas de toque/leitura são proporções fixas da tela calibradas em prints do autor (podem exigir ajuste em outros aparelhos/resoluções).
- Os times, versões de cartas e equipamentos estão **fixos no código** (`TeamSwap`).
- Nomes de botões dependem do idioma do jogo (português) e da leitura do OCR.
- Em `Brain.start()` a variável `team` **não** é reiniciada: depois de uma troca para o time 2 numa sessão, um novo Play na mesma sessão continua considerando o time 2.
- Só há uma fila de rodadas de rolagem por busca; listas muito longas podem exigir mais de 3 rodadas.
