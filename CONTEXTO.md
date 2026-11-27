# CONTEXTO.md — Bot Sobrevivente

Contexto fixo do projeto para IA. Não guarda datas, pendências nem estados que mudam rápido. Arquivo mínimo: só traz decisões do autor já registradas; o resto deve ser perguntado ao autor, nunca presumido.

## Como ler
Abra só as seções da tarefa. Se a leitura falhar ou vier cortada, peça ao autor a seção pelo número e não presuma o conteúdo.

## Regras que não podem ser quebradas
1. Fotos de referência: usar as versões reduzidas primeiro (seção 1).
2. O bot reconhece a tela sozinho (texto por OCR e forma/cor). Não usar marcação manual de botões nem calibração (ver README.md).

## Índice
- Seção 1 — Fotos de referência. Ler antes de abrir qualquer imagem do jogo.

## 1. Fotos de referência
- Pasta: `C:\Users\teteu\Downloads\fotos` (inclui `Combate` e outras subpastas).
- **Preferência de acesso: as versões reduzidas.** São os arquivos `*_reduzida.jpg` (ao lado dos originais ou em `fotos\fotos_reduzida`). Abra o original só se a reduzida não existir ou se o detalhe pedir.
- Motivo: a ferramenta de leitura de arquivos recusa imagens acima de 1 MB, e os originais passam disso.
- Gerar reduzidas: `reduzir_selecionadas.ps1` (lista fixa de fotos) ou `reduzir_fotos.ps1` (pasta ou arquivos arrastados), ambos em `fotos\`. Fator 2, 4 ou 6. Os originais não são alterados.
- Reduzidas com fator alto (6) podem deixar texto pequeno ilegível; nesse caso pedir outra com fator menor.
