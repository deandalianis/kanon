# extractor-javaparser

`extractor-javaparser` is the semantic-first Java extraction backend. It walks Java source files with JavaParser and emits normalized facts, provenance anchors, conflicts, and a confidence score.

## Responsibility

- Parse Java source files with JavaParser.
- Produce `ExtractionResult` facts for types, fields, and methods.
- Emit provenance ranges tied to source files and symbols.
- Report parse failures as extraction conflicts instead of crashing the workflow.

## Module Position

```mermaid
flowchart LR
  source["Java source tree"] --> parser["JavaParserExtractorBackend"]
  parser --> facts["ExtractionResult"]
  facts --> core["compiler-core / specctl / workbench-api"]
```

## Extraction Logic

```mermaid
flowchart TD
  root["sourceRoot"] --> walk["Files.walk(...)"]
  walk --> parse["StaticJavaParser.parse(file)"]
  parse --> types["type facts"]
  parse --> fields["field facts"]
  parse --> methods["method facts"]
  types --> result["ExtractionResult"]
  fields --> result
  methods --> result
  parse --> provenance["range-based provenance"]
  provenance --> result
  parse --> conflicts["parse problems -> conflicts"]
  conflicts --> result
```

## Output Characteristics

- Emits `type`, `field`, and `method` facts.
- Includes parameter details for methods.
- Uses JavaParser ranges for provenance anchors.
- Computes a confidence score based on parsed files versus conflicts.

## Trade-Offs

- Better semantic detail than the Spoon backend for straightforward source trees.
- More sensitive to parse issues and symbol-resolution gaps.
- Works best when the Java source is syntactically coherent.

## Development Notes

- This module should stay a backend implementation only. Shared extraction contracts belong in `compiler-core`.
- If a new fact shape is introduced, keep the path conventions aligned with the Spoon backend so merge behavior stays predictable.

## Verification

- `.\gradlew.bat :tools:extractor-javaparser:test`

## Related Docs

- [Root README](../../README.md)
- [compiler-core](../compiler-core/README.md)
- [extractor-spoon](../extractor-spoon/README.md)
