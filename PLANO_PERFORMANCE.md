# PLANO_PERFORMANCE.md — Bot Sobrevivente mais rápido e mais certeiro

Documento de execução. Quem for implementar (pessoa ou IA) segue as fases na ordem, uma por commit, e só avança quando os **critérios de aceite** da fase atual forem cumpridos.
Base: código real em `app/src/main/java/com/matheus/bot/`. Onde o `README.md` diverge do código, vale o código.

## Status (27/11/2026)

| Fase | Situação |
|---|---|
| 0 — Baseline e coleta | **Código pronto.** Falta você rodar uma luta e guardar o `perf.txt` (baseline). |
| 1 — Testes offline | **Removida a pedido.** Os ajustes de limiar passam a ser conferidos no aparelho (ver fase 2) e pelos quadros do modo de coleta. |
| 2 — Percepção em paralelo | **Código pronto.** Falta validar no celular (critérios abaixo). |
| 3 — Ping pong e latência do toque | **Código pronto.** Falta validar no celular. |
| 4 — Personagem pela arte (passo A, OpenCV) | **Código pronto.** Falta validar no celular e calibrar os limites com `debug/arte_scores.txt`. |
| 5 a 6 | Não iniciadas. |
| 7 — Higiene e robustez | **Parcialmente concluída.** Logs de sessão e atualização do README já existem; falta definir o ciclo de `team`/`fights` para uma nova execução dentro do mesmo serviço. |

**Nada foi compilado nem testado no celular.** O primeiro passo é abrir no Android Studio, dar o Sync e corrigir qualquer erro de compilação.

### Revisão estática do código (27/11, depois das fases 0, 2 e 3)

Li `Brain`, `Heur`, `TextVision`, `TeamSwap`, `BotService` e o `build.gradle.kts` e conferi o que dava para conferir sem compilar:
- [x] **Erro de compilação corrigido:** `BotService.onStartCommand` já tinha `val ch = "bot"` (canal da notificação) e a captura reduzida declarou outro `val ch` no mesmo escopo. Renomeado para `capW`/`capH`.
- [x] **Tela ligada:** o painel flutuante agora usa `FLAG_KEEP_SCREEN_ON`. Sem isso a tela apaga durante um ciclo longo e a captura para.
- [x] **Vigia mais leve:** cada quadro novo custa uma cópia de ~10 MB. Fora da janela de minigame o vigia lê ~30 quadros/s (antes ~80); dentro da janela, o ritmo máximo.
- [x] **Log de sessão** (`Session.kt` → `debug/sessao_<inicio>.txt`, 1 gravação por minuto, sobrescreve): lutas, ritmo (lutas/min), tempo em combate, minigames `ok`/`falso`/`forma`, poderes usados e travados, pausas, tela desconhecida, time morto. É a métrica para comparar versões.
- [ ] Conferido por leitura, mas **sem compilar**: o resto das edições das fases 2 e 3 não mostrou outro erro de tipo ou de escopo. Só o Gradle confirma.

Pendências que só você consegue fechar (o código não resolve sozinho):
1. Compilar, rodar uma luta e guardar `perf.txt` e `sessao_*.txt`.
2. Rodar com `Coletar quadros` ligado para formar o `dataset/` (necessário para as fases 4 e 5).
3. Rodar 10 ping pongs e ler `pingpong_acertos.txt`.
Sem esses três arquivos qualquer "otimização" nova é chute.

---

## 0. Regras de execução (valem para todas as fases)

1. **Medir antes e depois.** Nenhuma mudança de comportamento entra sem número do `debug/perf.txt` antes e depois.
2. **Uma fase = um commit** (ou uma branch), com a mensagem citando a fase. Se piorar, reverte.
3. **Respeitar o `CONTEXTO.md`:** o bot reconhece a tela sozinho (texto por OCR e forma/cor); nada de marcar botões na mão nem calibração manual. Modelos treinados (fases 5 e 6) cumprem isso, pois reconhecem a tela.
4. **Não inventar número.** Todo valor marcado como *meta* é uma estimativa a confirmar; o que vale é o medido.
5. **Fotos de referência:** usar as versões reduzidas primeiro (`C:\Users\teteu\Downloads\fotos`, ver `CONTEXTO.md`).
6. Toda fase termina com Run no aparelho e uma luta completa.

