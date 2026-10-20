import 'dart:typed_data';
import 'edge_detection.dart';
import 'contours.dart';
import 'models/quad.dart';

const List<double> _thresholdMultipliers = [0.7, 1.0, 1.3];

/// Runs the grayscale->blur->sobel->(multi-threshold)->dilate->quad
/// pipeline on an already-grayscale buffer. Pure function, no I/O —
/// shared by the file-based one-shot entry point above and the live-scan
/// persistent isolate, so the edge/contour logic only exists in one
/// place.
///
/// Unlike a single-threshold pipeline, this binarizes the Sobel magnitude
/// at several thresholds around the Otsu-computed one (see
/// [_thresholdMultipliers]), pools every valid candidate quad found across
/// all of them, and picks the best via [pickBestQuad]'s area/squareness
/// scoring — the same "try several strategies, score the pool" approach
/// the reference app's native detector uses, adapted to this pure-Dart
/// pipeline's single-channel (grayscale) input.
///
/// [previousQuad], when supplied, nudges [pickBestQuad] toward whichever
/// candidate best corresponds to it — see that function's doc comment.
/// Always null for the one-shot file-detection path above (no "previous
/// frame" concept there); the live-scan worker isolate supplies its own
/// last-seen quad.
Quad? detectQuadFromGrayscale(Uint8List gray, int width, int height,
    {Quad? previousQuad}) {
  final blurred = gaussianBlur3(gray, width, height);
  final magnitude = sobelMagnitude(blurred, width, height);
  final baseThreshold = otsuThreshold(magnitude);

  final candidates = <Quad>[];
  for (final multiplier in _thresholdMultipliers) {
    final t = (baseThreshold * multiplier).round().clamp(0, 255);
    final binary = threshold(magnitude, t);
    final dilated = dilate(binary, width, height, 4);
    candidates.addAll(findDocumentQuadCandidates(dilated, width, height));
  }

  return pickBestQuad(candidates, width, height, previousQuad: previousQuad);
}

