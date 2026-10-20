package com.folio.scanner;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.folio.scanner.storage.ScanStore;
import com.folio.scanner.storage.ScanStore.Scan;
import com.folio.scanner.ui.ScanIllustration;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ScannerFlowTest {
  @Test
  public void sampleDetectionCropFiltersSaveRenameAndReopen() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ScanStore store = new ScanStore(context);
    // Clean only previous scans created by this test, never customer files.
    for (Scan scan : store.list()) if (scan.name.startsWith("Flow test")) store.delete(scan);
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      waitFor("Paper, made clear.");
      snapshot("home.png");
      onView(withText("Try a sample page")).perform(click());
      waitFor("Apply scan");
      scenario.recreate();
      waitFor("Apply scan");
      onView(withText("Page detected. Check the corners before continuing."))
          .check(matches(isDisplayed()));
      snapshot("crop.png");
      onView(withText("Apply scan")).perform(click());
      waitFor("Save scan");
      scenario.recreate();
      waitFor("Save scan");
      snapshot("auto-color.png");
      onView(withText("Original")).perform(click());
      waitForDescription("Original, selected");
      snapshot("original.png");
      onView(withText("Gray")).perform(click());
      waitForDescription("Gray, selected");
      onView(withText("B&W")).perform(click());
      waitForDescription("B&W, selected");
      onView(withText("Auto color")).perform(click());
      waitForDescription("Auto color, selected");
      onView(withText("Save scan")).perform(click());
      onView(isAssignableFrom(EditText.class))
          .perform(replaceText("Flow test sample"), closeSoftKeyboard());
      onView(withText("Save")).perform(click());
      waitFor("Export PDF");
      onView(withText("Flow test sample")).check(matches(isDisplayed()));
      assertTrue(store.list().stream().anyMatch(s -> s.name.equals("Flow test sample")));
      snapshot("saved.png");
      onView(withText("Rename")).perform(click());
      onView(isAssignableFrom(EditText.class))
          .perform(replaceText("Flow test renamed"), closeSoftKeyboard());
      onView(withText("Save")).perform(click());
      waitFor("Flow test renamed");
      scenario.recreate();
      waitFor("Export PDF");
      onView(withText("Flow test renamed")).check(matches(isDisplayed()));
      onView(withText("‹ Back")).perform(click());
      waitFor("Your scans");
      onView(withText("Flow test renamed")).perform(scrollTo(), click());
      waitFor("Export PDF");
    }
  }

  @Test
  public void exifOrientationAndReceiptPdfKeepGeometry() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Bitmap landscape = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
    landscape.eraseColor(android.graphics.Color.WHITE);
    File jpg = new File(context.getCacheDir(), "test-exif.jpg"),
        pdfFile = new File(context.getCacheDir(), "test-receipt.pdf");
    Bitmap upright = null, receipt = null;
    try {
      ScanStore.writeJpg(landscape, jpg);
      androidx.exifinterface.media.ExifInterface exif =
          new androidx.exifinterface.media.ExifInterface(jpg);
      exif.setAttribute(
          androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
          Integer.toString(androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90));
      exif.saveAttributes();
      upright = ScanStore.decode(context, android.net.Uri.fromFile(jpg));
      assertEquals(100, upright.getWidth());
      assertEquals(200, upright.getHeight());
      receipt = Bitmap.createBitmap(100, 1000, Bitmap.Config.ARGB_8888);
      receipt.eraseColor(android.graphics.Color.WHITE);
      try (OutputStream out = new FileOutputStream(pdfFile)) {
        ScanStore.writePdf(receipt, out);
      }
      try (ParcelFileDescriptor file =
              ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer pdf = new PdfRenderer(file);
          PdfRenderer.Page page = pdf.openPage(0)) {
        assertEquals(300, page.getWidth());
        assertEquals(3000, page.getHeight());
      }
    } finally {
      landscape.recycle();
      if (upright != null) upright.recycle();
      if (receipt != null) receipt.recycle();
      jpg.delete();
      pdfFile.delete();
    }
  }

  @Test
  public void privateLibraryAndRealJpgPdfExportsRoundTrip() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ScanStore store = new ScanStore(context);
    Bitmap page = ScanIllustration.sample();
    Scan scan = null;
    Bitmap decoded = null;
    File pdfFile = new File(context.getCacheDir(), "test-export.pdf");
    try {
      scan = store.save(page, "Storage test / page");
      assertEquals("Storage test   page", scan.name);
      decoded = BitmapFactory.decodeFile(scan.image.getAbsolutePath());
      assertNotNull(decoded);
      assertEquals(page.getWidth(), decoded.getWidth());
      assertEquals(page.getHeight(), decoded.getHeight());
      Scan renamed = store.rename(scan, "Storage test renamed");
      assertTrue(store.list().stream().anyMatch(s -> s.name.equals(renamed.name)));
      try (OutputStream out = new FileOutputStream(pdfFile)) {
        ScanStore.writePdf(decoded, out);
      }
      try (ParcelFileDescriptor file =
              ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer pdf = new PdfRenderer(file)) {
        assertEquals(1, pdf.getPageCount());
        try (PdfRenderer.Page first = pdf.openPage(0)) {
          assertEquals(595, first.getWidth());
          assertTrue(first.getHeight() > 700);
        }
      }
      File shots = context.getExternalFilesDir(null);
      if (shots != null)
        try (OutputStream out = new FileOutputStream(new File(shots, "sample-photo.jpg"))) {
          ScanStore.writeJpg(page, out);
        }
    } finally {
      if (scan != null) store.delete(scan);
      page.recycle();
      if (decoded != null) decoded.recycle();
      pdfFile.delete();
    }
  }

  private static void waitFor(String text) throws Exception {
    long deadline = System.currentTimeMillis() + 25000;
    Throwable last = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        onView(withText(text)).check(matches(isDisplayed()));
        return;
      } catch (Throwable e) {
        last = e;
        Thread.sleep(150);
      }
    }
    throw new AssertionError("Screen did not show " + text, last);
  }

  private static void waitForDescription(String description) throws Exception {
    long deadline = System.currentTimeMillis() + 25000;
    Throwable last = null;
    while (System.currentTimeMillis() < deadline) {
      try {
        onView(withContentDescription(description)).check(matches(isDisplayed()));
        return;
      } catch (Throwable e) {
        last = e;
        Thread.sleep(150);
      }
    }
    throw new AssertionError("Filter did not complete " + description, last);
  }

  private static void snapshot(String name) throws IOException {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    try (OutputStream out =
        new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      image.recycle();
    }
  }
}