---

## 1. Diagnóstico (pelo código antes das fases 0 a 3)

| # | Problema | Situação |
|---|---|---|
| D1 | O OCR decide quase tudo (botões, texto de evento, nome de carta); é lento e erra letra pequena sobre arte brilhante. | Aberto (fases 4 a 6). |
| D2 | Cada quadro novo vira um `Bitmap` de ~10 MB. | **Parcial:** captura em % da tela (`captureScalePct`), padrão 100. Falta medir se compensa. |
| D3 | Percepção e ação rodam em série; poder só era lido a cada 2 arrastos. | **Feito (fase 2):** vigia em paralelo. |
| D4 | Cada arrasto do combo era um `dispatchGesture` separado. | **Feito (fase 2):** `swipeBatch`. |
| D5 | Limiares de cor calibrados com poucos prints. | Aberto. O modo de coleta (fase 0) junta os quadros para reajustar depois. |
| D6 | Atraso real do toque desconhecido; `pingLeadMs` chutado. | **Feito (fase 3):** medição e autocalibração opcional. |
| D7 | O nome da carta é a única identidade do personagem. | Aberto (fase 4). |
| D9 | Higiene (`tossDelayMs` morto, `pingLeadMs` sem campo, `debug/` sem limite). | **Feito** nos itens de configuração e limpeza; o reset de `team`/`fights` segue na fase 7. |

---

## 2. Metas (a confirmar com medição)

| Métrica | Hoje (estimado) | Meta |
|---|---|---|
| Reação ao minigame (aparecer até o 1º gesto útil) | ~0,5 a 1 s | ≤ 0,15 s |
| Uso do poder 2 depois de carregar | até ~1 s | ≤ 0,15 s |
| Falso positivo de minigame no meio da luta | ocorre | ≈ 0 por luta |
| Ping pong | sem medida | ≥ 9 acertos em 10 |
| Montagem de time | rodadas de até 50 s cada | ≤ 1 rodada de rolagem na maioria dos casos |
| Versão de carta errada num quadrado | ocorre | 0 em 100 trocas |

---

## 3. Fases

### Fase 0 — Baseline e coleta de dados — CÓDIGO PRONTO

Feito no código:
- [x] `Perf` ganhou `frame_age` (idade do quadro), `watch_power`, `gesture_overhead_burst`, `gesture_overhead_batch` e as medianas recentes no cabeçalho de cada bloco do `perf.txt`.
- [x] `tapBurst` mede o próprio atraso (etiqueta separada, para não misturar com o `gesture_overhead` dos toques simples que alimenta o ping pong).
- [x] **Modo de coleta** (`BotConfig.record`, caixa "Coletar quadros do combate"): grava em `debug/dataset/` até 4 quadros por segundo, `f_<horario>.jpg` (1280 px) mais `f_<horario>.json` (estado, minigame, texto do evento, hud, roxo, brilho/rosa/X de cada slot, poderes lidos). Limite de 2000 arquivos e 400 MB. Roda em outra thread; pode custar um pouco de CPU durante o ping pong.
- [x] **Limpeza:** `debug/` limitado a 500 arquivos / 300 MB (apaga os mais antigos; não mexe em `perf*`, `pingpong_acertos*`, `sessao*` nem em `dataset/`); `perf.txt` vira `perf_old.txt` ao passar de 1 MB.

Falta você fazer:
- [ ] Rodar uma luta completa (com pelo menos 1 minigame de cada tipo) e copiar `Android/data/com.matheus.bot/files/debug/perf.txt` para `docs/perf_baseline.txt`. **Faça isto com a versão ANTERIOR ao commit das fases 2 e 3, ou com `Arrastos por lote = 1`,** para o baseline ser comparável.
- [ ] Rodar uma ou duas lutas com a coleta ligada e conferir se `dataset/` junta quadros de poder carregado, com X, vazio e de cada minigame. O ideal é ter pelo menos 300 quadros variados antes da fase 5.

Aceite: `perf_baseline.txt` guardado; `dataset/` com quadros variados.

---

### Fase 1 — Testes offline — REMOVIDA

Retirada a pedido. Consequência: não há mais verificação automática dos limiares de `Heur.kt`. Cada ajuste de limiar é conferido no aparelho e, se necessário, pelos quadros e `.json` do `dataset/`.

