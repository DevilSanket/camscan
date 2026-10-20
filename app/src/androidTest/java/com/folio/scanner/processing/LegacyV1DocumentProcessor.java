/* Archived v1.0 detector used only to reproduce the reported misses in regression tests. */
package com.folio.scanner.processing;

import android.graphics.Bitmap;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * On-device document geometry and scan rendering. OpenCV must be initialized by the caller. All
 * operations are synchronous: call them from a worker thread, not the Android UI thread. This class
 * has no mutable instance state and may be used by separate preview/export workers.
 */
public final class LegacyV1DocumentProcessor {
  private static final int MAX_OUTPUT_EDGE = 2600;
  private static final int MAX_DETECTION_EDGE = 960;
  private static final double MIN_PAGE_AREA = 0.12;

  public enum Filter {
    AUTO_COLOR,
    ORIGINAL,
    GRAYSCALE,
    BLACK_WHITE
  }

  public static final class Detection {
    /** Normalized image coordinates, clockwise: top-left, top-right, bottom-right, bottom-left. */
    public final PointF[] corners;

    /** Measured geometric/edge quality in [0, 1]; this is not a calibrated probability. */
    public final double confidence;

    private Detection(PointF[] corners, double confidence) {
      this.corners = corners;
      this.confidence = confidence;
    }
  }

  /** Finds an actual page contour; returns null rather than substituting the image bounds. */
  public Detection detect(Bitmap bitmap) {
    requireBitmap(bitmap);
    try (Mats mats = new Mats()) {
      Mat rgba = boundedRgba(bitmap, MAX_DETECTION_EDGE, mats);
      Mat gray = mats.add(new Mat());
      Mat blur = mats.add(new Mat());
      Mat edges = mats.add(new Mat());
      Mat closedEdges = mats.add(new Mat());
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
      Imgproc.GaussianBlur(gray, blur, new Size(5, 5), 0);

      byte[] grayPixels = bytes(gray);
      int median = percentile(histogram(grayPixels), grayPixels.length, 0.50);
      double lower = Math.max(25, Math.min(80, median * 0.35));
      double upper = Math.max(75, Math.min(200, lower * 3));
      Imgproc.Canny(blur, edges, lower, upper);
      byte[] edgePixels = bytes(edges);
      Mat edgeKernel = mats.add(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5)));
      Imgproc.morphologyEx(edges, closedEdges, Imgproc.MORPH_CLOSE, edgeKernel);

      Candidate best =
          findPage(closedEdges, edgePixels, grayPixels, gray.cols(), gray.rows(), false, mats);

