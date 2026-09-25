# Comparação de engines de PDF

> Pesquisa feita em 2026-09-25 com fontes primárias (referência da API Android, código AOSP/androidx,
> repositórios, AARs baixados do Maven Central e medidos). Onde algo não pôde ser verificado, está
> indicado. Objetivo (§70): escolher a engine por critério técnico, não por popularidade.

## 1. Candidatos

| Engine | O que é | Licença | Versão avaliada |
|---|---|---|---|
| **PdfRenderer (framework)** | `android.graphics.pdf.PdfRenderer` — PDFium dentro do SO (Mainline desde o Android 15; `PdfRendererPreV` no 31–34 com SDK ext. 13) | Plataforma (Apache-2.0) | API 21–37 |
| **androidx.pdf** | Jetpack sobre o PdfRenderer, com viewer View/Compose e serviço em processo isolado | Apache-2.0 | 1.0.0-beta01 (26/08/2026) |
| **PDFium + JNI próprio** | Binários `bblanchon/pdfium-binaries` pinados + camada JNI nossa | PDFium BSD-3 + Apache-2.0; scripts MIT | chromium/8066 (21/09/2026) |
| **io.legere:pdfiumandroid** | Wrapper Kotlin/JNI pronto sobre PDFium (fork do PdfiumAndroid) | Apache-2.0 | 2.0.3 (26/07/2026) |
| **MuPDF (fitz)** | Engine C da Artifex com binding Java/JNI | **AGPL-3.0** ou comercial | 1.28.5 (25/09/2026) |
| **PdfBox-Android** | Port Java do Apache PDFBox | Apache-2.0 | 2.0.27.0 (2023) |
| **pdf.js em WebView** | Engine JS da Mozilla | Apache-2.0 | v6.3.289 |
| **SDKs comerciais** | Nutrient (PSPDFKit), Apryse (PDFTron), Foxit, ComPDFKit | Comercial | 2026 |

## 2. Fatos que decidem

### PdfRenderer (framework)
- `Page.render(Bitmap, Rect, Matrix, int)` aceita matriz afim + clip → tiles funcionam em todo API
  level; **só ARGB_8888**; não pinta fundo (é preciso `eraseColor(WHITE)`).
- **Uma página aberta por vez** (API 21–34); lock estático de processo em toda chamada (API 26+) —
  nada roda em paralelo, **nenhum render é cancelável ou progressivo**.
- Lê o arquivo sob demanda (`pread`), mas exige descritor **seekable** (pipes de provedores de
  nuvem lançam `IllegalArgumentException`).
- Texto, busca, seleção, links, formulários e senha só no **API 35** (ou 31–34 com SDK extension 13
  via `PdfRendererPreV`); bounds = **um retângulo inteiro por linha**, sem caixas por caractere;
  `getTextContents()` retorna o texto da página sem bounds (AOSP até android-16.0.0_r3).
- Anotações (Highlight/FreeText/Stamp) só no **36.1** (Android 16 QPR2) ou SDK ext. 18; highlight
  **sem campo de nota**; `write()` reescreve o arquivo inteiro (não incremental).
- **Nenhum API level** expõe sumário (TOC), page labels, metadados ou tamanho de página sem abrir a
  página. Anotações existentes nunca são desenhadas no API 21–34.
- Vantagens reais: **0 bytes no APK** e correções de segurança do PDFium entregues pelo Google
  (Mainline, Android 11+).

### androidx.pdf 1.0.0-beta01
- Mesmo backend (e mesmas lacunas: sem TOC, sem caixas por caractere); texto/busca só API 35 ou ext.
  13; render-only em API 28–30.
- Serviço em `isolatedProcess` (bom para crash de PDF malformado), mas **IPC por tile** e um bitmap
  ARGB_8888 novo por tile (sem pool); `PdfDocument` tem membros abstratos `@RestrictTo`, então não
  serve como interface de engine nossa.
- O composable `PdfViewer` é um `AndroidView` experimental; sem modo sépia/escuro, sem modo
  paginado, highlights só como retângulos preenchidos.
- Correções de vazamento de memória e races pós-beta01 ainda não publicadas (25/09/2026).