---

### Fase 2 — Percepção em paralelo e combo interrompível — CÓDIGO PRONTO

Feito no código (`Brain.kt`, `BotAccessibilityService.kt`, `Capture.kt`):
- [x] **Vigia** (`Brain.watch`): coroutine própria que, em combate, lê cada quadro novo só com recortes pequenos. Publica os poderes (`Heur.power`) em `perceived`, mantém o OCR do texto do evento rodando (`scanEvent`) e confirma a forma da barra de encher (`barConfirmed`). Só percebe; quem age é o `Brain`.
- [x] **Combo interrompível:** o combo consulta o vigia entre os lotes de arrasto (leitura de variável) e sai na hora se há minigame, barra confirmada ou poder carregado. Se a leitura do vigia tem mais de 300 ms, lê o quadro mais recente.
- [x] **Arrastos em lote** (`swipeBatch`): vários arrastos num único gesto, `Arrastos por lote` (padrão 3, máximo 4; **1 = comportamento antigo**). Respeita `GestureDescription.getMaxStrokeCount()`.
- [x] **Captura mais barata:** `Tamanho da captura (%)` (padrão 100). Abaixo de 100 a captura encolhe e todos os gestos são convertidos para a tela real por `BotAccessibilityService.gestureScale`. Atenção: o OCR enxerga letra menor; antes de usar abaixo de 100, confira a montagem de time (equipamentos).
- [x] **Anti-flap:** a barra continua exigindo 2 leituras seguidas no mesmo ponto (agora feitas pelo vigia, sem esperar o combo); poder carregado age no 1º quadro, como antes, com a trava `usePower`.
- [x] `evBusy` virou `AtomicBoolean` (vários leitores chamam `scanEvent`) e `last`, `lastEvCheck` e `evBlockUntil` viraram `@Volatile`.
- [ ] Reutilizar o `Bitmap` entre quadros: **não feito de propósito.** Vários leitores guardam o quadro (OCR em segundo plano, vigia, ping pong); reutilizar o mesmo `Bitmap` traria corrida de dados. Reavaliar só se o `capture_copy` do baseline mostrar que o custo importa.

Como validar (no `perf.txt` e na luta):
- [ ] `watch_power` por quadro bem menor que `snap`; sem aumento de `capture_copy` com o vigia ligado.
- [ ] Em 20 minigames, o 1º gesto sai ≤ 0,15 s depois de o texto/forma aparecer (mediana). Compare `Arrastos por lote` 1 e 3.
- [ ] Poder 2 usado ≤ 0,15 s depois de carregar.
- [ ] Nenhum minigame disparado em falso no meio da luta (mesma taxa ou melhor que antes).
- [ ] Se `gesture_overhead_batch` for muito maior que o esperado, ou o combo ficar menos fluido, volte `Arrastos por lote` para 1 ou 2.

---

### Fase 3 — Ping pong e latência do toque — CÓDIGO PRONTO

Feito no código:
- [x] **Horário real do quadro:** `Capture.latestFrame()` devolve o quadro junto com o horário em que foi **capturado** (carimbo do `Image`, convertido para `currentTimeMillis`). O carimbo só é usado se parecer estar na base de `System.nanoTime()` (idade entre 0 e 1 s); a idade medida aparece no `perf.txt` como `frame_age`. Se não for confiável, o bot cai no horário de leitura, como antes.
- [x] **Antecipação:**
  - Manual (padrão): `pingLeadMs` continua sendo a antecipação TOTAL. Com o carimbo confiável, o bot desconta a idade mediana do quadro e usa o resto como atraso do toque. O comportamento total fica o mesmo, com menos oscilação.
  - Automático (caixa "Ping pong: antecipação automática"): usa a mediana do atraso do toque medido (`gesture_overhead`, só toques e arrastos curtos) + `Ajuste fino`. **Desligado por padrão**, porque o atraso medido inclui também a espera depois do toque e pode passar do atraso real até o jogo registrar. Ligue só depois de conferir o registro abaixo.
