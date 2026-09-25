# GigReader — Análise e Arquitetura

> Documento de decisão técnica. Responde à "Primeira tarefa" (§76 do plano): análise de requisitos,
> arquitetura, engines de PDF, modelo de dados, cache, rendering, energia, navegação, módulos, riscos,
> MVP e plano incremental. Documentos complementares:
>
> - [`PDF_ENGINE_COMPARISON.md`](PDF_ENGINE_COMPARISON.md): comparação detalhada das engines
> - [`PERFORMANCE_AND_POWER.md`](PERFORMANCE_AND_POWER.md): CPU, GPU, memória, bateria, benchmarks
> - [`NOTEZIP_FORMAT.md`](NOTEZIP_FORMAT.md): formato nativo de exportação

---

## 1. Análise dos requisitos

### 1.1 O que o produto é

Uma **biblioteca pessoal local** de documentos (principalmente PDFs acadêmicos e técnicos), com um
**leitor** que "desaparece" durante a leitura, **anotações semânticas** (highlight + nota) e **busca**
sobre tudo o que foi estudado. Funciona 100% offline; nuvem é uma camada opcional futura.

### 1.2 Prioridades (em caso de conflito, na ordem do §72)

| # | Prioridade | Como se traduz em decisões |
|---|---|---|
| 1 | Correção | Escritas atômicas, transações, testes de repositório e de formato |
| 2 | Performance de PDF | Viewport próprio com tiles, render sob demanda, caches com orçamento |
| 3 | Eficiência energética | Arquitetura orientada a eventos; nada roda quando o usuário não faz nada |
| 4 | Integridade de dados | Cópia gerenciada do PDF, fsync + rename atômico, soft delete + lixeira, tombstones |
| 5 | UX | Home como biblioteca, leitor limpo, layouts adaptativos, desfazer em ações destrutivas |
| 6 | Acessibilidade | Semântica, alvos de toque ≥ 48 dp, contraste, escala de fonte, teclado/mouse |
| 7 | Acabamento visual | Identidade própria, poucas cores, sem sombras/gradientes gratuitos |
| 8 | Extras | Somente depois que 1–7 estiverem medidos |

### 1.3 Requisitos que moldam a arquitetura

- **PDFs de 1000–2000+ páginas e até 1 GB** → nunca carregar o arquivo inteiro; nunca rasterizar
  todas as páginas; layout de páginas em O(log n); tamanhos de página cacheados; render só do que
  está visível (tiles em zoom alto).
- **"Quando o usuário não faz nada, o app não faz nada"** → sem polling, sem timers, sem loops
  infinitos; trabalhadores que ficam **suspensos** em canais; `WorkManager` só para trabalho
  adiável com restrições (carregando/ocioso).
- **Highlights semânticos** (texto + retângulos + índices de caracteres) → a engine precisa expor
  texto com geometria; anotações ficam no nosso banco, não "queimadas" no PDF.
- **Preparado para sync incremental** → toda entidade tem `id`, `createdAt`, `modifiedAt`,
  `version`, `deletedAt` desde a versão 1 do schema.
- **Tablet como app de produtividade** → size classes (Compact/Medium/Expanded), rail de navegação,
  painéis laterais, drag & drop, teclado e mouse.
- **Anti-overengineering (§73)** → poucas dependências, DI manual, navegação própria simples,
  nenhuma abstração sem uso concreto.

### 1.4 Conflitos identificados e como foram resolvidos

| Conflito | Resolução |
|---|---|
| "Não duplicar PDFs" × engine precisa de arquivo *seekable* e sempre disponível | Importar = **uma** cópia gerenciada no armazenamento privado (a única), com hash para detectar duplicatas. "Abrir com" externo abre **sem importar**. O modelo já suporta `DocumentSource.Linked` (SAF persistente) para quem não quiser cópia. |
| Microinterações × energia | Animações curtas, disparadas por evento, desligáveis em Configurações (`animationsEnabled`), nenhuma animação infinita. |
| Highlights semânticos × engine sem texto em aparelhos antigos | Interface `PdfEngine` com `EngineCapabilities`; engine principal PDFium (texto em todas as versões); fallback do framework oculta recursos indisponíveis. |
| Busca em PDFs grandes × "não indexar na abertura" | Busca página-a-página sob demanda com cancelamento; indexação FTS incremental apenas em background com restrições (fase de busca). |
| Autosave × "não escrever a cada evento" | `CoalescingSaver`: mantém só o último valor, grava no máximo 1× por janela e faz `flush` no `onStop`. |

