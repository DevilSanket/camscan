# OpenScan document detection in Folio 1.1.0

Folio's camera and imported-photo flows now use a Java adaptation of the current OpenScan document detector. The implementation is pinned to [OpenScan commit `27bb7ab`](https://github.com/ethereal-developers/OpenScan/tree/27bb7ab973fbb621fce182c43bc875856c785a30), rather than the native plugin used in older OpenScan releases.

## Source and license

The upstream files used for the detector are:

- [`document_detector.dart`](https://github.com/ethereal-developers/OpenScan/blob/27bb7ab973fbb621fce182c43bc875856c785a30/lib/core/cv/document_detector.dart): image reduction, grayscale conversion, threshold candidates, and selection.
- [`edge_detection.dart`](https://github.com/ethereal-developers/OpenScan/blob/27bb7ab973fbb621fce182c43bc875856c785a30/lib/core/cv/edge_detection.dart): blur, Sobel gradients, Otsu threshold, and dilation.
- [`contours.dart`](https://github.com/ethereal-developers/OpenScan/blob/27bb7ab973fbb621fce182c43bc875856c785a30/lib/core/cv/contours.dart): connected components, convex hulls, polygon simplification, corner ordering, and consensus.
- `models/point.dart` and `models/quad.dart`: geometry helpers.

The live tracker also adapts the upstream [`quad_smoother.dart`](https://github.com/ethereal-developers/OpenScan/blob/27bb7ab973fbb621fce182c43bc875856c785a30/lib/view/screens/live_scan/quad_smoother.dart) and [`auto_capture_detector.dart`](https://github.com/ethereal-developers/OpenScan/blob/27bb7ab973fbb621fce182c43bc875856c785a30/lib/view/screens/live_scan/auto_capture_detector.dart).

OpenScan's BSD 3-Clause copyright notice names Vijay T S and Vikram H. Full terms are retained in [the source license](../licenses/OpenScan-BSD-3-Clause.txt), `app/src/main/assets/licenses/OpenScan-BSD-3-Clause.txt`, and the app's third-party notices. The user can read the notice through **About → Third-party notices**. The copied Dart reference files have their own [license copy](../tools/openscan_reference/LICENSE).

## Detector pipeline

`processing/OpenScanDetector.java` implements the byte-buffer algorithm directly. It preserves upstream sampling, rounding, border handling, threshold comparisons, and geometric selection rather than substituting OpenCV operators.

1. Reduce the longest image edge to at most **700 pixels**, using floor-indexed nearest-neighbor source sampling. Convert RGB with Rec. 709 weights: `0.2126 R + 0.7152 G + 0.0722 B`.
2. Apply a **3×3 Gaussian kernel** divided by 16, with clamped borders and rounded values.
3. Compute **Sobel gradient magnitude**, clamp it to 255, and round.
4. Compute an **Otsu threshold**. Test rounded multiples **0.7, 1.0, and 1.3**, using an inclusive `>=` comparison.
5. Dilate each edge mask by a **square radius of 4**. Find **8-connected components**, sort by member-pixel count, and examine the largest **five**.
6. Compute a monotone-chain **convex hull**. Simplify closed hulls with Ramer–Douglas–Peucker tolerances of **2%, 4%, 6%, 8%, and 10%** of perimeter. Keep convex four-corner polygons occupying at least **5%** of the image, with angles from **15° to 165°**.
7. Order and align corners. Group candidates whose average corner distance is at most **3% of the frame diagonal**, then average each cluster. Rank by area and squareness, with a support bonus for candidate agreement. Live processing adds an upstream proximity preference for the previous accepted raw quadrilateral.

The returned quality value contains only area/squareness quality. Candidate-support and previous-page preferences select the result; they are not reported as an accuracy probability. No fixed quality cutoff discards a selected page.

`DocumentProcessor.detect(bitmap)` delegates to this implementation for imported images and captured photos. The live overload accepts previous normalized raw corners. The Android adapter converts between OpenScan's working-pixel coordinates and Folio's normalized pixel-center coordinates, preserving the perspective warp's `size - 1` convention. OpenCV remains responsible for perspective correction and the existing scan filters.

## Why this fixes the reproduced misses

The original Folio detector combined Canny contours with a bright-paper mask at a 960-pixel limit. Its gates included a 12% minimum page area, a minimum edge length, a near-frame rejection, a bright-paper threshold, and a final score cutoff of 0.60. These could reject a valid page before the user saw its corners.

Version 1.1.0 removes those old gates and follows OpenScan's threshold and consensus selection. An archived copy of the original implementation lives **only in the Android test source** as `LegacyV1DocumentProcessor`. The regression test verifies that it misses four synthetic inputs—faint page, small page, narrow receipt, and near-frame page—and that the new implementation detects each. This is direct before/after evidence on those inputs, not an accuracy claim about every photograph.

## Live-camera integration

`PreviewView.getBitmap()` includes the full view, including bars produced by `FIT_CENTER`. `PreviewFrameMapper` uses CameraX's output transform to find the actual visible content rectangle. Detection receives that content crop; the same integer bounds map the detected corners back to the full overlay. The bitmap is already a view snapshot, so raw sensor rotation is not applied to its corners again.

`LivePageTracker` matches all 24 corner assignments to maintain correspondence, then applies a One Euro filter to the displayed corners. A large competing contour must agree across three detections before replacing the tracked page. The previous raw accepted contour, rather than the smoothed overlay, guides the detector's live candidate preference.

A missing detection may leave the overlay visible for **500 ms**, but it immediately resets automatic-capture stability. Capture requires at least **three fresh accepted detections** and **700 ms** of stability, within 2% per coordinate of both the previous raw frame and a fixed stability anchor. The fixed anchor is a Folio addition that prevents gradual drift from accumulating into a capture. A **two-second cooldown** follows capture. Lifecycle changes, auto toggles, viewport changes, and stale worker results clear or invalidate tracking.

## Independent parity checks

Nine synthetic grayscale fixtures are stored in `app/src/androidTest/assets/openscan/golden-fixtures.json`: skewed page, faint page, narrow receipt, small page, near-frame page, steep perspective, tilted page, blank white, and blank dark.

Expected coordinates were generated by running the original OpenScan Dart functions, independently of the Java implementation. `tools/openscan_reference` contains unchanged edge/component/geometry sources and the original grayscale detector function with lightweight imports. Its runner has no Flutter or image-package dependency.

To regenerate expected coordinates with Dart:

```sh
dart tools/openscan_reference/runner.dart \
  app/src/androidTest/assets/openscan/golden-fixtures.json \
  /tmp/folio-openscan-golden-fixtures.json
```

The development run used Dart 3.13.5. The runner reads each grayscale input and replaces its expected coordinates, so the checked-in fixture file can serve as its input. Review regenerated output before replacing the checked-in asset.

`OpenScanParityTest` checks Java coordinates against original Dart results with a tolerance of **`1e-6` pixels**, exercises the Android normalization adapter, verifies blank inputs, tests previous-page candidate preference, and compares four misses with the old detector. [The targeted run](openscan-parity-results.txt) passed all three tests. [The full suite](instrumentation-results.txt) passed 27 tests before the final baseline test was added; 28 distinct tests have passed across the two runs.

Additional runtime tests cover camera mapping and tracking; warp, color retention, storage, and export regressions remain passing. See [VALIDATION.md](VALIDATION.md) for packaging and smoke-check status. Physical-phone behavior and representative customer photographs remain unverified.
