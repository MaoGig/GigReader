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
- **Uma página aberta por vez** (API 21–34); lock estático de processo em toda chamada (desde o Android 7.1.1; antes disso a classe nem sincroniza as chamadas e se declara "not thread safe") —
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
  `FPDF_GetPageLabel`, `FPDFLink_*`, `FPDFPage_GetAnnotCount`/`GetAnnot` (leitura),
  `FPDFPage_CreateAnnot(HIGHLIGHT)` + `FPDFAnnot_AppendAttachmentPoints` + `FPDFAnnot_SetRect`
  (a `/Rect` é obrigatória e não é criada automaticamente) + `SetColor` +
  `SetStringValue("Contents")` (**highlight com nota**) e `FPDF_SaveAsCopy(FPDF_INCREMENTAL)`.
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

> **Decisão final (dono do produto, 2026-09-29): engine principal = MuPDF 1.28.5**
> (`com.artifex.mupdf:fitz`, AGPL-3.0, repositório `https://maven.ghostscript.com`).
> **Fallback: PdfRenderer do framework**, mantido (zero bytes, segurança via Google) e usado
> automaticamente se a biblioteca nativa do MuPDF não carregar.

A análise técnica acima (§3) já apontava o MuPDF como a melhor engine; a única objeção era a licença,
e o dono do produto aceitou a consequência (§5). A opção anterior (PDFium + JNI próprio) fica
registrada como alternativa caso a decisão de licença mude: a troca continua localizada em um backend
de `PdfEngine`.

### Consequência da AGPL-3.0

- **Distribuir o app com o MuPDF exige liberar o código-fonte do app inteiro sob AGPL-3.0** (a quem
  receber o APK, incluindo lojas) **ou comprar uma licença comercial da Artifex** (preço sob consulta).
- Enquanto for AGPL: nada de componentes proprietários incompatíveis (Play Services, Firebase/
  Crashlytics, AdMob, provavelmente Play Billing e ML Kit). O app não usa nenhum deles hoje.
- O app mostra o aviso na tela **Configurações → Licenças de código aberto** (MuPDF, AGPL-3.0,
  Artifex, links para mupdf.com e o texto da licença). O arquivo `LICENSE` do repositório é decisão
  do dono e **não** foi adicionado; o README traz uma nota "Licença".

### Como ficou (Fase 2, backend implementado)

- `core:pdf/mupdf`: `MuPdfEngine` (`id = "mupdf"`) + `MuPdfDocument`. Capacidades: busca, extração
  de texto, sumário, links, tamanhos de página baratos e **senha em todos os API levels**; seleção de
  texto (Fase 3) e escrita de anotações (Fase 5) ainda desligadas.
- Abertura: `File` por caminho (`Document.openDocument(path)`) quando termina em `.pdf`; caso
  contrário (arquivos `.part` da importação) e para `ParcelFileDescriptor`, por `SeekableInputStream`
  sobre o `FileChannel` do descritor com o formato forçado (`application/pdf`). O MuPDF não tem API de
  fd e reabrir `/proc/self/fd/N` falha para arquivos de provedores; o stream funciona sempre e o
  descritor é fechado pela engine.
- Threads: MuPDF não é thread-safe por documento, mas documentos são independentes (o store global
  tem lock). Cada documento tem sua **própria fila serial** (`Dispatchers.IO.limitedParallelism(1)`);
  todo `Page`/`StructuredText`/`Device`/`Cookie`/`Link` é destruído em `finally`.
- Render: `Page.run` com `AndroidDrawDevice` direto no bitmap do pool (patch = tile), fundo branco,
  anotações e widgets incluídos.
- Tamanhos: lidos da árvore de páginas (`findPage` + MediaBox/CropBox/Rotate herdáveis + UserUnit),
  sem carregar a página (`cheapPageSizes = true`).
- Busca: `StructuredText` percorrido caractere a caractere, texto normalizado (NFKD, sem marcas,
  minúsculas, espaços colapsados; ligaduras casam com suas letras; quebra de linha vale espaço) e
  retângulos por linha em coordenadas normalizadas. Lógica pura em `PageTextIndex` (testada na JVM).
- Memória: `MuPdfEngine.trimMemory(level)` reduz/esvazia o store do MuPDF (`Context.shrinkStore`/
  `emptyStore`) em `onTrimMemory`; nada acontece se o MuPDF nunca foi usado.
- Seleção da engine: `FallbackPdfEngine` tenta o MuPDF; se a lib nativa falhar
  (`UnsatisfiedLinkError`/`ExceptionInInitializerError`) passa **uma vez** ao framework e lembra. `id`
  reflete a engine ativa (aparece no diagnóstico). Documento corrompido **não** é repetido no
  framework: o erro é mostrado como está.
- Build: `maven.ghostscript.com` só é consultado para o grupo `com.artifex.mupdf`
  (`exclusiveContent`). O AAR não traz regras de consumidor; `core/pdf/consumer-rules.pro` mantém
  `com.artifex.mupdf.fitz.**` (o JNI acessa campos e construtores pelo nome).
- Custo medido em terceiros: ~5,6 MB comprimidos por ABI (mais `libarchive.so`); considerar
  `abiFilters`/App Bundle na distribuição.

Ambas as engines ficam atrás de `PdfEngine` (`core:pdf`), com `EngineCapabilities` para a UI ocultar
o que não existe e `PdfEngine.supportsPasswords` para o diálogo de senha.

Por que não as outras:

| Engine | Motivo da rejeição |
|---|---|
| Framework (como principal) | Sem TOC, sem caixas por caractere, texto só no API 35/ext. 13, anotação só 36.1, sem cancelamento; **mantido como fallback** |
| androidx.pdf | Mesmas lacunas + IPC por tile + beta/experimental; poderia virar backend secundário |
| PDFium + JNI próprio | Segunda colocada (99 vs 102): sem obrigação de licença, mas exige JNI/C++ e atualizações de segurança por nossa conta; reavaliar se a AGPL deixar de ser aceitável |
| pdfiumandroid | Sem anotações; binário PDFium antigo de procedência desconhecida |
| PdfBox / pdf.js | Performance e memória inaceitáveis para 1 GB / PDFs escaneados |
| Comerciais | Custo recorrente, 22–63 MB por ABI, lock-in |

## 5. Modelo de licença (decidido)

O produto adota o **MuPDF sob AGPL-3.0**. Para distribuir o app (APK em loja ou fora dela) é preciso
**(a)** publicar o código-fonte completo do app sob AGPL-3.0, ou **(b)** adquirir a licença comercial
da Artifex antes da distribuição. Enquanto o app não for distribuído, nada é exigido. Se um dia for
preferível um app fechado sem custo de licença, o caminho é PDFium + JNI (tabela acima); a troca é só
um novo backend de `PdfEngine`.