---

## 2. Arquitetura proposta

### 2.1 Camadas

```text
UI (Compose)           feature:* — telas, ViewModels, estado imutável
  ↓ StateFlow / eventos
Domain                  core:model — entidades puras, regras (ordenação, sync, progresso)
  ↓
Data                    core:data — repositórios (interfaces + implementações locais), importação
  ↓
Infrastructure          core:database (Room) · core:pdf (engines, render, caches) · core:common (IO, math)
```

- **Unidirecional**: UI observa `StateFlow` de ViewModels; ViewModels chamam repositórios; o banco
  (Room) emite `Flow` por invalidação — nenhuma tela faz polling.
- **Local-first**: a fonte da verdade é o SQLite + diretório gerenciado. Um futuro `SyncEngine`
  observa as mesmas tabelas (via `version`/`deletedAt`) — nunca é acoplado à UI.
- **Repositórios como interfaces** (`LibraryRepository`, `ReaderRepository`, `NotesRepository`,
  `SettingsRepository`): hoje só há a implementação local; testes usam fakes.

### 2.2 Preparação para nuvem (sem implementá-la agora)

```text
Library ──► SyncEngine ──► BackupProvider
                            ├── LocalFolderProvider   (fase 7: backup .zip via SAF)
                            ├── GoogleDriveProvider   (fase 7+: preparado, não implementado)
                            └── FutureProvider
```

- Detecção de mudanças por `version` (contador por entidade) comparado com a última versão
  sincronizada; `classifyChange()` em `core:model` já classifica novo/modificado/excluído/conflito.
- Exclusões viram **tombstones** (`deletedAt`, conteúdo apagado, linha mantida).
- Estado de leitura (`reading_positions`) é separado dos metadados do documento para que virar
  página não gere tráfego de sync.
- Nenhuma dependência de nuvem, login ou rede existe no app.

### 2.3 Injeção de dependências

Manual (`AppContainer` criado no `Application`, com `lazy` para tudo): zero bibliotecas, zero
geração de código, zero custo de startup. O banco, as engines e os caches só são criados no primeiro
uso real. ViewModels recebem dependências via `viewModelFactory { initializer { … } }`.

---

## 3. Engine de PDF (resumo — detalhes em `PDF_ENGINE_COMPARISON.md`)

**Decisão: engine própria sobre PDFium** (binários pinados de `bblanchon/pdfium-binaries`, licença
BSD-3/Apache-2.0, com camada JNI fina escrita por nós) **como engine principal**, e o
`android.graphics.pdf.PdfRenderer` do framework **como fallback** (tamanho zero, segurança mantida
pelo Google via Mainline) — ambos atrás da interface `PdfEngine`.

Por quê:

- O `PdfRenderer` do framework é PDFium, mas expõe pouco: **sem sumário (TOC), sem page labels,
  sem tamanhos de página baratos, sem caixas por caractere**, uma página aberta por vez, lock
  global, sem cancelamento; texto/busca só no API 35 (ou 31–34 com SDK extension 13) e
  anotações só no 36.1 — inviável como base de um leitor acadêmico premium em todos os aparelhos.
- `androidx.pdf` (1.0.0-beta01) herda essas limitações, roda em processo isolado com IPC por tile,
  não reutiliza bitmaps e ainda é beta/experimental na parte Compose.
- MuPDF é excelente tecnicamente, mas **AGPL-3.0** (ou licença comercial paga) — incompatível com
  um app fechado sem custo de licença.
- Wrappers PDFium prontos (ex.: `io.legere:pdfiumandroid`) não escrevem anotações e trazem um
  binário antigo de procedência não documentada — risco de segurança.

**Faseamento**: a Fase 1 entrega o leitor básico sobre o backend do framework (suficiente para
render; zero dependências nativas) com todo o pipeline engine-agnóstico (layout, tiles, caches,
scheduler). A engine PDFium entra na Fase 2, quando texto/busca/TOC passam a ser necessários, sem
tocar na UI.

---

## 4. Modelo de dados

### 4.1 Entidades (tabelas Room, schema v1)

