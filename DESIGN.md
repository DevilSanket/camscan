# Folio Scan

The customer needs two things: identify the page in a photograph and make its existing colors look like a clean scan. The app follows one short path: capture or import → confirm the page → automatically enhance → save or export.

## Design decisions

Use a quiet, bright workspace that lets the document carry the color. Palette: ink `#172B3A`, teal `#075E68`, paper `#F4F8FA`, white `#FFFFFF`, line `#DCE9ED`, soft teal `#E6F4F2`. Android sans-serif and sans-serif-medium keep small controls legible. Titles are 28–32sp; supporting text 14–16sp. Content and controls are left aligned except the capture guidance.

```
Library                  Page corners                Scan preview
Folio Scan               Back   Adjust page           Back   Your scan
Scan a page              Detected / adjust hint       Auto color applied
Document illustration    Full photograph + handles   Rectified page
Camera / Import          Reset / Rotate               Auto / Original / Gray / B&W
Recent saved scans       Apply scan                   Save scan
```

The memorable element is the paper itself, a specimen with colored ink that can be tried through the actual processing pipeline. A single prominent camera action, a quieter import action, and saved documents below keep the flow focused. Camera feedback describes observable state rather than displaying an accuracy percentage.

## Scope and implementation

- Native Java Android app, API 26+, CameraX, and OpenCV bundled for offline operation.
- OpenScan's current document detector adapted to Java for camera frames and imported images, with perspective correction and manual corner adjustment.
- Camera content mapping, smoothed corner display, and automatic capture based on fresh, stable detections.
- Default Auto color: background-lighting correction and conservative paper-color correction while retaining colored ink.
- Original, grayscale, and black-and-white comparisons.
- Private local library, rename/delete, sharing, and user-selected JPG/PDF export.
- No account or server. Processing and saved files stay on the device.

Version 1.1.0 replaces the initial detector with OpenScan's 700-pixel grayscale/Sobel/Otsu and quadrilateral-consensus pipeline. It also excludes preview letterboxing from live detection and separates overlay smoothing from the raw detections that trigger capture. Perspective correction and color enhancement retain their previously verified behavior. See [the adaptation notes](docs/OPENSCAN_DETECTION.md) for details and source attribution.

Exact OKEN comparisons require its APK and representative input/output pairs; these were unavailable. REA is used to investigate built packages. OpenScan supplies the detector implementation under BSD 3-Clause.

## Validation targets

Build an installable APK. Check Android runtime behavior for detection, cropping, color retention, storage, and export. Compare the detector against expected coordinates from the original OpenScan functions, and reproduce misses using the archived initial detector. Test live geometry and capture state separately so smoothed or stale overlays cannot trigger a capture.

Synthetic fixtures and emulator checks establish these regressions. A physical phone and diverse customer photographs remain necessary to assess glare, curved pages, low contrast, and actual camera movement before acceptance.