### PDFium com JNI próprio
- A API C expõe tudo o que precisamos: `FPDF_LoadCustomDocument` (streaming, arquivos de 1 GB),
  `FPDF_GetPageSizeByIndexF` (tamanhos **sem carregar páginas**), `FPDF_RenderPageBitmapWithMatrix`
  (tiles), `FPDF_RenderPageBitmap_Start/Continue` + `IFSDK_PAUSE` (**render cancelável**),
  `FPDFText_*` (caixas por caractere, `GetCharIndexAtPos`, busca), `FPDFBookmark_*` (**TOC**),
  `FPDF_GetPageLabel`, `FPDFLink_*`, `FPDFPage_CreateAnnot(HIGHLIGHT)` +
  `FPDFAnnot_AppendAttachmentPoints` + `SetStringValue("Contents")` (**highlight com nota**) e
  `FPDF_SaveAsCopy(FPDF_INCREMENTAL)`.
- Várias páginas podem ficar abertas (cache LRU de `FPDF_PAGE`/`FPDF_TEXTPAGE`), ao contrário do
  framework. Benchmarks do PdfiumAndroidKt: reabrir a página a cada passe custa ~6–7 ms vs ~3 ms com
  a página mantida aberta.
- **Não é thread-safe** (fpdfview.h): uma fila serial por processo — igual ao framework, que já
  serializa tudo.
- `bblanchon/pdfium-binaries`: release semanal, Android arm/arm64/x64/x86, sem V8/XFA, libc++
  estática, **alinhado a 16 KB**. arm64: 6,5 MB descomprimido / **3,3 MB comprimido** por ABI.
- Custo: somos donos das atualizações de segurança (CVEs de PDFium aparecem com frequência nos
  releases do Chrome) e do código JNI (C++).

### io.legere:pdfiumandroid 2.0.3
- Bom wrapper (streaming por fd, render com matriz, caixas por caractere, busca, TOC, links,
  coroutines), mas **não lê nem cria anotações**, não tem page labels nem render cancelável.