| Tabela | Conteúdo | Observações |
|---|---|---|
| `folders` | árvore de pastas (`parent_id`) | lixeira em cascata via `trash_root_id` |
| `documents` | metadados, origem (gerenciado/linkado), `content_hash`, tamanho, páginas, favorito, arquivado, `annotation_count` | `annotation_count` desnormalizado (atualizado na mesma transação) evita N+1 e subconsultas no filtro "anotados" |
| `reading_positions` | `page`, `page_offset`, `zoom`, `max_page_reached` | separado de `documents` (alta frequência de escrita, não gera sync) |
| `page_metrics` | tamanhos de todas as páginas (blob de 8 bytes/página) | dado derivado; reabrir um PDF de 2000 páginas tem layout instantâneo |
| `notes` | notas rápidas; podem apontar para documento + página | criação instantânea, sem pasta obrigatória |
| `text_annotations` | highlight/underline/strikeout: `page`, `text`, `rects` normalizados, `color`, `note`, `char_start/char_end` | tinta/desenho serão **tabelas separadas** (§39) |
| `bookmarks` | página + título | |
| `tags`, `document_tags` | tags normalizadas (`#ASME` → `asme`) | N:N |

Campos comuns de sync em todas as entidades do usuário: `id` (UUID), `created_at`, `modified_at`,
`version`, `deleted_at`. Itens de biblioteca também têm `trashed_at` e `trash_root_id`.

### 4.2 Decisões importantes

- **Retângulos normalizados (0..1)** no espaço da página não rotacionada: independentes de zoom,
  densidade e resolução de render; exportáveis para pontos PDF multiplicando pelo tamanho da página.
- **Âncora semântica** do highlight: texto + retângulos + índices de caractere (quando a engine
  fornece). Permite busca, exportação, sync e reconstrução.
- **Nota do highlight inline** (`text_annotations.note`) em vez de uma entidade `Note` separada: uma
  linha, uma transação, desfazer atômico. Ligações nota↔documento↔página usam `notes.linked_*`.
- **Lixeira** (`trashed_at`) ≠ **tombstone** (`deleted_at`). Mandar uma pasta para a lixeira marca a
  subárvore inteira com `trash_root_id = pasta`; restaurar traz tudo de volta; a tela de lixeira
  lista só as raízes. Itens expiram em 30 dias (purga preguiçosa, sem job periódico).
- **Índices** em toda coluna usada em `WHERE`/`ORDER BY` de listas: `folder_id`, `parent_id`,
  `last_opened_at`, `modified_at`, `favorite`, `content_hash`, `(document_id, page)`.
- **Projeções leves** para listas (`DocumentRow`, `NoteRow` com `substr(body, 1, 160)`): rolar a
  biblioteca nunca carrega corpo de notas nem anotações.

---

## 5. Armazenamento de arquivos

```text
filesDir/library/{documentId}.pdf     ← única cópia do documento (gerenciada)
filesDir/library/.incoming/*.part     ← importações em andamento (limpas se órfãs)
cacheDir/covers/{documentId}.webp     ← capa (1ª página, 360 px, ~20 KB), regenerável
```

- **Importação atômica**: stream SAF → arquivo temporário (hash SHA-256 no mesmo passe, leitura única)
  → `fsync` → validação pela engine + capa → `rename` atômico → uma transação no banco. Um crash em
  qualquer ponto deixa apenas temporários/órfãos, removidos depois — nunca uma linha apontando para
  arquivo truncado.
- **Duplicatas**: mesmo SHA-256 → não copia de novo; oferece abrir o existente.
- **Exportação** escreve direto no destino SAF (sem cópia intermediária). O `.gigreader` (ZIP)
  embute o PDF por definição.
- **"Abrir com"** (ACTION_VIEW): abre direto do `content://` se o descritor for *seekable*; senão
  copia para um cache temporário limitado. Não importa sem o usuário pedir.

---

## 6. Estratégia de rendering

```text
PDF (arquivo, lido sob demanda por pread)
 ↓
PdfEngine (thread única por engine; página carregada 1× por lote de jobs)
 ↓
RenderPipeline (worker suspenso até receber um plano; pula jobs obsoletos)
 ↓
RenderPlanner (o que a viewport precisa: páginas visíveis → tiles → vizinhas)
 ↓
Caches com orçamento (LRU por bytes) + BitmapPool
 ↓
Viewport Compose (um único Canvas; scroll/zoom só invalidam o draw)
```

### 6.1 Viewport próprio (não LazyColumn)

- `DocumentLayout` (puro Kotlin, testado): prefix-sum das alturas; página em `y` por busca binária;
  5000 páginas = dois `FloatArray`.
