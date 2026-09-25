# GigReader

Biblioteca pessoal, leitor de PDF e notas para Android — **local-first**, rápida com PDFs grandes
e econômica em bateria.

> *"Quando o usuário não está fazendo nada, o aplicativo também não deve estar fazendo nada."*

## Documentação

| Documento | Conteúdo |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Análise de requisitos, arquitetura, modelo de dados, cache, rendering, energia, navegação, módulos, riscos, MVP e plano incremental |
| [`docs/PDF_ENGINE_COMPARISON.md`](docs/PDF_ENGINE_COMPARISON.md) | PdfRenderer × androidx.pdf × PDFium × MuPDF × outros, com pontuação ponderada e decisão |
| [`docs/PERFORMANCE_AND_POWER.md`](docs/PERFORMANCE_AND_POWER.md) | CPU, GPU, memória, bateria, ciclo de vida, recomposição, benchmarks e metas |
| [`docs/NOTEZIP_FORMAT.md`](docs/NOTEZIP_FORMAT.md) | Formato nativo `.gigreader` (ZIP estruturado) |

## Estado

**Fase 1 — Fundação** (em andamento): biblioteca com pastas, importação, lixeira e desfazer;
leitor com viewport próprio (tiles, zoom, posição de leitura); notas rápidas; configurações;
diagnóstico (debug); benchmarks. Veja o plano de fases em `docs/ARCHITECTURE.md` §13.

## Stack

Kotlin 2.4 · Jetpack Compose (BOM 2026.09) · Material 3 · Room 2.8 + KSP · DataStore ·
Coroutines/Flow · AGP 9.4 (Kotlin embutido) · Gradle 9.6 · compileSdk 37 · minSdk 26 · targetSdk 36.

Sem framework de DI, sem biblioteca de navegação, sem biblioteca de imagens: cada dependência está
justificada na arquitetura.

## Módulos

```text
app                 Activity, navegação, DI manual, intents, diagnóstico (debug)
core/model          [JVM] entidades, ordenação, sync, configurações
core/common         [JVM] layout/viewport/tiles, LRU, undo, autosave, IO atômico
core/database       Room (schema v1 com metadados de sync)
core/data           repositórios, importação, capas, configurações
core/pdf            PdfEngine, backend do framework, pipeline de render, caches
core/ui             tema e componentes
feature/library     biblioteca / Home
feature/reader      leitor
feature/notes       notas rápidas
feature/settings    configurações
benchmark           Macrobenchmark + Baseline Profile
```

## Build

Requer JDK 17+ (recomendado 21) e o Android SDK com a plataforma 37.

```bash
./gradlew :app:assembleDebug          # APK de debug
./gradlew test                        # testes JVM (inclui Room via Robolectric)
./gradlew :core:model:test :core:common:test   # lógica pura, segundos
./gradlew :benchmark:connectedBenchmarkAndroidTest   # benchmarks (aparelho físico)
```

O CI (GitHub Actions) executa testes, lint e gera o APK de debug como artefato a cada push.
