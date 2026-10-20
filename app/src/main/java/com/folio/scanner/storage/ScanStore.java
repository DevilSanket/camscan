package com.folio.scanner.storage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Private local files; shared storage is written only via the system document picker. */
public final class ScanStore {
  public static final class Scan {
    public final String id, name;
    public final long created;
    public final File image;

    public Scan(String id, String name, long created, File image) {
      this.id = id;
      this.name = name;
      this.created = created;
      this.image = image;
    }
  }

  private final File directory;

  public ScanStore(Context context) {
    directory = new File(context.getFilesDir(), "scans");
    if (!directory.exists() && !directory.mkdirs())
      throw new IllegalStateException("Cannot create scan library");
  }

  public synchronized Scan save(Bitmap image, String name) throws Exception {
    String id = UUID.randomUUID().toString();
    File jpg = new File(directory, id + ".jpg");
    try {
      writeJpg(image, jpg);
      Scan scan = new Scan(id, cleanName(name), System.currentTimeMillis(), jpg);
      writeMetadata(scan);
      return scan;
    } catch (Exception e) {
      jpg.delete();
      new File(directory, id + ".json").delete();
      throw e;
    }
  }

  public synchronized List<Scan> list() {
    List<Scan> scans = new ArrayList<>();
    File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
    if (files != null)
      for (File file : files)
        try {
          JSONObject json = new JSONObject(read(file));
          String id = file.getName().replace(".json", "");
          File image = new File(directory, id + ".jpg");
          if (image.exists())
            scans.add(new Scan(id, json.getString("name"), json.getLong("created"), image));
        } catch (Exception ignored) {
          /* A damaged entry does not hide intact scans. */
        }
    scans.sort((a, b) -> Long.compare(b.created, a.created));
    return scans;
  }

  public synchronized Scan rename(Scan scan, String name) throws Exception {
    Scan renamed = new Scan(scan.id, cleanName(name), scan.created, scan.image);
    writeMetadata(renamed);
    return renamed;
  }

  public synchronized void delete(Scan scan) throws IOException {
    if (scan.image.exists() && !scan.image.delete()) throw new IOException("Could not delete scan");
    File metadata = new File(directory, scan.id + ".json");
    if (metadata.exists() && !metadata.delete())
      throw new IOException("Could not delete scan name");
  }

  private void writeMetadata(Scan scan) throws Exception {
    JSONObject json = new JSONObject();
    json.put("name", scan.name);
    json.put("created", scan.created);
    File temporary = new File(directory, scan.id + ".tmp");
    try (OutputStream out = new FileOutputStream(temporary)) {
      out.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    if (!temporary.renameTo(new File(directory, scan.id + ".json")))
      throw new IOException("Could not save scan name");
  }

  private static String read(File file) throws IOException {
    try (InputStream input = new FileInputStream(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
      return bytes.toString("UTF-8");
    }
  }

  public static String cleanName(String name) {
    String clean = name == null ? "" : name.trim().replaceAll("[\\p{Cntrl}/\\\\]", " ");
    return clean.isEmpty() ? "Untitled scan" : clean.substring(0, Math.min(clean.length(), 100));
  }

  public static void writeJpg(Bitmap image, File file) throws IOException {
    try (OutputStream out = new FileOutputStream(file)) {
      writeJpg(image, out);
    }
  }

  public static void writeJpg(Bitmap image, OutputStream out) throws IOException {
    if (!image.compress(Bitmap.CompressFormat.JPEG, 96, out))
      throw new IOException("Could not encode scan");
  }

  public static void writePdf(Bitmap image, OutputStream out) throws IOException {
    PdfDocument pdf = new PdfDocument();
    try {
      double scale = Math.min(595.0 / image.getWidth(), 3000.0 / image.getHeight());
      int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
      PdfDocument.Page page =
          pdf.startPage(new PdfDocument.PageInfo.Builder(width, height, 1).create());
      Canvas canvas = page.getCanvas();
      canvas.drawColor(Color.WHITE);
      canvas.drawBitmap(
          image,
          null,
          new android.graphics.Rect(0, 0, width, height),
          new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
      pdf.finishPage(page);
      pdf.writeTo(out);
    } finally {
      pdf.close();
    }
  }

  public static Bitmap decode(Context context, Uri uri) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
      BitmapFactory.decodeStream(stream, null, options);
    }
    if (options.outWidth <= 0 || options.outHeight <= 0)
      throw new IOException("Choose a supported photo, such as JPG or PNG");
    options.inSampleSize = 1;
    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 3200)
      options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap bitmap;
    try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
      bitmap = BitmapFactory.decodeStream(stream, null, options);
    }
    if (bitmap == null) throw new IOException("This photo could not be opened");
    Matrix matrix = new Matrix();
    try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
      ExifInterface exif = new ExifInterface(stream);
      int orientation =
          exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      switch (orientation) {
        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
          matrix.setScale(-1, 1);
          break;
        case ExifInterface.ORIENTATION_ROTATE_180:
          matrix.setRotate(180);
          break;
        case ExifInterface.ORIENTATION_FLIP_VERTICAL:
          matrix.setScale(1, -1);
          break;
        case ExifInterface.ORIENTATION_TRANSPOSE:
          matrix.setRotate(90);
          matrix.postScale(-1, 1);
          break;
        case ExifInterface.ORIENTATION_ROTATE_90:
          matrix.setRotate(90);
          break;
        case ExifInterface.ORIENTATION_TRANSVERSE:
          matrix.setRotate(-90);
          matrix.postScale(-1, 1);
          break;
        case ExifInterface.ORIENTATION_ROTATE_270:
          matrix.setRotate(-90);
          break;
      }
    } catch (IOException ignored) {
      /* Formats without EXIF keep their stored orientation. */
    }
    if (!matrix.isIdentity()) {
      Bitmap rotated =
          Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
      if (rotated != bitmap) bitmap.recycle();
      bitmap = rotated;
    }
    int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
    if (edge > 2600) {
      Bitmap scaled =
          Bitmap.createScaledBitmap(
              bitmap,
              Math.round(bitmap.getWidth() * 2600f / edge),
              Math.round(bitmap.getHeight() * 2600f / edge),
              true);
      if (scaled != bitmap) bitmap.recycle();
      bitmap = scaled;
    }
    return bitmap;
  }
}
