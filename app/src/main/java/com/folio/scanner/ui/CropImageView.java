package com.folio.scanner.ui;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/** Fit-centered crop editor. Handles are normalized to the actual photograph. */
public final class CropImageView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final RectF imageRect = new RectF();
  private Bitmap bitmap;
  private PointF[] corners;
  private int active = -1;

  public CropImageView(Context context) {
    super(context);
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    setContentDescription("Document corners. Drag the four corner handles to adjust the page.");
  }

  public void setImage(Bitmap image, PointF[] points) {
    bitmap = image;
    setCorners(points);
  }

  public void setCorners(PointF[] points) {
    corners = new PointF[4];
    for (int i = 0; i < 4; i++) corners[i] = new PointF(points[i].x, points[i].y);
    invalidate();
  }

  public PointF[] getCorners() {
    PointF[] copy = new PointF[4];
    for (int i = 0; i < 4; i++) copy[i] = new PointF(corners[i].x, corners[i].y);
    return copy;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (bitmap == null || corners == null) return;
    float pad = 24 * getResources().getDisplayMetrics().density;
    float scale =
        Math.min(
            (getWidth() - 2 * pad) / bitmap.getWidth(),
            (getHeight() - 2 * pad) / bitmap.getHeight());
    float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
    imageRect.set(
        (getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
    paint.setColor(Color.WHITE);
    paint.setStyle(Paint.Style.FILL);
    canvas.drawBitmap(bitmap, null, imageRect, paint);
    Path page = new Path();
    for (int i = 0; i < 4; i++) {
      float x = x(corners[i]), y = y(corners[i]);
      if (i == 0) page.moveTo(x, y);
      else page.lineTo(x, y);
    }
    page.close();
    Path shade = new Path();
    shade.addRect(imageRect, Path.Direction.CW);
    shade.addPath(page);
    shade.setFillType(Path.FillType.EVEN_ODD);
    paint.setColor(0x990D2131);
    canvas.drawPath(shade, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setColor(0xFF48D4C3);
    paint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
    canvas.drawPath(page, paint);
    paint.setStyle(Paint.Style.FILL);
    float radius = 10 * getResources().getDisplayMetrics().density;
    for (int i = 0; i < 4; i++) {
      paint.setColor(Color.WHITE);
      canvas.drawCircle(x(corners[i]), y(corners[i]), radius, paint);
      paint.setColor(0xFF075E68);
      canvas.drawCircle(x(corners[i]), y(corners[i]), radius * .48f, paint);
    }
  }

  private float x(PointF p) {
    return imageRect.left + p.x * imageRect.width();
  }

  private float y(PointF p) {
    return imageRect.top + p.y * imageRect.height();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (corners == null || imageRect.width() <= 0) return false;
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      float best = 40 * getResources().getDisplayMetrics().density;
      active = -1;
      for (int i = 0; i < 4; i++) {
        float d = (float) Math.hypot(event.getX() - x(corners[i]), event.getY() - y(corners[i]));
        if (d < best) {
          best = d;
          active = i;
        }
      }
      if (active < 0) return false;
      getParent().requestDisallowInterceptTouchEvent(true);
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_MOVE && active >= 0) {
      PointF previous = corners[active];
      corners[active] =
          new PointF(
              Math.max(0, Math.min(1, (event.getX() - imageRect.left) / imageRect.width())),
              Math.max(0, Math.min(1, (event.getY() - imageRect.top) / imageRect.height())));
      if (!valid(corners)) corners[active] = previous;
      invalidate();
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_UP
        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      active = -1;
      getParent().requestDisallowInterceptTouchEvent(false);
      performClick();
      return true;
    }
    return active >= 0;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  private static boolean valid(PointF[] p) {
    double area = 0;
    for (int i = 0; i < 4; i++) {
      PointF a = p[i], b = p[(i + 1) % 4], c = p[(i + 2) % 4];
      if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) < .006) return false;
      if (Math.hypot(b.x - a.x, b.y - a.y) < .04) return false;
      area += a.x * b.y - b.x * a.y;
    }
    return area > .035;
  }
}
