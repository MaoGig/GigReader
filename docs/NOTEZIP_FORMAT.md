# Formato nativo `.gigreader` (v1)

Contêiner ZIP estruturado para exportar/importar um documento com tudo o que o usuário produziu sobre
ele. Pensado para backup, sincronização, portabilidade e migração futura (§31).

- Extensão: `.gigreader`
- MIME: `application/vnd.gigreader.package+zip`
- Implementação prevista: Fase 5 (`core:common` para leitura/escrita pura em `java.util.zip`,
  testável em JVM).

## Estrutura

```text
document.gigreader
├── mimetype                 "application/vnd.gigreader.package+zip" (primeira entrada, STORED)
├── manifest.json            versão do formato, inventário, hashes
├── document.pdf             o PDF original, byte a byte (STORED — PDFs já são comprimidos)
├── metadata.json            metadados do documento
├── annotations.json         anotações de texto (highlight/underline/strikeout)
├── bookmarks.json
├── reading.json             posição de leitura
├── notes/{noteId}.json      notas vinculadas ao documento
├── thumbnails/cover.webp    opcional (regenerável)
└── attachments/…            reservado (futuro)
```

- `document.pdf` é gravado com `STORED` (sem recompressão): exportar um PDF de 1 GB não gasta CPU
  comprimindo dados que já são comprimidos.
- Tudo é escrito em **stream** direto no destino SAF; nenhuma cópia temporária do PDF.
- Leitores devem **ignorar** entradas e campos desconhecidos (compatibilidade para a frente).

## `manifest.json`

```json
{
  "format": "gigreader",
  "formatVersion": 1,
  "createdBy": "GigReader 1.0 (Android)",
  "exportedAt": "2026-09-25T21:00:00Z",
  "document": { "path": "document.pdf", "sha256": "…", "size": 1234567 },
  "entries": ["metadata.json", "annotations.json", "bookmarks.json", "reading.json"]
}
```

A importação verifica o `sha256` do PDF (integridade) e usa-o para detectar duplicatas na biblioteca.

## `metadata.json`

```json
{
  "version": 1,
  "documentId": "3f2a…",
  "title": "Thesis",
  "fileName": "Thesis.pdf",
  "createdAt": "2026-01-10T12:00:00Z",
  "modifiedAt": "2026-09-20T08:30:00Z",
  "pageCount": 254,
  "favorite": true,
  "tags": ["diffusion", "research"],
  "entityVersion": 7
}
```

## `annotations.json`

```json
{
  "version": 1,
  "annotations": [
    {
      "id": "a1b2…",
      "page": 42,
      "type": "HIGHLIGHT",
      "text": "Anomalous diffusion…",
      "rects": [[0.112, 0.304, 0.871, 0.321], [0.112, 0.323, 0.540, 0.340]],
      "color": "yellow",
      "note": "Compare with Chapter 4.",
      "charStart": 1834,
      "charEnd": 1901,
      "createdAt": "2026-09-20T08:30:00Z",
      "modifiedAt": "2026-09-20T08:31:00Z",
      "entityVersion": 2
    }
  ]
}
```

- `page` é zero-based.
- `rects`: `[left, top, right, bottom]` normalizados (0..1) no espaço da página sem rotação; para
  pontos PDF multiplique por largura/altura da página.
- `color`: chave da paleta (`yellow`, `green`, `blue`, `pink`, `orange`, `purple`).

## Regras de importação

1. Validar `mimetype`, `formatVersion` (≤ versão suportada; maior → erro amigável).
2. Transmitir `document.pdf` para `library/.incoming` calculando SHA-256; comparar com o manifesto.
3. Se o hash já existe na biblioteca: mesclar anotações por `id` (maior `entityVersion` vence;
   empate com conteúdo diferente = manter ambas).
4. Tudo numa transação; o arquivo só é renomeado para o destino final após o sucesso.

## Versionamento

- Mudanças compatíveis (campos novos) não alteram `formatVersion`.
- Mudanças incompatíveis incrementam `formatVersion`; o importador mantém leitores das versões antigas.
