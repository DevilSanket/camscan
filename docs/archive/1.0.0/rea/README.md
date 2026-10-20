# REA checks of the Folio Scan APK

These are REA 6.5.0 observations of this project’s own Android app, **Folio Scan**, not observations of an OKEN APK. Analysis ran locally with the verified JADX 0.7.1 JAR, a full JDK 17, and Node 24. The target APK was not executed by these operations.

The package inspection observed `com.folio.scanner` version `1.0.0`, minimum SDK 26 and target SDK 36. The decoded manifest contains `MainActivity` with a launcher intent, camera permission, and AndroidX’s internal signature permission. No Internet permission is declared.

The class inventory contains `detect(Bitmap)`, `warp(Bitmap, PointF[])`, and `enhance(Bitmap, Filter)`. Both inspected method bodies were returned as complete Java text with no fallback. The detector uses Canny edges and a thresholded paper mask before selecting a candidate. The enhancement method’s automatic-color branch normalizes luminance, balances paper color, merges the color channels and returns a color image; grayscale and adaptive-threshold branches are separate.

These results establish what the decompiler observed in the supplied APK. They do not establish image quality, runtime execution, native OpenCV correctness, APK signature validity, or equivalence to OKEN. Runtime and image-quality tests belong to the app’s separate validation results.

Each JSON retains its original request, APK digest, raw producer responses, provider identity, source status and limitations. All four results below refer to the frozen universal deliverable at `deliverables/folio-scan-universal-debug.apk`. Its SHA-256 was checked against the final artifact and every REA result. These records do not use the changing Gradle build-output path.

| Evidence file | Operation | APK SHA-256 |
| --- | --- | --- |
| [folio-package.json](folio-package.json) | `inspect_android_package` | `537c6226356a863fe2f028c00b35e659c4743bafb07448573be616b53461c860` |
| [folio-processor-class.json](folio-processor-class.json) | `inspect_android_class` | `537c6226356a863fe2f028c00b35e659c4743bafb07448573be616b53461c860` |
| [folio-detect.json](folio-detect.json) | `inspect_android_method` | `537c6226356a863fe2f028c00b35e659c4743bafb07448573be616b53461c860` |
| [folio-enhance.json](folio-enhance.json) | `inspect_android_method` | `537c6226356a863fe2f028c00b35e659c4743bafb07448573be616b53461c860` |

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
inspect-android-method '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' com.folio.scanner.processing.DocumentProcessor detect --format json
inspect-android-method '/home/sanket/Documents/CamScanner new/deliverables/folio-scan-universal-debug.apk' com.folio.scanner.processing.DocumentProcessor enhance --format json
```

The engine JAR SHA-256 in every result is `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`.

## Native APK variants

The universal artifact is the exact REA target above. The ARM64 artifact packages the same app code for devices using `arm64-v8a`; ZIP inspection found all five `classes*.dex` payload hashes identical across the two variants. Its other native architectures are omitted to reduce size. This comparison is a ZIP/DEX hash check, separate from the REA observations and runtime tests.

| Artifact | Native ABIs | Size | APK SHA-256 |
| --- | --- | --- | --- |
| [folio-scan-universal-debug.apk](../../deliverables/folio-scan-universal-debug.apk) | arm64-v8a, armeabi-v7a, x86, x86_64 | 139.4 MiB | `537c6226356a863fe2f028c00b35e659c4743bafb07448573be616b53461c860` |
| [folio-scan-arm64-debug.apk](../../deliverables/folio-scan-arm64-debug.apk) | arm64-v8a | 28.0 MiB | `93b7713e69b73e85e429ab9ba88e0d171258378b038018cec7b95363ecb88e9f` |
