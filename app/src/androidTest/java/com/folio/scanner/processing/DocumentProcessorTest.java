package com.folio.scanner.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.folio.scanner.ui.ScanIllustration;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;

/** Generated fixtures execute the actual Java/OpenCV Android pipeline and JNI implementation. */
@RunWith(AndroidJUnit4.class)
public class DocumentProcessorTest {
  private final DocumentProcessor processor = new DocumentProcessor();

  @BeforeClass
  public static void initializeOpenCv() {
    assertTrue("OpenCV Android native library must load", OpenCVLoader.initLocal());
  }

  @Test
  public void detectsSkewedBrightPageAndWarpsItsContent() {
    Bitmap source = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888);
    Bitmap warped = null;
    try {
      Canvas canvas = new Canvas(source);
      canvas.drawColor(Color.rgb(49, 57, 62));
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      PointF[] expected = {
        new PointF(130f / 799, 100f / 999),
        new PointF(675f / 799, 70f / 999),
        new PointF(720f / 799, 880f / 999),
        new PointF(80f / 799, 915f / 999)
      };
      Path page = new Path();
      page.moveTo(130, 100);
      page.lineTo(675, 70);
      page.lineTo(720, 880);
      page.lineTo(80, 915);
      page.close();
      paint.setColor(Color.rgb(247, 246, 241));
      canvas.drawPath(page, paint);
      paint.setColor(Color.rgb(45, 45, 45));
      for (int y = 240; y < 760; y += 45) canvas.drawRect(190, y, 575, y + 7, paint);

      DocumentProcessor.Detection detection = processor.detect(source);
      assertNotNull("Visible skewed paper should produce a real contour", detection);
      assertTrue(detection.confidence > 0 && detection.confidence <= 1);
      for (int i = 0; i < 4; i++) {
        assertEquals("Corner x " + i, expected[i].x, detection.corners[i].x, 0.035);
        assertEquals("Corner y " + i, expected[i].y, detection.corners[i].y, 0.035);
      }
      warped = processor.warp(source, detection.corners);
      assertTrue(warped.getWidth() > 500);
      assertTrue(warped.getHeight() > 750);
      assertTrue("Crop should remove the dark desk", brightness(warped.getPixel(20, 20)) > 220);
    } finally {
      source.recycle();
      if (warped != null) warped.recycle();
    }
  }

  @Test
  public void rejectsUniformImagesInsteadOfInventingPageBounds() {
    Bitmap blank = Bitmap.createBitmap(640, 800, Bitmap.Config.ARGB_8888);
    try {
      blank.eraseColor(Color.WHITE);
      assertNull(processor.detect(blank));
      blank.eraseColor(Color.rgb(85, 85, 85));
      assertNull(processor.detect(blank));
    } finally {
      blank.recycle();
    }
  }

  @Test
  public void detectsActualAppSampleAndRetainsItsTealPanel() {
    Bitmap sample = ScanIllustration.sample();
    Bitmap warped = null;
    Bitmap clean = null;
    try {
      PointF[] expected = {
        new PointF(191f / 1199, 164f / 1599),
        new PointF(1036f / 1199, 235f / 1599),
        new PointF(976f / 1199, 1443f / 1599),
        new PointF(123f / 1199, 1355f / 1599)
      };
      DocumentProcessor.Detection detection = processor.detect(sample);
      assertNotNull("The app's actual sample photograph should be detected", detection);
      for (int i = 0; i < 4; i++) {
        assertEquals("Sample corner x " + i, expected[i].x, detection.corners[i].x, 0.035);
        assertEquals("Sample corner y " + i, expected[i].y, detection.corners[i].y, 0.035);
      }
      warped = processor.warp(sample, expected);
      clean = processor.enhance(warped, DocumentProcessor.Filter.AUTO_COLOR);
      // Below the white caption text, inside the wide teal panel in the sample page.
      int panelX = Math.round(clean.getWidth() * 0.78f);
      int panelY = Math.round(clean.getHeight() * 0.75f);
      int originalPanel = warped.getPixel(panelX, panelY);
      int cleanPanel = clean.getPixel(panelX, panelY);
      float[] originalHsv = new float[3];
      float[] cleanHsv = new float[3];
      Color.colorToHSV(originalPanel, originalHsv);
      Color.colorToHSV(cleanPanel, cleanHsv);
      assertTrue(
          "The actual sample must retain a visibly saturated teal panel", cleanHsv[1] > 0.25f);
      assertTrue(
          "The panel's teal hue must remain recognizable",
          Math.abs(originalHsv[0] - cleanHsv[0]) < 25);
      assertTrue(Color.green(cleanPanel) > Color.red(cleanPanel) + 30);
      assertTrue(Color.blue(cleanPanel) > Color.red(cleanPanel) + 30);
      int originalMin = 255;
      int originalMax = 0;
      int cleanMin = 255;
      int cleanMax = 0;
      for (float x : new float[] {0.14f, 0.28f, 0.42f, 0.58f, 0.78f}) {
        int pixelX = Math.round(clean.getWidth() * x);
        int originalValue = brightness(warped.getPixel(pixelX, panelY));
        int cleanValue = brightness(clean.getPixel(pixelX, panelY));
        originalMin = Math.min(originalMin, originalValue);
        originalMax = Math.max(originalMax, originalValue);
        cleanMin = Math.min(cleanMin, cleanValue);
        cleanMax = Math.max(cleanMax, cleanValue);
      }
      assertTrue(
          "Auto Color must not invent a gradient within the broad sample panel",
          cleanMax - cleanMin <= originalMax - originalMin + 10);
      assertTrue(
          "A dark teal panel must retain its original darkness", cleanMax <= originalMax + 20);
      System.out.println(
          "Sample teal panel RGB: original="
              + Color.red(originalPanel)
              + ","
              + Color.green(originalPanel)
              + ","
              + Color.blue(originalPanel)
              + " enhanced="
              + Color.red(cleanPanel)
              + ","
              + Color.green(cleanPanel)
              + ","
              + Color.blue(cleanPanel));
    } finally {
      sample.recycle();
      if (warped != null) warped.recycle();
      if (clean != null) clean.recycle();
    }
  }

  @Test
  public void autoColorLiftsPaperShadowsAndRetainsRedAndBlueInk() {
    Bitmap source = shadowedColorPage();
    Bitmap clean = null;
    try {
      clean = processor.enhance(source, DocumentProcessor.Filter.AUTO_COLOR);
      int originalDarkPaper = brightness(source.getPixel(50, 100));
      int correctedDarkPaper = brightness(clean.getPixel(50, 100));
      int correctedLightPaper = brightness(clean.getPixel(580, 100));
      assertTrue(
          "Shadowed paper should become brighter", correctedDarkPaper > originalDarkPaper + 45);
      assertTrue("Paper should look white", correctedDarkPaper > 215);
      assertTrue(
          "Uneven illumination should be reduced",
          Math.abs(correctedDarkPaper - correctedLightPaper) < 30);
      int red = clean.getPixel(145, 320);
      int blue = clean.getPixel(445, 320);
      assertTrue(
          "Red ink must remain red",
          Color.red(red) > Color.green(red) + 40 && Color.red(red) > Color.blue(red) + 40);
      assertTrue(
          "Blue ink must remain blue",
          Color.blue(blue) > Color.red(blue) + 40 && Color.blue(blue) > Color.green(blue) + 35);
      assertTrue("Black text must remain dark", brightness(clean.getPixel(240, 455)) < 80);
    } finally {
      source.recycle();
      if (clean != null) clean.recycle();
    }
  }

  @Test
  public void filtersProduceGrayAndBinaryScansAndOriginalCopy() {
    Bitmap source = shadowedColorPage();
    Bitmap original = null;
    Bitmap gray = null;
    Bitmap binary = null;
    try {
      original = processor.enhance(source, DocumentProcessor.Filter.ORIGINAL);
      assertEquals(source.getPixel(145, 320), original.getPixel(145, 320));
      gray = processor.enhance(source, DocumentProcessor.Filter.GRAYSCALE);
      int value = gray.getPixel(145, 320);
      assertEquals(Color.red(value), Color.green(value));
      assertEquals(Color.green(value), Color.blue(value));
      binary = processor.enhance(source, DocumentProcessor.Filter.BLACK_WHITE);
      for (int y = 50; y < binary.getHeight(); y += 71) {
        for (int x = 40; x < binary.getWidth(); x += 57) {
          int pixel = binary.getPixel(x, y);
          assertTrue(pixel == Color.WHITE || pixel == Color.BLACK);
        }
      }
    } finally {
      source.recycle();
      if (original != null) original.recycle();
      if (gray != null) gray.recycle();
      if (binary != null) binary.recycle();
    }
  }

  @Test
  public void largeUniformColorPanelStaysFlatAndDoesNotCreatePaperHalos() {
    Bitmap source = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888);
    Bitmap clean = null;
    try {
      source.eraseColor(Color.rgb(220, 220, 220));
      Canvas canvas = new Canvas(source);
      Paint paint = new Paint();
      int teal = Color.rgb(7, 94, 104);
      paint.setColor(teal);
      canvas.drawRect(70, 300, 570, 530, paint);
      clean = processor.enhance(source, DocumentProcessor.Filter.AUTO_COLOR);
      int minimum = 255;
      int maximum = 0;
      for (int y = 320; y <= 510; y += 38) {
        for (int x = 90; x <= 550; x += 46) {
          int value = brightness(clean.getPixel(x, y));
          minimum = Math.min(minimum, value);
          maximum = Math.max(maximum, value);
        }
      }
      assertTrue(
          "A uniformly colored panel must not develop a bright center or edge",
          maximum - minimum <= 12);
      assertTrue("The dark panel must not be washed out", maximum <= brightness(teal) + 20);
      float[] originalHsv = new float[3];
      float[] cleanHsv = new float[3];
      Color.colorToHSV(teal, originalHsv);
      Color.colorToHSV(clean.getPixel(320, 415), cleanHsv);
      assertTrue("Teal saturation must remain visible", cleanHsv[1] > 0.55f);
      assertTrue("Teal hue must remain stable", Math.abs(cleanHsv[0] - originalHsv[0]) < 12);
      int nearPanel = brightness(clean.getPixel(320, 285));
      int farFromPanel = brightness(clean.getPixel(320, 220));
      assertTrue(
          "The panel must not create a bright halo on adjacent paper",
          Math.abs(nearPanel - farFromPanel) <= 8);
      assertTrue("Neutral paper should still look clean", farFromPanel > 235);
    } finally {
      source.recycle();
      if (clean != null) clean.recycle();
    }
  }

  @Test
  public void rejectsCrossedCropCorners() {
    Bitmap source = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888);
    try {
      processor.warp(
          source,
          new PointF[] {new PointF(0, 0), new PointF(1, 1), new PointF(1, 0), new PointF(0, 1)});
      fail("A self-crossing crop must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("convex"));
    } finally {
      source.recycle();
    }
  }

  @Test
  public void exportAndEnhancementLimitTheLongestEdge() {
    Bitmap source = Bitmap.createBitmap(3200, 1000, Bitmap.Config.ARGB_8888);
    Bitmap warped = null;
    Bitmap original = null;
    Bitmap clean = null;
    try {
      source.eraseColor(Color.rgb(233, 231, 223));
      warped =
          processor.warp(
              source,
              new PointF[] {
                new PointF(0, 0), new PointF(1, 0), new PointF(1, 1), new PointF(0, 1)
              });
      original = processor.enhance(source, DocumentProcessor.Filter.ORIGINAL);
      clean = processor.enhance(source, DocumentProcessor.Filter.AUTO_COLOR);
      for (Bitmap output : new Bitmap[] {warped, original, clean}) {
        assertEquals(
            "The long edge must be bounded", 2600, Math.max(output.getWidth(), output.getHeight()));
        assertTrue(
            "Aspect ratio must remain approximately 3.2:1",
            Math.abs(output.getWidth() / (float) output.getHeight() - 3.2) < 0.02);
      }
    } finally {
      source.recycle();
      if (warped != null) warped.recycle();
      if (original != null) original.recycle();
      if (clean != null) clean.recycle();
    }
  }

  @Test
  public void rejectsTinyImagesBeforeNativeProcessing() {
    Bitmap tiny = Bitmap.createBitmap(23, 70, Bitmap.Config.ARGB_8888);
    try {
      expectInvalidImage(() -> processor.detect(tiny));
      expectInvalidImage(() -> processor.enhance(tiny, DocumentProcessor.Filter.AUTO_COLOR));
      expectInvalidImage(
          () ->
              processor.warp(
                  tiny,
                  new PointF[] {
                    new PointF(0, 0), new PointF(1, 0), new PointF(1, 1), new PointF(0, 1)
                  }));
    } finally {
      tiny.recycle();
    }
  }

  private static void expectInvalidImage(Runnable operation) {
    try {
      operation.run();
      fail("Images below the documented minimum must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("24"));
    }
  }

  private static Bitmap shadowedColorPage() {
    Bitmap source = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888);
    int[] pixels = new int[640 * 900];
    for (int y = 0; y < 900; y++) {
      for (int x = 0; x < 640; x++) {
        int tone = Math.round(135 + 115 * x / 639f);
        pixels[y * 640 + x] = Color.rgb(tone, tone, tone);
      }
    }
    source.setPixels(pixels, 0, 640, 0, 0, 640, 900);
    Canvas canvas = new Canvas(source);
    Paint paint = new Paint();
    paint.setColor(Color.rgb(175, 28, 40));
    canvas.drawRect(100, 300, 190, 340, paint);
    paint.setColor(Color.rgb(24, 62, 185));
    canvas.drawRect(400, 300, 490, 340, paint);
    paint.setColor(Color.rgb(23, 23, 23));
    for (int y = 450; y < 730; y += 40) canvas.drawRect(130, y, 510, y + 9, paint);
    return source;
  }

  private static int brightness(int color) {
    return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
  }
}
