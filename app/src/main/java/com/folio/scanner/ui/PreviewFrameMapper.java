package com.folio.scanner.ui;

import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

/** Maps a content-only detection back onto PreviewView's full, possibly letterboxed bitmap. */
public final class PreviewFrameMapper {
  private PreviewFrameMapper() {}

  public static final class FrameMapping {
    private final Rect crop;
    private final int viewWidth, viewHeight;

    private FrameMapping(Rect crop, int viewWidth, int viewHeight) {
      this.crop = new Rect(crop);
      this.viewWidth = viewWidth;
      this.viewHeight = viewHeight;
    }

    /** Integer screenshot region actually supplied to the detector. */
    public Rect getCrop() {
      return new Rect(crop);
    }

    public PointF[] toOverlay(PointF[] contentCorners) {
      if (contentCorners == null) return null;
      PointF[] mapped = new PointF[contentCorners.length];
      for (int i = 0; i < contentCorners.length; i++) {
        PointF p = contentCorners[i];
        mapped[i] =
            new PointF(
                (crop.left + p.x * crop.width()) / viewWidth,
                (crop.top + p.y * crop.height()) / viewHeight);
      }
      return mapped;
    }

    public boolean sameGeometry(FrameMapping other) {
      return other != null
          && viewWidth == other.viewWidth
          && viewHeight == other.viewHeight
          && crop.equals(other.crop);
    }
  }

  /**
   * CameraX OutputTransform maps normalized [-1,-1]..[1,1] crop space into the view. See
   * PreviewView.getOutputTransform and CameraX camera-view 1.4.2 source. Its FIT_CENTER content
   * rectangle excludes bars introduced by PreviewView.getBitmap().
   */
  public static FrameMapping fromTransform(Matrix normalizedToView, int viewWidth, int viewHeight) {
    if (normalizedToView == null || viewWidth <= 0 || viewHeight <= 0) return null;
    RectF visible = new RectF(-1, -1, 1, 1);
    normalizedToView.mapRect(visible);
    if (!Float.isFinite(visible.left)
        || !Float.isFinite(visible.top)
        || !Float.isFinite(visible.right)
        || !Float.isFinite(visible.bottom)
        || !visible.intersect(0, 0, viewWidth, viewHeight)) return null;
    // Exclude partial bar pixels; use these same rounded bounds when remapping detections.
    Rect crop =
        new Rect(
            (int) Math.ceil(visible.left),
            (int) Math.ceil(visible.top),
            (int) Math.floor(visible.right),
            (int) Math.floor(visible.bottom));
    if (crop.width() < 24 || crop.height() < 24) return null;
    return new FrameMapping(crop, viewWidth, viewHeight);
  }
}
