package com.folio.scanner.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public final class ScanIllustration extends View {
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

  public ScanIllustration(Context c) {
    super(c);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.save();
    c.translate(getWidth() / 2f, getHeight() / 2f);
    float s = Math.min(getWidth() / 300f, getHeight() / 200f);
    c.scale(s, s);
    p.setColor(0xFFE6F4F2);
    c.drawOval(new RectF(-125, -85, 125, 85), p);
    c.save();
    c.rotate(-9);
    p.setColor(0xFFD2E4E8);
    c.drawRoundRect(new RectF(-64, -76, 68, 92), 8, 8, p);
    c.restore();
    p.setColor(Color.WHITE);
    c.drawRoundRect(new RectF(-62, -86, 62, 78), 7, 7, p);
    p.setColor(0xFF075E68);
    c.drawRoundRect(new RectF(-44, -65, 10, -56), 2, 2, p);
    p.setColor(0xFFCFDFE4);
    for (int i = 0; i < 4; i++)
      c.drawRoundRect(new RectF(-44, -40 + i * 13, i == 3 ? 15 : 43, -36 + i * 13), 2, 2, p);
    p.setColor(0xFF81ADA5);
    c.drawRoundRect(new RectF(-44, 23, -12, 53), 3, 3, p);
    p.setColor(0xFFECAF6D);
    c.drawRoundRect(new RectF(-4, 23, 43, 53), 3, 3, p);
    p.setColor(0xFF075E68);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(3);
    p.setStrokeCap(Paint.Cap.ROUND);
    for (int x : new int[] {-78, 78})
      for (int y : new int[] {-99, 92}) {
        int dx = x < 0 ? 1 : -1, dy = y < 0 ? 1 : -1;
        c.drawLine(x, y, x + dx * 17, y, p);
        c.drawLine(x, y, x, y + dy * 17, p);
      }
    p.setStyle(Paint.Style.FILL);
    c.restore();
  }

  /** A real synthetic camera photograph, used by the app and instrumented tests. */
  public static Bitmap sample() {
    Bitmap page = Bitmap.createBitmap(900, 1250, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(page);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    c.drawColor(0xFFF5F0E7);
    p.setColor(0xFF16384D);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    p.setTextSize(54);
    c.drawText("A little more clarity.", 70, 125, p);
    p.setTextSize(26);
    p.setColor(0xFF566775);
    c.drawText("Field notes / 14 October 2026", 70, 176, p);
    p.setColor(0xFFCBD4D6);
    c.drawRect(70, 210, 830, 213, p);
    p.setColor(0xFF173F54);
    p.setTextSize(34);
    c.drawText("Good ideas deserve a clear page.", 70, 291, p);
    p.setTextSize(25);
    p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    String[] lines = {
      "Keep the blue ink. Keep the small details.",
      "Remove the shadow falling across the paper.",
      "Straighten the edges, and let the page breathe.",
      "That is all a useful scanner needs to do."
    };
    for (int i = 0; i < lines.length; i++) c.drawText(lines[i], 70, 353 + i * 47, p);
    p.setColor(0xFF247DC0);
    p.setTextSize(40);
    p.setTypeface(Typeface.create("serif", Typeface.ITALIC));
    c.drawText("Make something worth keeping.", 70, 632, p);
    p.setColor(0xFFD65C50);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(4);
    c.drawLine(70, 650, 711, 650, p);
    p.setStyle(Paint.Style.FILL);
    p.setColor(0xFF075E68);
    c.drawRoundRect(new RectF(70, 733, 830, 971), 8, 8, p);
    p.setColor(0xFFE6F4F2);
    p.setTextSize(33);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    c.drawText("Color stays. Shadows go.", 109, 802, p);
    p.setTextSize(25);
    c.drawText("An example document for Folio Scan.", 109, 853, p);
    c.drawText("Try Original to compare the lighting.", 109, 895, p);
    p.setColor(0xFF738089);
    p.setTextSize(23);
    c.drawText("Folio / sample page", 70, 1166, p);
    c.drawText("01", 790, 1166, p);
    p.setShader(
        new LinearGradient(
            0, 0, 900, 400, new int[] {0x850B1C2C, 0x080B1C2C}, null, Shader.TileMode.CLAMP));
    c.drawRect(0, 0, 900, 1250, p);
    p.setShader(null);
    Bitmap photo = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888);
    Canvas shot = new Canvas(photo);
    shot.drawColor(0xFF344A58);
    p.setColor(0xFF3C5360);
    for (int y = 0; y < 1600; y += 40) shot.drawRect(0, y, 1200, y + 2, p);
    float[] src = {0, 0, 900, 0, 900, 1250, 0, 1250};
    float[] dst = {191, 164, 1036, 235, 976, 1443, 123, 1355};
    Matrix m = new Matrix();
    m.setPolyToPoly(src, 0, dst, 0, 4);
    shot.drawBitmap(page, m, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
    page.recycle();
    return photo;
  }
}
