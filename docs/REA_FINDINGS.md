# REA investigation notes

Updated 15 October 2026 for Folio Scan 1.1. The customer requested document detection and automatic cleanup of existing colors with a scan effect. After the initial implementation, the customer requested the detector used by [OpenScan](https://github.com/ethereal-developers/OpenScan).

## Scope and source

[REA](https://github.com/morluto/rea) is an investigation tool, rather than a scanning SDK. Its Android static-analysis commands need a local APK. The supplied REA repository does not contain OKEN's implementation; no OKEN target APK was supplied. This project therefore makes no claim to have recovered OKEN's algorithms or reproduced its output exactly.

The replacement detection algorithm was adapted directly from OpenScan's public Dart source at commit `27bb7ab973fbb621fce182c43bc875856c785a30`, with its BSD-3-Clause notice bundled. REA was then used to inspect the resulting **Folio Scan** APK. This is verification of our packaged implementation, rather than a reverse engineering claim about either reference app. See [OpenScan implementation and parity notes](OPENSCAN_DETECTION.md).

## Local workflow

Analysis used the installed `rea-agents@6.5.0`, Node `24.19.0`, and a full Temurin JDK `17.0.20.1` at `/home/sanket/.jdk/current`. The selected JADX engine was `/tmp/folio-rea-research/jadx-headless-mcp-0.7.1-all.jar`, verified against SHA-256:

```text
6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75
```

The original environment check returned scoped `doctor --provider jadx` status `healthy: true`. No REA `setup`, agent-registration changes, or target execution were needed. CLI help confirmed JSON output and explicit overload selection. Package and class inspection preceded method inspection so each requested overload came from the actual packaged inventory.

The frozen universal 1.1 APK has SHA-256:

```text
33e0cef0aec4ec8a16e7a9c22763b6c19074ffff2c9ee6efcc863c62fc770ae2
```

The six regenerated records all contain that digest and the frozen deliverable path. The [evidence README](rea/README.md) lists every operation and exact reproduction command. Full raw JSON preserves request parameters, provider identity, producer responses, source status and limitations. Previous 1.0 observations remain archived separately.

## Result

REA observes version `1.1.0`/code `2`, minimum SDK 26, target SDK 36, and a manifest without Internet permission. It finds the new `OpenScanDetector` inventory and decompiles the two-argument processing entry point, the four-argument grayscale detector, and the color-enhancement entry point. All three method bodies are complete Java text with no decompiler fallback.

The packaged processing entry point delegates to `OpenScanDetector`; its inspected grayscale method applies blur/Sobel/Otsu, pools candidates across threshold masks after radius-4 dilation, and selects a quad using the previous-frame reference. The enhancement entry point retains a separate automatic-color branch which returns color after paper-lighting normalization and balance.

These are static observations of the supplied artifact. Runtime behavior, edge accuracy, native OpenCV execution, APK signatures and physical-device behavior need separate checks. [Android validation](VALIDATION.md) records the runtime and image tests. The upstream guides describe the [static Android workflow](https://github.com/morluto/rea/blob/main/docs/android-analysis.md) and optional [ADB acquisition workflow](https://github.com/morluto/rea/blob/main/docs/adb-device-analysis.md).
