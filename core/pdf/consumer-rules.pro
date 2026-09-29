# MuPDF's JNI layer looks up Java fields, methods and constructors of com.artifex.mupdf.fitz.* by
# name and signature (GetFieldID / GetMethodID / NewObject) and reads/writes the private `pointer`
# fields. R8 must therefore neither rename, remove nor shrink any of them. The fitz AAR ships no
# consumer rules of its own, so they live here.
-keep class com.artifex.mupdf.fitz.** { *; }