- [x] **UI:** `tossDelayMs` (campo morto) removido; `Antecipação do ping pong`, `Ajuste fino` e a caixa automática adicionados.
- [x] **Registro do acerto:** o toque sai em paralelo e o bot lê quadros por mais 600 ms. Cada ping pong escreve uma linha em `debug/pingpong_acertos.txt` com o modo, a antecipação usada, a idade do quadro, o atraso do toque medido, o que o modo automático teria usado, a velocidade e posição estimadas e **quando o marcador cruzou o centro de verdade** (`cruzouApos`, `erroPrevisao`).
  - `erroPrevisao > 0`: o marcador chegou depois do que o bot previu (toque cedo). `< 0`: chegou antes (toque tarde).
  - Limite do que isso mede: mostra se a **previsão do cruzamento** está certa, não em que instante o jogo registrou o toque. O acerto final continua sendo o resultado na tela do jogo.

Falta você fazer:
- [ ] Rodar 10 ping pongs e ler o `pingpong_acertos.txt`. Se `erroPrevisao` fica perto de 0, a previsão está boa; ajuste `pingLeadMs` (ou o `Ajuste fino`, no automático) pelo resultado no jogo.
- [ ] Conferir no `perf.txt` se `frame_age` aparece (carimbo válido). Se não aparecer, o modo é "manual+relogio" e funciona como antes.
- **Regra de decisão sobre injeção de toque rápida (Shizuku, ADB wireless):** só avaliar se a mediana de `gesture_overhead` > **80 ms** e o ping pong errar mais de 1 em 5 mesmo depois do ajuste. Custo: configuração extra no aparelho e dependência de um serviço externo.

Aceite: ≥ 9 acertos em 10 ping pongs seguidos (resultado no jogo) e `erroPrevisao` estável no log.

---

### Fase 4 — Reconhecer personagem pela arte, não pelo nome — PASSO A: CÓDIGO PRONTO

**Objetivo:** acabar com a dependência de ler subtítulo/nível (D7).

Feito no código (passo A, OpenCV, sem treino):
- [x] **`CardArt.kt`:** compara recortes só da ARTE de referência (sem nível, subtítulo, nome nem números) com a faixa da lista de baixo (y 0,43 a 0,80) por *template matching* (`TM_CCOEFF_NORMED`, cor, 3 escalas: 0,88 / 1,0 / 1,14, largura de trabalho 640). Resultado: pontuação 0 a 1 por posição horizontal, com cache por quadro.
- [x] **Referências** em `app/src/main/assets/cartas/` (cópias das suas duas fotos de escolha de personagens, tela cheia): `equipe1.jpg` (Flash Reverso, Apocalipse "Contenção" e Nevasca nos quadrados; Batman Ninja, Cavaleiro Arkham e Caçador "Noite Mais Densa" também na lista) e `equipe2.jpg` (Batman, Cavaleiro Arkham e Caçador nos quadrados). Os recortes são proporções da foto, calculadas a partir dessas imagens; os três do time 2 têm 2 referências (quadrado e lista).
- [x] **`TeamSwap.findCard`** (só personagens; equipamentos seguem só com texto): junta texto e arte.
  - Texto achou a carta e a arte nessa posição pontua abaixo de `Arte: rejeitar abaixo de` (padrão 30%): **rejeita** (versão errada). Só no modo 2 e só nas 2 primeiras rodadas; a 3ª rodada volta a valer só o texto (válvula de segurança se a arte estiver mal calibrada).
  - Texto não achou e a melhor carta pela arte pontua pelo menos `Arte: aceitar a partir de` (padrão 60%): **aceita** (nível lido e subtítulo errado ainda valem). Guarda até 3 prints por personagem em `debug/arte_aceito_*` para você conferir.
  - Vale na busca, na conferência de nome antes de arrastar e na conferência depois do arrasto.
- [x] **Configurações:** `Personagem pela arte` (0 = só texto; 1 = arte só ajuda quando o texto falha; 2 = também rejeita; padrão 2), `aceitar a partir de` e `rejeitar abaixo de`. Se a montagem de times piorar, ponha 0 e volta ao comportamento antigo.
- [x] **Diagnóstico:** `debug/arte_scores.txt` (máx. 600 linhas, nunca apagado pelo `DebugStore`) com a melhor pontuação de cada personagem que falta a cada parada de análise e cada ACEITO/VETO.
- Falha ao carregar as referências: `CardArt.has()` devolve false e o bot segue só com o OCR.

