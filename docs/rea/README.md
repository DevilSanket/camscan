# REA checks of Folio Scan 1.1

Verified on 15 October 2026 using the installed REA 6.5.0 CLI, JADX 0.7.1, JDK 17 and Node 24. These observations concern this project's own **Folio Scan** APK. REA did not analyze an OKEN APK or run the target app.

The frozen target is [folio-scan-universal-debug.apk](../../deliverables/folio-scan-universal-debug.apk), SHA-256:

```text
33e0cef0aec4ec8a16e7a9c22763b6c19074ffff2c9ee6efcc863c62fc770ae2
```

All six JSON records retain that exact absolute target path and digest, the request, raw producer responses, engine identity and limitations. They do not target Gradle's changing build-output file. The previous 1.0 records are preserved under [docs/archive/1.0.0/rea](../archive/1.0.0/rea/).

## Observations

The decoded manifest declares `com.folio.scanner`, version `1.1.0`/code `2`, minimum SDK 26 and target SDK 36. It contains the launcher activity, camera permission and AndroidX's internal signature permission. No Internet permission is declared.

The processing inventory now includes both `DocumentProcessor.detect(Bitmap)` and `detect(Bitmap, PointF[])`, plus `warp` and `enhance`. The inspected two-argument `detect` body validates the bitmap and delegates to `OpenScanDetector.detect` with the previous corners. The new detector inventory contains the byte-buffer pipeline, including `blur`, `sobel`, `otsu`, `dilate`, hull/RDP candidate helpers and `pickBest`.

The inspected four-argument `OpenScanDetector.detectGrayscale` body computes Sobel magnitude after blur, uses an Otsu-derived threshold loop, dilates each binary mask by radius 4, pools candidate quads, and calls `pickBest` with the previous corners. This confirms the detector entry point packaged in the APK; the full upstream adaptation and its parity tests are described in [OPENSCAN_DETECTION.md](../OPENSCAN_DETECTION.md).

The `enhance` automatic-color branch still normalizes lighting, balances paper color, merges Lab channels and returns a color image. Grayscale and adaptive black-and-white branches are separate. This implements cleanup of existing color, rather than synthesized photo colorization.

All three inspected method results report complete Java source, an available body, `fell_back: false` and no fallback markers. Static decompilation does not establish runtime image quality, native OpenCV correctness, APK signature validity, or equivalence to OKEN. See [VALIDATION.md](../VALIDATION.md) for the separate Android tests.

| Evidence | Operation / selected member |
| --- | --- |
| [folio-package.json](folio-package.json) | `inspect_android_package` |
| [folio-processor-class.json](folio-processor-class.json) | `DocumentProcessor` member inventory |
| [folio-openscan-class.json](folio-openscan-class.json) | `OpenScanDetector` member inventory |
| [folio-detect.json](folio-detect.json) | `DocumentProcessor.detect`, overload 1: `(Bitmap, PointF[])` |
| [folio-openscan-grayscale.json](folio-openscan-grayscale.json) | `OpenScanDetector.detectGrayscale`, overload 1: `(byte[], int, int, P[])` |
| [folio-enhance.json](folio-enhance.json) | `DocumentProcessor.enhance`, sole overload |

## Reproduction

Command form used from the repository root:

```sh
JAVA_HOME=/home/sanket/.jdk/current \
REA_JADX_MCP_JAR=/tmp/folio-rea-research/jadx-headless-mcp-0.7.1-all.jar \
REA_JADX_HEAP_MIB=2048 \
/home/sanket/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node \
/home/sanket/.npm/_npx/af752b90495482d7/node_modules/rea-agents/scripts/rea.mjs \
inspect-android-package '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' --format json
```

The remaining invocations replace the final command with:

```text
inspect-android-class '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' com.folio.scanner.processing.DocumentProcessor --format json
inspect-android-class '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' com.folio.scanner.processing.OpenScanDetector --format json
inspect-android-method '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' com.folio.scanner.processing.DocumentProcessor detect --overload-index 1 --format json
inspect-android-method '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' com.folio.scanner.processing.OpenScanDetector detectGrayscale --overload-index 1 --format json
inspect-android-method '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' com.folio.scanner.processing.DocumentProcessor enhance --format json
```

The engine JAR SHA-256 in every record is `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`. Overload indices were obtained from the corresponding class inventory and are specific to this engine and artifact.
