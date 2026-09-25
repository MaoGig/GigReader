package com.maogig.gigreader.core.common.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TextNormalizerTest {
    @Test
    fun foldsCaseAccentsAndWhitespace() {
        assertEquals("difusao anomala", TextNormalizer.normalize("  Difusão   ANÔMALA "))
        assertEquals(listOf("anomalous", "diffusion"), TextNormalizer.terms("Anomalous\tdiffusion"))
        assertEquals(emptyList(), TextNormalizer.terms("   "))
    }

    @Test
    fun titlesFromFileNames() {
        assertEquals("Thesis", FileNames.titleFromFileName("Thesis.PDF"))
        assertEquals("paper.v2", FileNames.titleFromFileName("/storage/x/paper.v2.pdf"))
        assertEquals("Untitled", FileNames.titleFromFileName(".pdf"))
        assertEquals("Untitled", FileNames.titleFromFileName(null))
    }

    @Test
    fun sanitizesExportNames() {
        assertEquals("a_b_c", FileNames.sanitize("a/b:c"))
        assertEquals("document", FileNames.sanitize("..."))
        assertEquals("x.pdf", FileNames.ensureExtension("x", "pdf"))
        assertEquals("x.PDF", FileNames.ensureExtension("x.PDF", "pdf"))
    }
}
