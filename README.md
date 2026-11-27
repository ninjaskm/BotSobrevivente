# Bot Sobrevivente (Android, Kotlin) — v0.2: reconhece a tela sozinho

Sem marcar botão nenhum. O bot lê a tela:
- **Texto dos botões** (OCR offline do Google ML Kit): SOBREVIVENTE, LUTAR, AVANÇAR, CONTINUAR, PARAR E RECEBER, GIRE A RODA, e os textos dos mini-eventos.
- **Forma/cor** (OpenCV, sem template): combate (barras azuis + slots roxos), círculo da bola, seta, brilho do slot de poder.

Estado: time 1 completo. Equipamentos e troca para o time 2 ainda NÃO existem (etapa 7).

## Fotos de referência (IA: leia isto antes de abrir imagens)
- Pasta: `C:\Users\teteu\Downloads\fotos` (e `fotos\Combate`).
- **Prefira sempre as versões reduzidas** (`*_reduzida.jpg`, ao lado dos originais ou em `fotos\fotos_reduzida`). Abra o original só se não existir a reduzida ou se o detalhe pedir.
- Motivo: a ferramenta de leitura de arquivos recusa imagens acima de 1 MB, e os originais passam disso.
- Para gerar reduzidas das fotos novas: `reduzir_selecionadas.ps1` (lista fixa de fotos) ou `reduzir_fotos.ps1` (pasta ou arquivos arrastados), ambos em `fotos\`. Fator 2, 4 ou 6; os originais não são alterados.

## Como rodar
1. Apague a pasta antiga do projeto e abra esta no Android Studio. Sync. Rode no celular.
2. No app: sobreposição; "configurações restritas" (Config > Apps > Bot Sobrevivente > ⋮); acessibilidade; bateria "Sem restrições".
3. "Iniciar captura" > **Tela inteira**. Painel no canto superior esquerdo.
4. Deixe o time 1 montado e equipado, jogo na tela inicial ou no lobby, e aperte ▶ Play (o botão alterna Play/Pausa; começa pausado).

## Ciclo
Home → Sobrevivente → LUTAR → roleta → combate (combo, poder 2, mini-eventos) → AVANÇAR → CONTINUAR → LUTAR…
Meta de lutas → PARAR E RECEBER (só age se ler o aviso de confirmação) → Coringa → fim.
Sem botão LUTAR em 3 leituras seguidas = time morto → bot pausa e avisa.

## O que ainda é palpite (ajustar nos testes)
- Minigames x combo: o combo roda sempre; o minigame só é procurado (a) pelo texto específico (PASSE O DEDO, ARREMESSAR, TOQUE RÁPIDO), que vale sempre porque pode vir por um ícone, e (b) pelos gatilhos genéricos (palavras soltas e a forma da barra de encher), que só valem por 6 s depois de o bot tocar num poder (1, 2 ou 3). Ainda falta mapear o ícone que também dispara minigame.
- Combo em spam: 8 arrastos por rodada, sem pausa (`comboSwipes`, `comboGapMs` em BotConfig). A leitura de texto dos mini-eventos roda em segundo plano para não parar o combo.
- Roleta: o texto "GIRE A RODA..." fica cortado no topo da tela. O bot lê a faixa de cima separadamente e, se a tela continuar desconhecida, tenta girar às cegas (arrasto para a esquerda) a cada 4 s e salva `debug/cega_*.jpg`.
- Poder carregado = brilho do slot acima do limite (padrão 55%). O painel mostra os valores em tempo real.
- Seta do PASSE O DEDO: direção por forma/cor; se falhar, assume "baixo" e salva `debug/seta_*.jpg`. O texto do evento some logo, então o fim é decidido pela seta (some ~0,7 s = acabou). Começa só pelo texto do evento: o gatilho por forma foi removido porque brilhos azuis (ex.: Batman) interrompiam o combo.
- ENCHER A BARRA: círculo pequeno + barra vertical clara (~57% da largura, ~62% da altura), achado pela forma (calibrado em 1 print; salva `debug/barra_ok_*`). Toques em rajada (`tapBurst`, até 10 toques por gesto) no círculo até ele sumir.
- TOQUE RÁPIDO: rajadas no centro da bola; o fim é decidido pela bola sumir, não pelo texto.
- TOQUE PARA ARREMESSAR: barra em forma de ampulheta com mira no centro (~56% da largura, ~48% da altura). Marcador ainda não mapeado: toca após atraso configurável e grava 24 quadros (2,4 s) em `debug/arremesso*_NN` nas 2 primeiras vezes.
- Telas não reconhecidas: salva `debug/desconhecida_*.jpg` e pausa depois de ~35 s.
- Pasta de debug: Android/data/com.matheus.bot/files/debug