- **Binário PDFium de 07/08/2025, procedência não documentada** (issue #54 sem resposta), exige
  API 26 na prática (issue #55), puxa Guava; um mantenedor; bug de mutex desbloqueado duas vezes no
  JNI. Serve para protótipo, não para produção.

### MuPDF 1.28.5
- Tecnicamente o mais completo: render muito rápido direto em `Bitmap` com patches (tiles),
  cancelamento via `Cookie`, `StructuredText.highlight/snapSelection/copy` (quads prontos para
  anotação), busca com regex/acentos, anotações Highlight completas com journal undo/redo e save
  incremental; manutenção muito ativa.
- **Licença AGPL-3.0**: segundo o guia Android da própria Artifex, o app inteiro precisa ser
  open source e não pode incluir componentes proprietários (Play Services, Firebase/Crashlytics,
  AdMob — e provavelmente Play Billing e ML Kit). Alternativa: licença comercial com preço sob
  consulta.
- ~11,4 MB descomprimido / ~5,6 MB comprimido por ABI (medido num build de terceiros 1.27.1);
  store de 256 MB por padrão (ajustável só por variável de ambiente); save incremental relê o
  arquivo inteiro; artefatos fora do Maven Central (`maven.ghostscript.com`).

### Outros
- **PdfBox-Android**: sem manutenção desde 2023, sem decoder JBIG2 (PDFs escaneados), render lento
  em Java, BouncyCastle de 9,3 MB. No máximo como escritor de exportação isolado.
- **pdf.js/WebView**: canvas limitado a 5 MP no Android, processo do WebView, salvar exige o
  arquivo inteiro em memória JS. Inadequado para 1 GB.
- **Comerciais**: excelentes, porém 22–63 MB de código nativo por ABI e licenças anuais de milhares
  a dezenas de milhares de USD (estimativas de terceiros).

## 3. Pontuação ponderada

Pesos do §70: Muito alto = 3, Alto = 2. Notas 1–5.

| Critério (peso) | Framework | androidx.pdf | **PDFium + JNI** | pdfiumandroid | MuPDF | PdfBox | pdf.js | Comercial |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Rendering (3) | 3 | 3 | 4 | 4 | 5 | 1 | 2 | 5 |
| Memória (3) | 4 | 3 | 4 | 4 | 4 | 2 | 1 | 4 |
| PDFs grandes (3) | 2 | 2 | 4 | 4 | 4 | 2 | 1 | 4 |
| Anotações (2) | 1 | 3 | 4 | 2 | 5 | 4 | 3 | 5 |
| Seleção de texto (3) | 2 | 4 | 4 | 4 | 5 | 2 | 3 | 5 |
| Extração de texto (2) | 2 | 3 | 4 | 4 | 5 | 3 | 3 | 5 |
| Licença (3) | 5 | 5 | 5 | 5 | 2 | 5 | 5 | 1 |
| Tamanho do APK (2) | 5 | 4 | 3 | 3 | 3 | 3 | 4 | 2 |
| Integração Android (2) | 3 | 3 | 3 | 3 | 3 | 3 | 2 | 5 |
| Manutenção (2) | 3 | 4 | 4 | 2 | 5 | 1 | 5 | 5 |
| **Total (máx. 125)** | **76** | **85** | **99** | **91** | **102** | **64** | **70** | **101** |

Leitura da tabela:

- **MuPDF** e **comerciais** lideram tecnicamente, mas a nota de licença não captura o fato de que
  ela é um **bloqueio**, não um desconto: AGPL obriga o app inteiro a ser AGPL e sem SDKs
  proprietários; comercial significa custo anual e dezenas de MB por ABI.
- Entre as opções **sem custo e sem obrigação de abrir o código**, **PDFium + JNI próprio** é a melhor
  por margem clara (99 vs 91 do wrapper pronto, que não escreve anotações e tem binário antigo).

## 4. Decisão

**Engine principal: PDFium com camada JNI própria** (binário `bblanchon/pdfium-binaries` pinado,
SHA-256 verificado, baixado por task Gradle; atualização mensal e imediata em CVE).
**Fallback: PdfRenderer do framework** (zero bytes, segurança via Google), usado se a biblioteca
nativa não carregar e como engine da Fase 1.

Ambas atrás de `PdfEngine` (`core:pdf`), com `EngineCapabilities` para a UI ocultar o que não existe.

Por que não as outras:

| Engine | Motivo da rejeição |
|---|---|
| Framework (como principal) | Sem TOC, sem caixas por caractere, texto só no API 35/ext. 13, anotação só 36.1, sem cancelamento |
| androidx.pdf | Mesmas lacunas + IPC por tile + beta/experimental; poderia virar backend secundário |
| pdfiumandroid | Sem anotações; binário PDFium antigo de procedência desconhecida |
| MuPDF | AGPL-3.0 (ou licença paga) — ver §5 |
| PdfBox / pdf.js | Performance e memória inaceitáveis para 1 GB / PDFs escaneados |
| Comerciais | Custo recorrente, 22–63 MB por ABI, lock-in |

### Implementação prevista (Fase 2)

- `core:pdf/pdfium`: CMake + JNI C++ mínimo; `FPDF_InitLibraryWithConfig` uma vez; documento aberto
  com `FPDF_LoadCustomDocument` sobre `pread` do fd; cache LRU de páginas e text pages; render em
  bitmaps do pool via `AndroidBitmap_lockPixels` (sem cópia) com `FPDF_REVERSE_BYTE_ORDER`;
  render progressivo com `IFSDK_PAUSE` consultando um flag de cancelamento.
- Fila serial única (`limitedParallelism(1)`) com prioridade (tiles visíveis > prefetch >
  medição > indexação).
- Hardening posterior: engine em serviço `android:isolatedProcess` para que um PDF malicioso não
  derrube a UI nem alcance dados do app.
- Notices de terceiros (FreeType, libjpeg-turbo, OpenJPEG, lcms2, libpng, zlib, ICU, abseil…) na
  tela "Licenças".

## 5. Decisão pendente do produto: modelo de licença

Se o GigReader for **open source sob AGPL-3.0** (sem SDKs proprietários) ou se houver orçamento para
a **licença comercial da Artifex**, o MuPDF passa a ser a melhor escolha técnica (seleção/anotações
mais completas com menos código nativo nosso). A troca é localizada: só um novo backend de
`PdfEngine`. Até essa decisão, seguimos com PDFium (sem custo, sem obrigação de licença).