      // Bright paper on a dark desk can have broken edges under shadows. Thresholding
      // supplies another *observed* contour, but it goes through the same geometry checks.
      Mat paperMask = mats.add(new Mat());
      double otsu =
          Imgproc.threshold(blur, paperMask, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
      Imgproc.threshold(blur, paperMask, Math.max(125, otsu * 0.92), 255, Imgproc.THRESH_BINARY);
      Mat paperKernel = mats.add(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9)));
      Imgproc.morphologyEx(paperMask, paperMask, Imgproc.MORPH_CLOSE, paperKernel);
      Candidate bright =
          findPage(paperMask, edgePixels, grayPixels, gray.cols(), gray.rows(), true, mats);
      if (bright != null && (best == null || bright.score > best.score)) best = bright;
      if (best == null || best.score < 0.60) return null;

      PointF[] normalized = new PointF[4];
      for (int i = 0; i < 4; i++) {
        normalized[i] =
            new PointF(
                (float) (best.points[i].x / (gray.cols() - 1)),
                (float) (best.points[i].y / (gray.rows() - 1)));
      }
      return new Detection(normalized, best.score);
    }
  }

  /** Perspective-corrects a user-confirmed convex crop, limiting the longest output edge. */
  public Bitmap warp(Bitmap bitmap, PointF[] normalizedCorners) {
    requireBitmap(bitmap);
    validateNormalizedCorners(normalizedCorners);
    try (Mats mats = new Mats()) {
      Mat source = boundedRgba(bitmap, MAX_OUTPUT_EDGE, mats);
      Point[] points = new Point[4];
      for (int i = 0; i < 4; i++) {
        points[i] =
            new Point(
                normalizedCorners[i].x * (source.cols() - 1),
                normalizedCorners[i].y * (source.rows() - 1));
      }
      if (!convex(points) || polygonArea(points) < source.total() * 0.008) {
        throw new IllegalArgumentException("Crop corners must form a convex document area.");
      }
      double width = Math.max(distance(points[0], points[1]), distance(points[3], points[2])) + 1;
      double height = Math.max(distance(points[0], points[3]), distance(points[1], points[2])) + 1;
      if (width < 24 || height < 24) throw new IllegalArgumentException("Crop area is too small.");
      double scale = Math.min(1, MAX_OUTPUT_EDGE / Math.max(width, height));
      int outputWidth = Math.max(24, (int) Math.round(width * scale));
      int outputHeight = Math.max(24, (int) Math.round(height * scale));
      MatOfPoint2f from = mats.add(new MatOfPoint2f(points));
      MatOfPoint2f to =
          mats.add(
              new MatOfPoint2f(
                  new Point(0, 0),
                  new Point(outputWidth - 1, 0),
                  new Point(outputWidth - 1, outputHeight - 1),
                  new Point(0, outputHeight - 1)));
      Mat transform = mats.add(Imgproc.getPerspectiveTransform(from, to));
      Mat warped = mats.add(new Mat());
      Imgproc.warpPerspective(
          source,
          warped,
          transform,
          new Size(outputWidth, outputHeight),
          Imgproc.INTER_CUBIC,
          Core.BORDER_REPLICATE,
          new Scalar(255, 255, 255, 255));
      return bitmap(warped);
    }
  }

  /** Corrects paper lighting without replacing the original RGB image with a binary image. */
  public Bitmap enhance(Bitmap bitmap, Filter filter) {
    requireBitmap(bitmap);
    if (filter == null) throw new IllegalArgumentException("Choose a scan filter.");
    try (Mats mats = new Mats()) {
      Mat rgba = boundedRgba(bitmap, MAX_OUTPUT_EDGE, mats);
      if (filter == Filter.ORIGINAL) return bitmap(rgba);
      Mat rgb = mats.add(new Mat());
      Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
      Mat lab = mats.add(new Mat());
      Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
      List<Mat> channels = new ArrayList<>(3);
      try {
        Core.split(lab, channels);
      } finally {
        for (Mat channel : channels) mats.add(channel);
      }
      Mat luminance = channels.get(0);
      normalizeLighting(channels, mats);

      if (filter == Filter.AUTO_COLOR) {
        balancePaper(channels);
        Core.merge(channels, lab);
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);
        return bitmap(rgb);
      }
      if (filter == Filter.GRAYSCALE) return bitmap(luminance);
      Mat binary = mats.add(new Mat());
      int block = Math.min(81, Math.min(luminance.cols(), luminance.rows()) / 8);
      block = Math.max(3, block | 1);
      Imgproc.adaptiveThreshold(
          luminance,
          binary,
          255,
          Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
          Imgproc.THRESH_BINARY,
          block,
          12);
      return bitmap(binary);
    }
  }

  private static Candidate findPage(
      Mat mask, byte[] edges, byte[] gray, int width, int height, boolean brightPass, Mats mats) {
    List<MatOfPoint> contours = new ArrayList<>();
    Mat hierarchy = mats.add(new Mat());
    // findContours is allowed to modify its input; the original Canny evidence is retained.
    try {
      Imgproc.findContours(
          mask,
          contours,
          hierarchy,
          brightPass ? Imgproc.RETR_EXTERNAL : Imgproc.RETR_LIST,
          Imgproc.CHAIN_APPROX_SIMPLE);
    } finally {
      for (MatOfPoint contour : contours) mats.add(contour);
    }
    contours.sort(Comparator.comparingDouble((MatOfPoint c) -> Imgproc.contourArea(c)).reversed());
    Candidate best = null;
    int count = Math.min(contours.size(), 70);
    for (int index = 0; index < count; index++) {
      MatOfPoint contour = contours.get(index);
      double contourArea = Imgproc.contourArea(contour);
      if (contourArea < width * (double) height * MIN_PAGE_AREA * 0.85) break;
      MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
      MatOfPoint2f approximation = new MatOfPoint2f();
      try {
        double perimeter = Imgproc.arcLength(curve, true);
        for (double tolerance : new double[] {0.02, 0.035, 0.012}) {
          Imgproc.approxPolyDP(curve, approximation, tolerance * perimeter, true);
          if (approximation.total() != 4) continue;
          Point[] points = clockwiseCorners(approximation.toArray());
          Candidate candidate =
              evaluate(points, contourArea, edges, gray, width, height, brightPass);
          if (candidate != null && (best == null || candidate.score > best.score)) best = candidate;
          break;
        }
      } finally {
        approximation.release();
        curve.release();
      }
    }
    return best;
  }

  private static Candidate evaluate(
      Point[] points,
      double contourArea,
      byte[] edges,
      byte[] gray,
      int width,
      int height,
      boolean brightPass) {
    if (!convex(points)) return null;
    double area = polygonArea(points);
    double areaRatio = area / (width * (double) height);
    if (areaRatio < MIN_PAGE_AREA || areaRatio > 0.97) return null;
    double fit = Math.min(area, contourArea) / Math.max(area, contourArea);
    if (fit < 0.80) return null;
    int boundaryCorners = 0;
    int boundaryEdges = 0;
    double angleQuality = 0;
    for (int i = 0; i < 4; i++) {
      Point previous = points[(i + 3) % 4];
      Point current = points[i];
      Point next = points[(i + 1) % 4];
      if (distance(current, next) < Math.min(width, height) * 0.13) return null;
      double cosine =
          Math.abs(
              ((previous.x - current.x) * (next.x - current.x)
                      + (previous.y - current.y) * (next.y - current.y))
                  / (distance(previous, current) * distance(next, current)));
      if (cosine > 0.84) return null; // Reject very acute/flat corner artifacts.
      angleQuality += 1 - cosine;
      if (nearBoundary(current, width, height)) boundaryCorners++;
      if (sameBoundary(current, next, width, height)) boundaryEdges++;
    }
    // A thresholded full-frame rectangle is not a detected page.
    if (boundaryCorners >= 3 || boundaryEdges >= 2) return null;
    angleQuality /= 4;
    Evidence evidence = edgeEvidence(points, edges, gray, width, height);
    double contrast = clamp(evidence.inside - evidence.outside, 0, 65) / 65;
    if (evidence.edgeSupport < 0.28 && contrast < 0.45) return null;
    if (brightPass && evidence.inside < 135) return null;
    double paper = 0.5 * clamp((evidence.inside - 70) / 165, 0, 1) + 0.5 * contrast;
    double score =
        0.20 * Math.min(1, areaRatio / 0.55)
            + 0.15 * angleQuality
            + 0.10 * fit
            + 0.35 * evidence.edgeSupport
            + 0.20 * paper;
    score -= boundaryCorners * 0.035;
    if (score < 0.60) return null;
    return new Candidate(points, clamp(score, 0, 1));
  }

  private static Evidence edgeEvidence(
      Point[] points, byte[] edges, byte[] gray, int width, int height) {
    double supported = 0;
    int samples = 0;
    double inside = 0;
    double outside = 0;
    int toneSamples = 0;
    double offset = Math.max(4, Math.min(width, height) * 0.018);
    int radius = Math.max(2, (int) Math.round(Math.min(width, height) * 0.006));
    for (int side = 0; side < 4; side++) {
      Point a = points[side];
      Point b = points[(side + 1) % 4];
      double length = distance(a, b);
      double nx = -(b.y - a.y) / length;
      double ny = (b.x - a.x) / length;
      for (int sample = 0; sample < 28; sample++) {
        double fraction = 0.10 + sample * 0.80 / 27;
        double x = a.x + (b.x - a.x) * fraction;
        double y = a.y + (b.y - a.y) * fraction;
        boolean found = false;
        int centerX = (int) Math.round(x);
        int centerY = (int) Math.round(y);
        for (int dy = -radius; dy <= radius && !found; dy++) {
          int py = centerY + dy;
          if (py < 0 || py >= height) continue;
          for (int dx = -radius; dx <= radius; dx++) {
            int px = centerX + dx;
            if (px >= 0 && px < width && (edges[py * width + px] & 255) > 0) {
              found = true;
              break;
            }
          }
        }
        if (found) supported++;
        samples++;
        int inX = (int) Math.round(x + nx * offset);
        int inY = (int) Math.round(y + ny * offset);
        int outX = (int) Math.round(x - nx * offset);
        int outY = (int) Math.round(y - ny * offset);
        if (inX >= 0
            && inX < width
            && inY >= 0
            && inY < height
            && outX >= 0
            && outX < width
            && outY >= 0
            && outY < height) {
          inside += gray[inY * width + inX] & 255;
          outside += gray[outY * width + outX] & 255;
          toneSamples++;
        }
      }
    }
    return new Evidence(
        supported / Math.max(1, samples),
        inside / Math.max(1, toneSamples),
        outside / Math.max(1, toneSamples));
  }

  private static void normalizeLighting(List<Mat> channels, Mats mats) {
    Mat luminance = channels.get(0);
    // Estimate illumination from neutral paper, not from the artwork printed on it.
    // A plain blur/closing of L makes a large colored panel look like a shadow and creates
    // a bright center and a halo. Normalized weighted blur excludes that panel entirely.
    double scale = Math.min(1, 480.0 / Math.max(luminance.cols(), luminance.rows()));
    Size size =
        new Size(
            Math.max(1, Math.round(luminance.cols() * scale)),
            Math.max(1, Math.round(luminance.rows() * scale)));
    Mat small = mats.add(new Mat());
    Mat smallA = mats.add(new Mat());
    Mat smallB = mats.add(new Mat());
    Imgproc.resize(luminance, small, size, 0, 0, Imgproc.INTER_AREA);
    Imgproc.resize(channels.get(1), smallA, size, 0, 0, Imgproc.INTER_AREA);
    Imgproc.resize(channels.get(2), smallB, size, 0, 0, Imgproc.INTER_AREA);
    Mat closed = mats.add(new Mat());
    int kernelSize =
        Math.max(5, (int) Math.round(Math.min(small.cols(), small.rows()) * 0.035) | 1);
    Mat kernel =
        mats.add(
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(kernelSize, kernelSize)));
    Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, kernel);
    byte[] reducedLight = bytes(closed);
    byte[] reducedA = bytes(smallA);
    byte[] reducedB = bytes(smallB);
    float[] weightedValues = new float[reducedLight.length];
    float[] weights = new float[reducedLight.length];
    int[] paperHistogram = new int[256];
    int paperSamples = 0;
    for (int i = 0; i < reducedLight.length; i++) {
      int value = reducedLight[i] & 255;
      double chroma = Math.hypot((reducedA[i] & 255) - 128, (reducedB[i] & 255) - 128);
      double weight = clamp((18 - chroma) / 6, 0, 1) * clamp((value - 100) / 35.0, 0, 1);
      weights[i] = (float) weight;
      weightedValues[i] = (float) (value * weight);
      if (weight > 0.75) {
        paperHistogram[value]++;
        paperSamples++;
      }
    }
    // With no credible paper samples, preserve a colored photograph's lighting.
    if (paperSamples < reducedLight.length * 0.025) return;
    int prior = percentile(paperHistogram, paperSamples, 0.65);
    int paperWhite = percentile(paperHistogram, paperSamples, 0.90);
    Mat weighted = mats.add(new Mat(small.rows(), small.cols(), CvType.CV_32FC1));
    Mat weightMap = mats.add(new Mat(small.rows(), small.cols(), CvType.CV_32FC1));
    weighted.put(0, 0, weightedValues);
    weightMap.put(0, 0, weights);
    double sigma = Math.max(8, Math.min(small.cols(), small.rows()) * 0.08);
    Imgproc.GaussianBlur(weighted, weighted, new Size(0, 0), sigma);
    Imgproc.GaussianBlur(weightMap, weightMap, new Size(0, 0), sigma);
    weighted.get(0, 0, weightedValues);
    weightMap.get(0, 0, weights);
    for (int i = 0; i < reducedLight.length; i++) {
      // Interpolate surrounding paper over ordinary panels; for a very large hole the
      // gentle global prior keeps the illumination field finite and stable.
      double support = clamp(weights[i] / 0.03, 0, 1);
      double local = weights[i] > 0.0001 ? weightedValues[i] / weights[i] : prior;
      reducedLight[i] = (byte) Math.round(clamp(local * support + prior * (1 - support), 70, 255));
    }
    Mat background = mats.add(new Mat(small.rows(), small.cols(), CvType.CV_8UC1));
    background.put(0, 0, reducedLight);
    Mat fullBackground = mats.add(new Mat());
    Imgproc.resize(background, fullBackground, luminance.size(), 0, 0, Imgproc.INTER_LINEAR);

    byte[] light = bytes(luminance);
    byte[] a = bytes(channels.get(1));
    byte[] b = bytes(channels.get(2));
    byte[] illumination = bytes(fullBackground);
    double targetWhite = Math.max(235, Math.min(250, paperWhite));
    for (int i = 0; i < light.length; i++) {
      int value = light[i] & 255;
      int localBackground = illumination[i] & 255;
      double gain = Math.min(1.85, targetWhite / Math.max(70, localBackground));
      double artwork = artworkWeight(a[i] & 255, b[i] & 255);
      // Colored ink and photographs receive only a mild lighting adjustment. In
      // particular, dark saturated artwork must never become a pale scan background.
      gain = gain * (1 - artwork) + clamp(gain, 0.97, 1.08) * artwork;
      light[i] = (byte) Math.round(clamp(value * gain, 0, 255));
    }
    int[] histogram = histogram(light);
    int black = Math.min(20, percentile(histogram, light.length, 0.005));
    int white = Math.max(225, percentile(histogram, light.length, 0.985));
    double contrastGain = Math.min(1.12, 250.0 / Math.max(1, white - black));
    for (int i = 0; i < light.length; i++) {
      double value = light[i] & 255;
      double contrasted = clamp((value - black) * contrastGain, 0, 255);
      double artwork = artworkWeight(a[i] & 255, b[i] & 255);
      light[i] = (byte) Math.round(value * artwork + contrasted * (1 - artwork));
    }
    luminance.put(0, 0, light);
  }

  private static double artworkWeight(int a, int b) {
    return clamp((Math.hypot(a - 128, b - 128) - 10) / 10, 0, 1);
  }

  private static void balancePaper(List<Mat> channels) {
    byte[] light = bytes(channels.get(0));
    byte[] a = bytes(channels.get(1));
    byte[] b = bytes(channels.get(2));
    int[] aHistogram = new int[256];
    int[] bHistogram = new int[256];
    int neutralSamples = 0;
    int stride = Math.max(1, light.length / 80000);
    for (int i = 0; i < light.length; i += stride) {
      int av = a[i] & 255;
      int bv = b[i] & 255;
      if ((light[i] & 255) > 205 && Math.abs(av - 128) < 18 && Math.abs(bv - 128) < 18) {
        aHistogram[av]++;
        bHistogram[bv]++;
        neutralSamples++;
      }
    }
    int aShift =
        neutralSamples > 100
            ? (int) clamp(percentile(aHistogram, neutralSamples, 0.5) - 128, -7, 7)
            : 0;
    int bShift =
        neutralSamples > 100
            ? (int) clamp(percentile(bHistogram, neutralSamples, 0.5) - 128, -7, 7)
            : 0;
    for (int i = 0; i < light.length; i++) {
      int av = (a[i] & 255) - aShift;
      int bv = (b[i] & 255) - bShift;
      int value = light[i] & 255;
      double chroma = Math.hypot(av - 128, bv - 128);
      if (chroma < 12 && value > 205) {
        // Lift neutral paper highlights gently, without bleaching saturated ink.
        double weight = (1 - chroma / 12) * Math.min(1, (value - 205) / 35.0);
        value = (int) Math.round(value + Math.max(0, 248 - value) * weight * 0.75);
      }
      light[i] = (byte) value;
      a[i] = (byte) Math.round(clamp(av, 0, 255));
      b[i] = (byte) Math.round(clamp(bv, 0, 255));
    }
    channels.get(0).put(0, 0, light);
    channels.get(1).put(0, 0, a);
    channels.get(2).put(0, 0, b);
  }

  private static Mat boundedRgba(Bitmap bitmap, int maxEdge, Mats mats) {
    Bitmap working = bitmap;
    double scale = Math.min(1, maxEdge / (double) Math.max(bitmap.getWidth(), bitmap.getHeight()));
    if (scale < 1) {
      working =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.max(1, (int) Math.round(bitmap.getWidth() * scale)),
              Math.max(1, (int) Math.round(bitmap.getHeight() * scale)),
              true);
    }
    Bitmap compatible = working;
    try {
      if (working.getConfig() != Bitmap.Config.ARGB_8888
          && working.getConfig() != Bitmap.Config.RGB_565) {
        compatible = working.copy(Bitmap.Config.ARGB_8888, false);
        if (compatible == null) throw new IllegalArgumentException("Unable to read this image.");
      }
      Mat rgba = mats.add(new Mat());
      Utils.bitmapToMat(compatible, rgba);
      return rgba;
    } finally {
      if (compatible != null && compatible != working) compatible.recycle();
      if (working != bitmap) working.recycle();
    }
  }

  private static Bitmap bitmap(Mat mat) {
    Bitmap result = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
    try {
      Utils.matToBitmap(mat, result);
      return result;
    } catch (RuntimeException exception) {
      result.recycle();
      throw exception;
    }
  }

  private static void requireBitmap(Bitmap bitmap) {
    if (bitmap == null
        || bitmap.isRecycled()
        || bitmap.getWidth() < 24
        || bitmap.getHeight() < 24) {
      throw new IllegalArgumentException(
          "A readable image of at least 24 × 24 pixels is required.");
    }
  }

  private static void validateNormalizedCorners(PointF[] points) {
    if (points == null || points.length != 4)
      throw new IllegalArgumentException("Four crop corners are required.");
    for (PointF point : points) {
      if (point == null
          || Float.isNaN(point.x)
          || Float.isNaN(point.y)
          || Float.isInfinite(point.x)
          || Float.isInfinite(point.y)
          || point.x < 0
          || point.x > 1
          || point.y < 0
          || point.y > 1) {
        throw new IllegalArgumentException("Crop corners must be within the image.");
      }
    }
  }

  private static Point[] clockwiseCorners(Point[] points) {
    double centerX = 0;
    double centerY = 0;
    for (Point point : points) {
      centerX += point.x;
      centerY += point.y;
    }
    final double cx = centerX / 4;
    final double cy = centerY / 4;
    Arrays.sort(points, Comparator.comparingDouble(p -> Math.atan2(p.y - cy, p.x - cx)));
    int start = 0;
    for (int i = 1; i < 4; i++) {
      if (points[i].x + points[i].y < points[start].x + points[start].y) start = i;
    }
    Point[] ordered = new Point[4];
    for (int i = 0; i < 4; i++) ordered[i] = points[(start + i) % 4];
    return ordered;
  }

  private static boolean convex(Point[] points) {
    double direction = 0;
    for (int i = 0; i < 4; i++) {
      Point a = points[i];
      Point b = points[(i + 1) % 4];
      Point c = points[(i + 2) % 4];
      double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
      if (Math.abs(cross) < 1e-5) return false;
      if (i == 0) direction = cross;
      else if (cross * direction < 0) return false;
    }
    return true;
  }

  private static double polygonArea(Point[] points) {
    double sum = 0;
    for (int i = 0; i < 4; i++) {
      Point a = points[i];
      Point b = points[(i + 1) % 4];
      sum += a.x * b.y - b.x * a.y;
    }
    return Math.abs(sum) * 0.5;
  }

  private static boolean nearBoundary(Point point, int width, int height) {
    return point.x < width * 0.012
        || point.x > width * 0.988
        || point.y < height * 0.012
        || point.y > height * 0.988;
  }

  private static boolean sameBoundary(Point a, Point b, int width, int height) {
    return (a.x < width * 0.012 && b.x < width * 0.012)
        || (a.x > width * 0.988 && b.x > width * 0.988)
        || (a.y < height * 0.012 && b.y < height * 0.012)
        || (a.y > height * 0.988 && b.y > height * 0.988);
  }

  private static byte[] bytes(Mat mat) {
    byte[] values = new byte[(int) (mat.total() * mat.channels())];
    mat.get(0, 0, values);
    return values;
  }

  private static int[] histogram(byte[] values) {
    int[] histogram = new int[256];
    for (byte value : values) histogram[value & 255]++;
    return histogram;
  }

  private static int percentile(int[] histogram, int total, double fraction) {
    int target = Math.max(1, (int) Math.ceil(total * fraction));
    int sum = 0;
    for (int i = 0; i < histogram.length; i++) {
      sum += histogram[i];
      if (sum >= target) return i;
    }
    return 255;
  }

  private static double distance(Point a, Point b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private static final class Candidate {
    final Point[] points;
    final double score;

    Candidate(Point[] points, double score) {
      this.points = points;
      this.score = score;
    }
  }

  private static final class Evidence {
    final double edgeSupport;
    final double inside;
    final double outside;

    Evidence(double edgeSupport, double inside, double outside) {
      this.edgeSupport = edgeSupport;
      this.inside = inside;
      this.outside = outside;
    }
  }

  /** Explicitly owns native allocations so every return/exception releases intermediate Mats. */
  private static final class Mats implements AutoCloseable {
    private final List<Mat> values = new ArrayList<>();

    <T extends Mat> T add(T mat) {
      values.add(mat);
      return mat;
    }

    @Override
    public void close() {
      for (int i = values.size() - 1; i >= 0; i--) values.get(i).release();
    }
  }
}