- `ViewportMath`: zoom em torno do ponto focal, pan, clamp, posição ↔ (página, fração) — o que
  permite restaurar a posição mesmo após rotação ou mudança de tamanho de janela.
- Um único `Canvas`: o offset de scroll é lido **apenas na fase de draw**, então rolar não causa
  recomposição nem relayout; nenhuma alocação por frame.
- Gestos: pan/fling (decay), pinça (focal), duplo toque (alterna fit ↔ 2,5×); seleção de texto
  (fase 3) terá prioridade de gesto após long-press.

### 6.2 Níveis de detalhe

- **Base**: página inteira em "fit width", limitada a `maxBaseWidthPx` (1440 px; 1080 em aparelhos
  low-RAM). Serve de fallback instantâneo durante zoom/scroll.
- **Tiles 512×512** apenas das áreas visíveis quando a resolução exibida excede a base em >15 %,
  em **buckets de zoom** `2^(k/3)` (1; 1,26; 1,59; 2; …): texto sempre nítido, sobreamostragem ≤26 %,
  pequenas mudanças de zoom reutilizam tiles.
- **Durante a pinça** só se renderizam bases (tiles de zoom intermediário seriam descartados); ao
  soltar, tiles do bucket final.
- **Prioridade**: páginas visíveis (mais perto do centro primeiro) → tiles visíveis → vizinhas
  (prefetch). Jobs que saem do plano enquanto enfileirados são **pulados**. Cada chave é tentada no
  máximo uma vez por plano (impede loops de render/evicção se o orçamento for pequeno).
- **Tamanhos de página**: estimados pela primeira página, medidos em background em blocos pequenos
  (intercalados com render) e persistidos em `page_metrics`; com PDFium, lidos sem carregar páginas.

### 6.3 Concorrência controlada

- PDFium não é thread-safe e o `PdfRenderer` tem lock global: **uma** fila serial por engine
  (`Dispatchers.IO.limitedParallelism(1)`), nenhuma thread dedicada extra.
- Worker de render no `Dispatchers.Default`, 1 coroutine por documento aberto, suspensa quando não
  há plano pendente.
- Banco: executores do Room. Arquivos: `Dispatchers.IO`. Main thread: só UI.

---

## 7. Estratégia de cache

| Cache | Chave | Política | Orçamento |
|---|---|---|---|
| `PageBitmapCache` + tiles | `PageKey(page, w, h)` / `TileKey(page, bucket, col, row)` | LRU por bytes (`WeightedLruCache`), acesso no draw renova recência | `RenderBudgets`: ≥ 3 páginas base + 2 telas de tiles; teto 160 MB (64 MB low-RAM) |
| `BitmapPool` | dimensões | bitmaps ociosos para reuso (tiles têm tamanho fixo) | ≤ 1/4 do cache, teto 24 MB |
| Capas (disco) | `documentId` | geradas 1× na importação; `cacheDir` (o sistema pode limpar) | ~20 KB cada |
| `page_metrics` (banco) | `documentId` | derivado, reescrito ao medir | 8 bytes/página |
| `TextCache` / `SearchCache` | página / consulta | LRU por contagem | fase de busca |

- `onTrimMemory` → descarta tudo que não é do plano atual e esvazia o pool.
- Fechar o documento → cancela o worker, limpa caches e pool, fecha a engine: CPU/memória voltam ao
  repouso.
- Bitmaps **não** recebem `recycle()` explícito (a UI pode estar gravando um draw do bitmap que o
  worker acabou de evictar); desde o API 26 os pixels ficam no heap nativo e são liberados na coleta.

---

## 8. Estratégia energética (detalhes em `PERFORMANCE_AND_POWER.md`)

- **Evento → processa → ocioso**: `Channel.CONFLATED` acorda o worker de render; o importador
  consome uma fila e suspende; o autosave agenda **uma** gravação por janela e só existe enquanto há
  algo pendente.
- **Nada no startup** além de mostrar a UI: o banco abre na primeira consulta; nenhum PDF é aberto
  para listar a biblioteca (capas pré-geradas); nenhuma indexação, sync ou limpeza no `onCreate`.
- **Sem wake locks, sem serviços, sem sensores, sem listeners globais.** "Manter tela ligada" no
  leitor é opcional e desligado por padrão.
- **Tema AMOLED** (pixels pretos) e modo de leitura escuro por filtro de cor no draw (sem re-render).
- **Animações**: curtas, por evento, desligáveis; nenhuma `infiniteTransition`.

---

## 9. Navegação

