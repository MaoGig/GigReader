# Status do desenvolvimento

> Onde o trabalho parou, o que já foi feito e o que falta. Atualizado em 2026-09-29.

## Resumo

- **Fase 1 (fundação)**: concluída e em `main` (PRs #1 e #2). CI verde no GitHub: testes JVM +
  Robolectric/Room, APK debug e lint.
- **Fase 2 (leitor)**: **em andamento, interrompida de propósito** nesta branch
  (`claude/siga-o-plano-vkjjpx`). A engine MuPDF e a matemática dos modos de leitura estão prontas;
  a navegação do leitor está pela metade (lógica pronta, telas não ligadas); busca no documento e
  integração dos modos de leitura não começaram.
- Tudo que está commitado compila e passa nos testes do harness local (263 testes JVM + 28 testes de
  Room sobre SQLite real). **Nada da Fase 2 rodou ainda num build Android real nem num aparelho**:
  o primeiro build real (com o download da MuPDF) é o CI deste commit.

## Decisão de engine: MuPDF

O dono do produto escolheu a **MuPDF** (`com.artifex.mupdf:fitz:1.28.5`, de
`https://maven.ghostscript.com`), no lugar do PDFium planejado. Detalhes em
`PDF_ENGINE_COMPARISON.md`.

- **Licença AGPL-3.0**: distribuir o app com a MuPDF exige publicar o código-fonte do app sob
  AGPL-3.0, ou comprar uma licença comercial da Artifex. Nenhum arquivo `LICENSE` foi adicionado —
  é decisão do dono. O README explica; Configurações tem a tela "Licenças de código aberto".
- O `PdfRenderer` do Android continua como engine de reserva (`FallbackPdfEngine`), usada só se a
  biblioteca nativa da MuPDF não carregar.

## O que a Fase 2 já entregou (nesta branch)

### Engine MuPDF — `core/pdf` (pronta, não testada em aparelho)

- `mupdf/MuPdfEngine` + `MuPdfDocument`: abre arquivo ou descritor (adaptador `SeekableInputStream`
  sobre `FileChannel`), senha em **todas** as versões do Android, erros mapeados para
  `PdfOpenException`, fila serial por documento, objetos nativos destruídos em `finally`.
- Render com `AndroidDrawDevice` (páginas e tiles, com anotações e widgets), fundo branco.
- Tamanhos de página baratos para PDF (lidos do dicionário da página, sem carregar a página).
- Busca por página sem diferenciar maiúsculas **nem acentos** (`PageTextIndex`, testada na JVM).
- Sumário (`outline`) com página e posição vertical, links internos/externos, rótulos de página.
- `trimMemory` esvazia o cache interno da MuPDF em `onTrimMemory`.
- `FallbackPdfEngine`, regra R8 para `com.artifex.mupdf.fitz.**`, repositório Maven restrito ao
  grupo `com.artifex.mupdf`.
- Senha: o diálogo do leitor funciona em todas as versões quando a engine suporta senha.
- Configurações: tela "Licenças de código aberto" (MuPDF AGPL-3.0, AndroidX/Kotlin Apache-2.0).

### Modos de leitura — `core/common/render` (prontos, ainda não usados pelo leitor)

- `ReadingLayout` com três modos: `CONTINUOUS` (faixa vertical, igual à Fase 1), `PAGED` (uma página
  por tela, paginação horizontal) e `TWO_PAGE` (páginas lado a lado, capa sozinha opcional).
- Consultas O(log n): página no ponto, unidade (página/par) atual, alvo de "snap", próxima/anterior,
  limites de pan/zoom por modo, conversão de posição entre modos (`ReadingPositions`).
- `RenderPlanner` planeja para todos os modos (pré-carrega a unidade vizinha nos modos paginados).
- 49 testes novos.

### Navegação no leitor — `feature/reader/nav` (pela metade)

Pronto (lógica e ViewModel):

- `NavigationHistory` (voltar/avançar depois de saltos), `JumpTargets`.
- `TocModel` (árvore do sumário, seção atual).
- `ThumbnailLoader` + `ThumbnailPlanner` + `IdleGatedRenderQueue`: miniaturas renderizadas só
  enquanto o render principal está ocioso, com cache próprio liberado ao fechar o painel.
- `LinkHits`: toque em link interno salta (com histórico); link externo pede confirmação.
- `PageLabels`: indicador mostra o rótulo impresso ("iv (4 / 312)").
- `ReaderViewModel` já abre/fecha painéis, inicia/libera miniaturas, carrega sumário e links;
  `PdfViewport` já detecta toque em link. Strings en + pt-BR adicionadas.

## O que falta

### Para terminar a Fase 2

1. **Navegação — interface**: painéis de sumário e miniaturas em `ReaderScreen` (painel lateral em
   tela larga, bottom sheet em celular), chip "Voltar à p. X", confirmação de link externo, Back
   fechando painéis primeiro. Testes da `NavigationHistory` e dos helpers.
2. **Busca no documento**: barra de busca, resultados progressivos a partir da página atual,
   contador "3 / 41", anterior/próximo, destaque temporário na página, cancelamento; escondida
   quando a engine não tem busca (engine de reserva).
3. **Modos de leitura no leitor**: ligar `ReadingLayout` ao `PdfViewport`/`PdfViewportState`
   (swipe com snap, zonas de toque para virar página, zoom dentro da página), alternância rápida
   no menu e padrão em Configurações. `core/model` `ReaderScrollMode` precisa do valor `TWO_PAGE`.
4. **Revisão adversarial + correções** de toda a Fase 2 (estava planejada como etapa final).
5. **Verificação real**: CI verde com a MuPDF de verdade; testar em aparelho: render e tiles,
   senhas, links (`yFraction`), busca, memória, tamanho do APK.

### Pontos a verificar/ajustar (anotados pelos agentes)

- Suposições da MuPDF a confirmar em aparelho: origem do desenho em tiles com `AndroidDrawDevice`;
  `resolveLinkDestination().y` já em coordenadas de cima para baixo; `PDFObject.resolve()` na
  leitura barata de tamanhos (há fallback para `loadPage`).
- O render não aborta o `Cookie` da MuPDF quando um tile fica obsoleto (só pula antes de começar).
- Busca: palavras hifenizadas no fim da linha não são unidas; `textStartIndex` é aproximado.
- Importação de PDF com senha continua sem número de páginas e sem capa (sem a senha não há como);
  o leitor grava o número de páginas depois de abrir com senha.
- **Tamanho do APK**: a MuPDF traz 4 ABIs (~5,6 MB cada). Usar App Bundle ou `abiFilters` antes de
  publicar (Fase 6).
- `docs/ARCHITECTURE.md` ainda cita o PDFium na tabela de riscos (§11) e em alguns trechos fora do
  §3/§13.
- Schema do Room v1 (`core/database/schemas`) ainda não commitado: gerar num build real e commitar.

### Fases seguintes (sem alteração no plano, `ARCHITECTURE.md` §13)

3. Anotações (seleção de texto, highlight/sublinhado/tachado, notas, desfazer).
4. Notas vinculadas, bookmarks, tags, busca global (FTS4).
5. Exportação (anotações reais no PDF via `PDFAnnotation` da MuPDF, PDF achatado, formato
   `.gigreader`).
6. Performance (Baseline Profiles, metas do `PERFORMANCE_AND_POWER.md`, tamanho do APK).
7. Backup.

## Como retomar

1. Ler este arquivo, `ARCHITECTURE.md` e `PERFORMANCE_AND_POWER.md`.
2. Conferir o CI desta branch (build real com a MuPDF) e corrigir o que falhar.
3. Continuar pela lista "Para terminar a Fase 2", na ordem: navegação (UI) → busca → modos de
   leitura → revisão.
4. Só então abrir o PR da Fase 2 para `main`.
