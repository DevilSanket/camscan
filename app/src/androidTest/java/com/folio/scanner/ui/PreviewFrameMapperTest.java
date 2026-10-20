package com.folio.scanner.ui;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.folio.scanner.ui.PreviewFrameMapper.FrameMapping;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PreviewFrameMapperTest {
  @Test
  public void portraitFitCenterExcludesBarsAndMapsContentCornersToOverlay() {
    FrameMapping mapping = mapping(new RectF(0, 200, 900, 1400), 900, 1600);
    assertEquals(new Rect(0, 200, 900, 1400), mapping.getCrop());
    PointF[] overlay = mapping.toOverlay(bounds());
    assertEquals(0, overlay[0].x, .000001);
    assertEquals(.125, overlay[0].y, .000001);
    assertEquals(1, overlay[2].x, .000001);
    assertEquals(.875, overlay[2].y, .000001);
    Bitmap frame = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888);
    Bitmap content = null;
    try {
      Canvas canvas = new Canvas(frame);
      canvas.drawColor(Color.BLACK);
      Paint paint = new Paint();
      paint.setColor(Color.WHITE);
      canvas.drawRect(0, 200, 900, 1400, paint);
      Rect crop = mapping.getCrop();
      content = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width(), crop.height());
      assertEquals(900, content.getWidth());
      assertEquals(1200, content.getHeight());
      assertEquals(Color.WHITE, content.getPixel(0, 0));
      assertEquals(Color.WHITE, content.getPixel(899, 1199));
    } finally {
      frame.recycle();
      if (content != null && content != frame) content.recycle();
    }
  }

  @Test
  public void landscapeFitCenterMapsSideBarsAndInteriorPoint() {
    FrameMapping mapping = mapping(new RectF(200, 0, 1400, 900), 1600, 900);
    assertEquals(new Rect(200, 0, 1400, 900), mapping.getCrop());
    PointF[] overlay =
        mapping.toOverlay(new PointF[] {new PointF(0, 0), new PointF(1, 1), new PointF(.25f, .5f)});
    assertEquals(.125, overlay[0].x, .000001);
    assertEquals(.875, overlay[1].x, .000001);
    assertEquals(.3125, overlay[2].x, .000001);
    assertEquals(.5, overlay[2].y, .000001);
  }

  @Test
  public void rotatedAndMirroredOutputTransformStillFindsTheVisibleRect() {
    Matrix transform = new Matrix();
    transform.setRotate(90);
    transform.postScale(-450, 600);
    transform.postTranslate(450, 800);
    FrameMapping mapping = PreviewFrameMapper.fromTransform(transform, 900, 1600);
    assertNotNull(mapping);
    assertEquals(new Rect(0, 200, 900, 1400), mapping.getCrop());
  }

  @Test
  public void roundingAndViewClippingUseTheSameBoundsForOverlayAndCrop() {
    FrameMapping fractional = mapping(new RectF(.25f, 199.6f, 899.75f, 1400.4f), 900, 1600);
    assertEquals(new Rect(1, 200, 899, 1400), fractional.getCrop());
    PointF[] overlay = fractional.toOverlay(bounds());
    assertEquals(1.0 / 900, overlay[0].x, .000001);
    assertEquals(899.0 / 900, overlay[2].x, .000001);
    FrameMapping clipped = mapping(new RectF(-100, -50, 1000, 1650), 900, 1600);
    assertEquals(new Rect(0, 0, 900, 1600), clipped.getCrop());
    Rect leakedCopy = clipped.getCrop();
    leakedCopy.left = 500;
    assertEquals(0, clipped.getCrop().left);
    assertTrue(clipped.sameGeometry(mapping(new RectF(0, 0, 900, 1600), 900, 1600)));
    assertFalse(clipped.sameGeometry(fractional));
  }

  @Test
  public void missingTransformOrTinyVisibleContentCannotEnterDetector() {
    assertNull(PreviewFrameMapper.fromTransform(null, 900, 1600));
    assertNull(PreviewFrameMapper.fromTransform(new Matrix(), 0, 1600));
    assertNull(mapping(new RectF(0, 0, 23, 100), 900, 1600));
    assertNull(mapping(new RectF(1000, 0, 1200, 100), 900, 1600));
  }

  private static FrameMapping mapping(RectF content, int width, int height) {
    Matrix transform = new Matrix();
    transform.setRectToRect(new RectF(-1, -1, 1, 1), content, Matrix.ScaleToFit.FILL);
    return PreviewFrameMapper.fromTransform(transform, width, height);
  }

  private static PointF[] bounds() {
    return new PointF[] {new PointF(0, 0), new PointF(1, 0), new PointF(1, 1), new PointF(0, 1)};
  }
}