Navegação própria e mínima (pilha de destinos `@Immutable` salva no `SavedState`), sem dependência:
com 6 destinos, uma pilha + `BackHandler` + `SaveableStateHolder` é mais simples e mais leve que
uma biblioteca.

```text
Library(folderId?) ──► Reader(documentId | uri externo)
      │                    └── (fase 2+) painel: miniaturas, sumário, anotações, busca
      ├──► Search
      ├──► NoteEditor(noteId)
      ├──► Trash
      ├──► Settings
      └──► Diagnostics (somente debug)
```

- **Intents**: `ACTION_VIEW` (application/pdf) → Reader externo; `ACTION_SEND`/`SEND_MULTIPLE` →
  importação automática; atalho de launcher "Nova nota".
- **Adaptativo**: Compact = barra superior + FAB; Medium/Expanded = `NavigationRail` + grade larga
  + painel de pastas; no leitor, Expanded = painel lateral (miniaturas/anotações) + página.

---

## 10. Estrutura de módulos

```text
app                 Activity, navegação, AppContainer (DI manual), intents, diagnóstico (debug)
core/model          [JVM puro] entidades, ordenação, sync, configurações
core/common         [JVM puro] layout/viewport/tiles, LRU, undo, autosave, IO atômico, hash, árvore
core/database       Room: entidades, DAOs, schema exportado
core/data           repositórios, importação, capas, configurações (DataStore)
core/pdf            PdfEngine + backends, RenderPipeline, BitmapPool, orçamentos, capas
core/ui             tema (Light/Dark/AMOLED), componentes, size classes, strings compartilhadas
feature/library     Home/biblioteca, pastas, seleção múltipla, drag & drop, importação, lixeira
feature/reader      viewport, zoom, navegação, posição de leitura (anotações na fase 3)
feature/notes       notas rápidas (fase 4; editor mínimo já na fase 1)
feature/settings    aparência, leitor, biblioteca
benchmark           Macrobenchmark + geração de Baseline Profile
```

- `core:model` e `core:common` são **Kotlin/JVM puros**: a lógica mais crítica (geometria, tiles,
  LRU, undo, autosave, IO atômico) roda em testes JVM rápidos, sem emulador.
- Features não dependem umas das outras; o `app` as conecta.

---

### 10.1 Dependências (§69)

| Dependência | Por quê | Alternativa descartada |
|---|---|---|
| Kotlin + Coroutines/Flow | linguagem e concorrência estruturada (cancelamento, dispatchers limitados) | RxJava (pesado, sem necessidade) |
| Jetpack Compose (BOM) + Material 3 | UI declarativa com controle fino de recomposição; componentes acessíveis | Views/XML (mais código, menos controle de estado) |
| material-icons-extended | ícones consistentes; só os usados sobrevivem ao R8 no release | desenhar vetores à mão |
| Room + KSP | SQLite com consultas verificadas em compilação, `Flow` por invalidação, migrações | SQLDelight (bom, mas sem vantagem aqui), ORM próprio |
| DataStore Preferences | preferências assíncronas e transacionais | SharedPreferences (I/O na main thread) |
| lifecycle (viewmodel/runtime-compose) + activity-compose | ViewModel por tela, coleta ciente de ciclo de vida, Activity Result APIs | — |
| androidx.core | FileProvider, WindowInsetsController, IntentCompat | — |
| profileinstaller | instala Baseline Profiles (≈30 % mais rápido no primeiro uso) | — |
| **Sem** Hilt/Dagger/Koin | DI manual: 1 arquivo, zero custo de startup | Hilt (geração de código e tempo de build) |
| **Sem** Navigation | 6 destinos: pilha própria com `SaveableStateHolder` e `ViewModelStore` por entrada | navigation-compose / navigation3 |
| **Sem** Coil/Glide | capas pequenas pré-geradas + LRU próprio | biblioteca de imagens (resolve problemas que não temos) |

Testes: kotlin-test, coroutines-test, JUnit 4, Robolectric + androidx.test (Room em JVM),
Macrobenchmark + UiAutomator (desempenho).

### 10.2 minSdk 26

Android 8.0+ cobre ~97 % dos aparelhos ativos e é o ponto em que os **pixels de Bitmap passam para o
heap nativo** (base da estratégia de memória do leitor), além de ícones adaptativos e `java.time`. As
versões atuais da AndroidX já exigem minSdk 24.

## 11. Riscos técnicos