**Os limites 60 e 30 são um palpite, não foram medidos.** Para ajustar: abra uma montagem de time (ex.: time 2 depois de o time 1 morrer), leia `arte_scores.txt` e ponha `aceitar` um pouco abaixo da pontuação da carta certa e `rejeitar` acima da pontuação das outras.

Limites conhecidos:
- Não há foto das versões ERRADAS (Apocalipse "Noite Mais Densa", Nevasca "Regime", Batman "Foice", Caçador nível 45). O método compara só com a versão CERTA e assume que a arte da errada pontua bem menos. Se uma delas aparecer com pontuação alta, tire um print dela e adicione como referência negativa.
- Flash Reverso, Apocalipse e Nevasca têm só a referência do quadrado (não da lista). Se a arte do quadrado for cortada diferente da da lista, a pontuação deles pode sair baixa: confira no `arte_scores.txt` e, se precisar, adicione um print da lista com eles.
- A pontuação depende de a tela do bot ter a mesma proporção da foto (mesmo aparelho).

Passo A, OpenCV, sem treino:
- [ ] Recortar de prints reais uma **imagem de referência da arte** de cada carta e versão (Flash Reverso, Apocalipse "Contenção", Nevasca nível 50, Batman Ninja, Cavaleiro Arkham, Caçador de Marte "Noite Mais Densa" nível 50) e também das versões **erradas** conhecidas (Apocalipse "Noite Mais Densa", Nevasca "Regime", Batman "Foice", Caçador nível 45). Guardar em `app/src/main/assets/cartas/`.
- [ ] Comparar a carta da lista com as referências em escala fixa, só na região da **arte** (sem texto, nível e bordas): histograma HSV + correlação normalizada, ou `AKAZE`/`ORB` com razão de Lowe. Escolher o método pelo resultado nos quadros do `dataset/`.
- [ ] Resultado: `(personagem, versão, confiança)`. O `TeamSwap` usa isso para escolher a carta; o OCR do nome vira só confirmação.

Passo B, só se o A não chegar a 99%:
- [ ] Classificador pequeno de embeddings (MobileNetV3 ou EfficientNet-Lite, exportado para LiteRT/.tflite) com busca do vizinho mais próximo nas referências.

Aceite: em pelo menos 30 quadros de lista por personagem (rolagem em posições diferentes, do `dataset/` ou de prints), ≥ 99% de acerto de versão; 0 confusão entre versão certa e errada; troca de time 2 sem rodada extra de rolagem em 10 execuções.

---

### Fase 5 — Detector pequeno (só se as fases 2 a 4 não bastarem)

**Portão de decisão:** entrar nesta fase apenas se, depois da Fase 2, ainda houver (a) falsos positivos de minigame em mais de 1 a cada 20 lutas, ou (b) reação > 0,3 s por causa do OCR.

**Modelo:** YOLO26n (Ultralytics; lançado em 14/01/2026, nano pensado para celular). Comparar com YOLO11n. Exportar para **LiteRT** (`.tflite`, nome atual do TFLite).
- **Licença:** o Ultralytics é AGPL-3.0. Para uso pessoal não atrapalha; se um dia distribuir o app, rever a licença.
- **Exportar:** a exportação LiteRT da Ultralytics roda em Linux x86_64 e macOS. No Windows, usar **WSL2** ou Google Colab. A quantização `w8a32` não precisa de dados de calibração.
- **Treino:** no PC (GPU) ou Colab, a partir do `dataset/` da Fase 0.

Classes iniciais: `slot_poder_carregado`, `slot_poder_vazio`, `slot_poder_x`, `bolinha`, `barra_circulo`, `seta`, `marcador_pingpong`, `botao_lutar`, `botao_avancar`, `botao_continuar`, `botao_fechar`.

Tarefas:
- [ ] **Pré-rotular** os quadros do `dataset/` com as heurísticas atuais (os `.json` já têm os valores) e **revisar** no Label Studio/CVAT. Um modelo grande de visão (como o Claude) serve **só para pré-rotular e revisar offline**, nunca dentro do laço.
- [ ] Meta de dados: 300 a 1000 imagens, com variação de efeitos, personagens e cenários.
- [ ] Treinar com `imgsz` retangular (ex.: 960x416 ou 640x288; avaliar o que preserva os números pequenos dos slots).
- [ ] **Inferência no app:** LiteRT com delegate de GPU, numa coroutine própria, no lugar do cálculo do vigia (mantendo o contrato `Perceived`). Fallback automático para as heurísticas se o modelo falhar.
- [ ] Medir `det_infer` no `Perf` (meta: 10 a 30 ms por quadro; **confirmar no aparelho**).

