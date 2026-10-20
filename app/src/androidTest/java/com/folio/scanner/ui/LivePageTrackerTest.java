package com.folio.scanner.ui;

import static org.junit.Assert.*;

import android.graphics.PointF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic frame sequences use a fake monotonic clock, without a camera or sleeps. */
@RunWith(AndroidJUnit4.class)
public class LivePageTrackerTest {
  @Test
  public void jitterIsSmoothedButDetectorPreviousRemainsRaw() {
    LivePageTracker tracker = new LivePageTracker();
    PointF[] previousDisplay = null, previousRaw = null;
    double largestFilteredStep = 0, largestRawStep = 0;
    for (int i = 0; i < 30; i++) {
      PointF[] raw = quad(i % 2 == 0 ? .015f : -.015f, 0);
      LivePageTracker.Update update = tracker.update(raw, i * 100);
      assertNotNull(update.acceptedRawCorners);
      assertEquals(raw[0].x, tracker.getPreviousRaw()[0].x, .000001);
      if (previousDisplay != null) {
        largestFilteredStep =
            Math.max(largestFilteredStep, delta(previousDisplay, update.displayedCorners));
        largestRawStep = Math.max(largestRawStep, delta(previousRaw, raw));
      }
      previousDisplay = update.displayedCorners;
      previousRaw = raw;
    }
    assertTrue(largestFilteredStep < largestRawStep);
    // The detector cannot mutate tracker history through the returned prior.
    PointF[] prior = tracker.getPreviousRaw();
    prior[0].x = .99f;
    assertNotEquals(.99f, tracker.getPreviousRaw()[0].x, .000001f);
  }

  @Test
  public void dropoutKeepsOverlayBrieflyButNeverDrivesCapture() {
    LivePageTracker tracker = new LivePageTracker();
    tracker.update(quad(0, 0), 0);
    tracker.update(quad(0, 0), 250);
    LivePageTracker.Update miss = tracker.update(null, 500);
    assertNotNull(miss.displayedCorners);
    assertNull(miss.acceptedRawCorners);
    assertFalse(miss.captureReady);
    assertFalse(tracker.update(quad(0, 0), 750).captureReady);
    assertFalse(tracker.update(quad(0, 0), 1000).captureReady);
    assertFalse(tracker.update(quad(0, 0), 1250).captureReady);
    assertTrue(tracker.update(quad(0, 0), 1500).captureReady);
    LivePageTracker.Update expired = tracker.update(null, 2001);
    assertNull(expired.displayedCorners);
    assertNull(tracker.getPreviousRaw());
    PointF[] newPage = quad(.4f, .3f);
    assertEquals(0, delta(newPage, tracker.update(newPage, 2250).displayedCorners), .000001);
  }

  @Test
  public void competingPageNeedsThreeConsecutiveFramesAndCannotPrematurelyCapture() {
    LivePageTracker tracker = new LivePageTracker();
    PointF[] first = quad(0, 0), second = quad(.4f, .3f);
    tracker.update(first, 0);
    tracker.update(first, 250);
    LivePageTracker.Update pending = tracker.update(second, 500);
    assertEquals(0, delta(first, pending.displayedCorners), .000001);
    assertNull(pending.acceptedRawCorners);
    assertFalse(pending.captureReady);
    pending = tracker.update(second, 750);
    assertNull(pending.acceptedRawCorners);
    assertEquals(0, delta(first, pending.displayedCorners), .000001);
    LivePageTracker.Update confirmed = tracker.update(second, 1000);
    assertNotNull(confirmed.acceptedRawCorners);
    assertFalse(confirmed.captureReady);
    assertTrue(delta(first, confirmed.displayedCorners) > 0);
    assertTrue(delta(second, confirmed.displayedCorners) > .001);
    assertFalse(tracker.update(second, 1250).captureReady);
    assertFalse(tracker.update(second, 1500).captureReady);
    assertTrue(tracker.update(second, 1750).captureReady);
  }

  @Test
  public void missingFrameBreaksCompetingPageConfirmation() {
    LivePageTracker tracker = new LivePageTracker();
    PointF[] first = quad(0, 0), second = quad(.4f, .3f);
    tracker.update(first, 0);
    assertNull(tracker.update(second, 100).acceptedRawCorners);
    assertNull(tracker.update(second, 200).acceptedRawCorners);
    tracker.update(null, 250);
    assertNull(tracker.update(second, 300).acceptedRawCorners);
    assertNull(tracker.update(second, 400).acceptedRawCorners);
    assertNotNull(tracker.update(second, 500).acceptedRawCorners);
  }

