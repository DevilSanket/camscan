/*
 * Live tracking adapted from OpenScan's quad_smoother.dart,
 * auto_capture_detector.dart, and contours.dart corner correspondence.
 * https://github.com/ethereal-developers/OpenScan
 * Copyright (c) 2021, Vijay T S and Vikram H. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * See the bundled OpenScan license in the app's third-party notices.
 */
package com.folio.scanner.ui;

import android.graphics.PointF;

/** Main-thread live geometry state. Times are caller-supplied monotonic milliseconds. */
public final class LivePageTracker {
  public static final long DROPOUT_GRACE_MS = 500;
  public static final long MIN_STABLE_MS = 700;
  public static final long CAPTURE_COOLDOWN_MS = 2000;
  public static final int JUMP_CONFIRM_FRAMES = 3;
  public static final int MIN_STABLE_FRAMES = 3;
  private static final double JUMP_FRACTION = .06;
  private static final double POSITION_TOLERANCE = .02;
  private static final double DIAGONAL = Math.sqrt(2);

  public static final class Update {
    /** Filtered content-normalized geometry; may remain visible briefly after a miss. */
    public final PointF[] displayedCorners;

    /** Current accepted raw geometry, null for a miss or an unconfirmed competing contour. */
    public final PointF[] acceptedRawCorners;

    public final boolean captureReady;

    private Update(PointF[] displayed, PointF[] accepted, boolean ready) {
      displayedCorners = copy(displayed);
      acceptedRawCorners = copy(accepted);
      captureReady = ready;
    }
  }

  private PointF[] displayed, lastRaw, pending;
  private OneEuro[] filters;
  private long lastSampleAt = -1, lastSeenAt = -1;
  private int pendingStreak;
  private boolean autoCaptureEnabled = true;
  private long cooldownUntil;
  private PointF[] stableAnchor, stablePrevious;
  private long stableSince = -1;
  private int stableFrames;
  private boolean stableFired;

  public Update update(PointF[] raw, long nowMs) {
    if (!readable(raw)) raw = null;
    if (raw == null) {
      pending = null;
      pendingStreak = 0;
      resetStability();
      if (lastSeenAt < 0 || nowMs - lastSeenAt > DROPOUT_GRACE_MS) reset();
      return new Update(displayed, null, false);
    }
    lastSeenAt = nowMs;
    PointF[] accepted = null;
    if (lastRaw == null) {
      lastRaw = copy(raw);
      pending = null;
      pendingStreak = 0;
      seed(lastRaw, nowMs);
      accepted = lastRaw;
    } else {
      Assignment toTrack = match(raw, lastRaw);
      if (toTrack.distance / (4 * DIAGONAL) < JUMP_FRACTION) {
        pending = null;
        pendingStreak = 0;
        lastRaw = toTrack.points;
        continueTrack(lastRaw, nowMs);
        accepted = lastRaw;
      } else {
        Assignment toPending = pending == null ? null : match(raw, pending);
        if (toPending != null && toPending.distance / (4 * DIAGONAL) < JUMP_FRACTION) {
          pending = toPending.points;
          pendingStreak++;
        } else {
          pending = copy(raw);
          pendingStreak = 1;
        }
        if (pendingStreak >= JUMP_CONFIRM_FRAMES) {
          // Correspond to the actual drawn slots before easing onto the confirmed page.
          lastRaw = match(pending, displayed).points;
          pending = null;
          pendingStreak = 0;
          continueTrack(lastRaw, nowMs);
          accepted = lastRaw;
        }
      }
    }
    // Stale overlays and unconfirmed jumps never contribute to automatic capture.
    boolean ready = updateStability(accepted, nowMs);
    return new Update(displayed, accepted, ready);
  }

  /** Previous accepted RAW contour for detector candidate selection, never the filtered overlay. */
  public PointF[] getPreviousRaw() {
    return copy(lastRaw);
  }

  public void setAutoCaptureEnabled(boolean enabled) {
    autoCaptureEnabled = enabled;
    reset();
  }

  /** Clears tracking and timing; a capture's cooldown survives lifecycle/toggle resets. */
  public void reset() {
    displayed = null;
    lastRaw = null;
    pending = null;
    filters = null;
    lastSampleAt = -1;
    lastSeenAt = -1;
    pendingStreak = 0;
    resetStability();
  }

  public void notifyCaptured(long nowMs) {
    reset();
    cooldownUntil = nowMs + CAPTURE_COOLDOWN_MS;
  }