Aceite: precisão e revocação ≥ 98% nas classes de poder e minigame num conjunto de validação separado; 0 falso positivo de minigame em 20 lutas; latência dentro da meta.

---

### Fase 6 — OCR enxuto

- [ ] Em combate, OCR só para textos de evento que o detector não cobre, em **recorte fixo** (`0,50..0,95 x 0,15..0,33`, já usado).
- [ ] Menus: ler só a faixa de baixo (botões) numa 1ª passada barata; tela cheia só se nada for reconhecido.
- [ ] Cache por quadro: não ler duas vezes o mesmo quadro (`Capture` devolve o mesmo `Bitmap` quando nada mudou; comparar por identidade antes de chamar o OCR).
- [ ] Trocar o reconhecimento de botões fixos (LUTAR, AVANÇAR, CONTINUAR, FECHAR) por classes do detector (Fase 5) ou por template, mantendo o OCR como confirmação.

Aceite: `ocr` no `perf.txt` cai em chamadas por segundo durante o combate, sem queda de acerto nos menus.

---

### Fase 7 — Higiene e robustez (pode ser feita junto com qualquer fase) — PARCIAL

- [ ] Definir o que caracteriza um **novo ciclo**. Hoje `Brain.start()` zera `fights` apenas ao criar o laço pela primeira vez; pausar/continuar preserva a contagem e o time. `team` começa em 1 na criação do `Brain` e não é reiniciado durante a vida do serviço.
- [x] Remover `Snap.arrow()` duplicado; a seta usa `Heur.arrowFast`. `Heur.ballFast()` segue sem uso e pode ser removido em outra limpeza.
- [x] Atualizar o `README.md` com ping pong rastreado, `Perf`, vigia, lotes, configurações e `dragHoldMs = 900`.
- [x] Log de sessão em `debug/sessao_*.txt` com contadores de lutas, poderes, minigames, watchdog e tempo de combate. **Limite atual:** não há contadores específicos de trocas de time nem de erros; adicionar apenas se essas métricas forem necessárias.

---

## 4. Ordem recomendada e dependências

```
Fase 0 ──► Fase 2 ──► Fase 3
              │
Fase 4 ───────┘            (4 pode andar em paralelo com 2 e 3)
              │
              └──► [portão] ──► Fase 5 ──► Fase 6
Fase 7: a qualquer momento.
```

---

## 5. Riscos e como tratar

| Risco | Tratamento |
|---|---|
| Reduzir a captura desalinha os toques ou piora o OCR de equipamentos. | `captureScalePct` fica em 100 por padrão; ao testar menos, confira um toque conhecido (ex.: poder 2) e uma montagem de time. |
| Lote de arrastos tira a capacidade de interromper o combo. | Lotes curtos (máx. 4); `Arrastos por lote = 1` volta ao antigo. |
| `Image.timestamp` não está na base de `System.nanoTime()`. | O bot detecta (idade fora de 0 a 1 s) e usa o horário de leitura. |
| O atraso do toque medido passa do real. | Modo automático desligado por padrão; use o registro `pingpong_acertos.txt` e o `Ajuste fino`. |
| O vigia e o ping pong disputam a CPU. | O vigia fica em espera enquanto um minigame roda (`watcherHold`). Com a coleta ligada ele ainda grava quadros nesse período. |
| Detector erra em efeitos novos. | Fallback para heurísticas; continuar coletando quadros. |
| Os limiares ficam bons só neste aparelho. | Guardar o modelo do aparelho no log de sessão (fase 7) e reabrir a coleta ao trocar de aparelho. |
| O jogo mudar de tela/versão. | Refazer a coleta da Fase 0. |
| Uso de automação pode violar as regras do jogo e levar a punição da conta. | Decisão do dono do projeto; não afeta o plano técnico. |

---

## Anexo A — Como ler o `perf.txt`

Gravado a cada 15 s; cada bloco é uma janela. O cabeçalho traz `overhead_toque_med` e `idade_quadro_med` (as medianas usadas no ping pong).

