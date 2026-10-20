package com.folio.scanner;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.view.transform.OutputTransform;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.folio.scanner.processing.DocumentProcessor;
import com.folio.scanner.processing.DocumentProcessor.Filter;
import com.folio.scanner.storage.ScanStore;
import com.folio.scanner.storage.ScanStore.Scan;
import com.folio.scanner.ui.CropImageView;
import com.folio.scanner.ui.LivePageTracker;
import com.folio.scanner.ui.PreviewFrameMapper;
import com.folio.scanner.ui.PreviewFrameMapper.FrameMapping;
import com.folio.scanner.ui.ScanIllustration;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opencv.android.OpenCVLoader;

public final class MainActivity extends ComponentActivity {
  private static final int INK = 0xFF172B3A,
      TEAL = 0xFF075E68,
      BG = 0xFFF4F8FA,
      MUTED = 0xFF617584,
      LINE = 0xFFDCE9ED,
      SOFT = 0xFFE6F4F2;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService cameraWorker = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final AtomicBoolean analyzing = new AtomicBoolean(false);
  private FrameLayout root;
  private LinearLayout layout;
  private DocumentProcessor processor;
  private ScanStore store;
  private Bitmap source, cropped, processed;
  private PointF[] corners;
  private Filter filter = Filter.AUTO_COLOR;
  private String screen = "home", scanName = "", cropMessage = "";
  private Scan selected;
  private CropImageView cropView;
  private boolean busy = false, capturing = false, autoCapture = true, torch = false;
  private int generation = 0;
  private PreviewView previewView;
  private PageOverlay overlay;
  private TextView cameraStatus;
  private ProcessCameraProvider cameraProvider;
  private ImageCapture imageCapture;
  private Camera camera;
  private final LivePageTracker liveTracker = new LivePageTracker();
  private FrameMapping liveMapping;
  private int liveRevision = 0;
  private ActivityResultLauncher<String[]> importPhoto;
  private ActivityResultLauncher<String> cameraPermission;
  private ActivityResultLauncher<Intent> exportFile;
  private String exportMime = "application/pdf";
  private String draftFileName;
  private boolean restoringScan = false;
  private Uri pendingExportUri;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
    importPhoto =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
              if (uri != null) loadPhoto(uri);
            });
    cameraPermission =
        registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            allowed -> {
              if (allowed) showCamera();
              else
                showMessage(
                    "Camera permission is off",
                    "Allow camera access in Android settings, or use Import photo to scan an"
                        + " existing image.");
            });
    exportFile =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == RESULT_OK
                  && result.getData() != null
                  && result.getData().getData() != null) {
                Uri destination = result.getData().getData();
                if (restoringScan) pendingExportUri = destination;
                else writeExport(destination);
              }
            });
    root = new FrameLayout(this);
    root.setBackgroundColor(BG);
    setContentView(root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                back();
              }
            });
    store = new ScanStore(this);
    if (!OpenCVLoader.initLocal()) {
      showHome();
      showMessage(
          "Scanner could not start",
          "This device could not load the scan engine. Reinstall the app and try again.");
      return;
    }
    processor = new DocumentProcessor();
    showHome();
    if (state != null) restoreDraft(state);
  }

  private void base(String next, boolean dark) {
    generation++;
    screen = next;
    stopCamera();
    root.removeAllViews();
    root.setBackgroundColor(dark ? INK : BG);
    WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(!dark);
    WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(!dark);
    layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    root.addView(layout, new FrameLayout.LayoutParams(-1, -1));
  }

  private void showHome() {
    base("home", false);
    selected = null;
    source = null;
    cropped = null;
    processed = null;
    corners = null;
    cropView = null;
    draftFileName = null;
    LinearLayout top = row();
    top.setPadding(dp(24), dp(14), dp(20), dp(12));
    TextView brand = text("▱  Folio Scan", 23, INK, true);
    top.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
    top.addView(button("About", false, this::showAbout), new LinearLayout.LayoutParams(-2, dp(48)));
    layout.addView(top);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = column();
    body.setPadding(dp(24), dp(12), dp(24), dp(28));
    scroll.addView(body);
    body.addView(text("Paper, made clear.", 32, INK, true));
    TextView sub = text("A clean color scan, in a few taps.", 16, MUTED, false);
    marginAdd(body, sub, 8, 0);
    ScanIllustration art = new ScanIllustration(this);
    body.addView(art, new LinearLayout.LayoutParams(-1, dp(190)));
    body.addView(
        button("Scan with camera", true, this::requestCamera),
        new LinearLayout.LayoutParams(-1, dp(56)));
    marginAdd(body, button("Import photo", false, this::pickPhoto), 10, 0, 52);
    TextView sample = text("Try a sample page", 14, TEAL, true);
    sample.setGravity(Gravity.CENTER);
    sample.setPadding(0, dp(12), 0, dp(12));
    sample.setContentDescription("Try a sample page");
    sample.setOnClickListener(v -> loadSample());
    body.addView(sample, new LinearLayout.LayoutParams(-1, dp(48)));
    LinearLayout heading = row();
    marginAdd(body, heading, 24, 12);
    heading.addView(text("Your scans", 20, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    java.util.List<Scan> scans = store.list();
    heading.addView(text(Integer.toString(scans.size()), 14, MUTED, false));
    if (scans.isEmpty()) {
      LinearLayout empty = column();
      empty.setPadding(dp(20), dp(20), dp(20), dp(20));
      empty.setBackground(shape(Color.WHITE, 14, LINE));
      empty.addView(text("Your first page starts here", 16, INK, true));
      marginAdd(
          empty,
          text("Scan or import a document. Your saved pages will appear here.", 14, MUTED, false),
          6,
          0);
      body.addView(empty);
    } else
      for (Scan scan : scans) {
        LinearLayout item = row();
        item.setPadding(dp(12), dp(12), dp(12), dp(12));
        item.setBackground(shape(Color.WHITE, 12, LINE));
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 16;
        thumb.setImageBitmap(BitmapFactory.decodeFile(scan.image.getAbsolutePath(), options));
        item.addView(thumb, new LinearLayout.LayoutParams(dp(48), dp(64)));
        LinearLayout words = column();
        words.setPadding(dp(16), 0, dp(8), 0);
        words.addView(text(scan.name, 16, INK, true));
        marginAdd(
            words,
            text(
                new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
                        .format(new Date(scan.created))
                    + "  •  1 page",
                12,
                MUTED,
                false),
            5,
            0);
        item.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView open = text("›", 26, TEAL, false);
        item.addView(open);
        item.setContentDescription("Open scan " + scan.name);
        item.setOnClickListener(v -> openScan(scan));
        marginAdd(body, item, 0, 10);
      }
    TextView offline = text("Processed on your device. Always.", 12, MUTED, false);
    offline.setGravity(Gravity.CENTER);
    marginAdd(body, offline, 20, 0);
  }

  private void loadSample() {
    if (processor == null) return;
    beginWork("Preparing sample…");
    int token = generation;
    worker.execute(
        () -> {
          try {
            Bitmap b = ScanIllustration.sample();
            prepareSource(b, token);
          } catch (Exception e) {
            fail(token, "Sample could not open", e);
          }
        });
  }

  private void pickPhoto() {
    try {
      importPhoto.launch(new String[] {"image/*"});
    } catch (ActivityNotFoundException e) {
      showMessage(
          "No photo picker available",
          "Install or enable Android’s Files app, then try Import photo again.");
    }
  }

  private void loadPhoto(Uri uri) {
    if (processor == null) return;
    beginWork("Finding the page…");
    int token = generation;
    worker.execute(
        () -> {
          try {
            prepareSource(ScanStore.decode(this, uri), token);
          } catch (Exception e) {
            fail(token, "Photo could not open", e);
          }
        });
  }

  private void prepareSource(Bitmap bitmap, int token) throws Exception {
    DocumentProcessor.Detection detection = processor.detect(bitmap);
    File draft = storeDraft(bitmap);
    handler.post(
        () -> {
          if (!current(token)) {
            draft.delete();
            return;
          }
          endWork();
          source = bitmap;
          draftFileName = draft.getName();
          cropped = null;
          processed = null;
          selected = null;
          filter = Filter.AUTO_COLOR;
          scanName =
              "Scan "
                  + new SimpleDateFormat("d MMM yyyy, HHmm", Locale.getDefault())
                      .format(new Date());
          corners = detection == null ? fullCorners() : detection.corners;
          cropMessage =
              detection == null
                  ? "Page edges weren’t clear. Drag the corners to fit your page."
                  : "Page detected. Check the corners before continuing.";
          showCrop();
        });
  }

  private static PointF[] fullCorners() {
    return new PointF[] {new PointF(0, 0), new PointF(1, 0), new PointF(1, 1), new PointF(0, 1)};
  }

  private void showCrop() {
    if (source == null) {
      showHome();
      return;
    }
    base("crop", false);
    header("Adjust page", this::back);
    TextView hint = text(cropMessage, 14, MUTED, false);
    hint.setPadding(dp(24), dp(4), dp(24), dp(12));
    layout.addView(hint);
    cropView = new CropImageView(this);
    cropView.setImage(source, corners);
    cropView.setBackgroundColor(0xFFE3EBEF);
    layout.addView(cropView, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout actions = row();
    actions.setPadding(dp(24), dp(12), dp(24), dp(12));
    actions.addView(
        button(
            "Reset edges",
            false,
            () -> {
              corners = fullCorners();
              cropView.setCorners(corners);
            }),
        new LinearLayout.LayoutParams(0, dp(48), 1));
    LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(0, dp(48), 1);
    rotateParams.leftMargin = dp(12);
    actions.addView(button("Rotate", false, this::rotateSource), rotateParams);
    layout.addView(actions);
    LinearLayout footer = column();
    footer.setPadding(dp(24), 0, dp(24), dp(20));
    footer.addView(
        button("Apply scan", true, this::applyScan), new LinearLayout.LayoutParams(-1, dp(56)));
    layout.addView(footer);
  }

  private void rotateSource() {
    beginWork("Turning the page…");
    int token = generation;
    Bitmap original = source;
    worker.execute(
        () -> {
          try {
            Matrix m = new Matrix();
            m.postRotate(90);
            Bitmap rotated =
                Bitmap.createBitmap(
                    original, 0, 0, original.getWidth(), original.getHeight(), m, true);
            DocumentProcessor.Detection d = processor.detect(rotated);
            File draft = storeDraft(rotated);
            handler.post(
                () -> {
                  if (!current(token)) {
                    draft.delete();
                    return;
                  }
                  endWork();
                  source = rotated;
                  draftFileName = draft.getName();
                  corners = d == null ? fullCorners() : d.corners;
                  cropMessage =
                      d == null
                          ? "Drag the corners to fit your page."
                          : "Page detected. Check the corners before continuing.";
                  showCrop();
                });
          } catch (Exception e) {
            fail(token, "Page could not rotate", e);
          }
        });
  }

  private void applyScan() {
    corners = cropView.getCorners();
    beginWork("Cleaning up the colors…");
    int token = generation;
    Bitmap image = source;
    PointF[] points = corners;
    worker.execute(
        () -> {
          try {
            Bitmap page = processor.warp(image, points);
            Bitmap enhanced = processor.enhance(page, Filter.AUTO_COLOR);
            handler.post(
                () -> {
                  if (!current(token)) return;
                  endWork();
                  cropped = page;
                  processed = enhanced;
                  filter = Filter.AUTO_COLOR;
                  showReview();
                });
          } catch (Exception e) {
            fail(token, "Page could not be scanned", e);
          }
        });
  }

  private void showReview() {
    base("review", false);
    header(
        "Your scan",
        () -> {
          corners = cropView == null ? corners : cropView.getCorners();
          showCrop();
        });
    TextView hint =
        text(
            filter == Filter.AUTO_COLOR
                ? "Auto color applied. Original colors, clearer paper."
                : "Compare the finish. Choose the look you prefer.",
            14,
            MUTED,
            false);
    hint.setPadding(dp(24), 0, dp(24), dp(12));
    layout.addView(hint);
    addPageImage(processed);
    LinearLayout footer = column();
    footer.setPadding(dp(20), dp(14), dp(20), dp(20));
    LinearLayout filters = row();
    String[] labels = {"Auto color", "Original", "Gray", "B&W"};
    Filter[] choices = {Filter.AUTO_COLOR, Filter.ORIGINAL, Filter.GRAYSCALE, Filter.BLACK_WHITE};
    for (int i = 0; i < choices.length; i++) {
      Filter chosen = choices[i];
      Button b = button(labels[i], filter == chosen, () -> changeFilter(chosen));
      b.setTextSize(12);
      b.setPadding(dp(2), 0, dp(2), 0);
      b.setContentDescription(labels[i] + (filter == chosen ? ", selected" : ""));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
      if (i > 0) lp.leftMargin = dp(6);
      filters.addView(b, lp);
    }
    footer.addView(filters);
    marginAdd(footer, button("Save scan", true, this::saveScan), 14, 0, 56);
    layout.addView(footer);
  }

  private void changeFilter(Filter chosen) {
    if (filter == chosen) return;
    beginWork("Applying " + filterName(chosen) + "…");
    int token = generation;
    Bitmap page = cropped;
    worker.execute(
        () -> {
          try {
            Bitmap b = processor.enhance(page, chosen);
            handler.post(
                () -> {
                  if (!current(token)) return;
                  endWork();
                  processed = b;
                  filter = chosen;
                  showReview();
                });
          } catch (Exception e) {
            fail(token, "Filter could not be applied", e);
          }
        });
  }

  private String filterName(Filter f) {
    switch (f) {
      case ORIGINAL:
        return "original";
      case GRAYSCALE:
        return "grayscale";
      case BLACK_WHITE:
        return "black & white";
      default:
        return "auto color";
    }
  }

  private void saveScan() {
    EditText name = new EditText(this);
    name.setSingleLine(true);
    name.setText(scanName);
    name.setSelectAllOnFocus(true);
    name.setPadding(dp(24), dp(12), dp(24), dp(12));
    new AlertDialog.Builder(this)
        .setTitle("Name your scan")
        .setView(name)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Save",
            (d, w) -> {
              beginWork("Saving your scan…");
              int token = generation;
              Bitmap b = processed;
              String title = name.getText().toString();
              worker.execute(
                  () -> {
                    try {
                      Scan scan = store.save(b, title);
                      handler.post(
                          () -> {
                            if (!current(token)) return;
                            endWork();
                            selected = scan;
                            scanName = scan.name;
                            source = null;
                            cropped = null;
                            corners = null;
                            cropView = null;
                            clearDrafts();
                            showSaved();
                            toast("Scan saved");
                          });
                    } catch (Exception e) {
                      fail(token, "Scan could not be saved", e);
                    }
                  });
            })
        .show();
  }

  private void openScan(Scan scan) {
    beginWork("Opening scan…");
    int token = generation;
    worker.execute(
        () -> {
          try {
            Bitmap b = BitmapFactory.decodeFile(scan.image.getAbsolutePath());
            if (b == null) throw new IOException("The scan file could not be read");
            handler.post(
                () -> {
                  if (!current(token)) return;
                  endWork();
                  restoringScan = false;
                  selected = scan;
                  processed = b;
                  scanName = scan.name;
                  showSaved();
                  if (pendingExportUri != null) {
                    Uri destination = pendingExportUri;
                    pendingExportUri = null;
                    writeExport(destination);
                  }
                });
          } catch (Exception e) {
            fail(token, "Scan could not open", e);
          }
        });
  }

  private void showSaved() {
    base("saved", false);
    header("Saved scan", this::showHome);
    TextView title = text(scanName, 22, INK, true);
    title.setMaxLines(2);
    title.setPadding(dp(24), 0, dp(24), dp(6));
    layout.addView(title);
    TextView hint = text("1 page  •  Stored on this device", 13, MUTED, false);
    hint.setPadding(dp(24), 0, dp(24), dp(14));
    layout.addView(hint);
    addPageImage(processed);
    LinearLayout footer = column();
    footer.setPadding(dp(24), dp(14), dp(24), dp(20));
    footer.addView(
        button("Export PDF", true, () -> chooseExport("application/pdf")),
        new LinearLayout.LayoutParams(-1, dp(54)));
    LinearLayout second = row();
    marginAdd(footer, second, 10, 0);
    second.addView(
        button("Export JPG", false, () -> chooseExport("image/jpeg")),
        new LinearLayout.LayoutParams(0, dp(48), 1));
    LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(48), 1);
    shareParams.leftMargin = dp(10);
    second.addView(button("Share", false, this::share), shareParams);
    LinearLayout manage = row();
    manage.setGravity(Gravity.CENTER);
    marginAdd(footer, manage, 8, 0);
    manage.addView(
        button("Rename", false, this::rename), new LinearLayout.LayoutParams(dp(110), dp(44)));
    LinearLayout.LayoutParams delParams = new LinearLayout.LayoutParams(dp(110), dp(44));
    delParams.leftMargin = dp(12);
    manage.addView(button("Delete", false, this::delete), delParams);
    layout.addView(footer);
  }

  private void addPageImage(Bitmap image) {
    FrameLayout holder = new FrameLayout(this);
    holder.setBackgroundColor(0xFFE3EBEF);
    holder.setPadding(dp(24), dp(20), dp(24), dp(20));
    ImageView view = new ImageView(this);
    view.setScaleType(ImageView.ScaleType.FIT_CENTER);
    view.setImageBitmap(image);
    view.setContentDescription("Scanned document preview");
    holder.addView(view, new FrameLayout.LayoutParams(-1, -1));
    layout.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void rename() {
    EditText name = new EditText(this);
    name.setText(scanName);
    name.setSingleLine(true);
    name.setPadding(dp(24), dp(12), dp(24), dp(12));
    new AlertDialog.Builder(this)
        .setTitle("Rename scan")
        .setView(name)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Save",
            (d, w) -> {
              try {
                selected = store.rename(selected, name.getText().toString());
                scanName = selected.name;
                showSaved();
              } catch (Exception e) {
                showMessage("Name could not be saved", errorText(e));
              }
            })
        .show();
  }

  private void delete() {
    new AlertDialog.Builder(this)
        .setTitle("Delete this scan?")
        .setMessage("This removes the scan from this device. Exported copies are kept.")
        .setNegativeButton("Keep scan", null)
        .setPositiveButton(
            "Delete",
            (d, w) -> {
              try {
                store.delete(selected);
                processed = null;
                source = null;
                cropped = null;
                showHome();
                toast("Scan deleted");
              } catch (Exception e) {
                showMessage("Scan could not be deleted", errorText(e));
              }
            })
        .show();
  }

  private void chooseExport(String mime) {
    exportMime = mime;
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(mime);
    intent.putExtra(
        Intent.EXTRA_TITLE, scanName + (mime.equals("application/pdf") ? ".pdf" : ".jpg"));
    try {
      exportFile.launch(intent);
    } catch (ActivityNotFoundException e) {
      showMessage(
          "No file picker available", "Use Share to send this scan to an installed files app.");
    }
  }

  private void writeExport(Uri uri) {
    Bitmap b = processed;
    if (b == null) {
      showMessage(
          "Open the scan again",
          "The page is no longer loaded. Open it from Your scans and export again.");
      return;
    }
    beginWork("Exporting scan…");
    int token = generation;
    String mime = exportMime;
    worker.execute(
        () -> {
          try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("The selected file could not be written");
            if (mime.equals("application/pdf")) ScanStore.writePdf(b, out);
            else ScanStore.writeJpg(b, out);
            handler.post(
                () -> {
                  if (!current(token)) return;
                  endWork();
                  toast("Scan exported");
                });
          } catch (Exception e) {
            fail(token, "Export could not finish", e);
          }
        });
  }

  private void share() {
    beginWork("Preparing to share…");
    int token = generation;
    Bitmap b = processed;
    String name = scanName;
    worker.execute(
        () -> {
          try {
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs())
              throw new IOException("Could not create export folder");
            File file = new File(dir, ScanStore.cleanName(name) + ".pdf");
            try (OutputStream out = new FileOutputStream(file)) {
              ScanStore.writePdf(b, out);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            handler.post(
                () -> {
                  if (!current(token)) return;
                  endWork();
                  Intent send = new Intent(Intent.ACTION_SEND);
                  send.setType("application/pdf");
                  send.putExtra(Intent.EXTRA_STREAM, uri);
                  send.setClipData(ClipData.newRawUri("Scan", uri));
                  send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                  try {
                    startActivity(Intent.createChooser(send, "Share scan"));
                  } catch (ActivityNotFoundException e) {
                    showMessage("No sharing apps installed", "Use Export PDF to save a copy.");
                  }
                });
          } catch (Exception e) {
            fail(token, "Scan could not be shared", e);
          }
        });
  }

  private void requestCamera() {
    if (processor == null) return;
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) showCamera();
    else cameraPermission.launch(Manifest.permission.CAMERA);
  }

  private void showCamera() {
    base("camera", true);
    capturing = false;
    torch = false;
    resetLiveTracking();
    liveTracker.setAutoCaptureEnabled(autoCapture);
    header("Scan a page", this::showHome);
    TextView guidance = text("Place the whole page inside the frame.", 14, 0xFFBCD0D9, false);
    guidance.setPadding(dp(24), 0, dp(24), dp(14));
    layout.addView(guidance);
    FrameLayout cameraFrame = new FrameLayout(this);
    layout.addView(cameraFrame, new LinearLayout.LayoutParams(-1, 0, 1));
    previewView = new PreviewView(this);
    previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
    previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
    cameraFrame.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
    overlay = new PageOverlay(this);
    cameraFrame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout footer = column();
    footer.setPadding(dp(24), dp(14), dp(24), dp(20));
    cameraStatus = text("Finding page edges…", 14, Color.WHITE, false);
    cameraStatus.setGravity(Gravity.CENTER);
    footer.addView(cameraStatus);
    LinearLayout modes = row();
    marginAdd(footer, modes, 10, 10);
    Button auto = button(autoCapture ? "Auto capture on" : "Auto capture off", false, null);
    auto.setOnClickListener(
        v -> {
          autoCapture = !autoCapture;
          auto.setText(autoCapture ? "Auto capture on" : "Auto capture off");
          resetLiveTracking();
          liveTracker.setAutoCaptureEnabled(autoCapture);
          cameraStatus.setText("Finding page edges…");
        });
    modes.addView(auto, new LinearLayout.LayoutParams(0, dp(46), 1));
    LinearLayout.LayoutParams torchParams = new LinearLayout.LayoutParams(0, dp(46), 1);
    torchParams.leftMargin = dp(10);
    Button flash = button("Light off", false, null);
    flash.setOnClickListener(
        v -> {
          if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            torch = !torch;
            camera.getCameraControl().enableTorch(torch);
            flash.setText(torch ? "Light on" : "Light off");
          } else toast("This camera has no light");
        });
    modes.addView(flash, torchParams);
    footer.addView(
        button("Capture page", true, this::capture), new LinearLayout.LayoutParams(-1, dp(56)));
    layout.addView(footer);
    int token = generation;
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
    future.addListener(
        () -> {
          if (!current(token) || !screen.equals("camera")) return;
          try {
            cameraProvider = future.get();
            Preview preview =
                new Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .build();
            imageCapture =
                new ImageCapture.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            cameraProvider.unbindAll();
            camera =
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            handler.postDelayed(cameraTick, 650);
          } catch (Exception e) {
            cameraStatus.setText("Camera unavailable. Use Import photo.");
            showMessage("Camera could not start", errorText(e));
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  private final Runnable cameraTick =
      new Runnable() {
        @Override
        public void run() {
          if (!screen.equals("camera") || capturing || isFinishing() || !cameraResumed()) return;
          if (cameraResumed()
              && previewView != null
              && previewView.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING
              && analyzing.compareAndSet(false, true)) {
            analyzePreview();
          }
          handler.postDelayed(this, 250);
        }
      };

  private boolean cameraResumed() {
    return getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
  }

  /** PreviewView.getBitmap() is view-sized: strip FIT_CENTER bars before detection. */
  // CameraX 1.4.2 exposes the normalized transform matrix through this restricted accessor.
  @android.annotation.SuppressLint("RestrictedApi")
  @androidx.annotation.OptIn(markerClass = androidx.camera.view.TransformExperimental.class)
  private void analyzePreview() {
    Bitmap frame = null, content = null, input = null;
    boolean submitted = false;
    try {
      OutputTransform transform = previewView.getOutputTransform();
      if (transform == null) return;
      FrameMapping mapping =
          PreviewFrameMapper.fromTransform(
              transform.getMatrix(), previewView.getWidth(), previewView.getHeight());
      if (mapping == null) return;
      if (!mapping.sameGeometry(liveMapping)) {
        resetLiveTracking();
        liveMapping = mapping;
      }
      frame = previewView.getBitmap();
      if (frame == null
          || frame.getWidth() != previewView.getWidth()
          || frame.getHeight() != previewView.getHeight()) return;
      Rect region = mapping.getCrop();
      content =
          Bitmap.createBitmap(frame, region.left, region.top, region.width(), region.height());
      // The detector applies OpenScan's exact floor-indexed 700px downsample on its worker.
      input = content;
      final Bitmap sample = input;
      final PointF[] previous = liveTracker.getPreviousRaw();
      final int token = generation, revision = liveRevision;
      cameraWorker.execute(
          () -> {
            try {
              DocumentProcessor.Detection detection = processor.detect(sample, previous);
              handler.post(
                  () -> {
                    if (liveFrameCurrent(token, revision)) liveDetection(detection, mapping);
                  });
            } catch (Exception e) {
              handler.post(
                  () -> {
                    if (liveFrameCurrent(token, revision)) {
                      liveDetection(null, mapping);
                      cameraStatus.setText("Tap Capture page to scan");
                    }
                  });
            } finally {
              sample.recycle();
              analyzing.set(false);
            }
          });
      submitted = true;
    } catch (RuntimeException e) {
      if (screen.equals("camera") && cameraResumed())
        cameraStatus.setText("Tap Capture page to scan");
    } finally {
      // createBitmap can reuse its input for a full-frame crop; recycle each owned bitmap once.
      if (frame != null && frame != input && frame != content) frame.recycle();
      if (content != null && content != input) content.recycle();
      if (!submitted) {
        if (input != null) input.recycle();
        analyzing.set(false);
        resetLiveTracking();
      }
    }
  }

  private boolean liveFrameCurrent(int token, int revision) {
    return current(token)
        && revision == liveRevision
        && screen.equals("camera")
        && !capturing
        && cameraResumed();
  }

  private void liveDetection(DocumentProcessor.Detection detection, FrameMapping mapping) {
    LivePageTracker.Update update =
        liveTracker.update(
            detection == null ? null : detection.corners, SystemClock.elapsedRealtime());
    overlay.points = mapping.toOverlay(update.displayedCorners);
    overlay.invalidate();
    if (update.acceptedRawCorners == null) {
      cameraStatus.setText(
          update.displayedCorners == null ? "Finding page edges…" : "Checking page edges…");
    } else {
      cameraStatus.setText(
          autoCapture
              ? "Page found. Hold steady for automatic capture…"
              : "Page found. Tap Capture page.");
      if (autoCapture && update.captureReady) capture();
    }
  }

  private void resetLiveTracking() {
    liveRevision++;
    liveTracker.reset();
    liveMapping = null;
    if (overlay != null) {
      overlay.points = null;
      overlay.invalidate();
    }
  }

  private void capture() {
    if (capturing || imageCapture == null || !cameraResumed()) return;
    handler.removeCallbacks(cameraTick);
    resetLiveTracking();
    liveTracker.notifyCaptured(SystemClock.elapsedRealtime());
    capturing = true;
    cameraStatus.setText("Capturing…");
    File dir = new File(getCacheDir(), "captures");
    if (!dir.exists() && !dir.mkdirs()) {
      capturing = false;
      toast("Could not prepare camera file");
      handler.postDelayed(cameraTick, 250);
      return;
    }
    File file = new File(dir, "capture.jpg");
    int token = generation;
    imageCapture.takePicture(
        new ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(this),
        new ImageCapture.OnImageSavedCallback() {
          @Override
          public void onImageSaved(ImageCapture.OutputFileResults results) {
            if (!current(token)) return;
            stopCamera();
            loadPhoto(Uri.fromFile(file));
          }

          @Override
          public void onError(ImageCaptureException e) {
            if (!current(token)) return;
            capturing = false;
            cameraStatus.setText("Capture failed. Try again.");
            handler.postDelayed(cameraTick, 250);
            showMessage("Photo could not be captured", errorText(e));
          }
        });
  }

  private void stopCamera() {
    handler.removeCallbacks(cameraTick);
    resetLiveTracking();
    if (cameraProvider != null) cameraProvider.unbindAll();
    camera = null;
    imageCapture = null;
  }

  private final class PageOverlay extends View {
    PointF[] points;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    PageOverlay(Context context) {
      super(context);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      if (points == null) return;
      Path path = new Path();
      for (int i = 0; i < 4; i++) {
        float x = points[i].x * getWidth(), y = points[i].y * getHeight();
        if (i == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
      }
      path.close();
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0x2648D4C3);
      canvas.drawPath(path, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(3));
      paint.setColor(0xFF48D4C3);
      canvas.drawPath(path, paint);
    }
  }

  private void back() {
    if (busy) return;
    switch (screen) {
      case "review":
        showCrop();
        break;
      case "crop":
        new AlertDialog.Builder(this)
            .setTitle("Discard this scan?")
            .setMessage("This page has not been saved.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton(
                "Discard",
                (d, w) -> {
                  clearDrafts();
                  showHome();
                })
            .show();
        break;
      case "camera":
      case "saved":
        showHome();
        break;
      default:
        finish();
    }
  }

  private void header(String title, Runnable backAction) {
    LinearLayout bar = row();
    bar.setPadding(dp(16), dp(10), dp(24), dp(10));
    Button back = button("‹ Back", false, backAction);
    back.setTextSize(14);
    bar.addView(back, new LinearLayout.LayoutParams(dp(84), dp(46)));
    TextView t = text(title, 20, screen.equals("camera") ? Color.WHITE : INK, true);
    t.setPadding(dp(14), 0, 0, 0);
    bar.addView(t, new LinearLayout.LayoutParams(0, dp(46), 1));
    layout.addView(bar);
  }

  private TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setGravity(Gravity.CENTER_VERTICAL);
    t.setFontFeatureSettings("kern");
    t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  private Button button(String label, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(16);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(primary ? Color.WHITE : TEAL);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(14), 0, dp(14), 0);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setStateListAnimator(null);
    b.setBackground(shape(primary ? TEAL : Color.WHITE, 12, primary ? TEAL : LINE));
    b.setOnClickListener(
        v -> {
          if (!busy && action != null) action.run();
        });
    return b;
  }

  private GradientDrawable shape(int color, int radius, int border) {
    GradientDrawable s = new GradientDrawable();
    s.setColor(color);
    s.setCornerRadius(dp(radius));
    s.setStroke(dp(1), border);
    return s;
  }

  private LinearLayout row() {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    return r;
  }

  private LinearLayout column() {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    return c;
  }

  private void marginAdd(LinearLayout parent, View child, int top, int bottom) {
    marginAdd(parent, child, top, bottom, -2);
  }

  private void marginAdd(LinearLayout parent, View child, int top, int bottom, int height) {
    LinearLayout.LayoutParams p =
        new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height));
    p.topMargin = dp(top);
    p.bottomMargin = dp(bottom);
    parent.addView(child, p);
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void beginWork(String label) {
    busy = true;
    FrameLayout shade = new FrameLayout(this);
    shade.setTag("busy");
    shade.setClickable(true);
    shade.setBackgroundColor(0xCFF4F8FA);
    LinearLayout content = column();
    content.setGravity(Gravity.CENTER);
    ProgressBar progress = new ProgressBar(this);
    content.addView(progress, new LinearLayout.LayoutParams(dp(40), dp(40)));
    TextView status = text(label, 16, INK, true);
    marginAdd(content, status, 16, 0);
    status.setGravity(Gravity.CENTER);
    shade.addView(content, new FrameLayout.LayoutParams(-1, -1));
    root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
  }

  private void endWork() {
    busy = false;
    View shade = root.findViewWithTag("busy");
    if (shade != null) root.removeView(shade);
  }

  private boolean current(int token) {
    return token == generation && !isFinishing() && !isDestroyed();
  }

  private void fail(int token, String title, Exception e) {
    handler.post(
        () -> {
          if (!current(token)) return;
          endWork();
          restoringScan = false;
          pendingExportUri = null;
          if (screen.equals("camera") && capturing) showCamera();
          showMessage(title, errorText(e));
        });
  }

  private String errorText(Exception e) {
    android.util.Log.e("FolioScan", "Scan operation failed", e);
    if (e instanceof SecurityException) {
      return "Access to the photo or folder has expired. Select it again and retry.";
    }
    if (e instanceof FileNotFoundException) {
      return "The photo or folder could not be opened. Select another file or location.";
    }
    if (e instanceof IOException) {
      return "The file could not be read or written. Check free storage and try again.";
    }
    if (e instanceof IllegalArgumentException) {
      return "Check the photo and page corners, then try again.";
    }
    return "Please try again with a clear, well-lit photo of the whole page.";
  }

  private void showMessage(String title, String message) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show();
  }

  private void showAbout() {
    new AlertDialog.Builder(this)
        .setTitle("Made for your pages")
        .setMessage(
            "Detect the page, correct the perspective, and clean up its existing colors. Everything"
                + " runs on your device.\n\n"
                + "Saved scans stay in this app’s private storage. Export copies before"
                + " uninstalling.\n\n"
                + "Folio Scan 1.1 • OpenCV 4.12 • Android 8+\n\n"
                + "Document detection and live page tracking adapted from OpenScan (BSD 3-Clause).")
        .setNeutralButton("Third-party notices", (dialog, which) -> showThirdPartyNotices())
        .setPositiveButton("OK", null)
        .show();
  }

  private void showThirdPartyNotices() {
    try (InputStream input = getAssets().open("third_party_notices.txt");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
      showMessage("Third-party notices", bytes.toString("UTF-8"));
    } catch (IOException e) {
      showMessage(
          "Third-party notices",
          "OpenScan: BSD 3-Clause. Copyright (c) 2021,"
              + " Vijay T S and Vikram H. All rights reserved.\n\nOpenCV: Apache License 2.0.");
    }
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("screen", screen);
    state.putString("name", scanName);
    state.putString("filter", filter.name());
    state.putString("exportMime", exportMime);
    state.putString("cropMessage", cropMessage);
    state.putString("draftFileName", draftFileName);
    if (cropView != null && screen.equals("crop")) corners = cropView.getCorners();
    if (corners != null) {
      float[] values = new float[8];
      for (int i = 0; i < 4; i++) {
        values[i * 2] = corners[i].x;
        values[i * 2 + 1] = corners[i].y;
      }
      state.putFloatArray("corners", values);
    }
    if (selected != null) state.putString("selected", selected.id);
  }

  private void restoreDraft(Bundle state) {
    exportMime = state.getString("exportMime", "application/pdf");
    String savedScreen = state.getString("screen", "home");
    String id = state.getString("selected");
    if (savedScreen.equals("saved") && id != null) {
      for (Scan s : store.list())
        if (s.id.equals(id)) {
          restoringScan = true;
          openScan(s);
          return;
        }
    }
    String fileName = state.getString("draftFileName");
    if (fileName == null) return;
    File draft = new File(new File(getCacheDir(), "drafts"), new File(fileName).getName());
    if (!(savedScreen.equals("crop") || savedScreen.equals("review"))
        || !draft.exists()
        || processor == null) return;
    beginWork("Restoring your page…");
    int token = generation;
    worker.execute(
        () -> {
          try {
            Bitmap original = BitmapFactory.decodeFile(draft.getAbsolutePath());
            if (original == null) throw new IOException("Draft image could not be restored");
            float[] values = state.getFloatArray("corners");
            PointF[] points = fullCorners();
            if (values != null && values.length == 8)
              for (int i = 0; i < 4; i++) points[i] = new PointF(values[i * 2], values[i * 2 + 1]);
            Filter chosen = Filter.valueOf(state.getString("filter", Filter.AUTO_COLOR.name()));
            Bitmap page = savedScreen.equals("review") ? processor.warp(original, points) : null;
            Bitmap finish = page == null ? null : processor.enhance(page, chosen);
            PointF[] restored = points;
            handler.post(
                () -> {
                  if (!current(token)) return;
                  endWork();
                  source = original;
                  draftFileName = draft.getName();
                  corners = restored;
                  cropped = page;
                  processed = finish;
                  filter = chosen;
                  scanName = state.getString("name", "Untitled scan");
                  cropMessage = state.getString("cropMessage", "Check the page corners.");
                  if (page == null) showCrop();
                  else showReview();
                });
          } catch (Exception e) {
            fail(token, "Draft could not be restored", e);
          }
        });
  }

  private File storeDraft(Bitmap image) throws IOException {
    File directory = new File(getCacheDir(), "drafts");
    if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not store draft");
    File file = new File(directory, "draft-" + UUID.randomUUID() + ".jpg");
    try {
      ScanStore.writeJpg(image, file);
      return file;
    } catch (IOException e) {
      file.delete();
      throw e;
    }
  }

  private void clearDrafts() {
    File[] files = new File(getCacheDir(), "drafts").listFiles();
    if (files != null) for (File f : files) f.delete();
    draftFileName = null;
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(cameraTick);
    resetLiveTracking();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resetLiveTracking();
    if (screen.equals("camera") && !capturing && imageCapture != null) {
      handler.removeCallbacks(cameraTick);
      handler.postDelayed(cameraTick, 250);
    }
  }

  @Override
  protected void onDestroy() {
    generation++;
    stopCamera();
    handler.removeCallbacksAndMessages(null);
    worker.shutdown();
    cameraWorker.shutdown();
    super.onDestroy();
  }
}
