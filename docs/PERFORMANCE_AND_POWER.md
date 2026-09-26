# Performance e Energia

> Performance, memória e consumo energético são **requisitos funcionais** (§76). Este documento diz
> como o app se comporta, como medir e quais metas definem "pronto".

## 1. Princípio

> Quando o usuário não está fazendo nada, o aplicativo também não está fazendo nada.

Toda atividade tem um gatilho explícito (gesto, resultado de consulta, pedido do usuário). Não existem
timers periódicos, polling, `while(true)`, animações infinitas, serviços, wake locks, sensores ou
listeners globais.

```text
evento ──► processa ──► ocioso (coroutine suspensa, 0 % CPU, 0 frames)
```

## 2. Inventário de atividade

| Componente | Gatilho | Onde roda | Quando termina |
|---|---|---|---|
| Consultas da biblioteca | abrir/alterar pasta; invalidação do Room | executor do Room | a cada emissão; `Flow` coletado com `collectAsStateWithLifecycle` (para no `onStop`) |
| Worker de render | novo `RenderPlan` (viewport mudou) | `Dispatchers.Default` + fila serial da engine | suspende em `Channel.CONFLATED` quando o plano está completo; pausado (sem bitmaps) enquanto o leitor não está visível |
| Medição de tamanhos de página | documento aberto sem `page_metrics` completo | fila serial da engine, blocos de 16 páginas | ao terminar o documento, ao fechar o leitor ou quando ele deixa de estar visível (retoma no `onStart`) |
| Autosave (posição de leitura, notas) | mudança de página/zoom, digitação | `CoalescingSaver` (1 gravação por janela) | não existe timer quando não há valor pendente; `flush()` no `onStop` |
| Importação | usuário escolhe arquivos / compartilhamento | `Dispatchers.IO`, 1 arquivo por vez | fila vazia → worker suspenso |
| Purga da lixeira | abrir a tela da lixeira; 1× por processo na Home, após o primeiro conteúdo | IO | imediato |
| Indexação FTS (fase 4) | biblioteca ociosa **e** carregando | `WorkManager` com restrições | por lote, cancelável |

Nada disso roda no `Application.onCreate`.

## 3. CPU

- **Main thread**: só composição, layout, draw e entrada. Nenhum I/O, consulta, render de PDF,
  decodificação de imagem grande ou hash.
- **Concorrência controlada**: 1 fila serial por engine de PDF (PDFium não é thread-safe; o
  `PdfRenderer` tem lock global), `limitedParallelism(1)` sobre o pool de IO — nenhuma thread extra.
  Importação: 1 arquivo por vez.
- **Trabalho descartado cedo**: jobs de render que saem do plano são pulados antes de começar;
  medição de páginas verifica cancelamento entre páginas.
- **Hash + cópia em um passe** na importação (o arquivo é lido uma única vez).

## 4. GPU / frames

- Viewport do leitor = **um `Canvas`**. Scroll e zoom leem o estado **só na fase de draw**
  (`drawBehind`/`graphicsLayer` com lambdas): sem recomposição, sem relayout por frame.
- Bitmaps desenhados com `drawImage` (sem criar `ImageBitmap` novo por frame; wrappers cacheados).
- Modo escuro de leitura via `ColorFilter` no draw (não re-renderiza a página).
- Nenhuma animação contínua; ao parar o gesto o app não produz frames.
- Listas (biblioteca) com `key` e `contentType` estáveis; modelos `@Immutable`; capas pequenas.

## 5. Memória

| Item | Limite |
|---|---|
| Bitmaps do leitor (bases + tiles) | `RenderBudgets`: ≥ 3 bases + 2 telas de tiles; teto 160 MB (64 MB low-RAM) |
| Pool de bitmaps | ≤ 1/4 do cache, teto 24 MB (8 MB low-RAM) |
| Base de página | ≤ 1440 px de largura (1080 low-RAM); zoom maior = tiles só do visível |
| Tiles | 512×512 ARGB_8888 = 1 MiB cada |
| Capas | ~20 KB em disco (WebP, 360 px); decodificadas em RGB_565; LRU de 24 MB |
| Tamanhos de página | 8 bytes/página (5000 páginas = 40 KB) |

- `onTrimMemory(TRIM_MEMORY_UI_HIDDEN+)` → descarta tudo fora do plano atual e esvazia o pool.
- Leitor em segundo plano (`ON_STOP`, exceto mudança de configuração) → pipeline pausado: cache e
  pool liberados, nada renderiza nem mede; ao voltar, as páginas visíveis são re-renderizadas
  (~100 ms de papel em branco — troca consciente por memória e energia).
- Fechar o documento → caches e pool esvaziados, engine fechada, worker cancelado.
- O PDF nunca é carregado inteiro: as engines leem blocos sob demanda (`pread`).

## 6. Bateria

- Sem wake locks. "Manter tela ligada" no leitor é opção, desligada por padrão, aplicada só com o
  leitor em primeiro plano (`FLAG_KEEP_SCREEN_ON` na janela, removida ao sair).
