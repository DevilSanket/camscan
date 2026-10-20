/*
 * Java adaptation of OpenScan's document_detector.dart, edge_detection.dart and contours.dart.
 * Upstream: ethereal-developers/OpenScan, commit 27bb7ab973fbb621fce182c43bc875856c785a30.
 * Copyright (c) 2021, Vijay T S and Vikram H. All rights reserved.
 * BSD-3-Clause; complete terms in assets/licenses/OpenScan-BSD-3-Clause.txt.
 */
package com.folio.scanner.processing;

import android.graphics.Bitmap;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pinned OpenScan reference algorithm, plus the production Android detector adapter. */
final class OpenScanDetector {
  static final int MAX_DIMENSION = 700;
  private static final double[] THRESHOLDS = {0.7, 1.0, 1.3};
  private static final double[] EPSILONS = {0.02, 0.04, 0.06, 0.08, 0.1};

  static DocumentProcessor.Detection detect(Bitmap bitmap, PointF[] previous) {
    return DocumentBoundaryDetector.detect(bitmap, previous);
  }

  /** Preserves the upstream bitmap adapter for independently generated parity fixtures. */
  static DocumentProcessor.Detection detectLegacy(Bitmap bitmap, PointF[] previous) {
    int sourceWidth = bitmap.getWidth(), sourceHeight = bitmap.getHeight();
    double scale = Math.min(1, MAX_DIMENSION / (double) Math.max(sourceWidth, sourceHeight));
    int width = Math.max(1, (int) Math.round(sourceWidth * scale));
    int height = Math.max(1, (int) Math.round(sourceHeight * scale));
    byte[] gray = new byte[width * height];
    int[] row = new int[sourceWidth];
    for (int y = 0; y < height; y++) {
      int sy = (int) ((long) y * sourceHeight / height);
      bitmap.getPixels(row, 0, sourceWidth, 0, sy, sourceWidth, 1);
      for (int x = 0; x < width; x++) {
        int value = row[(int) ((long) x * sourceWidth / width)];
        gray[y * width + x] =
            (byte)
                Math.round(
                    0.2126 * ((value >>> 16) & 255)
                        + 0.7152 * ((value >>> 8) & 255)
                        + 0.0722 * (value & 255));
      }
    }
    P[] reference = null;
    if (previous != null && previous.length == 4) {
      reference = new P[4];
      for (int i = 0; i < 4; i++) {
        if (previous[i] == null) {
          reference = null;
          break;
        }
        reference[i] =
            new P(
                previous[i].x * (sourceWidth - 1.0) * width / sourceWidth,
                previous[i].y * (sourceHeight - 1.0) * height / sourceHeight);
      }
    }
    Result result = detectGrayscale(gray, width, height, reference);
    if (result == null) return null;
    PointF[] corners = new PointF[4];
    for (int i = 0; i < 4; i++) {
      // OpenScan rescales pixel coordinates by original/work. Folio's warp consumes
      // normalized pixel-center coordinates, so divide those source pixels by size-1.
      corners[i] =
          new PointF(
              (float)
                  Math.max(
                      0, Math.min(1, result.points[i].x * sourceWidth / width / (sourceWidth - 1))),
              (float)
                  Math.max(
                      0,
                      Math.min(
                          1, result.points[i].y * sourceHeight / height / (sourceHeight - 1))));
    }
    return new DocumentProcessor.Detection(corners, result.quality);
  }

  static Result detectGrayscale(byte[] gray, int width, int height) {
    return detectGrayscale(gray, width, height, null);
  }

  static Result detectGrayscale(byte[] gray, int width, int height, P[] previous) {
    if (width <= 0 || height <= 0 || gray.length != width * height)
      throw new IllegalArgumentException("Grayscale dimensions do not match pixels");
    byte[] magnitude = sobel(blur(gray, width, height), width, height);
    int base = otsu(magnitude);
    List<P[]> candidates = new ArrayList<>();
    for (double multiplier : THRESHOLDS) {
      int threshold = Math.max(0, Math.min(255, (int) Math.round(base * multiplier)));
      byte[] binary = new byte[gray.length];
      for (int i = 0; i < binary.length; i++)
        binary[i] = (byte) ((magnitude[i] & 255) >= threshold ? 1 : 0);
      candidates.addAll(findCandidates(dilate(binary, width, height, 4), width, height));
    }
    return pickBest(candidates, width, height, previous);
  }