| Etiqueta | Significado | Regra de bolso (provisória) |
|---|---|---|
| `capture_gap` | intervalo entre quadros **novos**; FPS ≈ 1000 / média | média > 50 ms: a captura é o gargalo |
| `capture_copy` | custo de copiar o quadro novo | > 15 ms: reduzir `Tamanho da captura` |
| `frame_age` | idade do quadro quando o bot o pega (captura até a leitura) | só aparece se o carimbo for válido |
| `ocr WxH` | uma leitura de texto naquele tamanho (inclui espera na fila) | > 300 ms em 1280 px: OCR não serve para reação rápida |
| `snap` | quadro para 800 px + HSV + cinza (laço principal) | > 25 ms: mover análises para recortes |
| `power` / `watch_power` | leitura dos 3 slots (combo / vigia) | deve ser bem menor que `snap` |
| `ping_profile` / `ping_gap` | perfil da barra do ping pong / intervalo entre quadros | `ping_gap` > 40 ms: poucos pontos para estimar a velocidade |
| `gesture_overhead` | atraso do Android além da duração do gesto (toques e arrastos simples) | mediana > 80 ms: ver regra da Fase 3 |
| `gesture_overhead_burst` / `_batch` | o mesmo para a rajada de toques e para o lote de arrastos | comparar o lote com o combo antigo |

Os limites da coluna da direita são pontos de partida; ajuste-os com o que o baseline mostrar.

## Anexo B — Prints de referência existentes

Pasta: `C:\Users\teteu\Downloads\fotos` (use as `*_reduzida.jpg` quando houver). Úteis para a Fase 4 e como exemplos na Fase 5.

| Arquivo | Conteúdo |
|---|---|
| `Combate\carregado especial 2 com o numero 2 .jpeg` | poder 2 carregado (único "carregado" hoje; coletar mais) |
| `Combate\minigame de encher barra.jpg` | barra de encher |
| `Combate\bola que precisa clicar dentro especial.jpg`, `bola clicar dentro dela o maximo possivelespecial.jpg` | bolinha |
| `Combate\especial arrasto de seta.jpg`, `especial 1 arrasto de seta.jpeg` | seta de arrastar |
| `Combate\seta de cima e baixo que precisa acerta no centro especial .jpg` | ping pong |
| `jogar time vivo.jpg`, `equipe 1 elimidada\equipe 1 elimidada.jpeg` | lobby vivo e morto |
| `parar e receber butao .jpg`, `dentro do parar e receber .jpg` | botão e confirmação |
| `depois da partida .jpg`, `tela de fim de luta.jpeg`, `tela de continuar pra sair do desafio.jpeg` | AVANÇAR e CONTINUAR |
| `roleta\roleta.jpeg` | roleta |
| `tela do coringa pra clicar na tela tentar mesnos no haha.jpeg` | coringa |
| `tela inicial do game.jpeg`, `tela inicial do modo vaizia sem personagens .jpeg` | HOME e EQUIPE_VAZIA |
| `reduzidas\*_x4.jpg`, `equipamentos equipe 1\*_x4.jpg` | cartas e equipamentos |
| `equipe 1 selecionada\...`, `equipe 2 selecionada\...` | quadrados do time preenchidos |

**Faltam prints para:** poder com X vermelho, poder vazio, poder carregado nos slots 1 e 3, ping pong em várias posições do marcador, especial em andamento e as cartas erradas conhecidas (Apocalipse "Noite Mais Densa", Nevasca "Regime", Batman "Foice", Caçador nível 45). A coleta da Fase 0 deve cobrir isso.

## Anexo C — Checklist final de aceite

- [ ] Fase 0: baseline guardado e `dataset/` com quadros variados.
- [ ] Fase 2: reação ≤ 0,15 s medida; combo interrompível; sem falso positivo novo.
- [ ] Fase 3: ping pong ≥ 9/10; registro `pingpong_acertos.txt` estável.
- [ ] Fase 4: personagem pela arte com ≥ 99%.
- [ ] Fase 5 (se o portão exigir): detector no app com fallback.
- [ ] Fase 6: OCR enxuto.
- [ ] Fase 7: decidir/resetar estado para novo ciclo; higiene restante (o README e o log de sessão já estão atualizados).
- [ ] 20 lutas seguidas e 1 troca de time completa sem intervenção.