- Tema AMOLED (preto verdadeiro) e fundo escuro de leitura.
- Trabalho adiável (indexação, backup automático) só com `WorkManager` + restrições
  (carregando/ocioso/rede não medida para nuvem).
- Sem rede no app hoje.

## 7. Ciclo de vida das coroutines

| Escopo | Dono | Cancelado quando |
|---|---|---|
| `viewModelScope` (Library/Reader/Notes) | ViewModel | tela sai da pilha |
| Worker do `RenderPipeline` | ViewModel do leitor | leitor fechado (`close()` explícito) |
| `ImportManager` | escopo da aplicação (`SupervisorJob`) | processo morre (fila vazia = suspenso) |
| Coleta de `Flow` na UI | `collectAsStateWithLifecycle` | `onStop` (nada é coletado em background) |

## 8. Recomposição

- Estado da tela = uma `data class` imutável por tela; listas `List` imutáveis (strong skipping do
  compilador Compose 2.x torna lambdas e parâmetros estáveis estáveis por padrão).
- Estados de alta frequência (offset de scroll, zoom, progresso de importação) ficam fora do estado
  da tela e são lidos o mais tarde possível (draw/layout) ou em composables folha.
- `derivedStateOf` para valores derivados de scroll (ex.: "página 12 / 184" só recompõe quando a
  página muda, não a cada pixel).

## 9. Como medir

### 9.1 Ferramentas

- **Android Studio Profiler** — CPU (System Trace para jank e threads), Memory (heap nativo de
  bitmaps), Energy/Power Profiler (em aparelhos com ODPM, ex.: Pixel 6+).
- **Macrobenchmark** (`:benchmark`) — `StartupTimingMetric`, `FrameTimingMetric`,
  `TraceSectionMetric` (seções `GigReader.*` instrumentadas no código) e `PowerMetric`
  (aparelhos com power rails).
- **Baseline Profiles** — gerados pelo `BaselineProfileGenerator` no módulo `:benchmark` (variante
  `benchmark`: minificada como o release, mas sem ofuscação, para que o perfil tenha nomes reais),
  copiados para `app/src/main/baseline-prof.txt` e instalados pelo `profileinstaller`. As seções
  de trace são assíncronas: rode os benchmarks em Android 10+ (API 29).
- **Tela de diagnóstico** (somente debug) — memória, cache de render, páginas em cache, tamanho do
  banco, duração do último render, jobs ativos.

### 9.2 Benchmarks (módulo `:benchmark`)

| # | Cenário | Métricas |
|---|---|---|
| 1 | Startup frio/morno | `StartupTimingMetric` |
| 2 | Abrir PDF pequeno (10 páginas) | `TraceSectionMetric("GigReader.openDocument")`, tempo até 1ª página |
| 3 | Abrir PDF grande (1000+ páginas) | idem |
| 4 | Scroll contínuo (fling ×10) | `FrameTimingMetric` (P50/P90/P99, frames > 16/33 ms) |
| 5 | Zoom (pinça + duplo toque) | `FrameTimingMetric`, `TraceSectionMetric("GigReader.renderPage")` |
| 6 | Adicionar highlight | fase 3 |
| 7 | Pesquisar texto | fase 2 |
| 8 | Trocar de página (próxima ×20) | `FrameTimingMetric` |
| 9 | Exportar | fase 5 |
| 10 | Fechar documento | `TraceSectionMetric("GigReader.closeDocument")` + CPU ociosa depois |

Os PDFs de teste são gerados pelo próprio benchmark (`android.graphics.pdf.PdfDocument`) com 10,
100, 500, 1000 e 2000 páginas, sem depender de arquivos grandes no repositório.

### 9.3 Metas (aceitação)

| Métrica | Meta (aparelho médio, ex.: Pixel 6a) |
|---|---|
| Startup frio (TTID) | < 500 ms |
| Startup morno | < 250 ms |
| Abrir PDF (até 1ª página visível) | < 300 ms (10 pp.) / < 600 ms (1000 pp.) |
| Frames no scroll | P90 < 12 ms, 0 frames > 33 ms após o primeiro segundo |
| Render de tile 512² | P90 < 40 ms (conteúdo vetorial típico) |
| Memória do leitor (bitmaps) | ≤ orçamento de `RenderBudgets` a qualquer momento |
| CPU lendo parado | ~0 % após o último tile (nenhuma thread acordada) |
| CPU após fechar documento | 0 % em < 1 s; caches liberados |
| Energia lendo parado | igual ao baseline do sistema com a tela ligada (±2 %) |

## 10. Logs

- `Log.d` e traços detalhados só em debug (`BuildConfig.DEBUG`); nunca em loops de render/scroll.
- Seções de trace (`android.os.Trace` via `core:pdf` `PerfTrace`, assíncronas quando o trabalho
  suspende) são praticamente gratuitas quando o trace não está ativo e permanecem no release para
  Macrobenchmark.
- Erros para o usuário nunca mostram stack trace (§50).