  static byte[] blur(byte[] gray, int width, int height) {
    byte[] result = new byte[gray.length];
    int[] kernel = {1, 2, 1, 2, 4, 2, 1, 2, 1};
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        int sum = 0, k = 0;
        for (int dy = -1; dy <= 1; dy++)
          for (int dx = -1; dx <= 1; dx++)
            sum += at(gray, width, height, x + dx, y + dy) * kernel[k++];
        result[y * width + x] = (byte) Math.round(sum / 16.0);
      }
    return result;
  }

  static byte[] sobel(byte[] gray, int width, int height) {
    byte[] result = new byte[gray.length];
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        int gx =
            -at(gray, width, height, x - 1, y - 1)
                - 2 * at(gray, width, height, x - 1, y)
                - at(gray, width, height, x - 1, y + 1)
                + at(gray, width, height, x + 1, y - 1)
                + 2 * at(gray, width, height, x + 1, y)
                + at(gray, width, height, x + 1, y + 1);
        int gy =
            -at(gray, width, height, x - 1, y - 1)
                - 2 * at(gray, width, height, x, y - 1)
                - at(gray, width, height, x + 1, y - 1)
                + at(gray, width, height, x - 1, y + 1)
                + 2 * at(gray, width, height, x, y + 1)
                + at(gray, width, height, x + 1, y + 1);
        result[y * width + x] = (byte) Math.round(Math.min(255, Math.sqrt(gx * gx + gy * gy)));
      }
    return result;
  }

  private static int at(byte[] pixels, int width, int height, int x, int y) {
    x = Math.max(0, Math.min(width - 1, x));
    y = Math.max(0, Math.min(height - 1, y));
    return pixels[y * width + x] & 255;
  }

  static int otsu(byte[] pixels) {
    int[] hist = new int[256];
    for (byte value : pixels) hist[value & 255]++;
    double sum = 0;
    for (int i = 0; i < 256; i++) sum += i * (double) hist[i];
    double sumB = 0, maxVariance = -1;
    int wB = 0, threshold = 128;
    for (int t = 0; t < 256; t++) {
      wB += hist[t];
      if (wB == 0) continue;
      int wF = pixels.length - wB;
      if (wF == 0) break;
      sumB += t * (double) hist[t];
      double mB = sumB / wB, mF = (sum - sumB) / wF;
      double between = wB * (double) wF * (mB - mF) * (mB - mF);
      if (between > maxVariance) {
        maxVariance = between;
        threshold = t;
      }
    }
    return threshold;
  }

  static byte[] dilate(byte[] mask, int width, int height, int radius) {
    byte[] row = new byte[mask.length], result = new byte[mask.length];
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        for (int dx = -radius; dx <= radius; dx++) {
          int sx = x + dx;
          if (sx >= 0 && sx < width && mask[y * width + sx] == 1) {
            row[y * width + x] = 1;
            break;
          }
        }
      }
    for (int x = 0; x < width; x++)
      for (int y = 0; y < height; y++) {
        for (int dy = -radius; dy <= radius; dy++) {
          int sy = y + dy;
          if (sy >= 0 && sy < height && row[sy * width + x] == 1) {
            result[y * width + x] = 1;
            break;
          }
        }
      }
    return result;
  }

  static List<P[]> findCandidates(byte[] mask, int width, int height) {
    List<int[]> components = components(mask, width, height);
    components.sort((a, b) -> Integer.compare(b.length, a.length));
    List<P[]> result = new ArrayList<>();
    for (int i = 0; i < Math.min(5, components.size()); i++) {
      int[] members = components.get(i);
      if (members.length < 4) continue;
      List<P> hull = hull(members, width, height);
      if (hull.size() < 4) continue;
      for (double epsilon : EPSILONS) {
        List<P> simple = hull.size() <= 4 ? hull : simplifyClosed(hull, epsilon);
        if (simple.size() == 4 && convex(simple)) {
          P[] quad = sortCorners(simple.toArray(new P[0]));
          if (plausible(quad, width, height)) result.add(quad);
        }
      }
    }
    return result;
  }

  private static List<int[]> components(byte[] mask, int width, int height) {
    byte[] visited = new byte[mask.length];
    int[] queue = new int[mask.length];
    List<int[]> result = new ArrayList<>();
    for (int start = 0; start < mask.length; start++) {
      if (mask[start] != 1 || visited[start] != 0) continue;
      int head = 0, tail = 1;
      queue[0] = start;
      visited[start] = 1;
      while (head < tail) {
        int index = queue[head++], x = index % width, y = index / width;
        for (int dy = -1; dy <= 1; dy++)
          for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int nx = x + dx, ny = y + dy;
            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
            int next = ny * width + nx;
            if (mask[next] == 1 && visited[next] == 0) {
              visited[next] = 1;
              queue[tail++] = next;
            }
          }
      }
      result.add(Arrays.copyOf(queue, tail));
    }
    return result;
  }

  private static List<P> hull(int[] members, int width, int height) {
    // Ordering by integer x*height+y exactly matches sorting the upstream pixel points by x,y.
    int[] ordered = new int[members.length];
    for (int i = 0; i < members.length; i++)
      ordered[i] = (members[i] % width) * height + members[i] / width;
    Arrays.sort(ordered);
    int[] lower = new int[ordered.length], upper = new int[ordered.length];
    int nL = 0, nU = 0;
    for (int value : ordered) {
      while (nL >= 2 && cross(lower[nL - 2], lower[nL - 1], value, height) <= 0) nL--;
      lower[nL++] = value;
    }
    for (int i = ordered.length - 1; i >= 0; i--) {
      int value = ordered[i];
      while (nU >= 2 && cross(upper[nU - 2], upper[nU - 1], value, height) <= 0) nU--;
      upper[nU++] = value;
    }
    List<P> result = new ArrayList<>();
    for (int i = 0; i < nL - 1; i++) result.add(new P(lower[i] / height, lower[i] % height));
    for (int i = 0; i < nU - 1; i++) result.add(new P(upper[i] / height, upper[i] % height));
    return result;
  }

  private static long cross(int o, int a, int b, int height) {
    return (long) (a / height - o / height) * (b % height - o % height)
        - (long) (a % height - o % height) * (b / height - o / height);
  }

  private static List<P> simplifyClosed(List<P> hull, double epsilonFactor) {
    double perimeter = 0, farthest = -1;
    int a = 0, b = 0;
    for (int i = 0; i < hull.size(); i++) {
      perimeter += distance(hull.get(i), hull.get((i + 1) % hull.size()));
      for (int j = i + 1; j < hull.size(); j++) {
        double d = distance(hull.get(i), hull.get(j));
        if (d > farthest) {
          farthest = d;
          a = i;
          b = j;
        }
      }
    }
    List<P> first = rdp(chain(hull, a, b), epsilonFactor * perimeter);
    List<P> second = rdp(chain(hull, b, a), epsilonFactor * perimeter);
    List<P> result = new ArrayList<>(first);
    result.addAll(second.subList(1, second.size() - 1));
    return result;
  }

  private static List<P> chain(List<P> points, int from, int to) {
    List<P> result = new ArrayList<>();
    int i = from;
    while (true) {
      result.add(points.get(i));
      if (i == to) return result;
      i = (i + 1) % points.size();
    }
  }

  private static List<P> rdp(List<P> points, double epsilon) {
    if (points.size() < 3) return points;
    double maxDistance = -1;
    int index = 0;
    P start = points.get(0), end = points.get(points.size() - 1);
    for (int i = 1; i < points.size() - 1; i++) {
      double d = perpendicular(points.get(i), start, end);
      if (d > maxDistance) {
        maxDistance = d;
        index = i;
      }
    }
    if (maxDistance > epsilon) {
      List<P> left = rdp(points.subList(0, index + 1), epsilon);
      List<P> right = rdp(points.subList(index, points.size()), epsilon);
      List<P> result = new ArrayList<>(left.subList(0, left.size() - 1));
      result.addAll(right);
      return result;
    }
    return Arrays.asList(start, end);
  }

  private static double perpendicular(P p, P a, P b) {
    double dx = b.x - a.x, dy = b.y - a.y, len = Math.sqrt(dx * dx + dy * dy);
    if (len == 0) return distance(p, a);
    double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (len * len);
    return distance(p, new P(a.x + t * dx, a.y + t * dy));
  }

  private static boolean convex(List<P> points) {
    Boolean positive = null;
    for (int i = 0; i < points.size(); i++) {
      P a = points.get(i),
          b = points.get((i + 1) % points.size()),
          c = points.get((i + 2) % points.size());
      double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
      if (cross == 0) continue;
      if (positive == null) positive = cross > 0;
      else if (positive != (cross > 0)) return false;
    }
    return true;
  }

  static boolean plausible(P[] points, int width, int height) {
    if (width <= 0 || height <= 0 || area(points) / (width * (double) height) < 0.05) return false;
    for (int i = 0; i < 4; i++) {
      double angle = angle(points[(i + 3) % 4], points[i], points[(i + 1) % 4]);
      if (angle < 15 || angle > 165) return false;
    }
    return true;
  }

  static P[] sortCorners(P[] points) {
    double cx = 0, cy = 0;
    for (P p : points) {
      cx += p.x / 4;
      cy += p.y / 4;
    }
    final double centerX = cx, centerY = cy;
    P[] ordered = points.clone();
    Arrays.sort(
        ordered,
        (a, b) ->
            Double.compare(
                Math.atan2(a.y - centerY, a.x - centerX),
                Math.atan2(b.y - centerY, b.x - centerX)));
    double best = Double.NEGATIVE_INFINITY;
    int start = 0;
    for (int i = 0; i < 4; i++) {
      P tl = ordered[i],
          tr = ordered[(i + 1) % 4],
          br = ordered[(i + 2) % 4],
          bl = ordered[(i + 3) % 4];
      double score = (tr.x - tl.x) + (br.x - bl.x) + (bl.y - tl.y) + (br.y - tr.y);
      if (score > best) {
        best = score;
        start = i;
      }
    }
    return new P[] {
      ordered[start], ordered[(start + 1) % 4], ordered[(start + 2) % 4], ordered[(start + 3) % 4]
    };
  }

  static Result pickBest(List<P[]> candidates, int width, int height, P[] previous) {
    if (candidates.isEmpty()) return null;
    double diagonal = Math.sqrt(width * (double) width + height * (double) height);
    List<Cluster> clusters = new ArrayList<>();
    for (P[] candidate : candidates) {
      Cluster matched = null;
      P[] aligned = candidate;
      for (Cluster cluster : clusters) {
        Match match = align(candidate, cluster.mean());
        if (match.distance / 4 <= diagonal * 0.03) {
          matched = cluster;
          aligned = match.points;
          break;
        }
      }
      if (matched == null) {
        matched = new Cluster();
        clusters.add(matched);
      }
      matched.add(aligned);
    }
    P[] best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (Cluster cluster : clusters) {
      P[] result = cluster.mean();
      double score = quality(result, width, height) + 0.12 * cluster.count / candidates.size();
      if (previous != null) {
        Match match = align(result, previous);
        result = match.points;
        score += 0.15 * (1 - Math.max(0, Math.min(1, match.distance / diagonal)));
      }
      if (score > bestScore) {
        bestScore = score;
        best = result;
      }
    }
    return new Result(best, quality(best, width, height));
  }

  static Match align(P[] points, P[] reference) {
    P[] best = null;
    double bestDistance = Double.POSITIVE_INFINITY;
    for (int a = 0; a < 4; a++)
      for (int b = 0; b < 4; b++)
        for (int c = 0; c < 4; c++)
          for (int d = 0; d < 4; d++) {
            if (a == b || a == c || a == d || b == c || b == d || c == d) continue;
            double total =
                distance(points[a], reference[0])
                    + distance(points[b], reference[1])
                    + distance(points[c], reference[2])
                    + distance(points[d], reference[3]);
            if (total < bestDistance) {
              bestDistance = total;
              best = new P[] {points[a], points[b], points[c], points[d]};
            }
          }
    return new Match(best, bestDistance);
  }

  private static double quality(P[] points, int width, int height) {
    double deviation = 0;
    for (int i = 0; i < 4; i++)
      deviation =
          Math.max(
              deviation, Math.abs(angle(points[(i + 3) % 4], points[i], points[(i + 1) % 4]) - 90));
    return Math.max(
        0,
        Math.min(1, area(points) / (width * (double) height) * (1 - Math.min(1, deviation / 90))));
  }

  private static double angle(P a, P b, P c) {
    double abx = a.x - b.x, aby = a.y - b.y, cbx = c.x - b.x, cby = c.y - b.y;
    double ab = Math.sqrt(abx * abx + aby * aby), cb = Math.sqrt(cbx * cbx + cby * cby);
    if (ab == 0 || cb == 0) return 0;
    return Math.acos(Math.max(-1, Math.min(1, (abx * cbx + aby * cby) / (ab * cb))))
        * 180
        / Math.PI;
  }

  private static double area(P[] points) {
    double sum = 0;
    for (int i = 0; i < 4; i++) {
      P a = points[i], b = points[(i + 1) % 4];
      sum += a.x * b.y - b.x * a.y;
    }
    return Math.abs(sum) / 2;
  }

  private static double distance(P a, P b) {
    double dx = a.x - b.x, dy = a.y - b.y;
    return Math.sqrt(dx * dx + dy * dy);
  }

  static final class P {
    final double x, y;

    P(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }

  static final class Result {
    final P[] points;
    final double quality;

    Result(P[] points, double quality) {
      this.points = points;
      this.quality = quality;
    }
  }

  static final class Match {
    final P[] points;
    final double distance;

    Match(P[] points, double distance) {
      this.points = points;
      this.distance = distance;
    }
  }

  private static final class Cluster {
    final double[] sums = new double[8];
    int count;

    void add(P[] points) {
      for (int i = 0; i < 4; i++) {
        sums[2 * i] += points[i].x;
        sums[2 * i + 1] += points[i].y;
      }
      count++;
    }

    P[] mean() {
      P[] result = new P[4];
      for (int i = 0; i < 4; i++) result[i] = new P(sums[2 * i] / count, sums[2 * i + 1] / count);
      return result;
    }
  }
}