| Risco | Impacto | Mitigação |
|---|---|---|
| PDF malformado derruba o processo (crash nativo no PDFium) | Alto | Captura de exceções Java; fase posterior: engine em processo `isolatedProcess`; mensagens amigáveis (§50) |
| Segurança do PDFium embarcado (CVEs frequentes) | Alto | Binário pinado + SHA-256 verificado; atualização mensal e imediata em CVE; SBOM/notices; fallback do framework (atualizado pelo Google) |
| Memória em tablets/zoom alto | Alto | Base limitada, tiles só visíveis, orçamento por aparelho, `onTrimMemory`, pool limitado |
| Render lento em páginas escaneadas pesadas (JBIG2/JPX) | Médio | Jobs obsoletos pulados; render progressivo cancelável (PDFium `RenderPageBitmap_Start` + pausa) na fase 2 |
| Fragmentação do `PdfRenderer` (API 21–37) | Médio | `EngineCapabilities` + UI que oculta o indisponível; PDFium como principal |
| Provedores SAF de nuvem entregam descritores não-*seekable* | Médio | Importação por cópia em stream; "Abrir com" cai para cópia temporária |
| Build Android indisponível no ambiente de desenvolvimento remoto (Google Maven bloqueado) | Médio | Lógica crítica em módulos JVM testados localmente; CI no GitHub Actions compila e testa o APK |
| Crescimento de escopo (IA, OCR, stylus) | Médio | Interfaces preparadas (engine, tabelas separadas por tipo de anotação), sem dependências antecipadas |

---

## 12. MVP (primeira versão funcional = Fase 1)

1. Projeto multi-módulo, CI, schema v1 completo com campos de sync.
2. Biblioteca: pastas/subpastas, raiz, criar/renomear/mover/excluir (lixeira + desfazer),
   favoritos, ordenação e filtros, grade/lista, seleção múltipla, drag & drop para pastas,
   "Continuar lendo", estados vazios, busca por nome.
3. Importação: SAF múltipla, compartilhar → importar, detecção de duplicatas, capa, validação com
   mensagens amigáveis.
4. Leitor básico: rolagem contínua, zoom por pinça e duplo toque com tiles, ir para página,
   anterior/próxima, posição de leitura persistida (página, offset, zoom), modo foco, fundo escuro.
5. Notas rápidas: criação instantânea (editor simples com autosave).
6. Configurações: tema (Sistema/Claro/Escuro/AMOLED), animações, layout da biblioteca, espaçamento.
7. Diagnóstico (debug): memória, cache de render, páginas em cache, tamanho do banco, último render.
8. Benchmarks: startup, abrir PDF pequeno/grande, scroll, zoom, trocar de página, fechar documento.

## 13. Plano incremental

| Fase | Conteúdo | Critério de saída |
|---|---|---|
| **1 — Fundação** | MVP acima | CI verde; testes JVM + Room; benchmark de startup e scroll executáveis |
| **2 — Leitor** | Engine PDFium (JNI próprio, binário pinado), miniaturas incrementais, sumário/TOC, busca no documento com destaque temporário, histórico de navegação, modo paginado e duas páginas | Abrir PDF de 1000 páginas < 500 ms até a 1ª página (aparelho médio); 0 frames > 32 ms no scroll do benchmark |
| **3 — Anotações** | Seleção de texto (caixas por caractere), highlight/underline/strike, nota no highlight, visão geral de páginas anotadas, painel lateral, desfazer/refazer | Highlight persistido < 16 ms na main thread; reconstrução exata após reabrir |
| **4 — Notas** | Notas vinculadas a página, bookmarks nomeados, tags, busca global (FTS4 do SQLite do sistema — o FTS5 não vem habilitado no Android — sobre nomes, notas, highlights, bookmarks) | Busca global < 100 ms em biblioteca de 5000 itens |
| **5 — Exportação** | PDF com anotações reais (FPDF_ANNOT_HIGHLIGHT + /Contents, save incremental), PDF achatado, formato `.gigreader`, importação do formato | Round-trip `.gigreader` sem perda (teste) |
| **6 — Performance** | Baseline Profiles, ajuste de caches, perfis de energia, metas do `PERFORMANCE_AND_POWER.md` | Metas atingidas em aparelho de referência |
| **7 — Backup** | `BackupProvider` + backup local (SAF), Google Drive preparado | Restaurar biblioteca em aparelho limpo |

Cada fase segue o §74: analisar → riscos → bibliotecas → justificar → implementar → testar → medir →
corrigir gargalos.