  @Test
  public void allCornerAssignmentsKeepTheSamePhysicalTrack() {
    LivePageTracker tracker = new LivePageTracker();
    PointF[] page = quad(0, 0);
    tracker.setAutoCaptureEnabled(false);
    tracker.update(page, 0);
    int frame = 0;
    for (int a = 0; a < 4; a++)
      for (int b = 0; b < 4; b++) {
        if (a == b) continue;
        for (int c = 0; c < 4; c++) {
          if (a == c || b == c) continue;
          int d = 6 - a - b - c;
          PointF[] flipped = {page[a], page[b], page[c], page[d]};
          LivePageTracker.Update update = tracker.update(flipped, ++frame * 100);
          assertNotNull(update.acceptedRawCorners);
          assertEquals(0, delta(page, update.displayedCorners), .000001);
          assertEquals(0, delta(page, tracker.getPreviousRaw()), .000001);
        }
      }
    assertEquals(24, frame);
  }

  @Test
  public void enablingAutoCaptureStartsANewWindowAndResetDropsTheOldPage() {
    LivePageTracker tracker = new LivePageTracker();
    tracker.update(quad(0, 0), 0);
    tracker.update(quad(0, 0), 350);
    tracker.setAutoCaptureEnabled(false);
    assertFalse(tracker.update(quad(0, 0), 700).captureReady);
    tracker.setAutoCaptureEnabled(true);
    assertNull(tracker.getPreviousRaw());
    assertFalse(tracker.update(quad(0, 0), 1050).captureReady);
    assertFalse(tracker.update(quad(0, 0), 1400).captureReady);
    assertTrue(tracker.update(quad(0, 0), 1750).captureReady);
    assertFalse(tracker.update(quad(0, 0), 2000).captureReady);
    tracker.reset();
    PointF[] second = quad(.4f, .3f);
    LivePageTracker.Update fresh = tracker.update(second, 2250);
    assertEquals(0, delta(second, fresh.displayedCorners), .000001);
    assertFalse(fresh.captureReady);
  }

  @Test
  public void continuousSlowDriftCannotAccumulateStableFrames() {
    LivePageTracker tracker = new LivePageTracker();
    for (int i = 0; i < 25; i++) {
      // Each step is within previous-frame tolerance; total movement exceeds the anchor.
      assertFalse("Drift frame " + i, tracker.update(quad(i * .012f, 0), i * 250).captureReady);
    }
  }

  @Test
  public void captureRequiresBothFrameCountAndDurationAndRespectsCooldown() {
    LivePageTracker tracker = new LivePageTracker();
    assertFalse(tracker.update(quad(0, 0), 0).captureReady);
    assertFalse(tracker.update(quad(0, 0), 0).captureReady);
    assertFalse(tracker.update(quad(0, 0), 0).captureReady);
    assertFalse(tracker.update(quad(0, 0), 350).captureReady);
    assertTrue(tracker.update(quad(0, 0), 700).captureReady);
    tracker.notifyCaptured(700);
    tracker.reset(); // A lifecycle/toggle reset must not remove the capture cooldown.
    for (int i = 950; i < 2700; i += 250) assertFalse(tracker.update(quad(0, 0), i).captureReady);
    assertFalse(tracker.update(quad(0, 0), 2700).captureReady);
    assertFalse(tracker.update(quad(0, 0), 3050).captureReady);
    assertTrue(tracker.update(quad(0, 0), 3400).captureReady);
  }

  private static PointF[] quad(float dx, float dy) {
    return new PointF[] {
      new PointF(.1f + dx, .1f + dy),
      new PointF(.5f + dx, .1f + dy),
      new PointF(.5f + dx, .5f + dy),
      new PointF(.1f + dx, .5f + dy)
    };
  }

  private static double delta(PointF[] a, PointF[] b) {
    double largest = 0;
    for (int i = 0; i < 4; i++)
      largest = Math.max(largest, Math.max(Math.abs(a[i].x - b[i].x), Math.abs(a[i].y - b[i].y)));
    return largest;
  }
}
