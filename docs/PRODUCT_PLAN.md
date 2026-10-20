# Focused scanner product plan

## Customer requirement

Create an Android app that finds the four edges of a paper document, corrects perspective, and automatically turns its existing colors into a clean scan. Colored ink, stamps, logos, and highlights should remain recognizable. “Colorize” means improving an existing color photograph, as confirmed by the customer. The follow-up requirement is to use OpenScan's document detection for both camera and imported photos.

## Experience

1. Take a photo or select an existing image.
2. Detect the document automatically and show its four corners.
3. Let the user adjust corners when needed.
4. Apply perspective correction and automatic color cleanup.
5. Compare the original, Auto color, grayscale, and black-and-white versions; save or share a JPEG or PDF.

Camera capture can run automatically when fresh document detections remain stable. The original remains available during preview, so the user can compare the enhancement before saving.

## Version 1.1.0 implementation

The stages remain separate: bounded image decoding and orientation, boundary detection, perspective correction, color enhancement, and export. Both capture and import call the same document processor.

The detector is adapted from OpenScan's current Dart implementation at commit `27bb7ab973fbb621fce182c43bc875856c785a30`. It reduces the longest edge to at most 700 pixels, applies grayscale conversion, Gaussian blur and Sobel gradients, and tests three Otsu-based thresholds. Dilated edge components produce convex hulls and four-corner candidates; agreement between candidates helps select the page. The previous brightness, minimum-edge-length, near-frame, and fixed quality-score gates have been removed. The new detector accepts candidate pages occupying at least 5% of the image, subject to convexity and corner-angle checks. [Source and parity details](OPENSCAN_DETECTION.md) document the adaptation.

Live detection runs only on the visible camera content. Corner matching and a One Euro filter steady the overlay; competing page changes must be confirmed across three detections. Automatic capture uses accepted raw corners, requires at least three fresh frames and 700 ms of stability, and resets after a detection miss. A two-second capture cooldown prevents immediate repeats.

Automatic color enhancement operates in Lab color space. It estimates illumination from a small image using morphological closing and a weighted blur of neutral-paper samples, adjusts luminance with bounded gains, and applies a small paper-color correction. Saturated artwork receives a smaller gain and bypasses the paper contrast stretch to limit halos around large colored panels. The default output retains color channels. Optional original, grayscale, and black-and-white filters provide alternatives. This describes Folio's source; it is not an assertion about OKEN's internals.

Perspective-corrected output is capped at 2600 pixels on its longest edge. Image work runs on a worker thread and temporary buffers are released promptly. When no candidate is found, manual corners remain available.

## Focus and limitations

The requested scope is scanning and enhancement. Accounts, cloud storage, OCR, signatures, subscriptions, and document conversion are outside this version. The app uses original naming and UI assets.

A single-photo perspective transform handles flat pages; curved book pages require a separate dewarping method. Completely clipped highlights contain no recoverable detail. Severe shadows, low contrast, glare, and partial page visibility need representative customer examples before scan quality can be assessed.

## Acceptance examples

| Input | Expected behavior |
| --- | --- |
| White form on a dark table | Four edges detected; square page; readable text |
| Angled receipt | Narrow page retained without clipping top or bottom |
| Small or near-frame page | Candidate considered without the old area/border rejection |
| Colored certificate or stamp | Existing colors retained; background cleaned |
| Warm indoor lighting | Paper cast reduced while colored marks remain recognizable |
| Page with broad soft shadow | More even background with legible text |
| Faint page edge or clutter | Automatic detection attempted; manual adjustment available |
| Landscape or rotated photo | Orientation consistent through preview and export |
| Temporary camera detection miss | Brief overlay grace; automatic capture stability resets |

The [validation record](VALIDATION.md) covers the synthetic before/after cases, original OpenScan parity fixtures, Android runtime tests, and remaining checks. Saved dimensions, actual file existence, and JPEG/PDF readability matter as well as preview appearance. Test capture and image import on a physical Android phone before customer sign-off; synthetic pictures do not substitute for real customer scans.