  private boolean updateStability(PointF[] accepted, long nowMs) {
    if (!autoCaptureEnabled || accepted == null || nowMs < cooldownUntil) {
      resetStability();
      return false;
    }
    if (stableAnchor == null
        || !withinTolerance(stablePrevious, accepted)
        || !withinTolerance(stableAnchor, accepted)) {
      stableAnchor = copy(accepted);
      stableSince = nowMs;
      stableFrames = 0;
      stableFired = false;
    }
    stablePrevious = copy(accepted);
    stableFrames++;
    if (!stableFired && stableFrames >= MIN_STABLE_FRAMES && nowMs - stableSince >= MIN_STABLE_MS) {
      stableFired = true;
      return true;
    }
    return false;
  }

  private void resetStability() {
    stableAnchor = null;
    stablePrevious = null;
    stableSince = -1;
    stableFrames = 0;
    stableFired = false;
  }

  private static boolean withinTolerance(PointF[] a, PointF[] b) {
    if (a == null || b == null) return false;
    for (int i = 0; i < 4; i++) {
      if (Math.abs(a[i].x - b[i].x) > POSITION_TOLERANCE
          || Math.abs(a[i].y - b[i].y) > POSITION_TOLERANCE) return false;
    }
    return true;
  }

  private void seed(PointF[] raw, long nowMs) {
    filters = new OneEuro[8];
    for (int i = 0; i < 8; i++) {
      filters[i] = new OneEuro();
      filters[i].seed(i % 2 == 0 ? raw[i / 2].x : raw[i / 2].y);
    }
    displayed = copy(raw);
    lastSampleAt = nowMs;
  }

  private void continueTrack(PointF[] raw, long nowMs) {
    if (filters == null) {
      seed(raw, nowMs);
      return;
    }
    double dt = Math.max(.001, (nowMs - lastSampleAt) / 1000.0);
    lastSampleAt = nowMs;
    displayed = new PointF[4];
    for (int i = 0; i < 4; i++) {
      displayed[i] =
          new PointF(
              (float) filters[i * 2].filter(raw[i].x, dt),
              (float) filters[i * 2 + 1].filter(raw[i].y, dt));
    }
  }

  private static final class Assignment {
    final PointF[] points;
    final double distance;

    Assignment(PointF[] points, double distance) {
      this.points = points;
      this.distance = distance;
    }
  }

  /** All 4! assignments preserve physical corner correspondence near label flips. */
  private static Assignment match(PointF[] raw, PointF[] reference) {
    double best = Double.POSITIVE_INFINITY;
    int[] indices = null;
    for (int a = 0; a < 4; a++)
      for (int b = 0; b < 4; b++) {
        if (b == a) continue;
        for (int c = 0; c < 4; c++) {
          if (c == a || c == b) continue;
          int d = 6 - a - b - c;
          int[] order = {a, b, c, d};
          double distance = 0;
          for (int i = 0; i < 4; i++) {
            distance +=
                Math.hypot(raw[order[i]].x - reference[i].x, raw[order[i]].y - reference[i].y);
          }
          if (distance < best) {
            best = distance;
            indices = order;
          }
        }
      }
    PointF[] assigned = new PointF[4];
    for (int i = 0; i < 4; i++) assigned[i] = new PointF(raw[indices[i]].x, raw[indices[i]].y);
    return new Assignment(assigned, best);
  }

  private static boolean readable(PointF[] points) {
    if (points == null || points.length != 4) return false;
    for (PointF p : points) {
      if (p == null
          || !Float.isFinite(p.x)
          || !Float.isFinite(p.y)
          || p.x < 0
          || p.x > 1
          || p.y < 0
          || p.y > 1) return false;
    }
    return true;
  }

  private static PointF[] copy(PointF[] points) {
    if (points == null) return null;
    PointF[] result = new PointF[points.length];
    for (int i = 0; i < points.length; i++) result[i] = new PointF(points[i].x, points[i].y);
    return result;
  }

  private static final class OneEuro {
    private double previous, derivative;

    void seed(double value) {
      previous = value;
      derivative = 0;
    }

    double filter(double value, double dt) {
      double velocity = (value - previous) / dt;
      double derivativeAlpha = alpha(1.0, dt);
      derivative = derivativeAlpha * velocity + (1 - derivativeAlpha) * derivative;
      double valueAlpha = alpha(.5 + .5 * Math.abs(derivative), dt);
      previous = valueAlpha * value + (1 - valueAlpha) * previous;
      return previous;
    }

    private static double alpha(double cutoffHz, double dt) {
      return 1 / (1 + 1 / (2 * Math.PI * cutoffHz * dt));
    }
  }
}
