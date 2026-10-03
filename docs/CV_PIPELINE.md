# CV pipeline: what actually runs

Status: full computational path implemented and tested. **Reference data is still placeholder.**

Read this before trusting any colour number this app produces. The measurements are real; the
profile they are compared against is not.

## Flow

```
Camera2 JPEG
  -> VisionNative.nativeProcessImage (JNI)
  -> processJpeg                    app/src/main/cpp/cv_pipeline.cpp
       decode, quality metrics
       ArUco detection against the profile's dictionary
       homography -> warpPerspective to the profile's rectified card (500x500)
       reference swatches + reaction window, in rectified card coordinates
       calibration (CCM, when the profile has authoritative patch colours)
       sRGB -> linear light -> XYZ -> CIE L*a*b*
       CIEDE2000 against the profile's comparison target
       classification from the profile's dE00 boundary table
  -> CvMeasurement + CvColorimetryMeasurement
  -> RealCvTestAnalyzer             app/src/main/java/nic/drugrepo/analysis/
  -> AnalysisResult -> ResultActivity -> AuditableRecord (Room/SQLCipher)
```

Kotlin performs no image maths. There is one implementation of the science and it is in C++.

## Module layout

| File | Responsibility |
| --- | --- |
| `app/src/main/cpp/reference_profile.h/.cpp` | Every value describing a physical card. One isolated profile. |
| `app/src/main/cpp/colorimetry.h/.cpp` | sRGB companding, RGB->XYZ, XYZ->Lab, CIEDE2000, CCM solve. No OpenCV. |
| `app/src/main/cpp/cv_pipeline.h/.cpp` | The stage itself. Owns no card values. |
| `app/src/main/cpp/vision_jni.h/.cpp` | JNI shim. ABI version 3. |
| `app/src/main/cpp/vision_test.cpp` | Native harness, including the published CIEDE2000 table. |

## The shipped profile is PROVISIONAL

`reference_profile.cpp` defines one profile: `NCB-CARD`, version `0.1.0-PROVISIONAL`,
`validation = kUnvalidated`, reagent `MARQUIS`.

**None of its concrete values came from a kit manufacturer, a printed card, or a published colour
dataset.** What the project documentation supplies is only the shape of the data: four corner ArUco
markers, rectification to 500x500, a CCM from at least six swatches, and a configurable dE00
boundary table with an INCONCLUSIVE band.

Placeholder, in order of how much they matter:

- marker ids `1, 2, 3, 4` of `DICT_4X4_50` — the first four ids of the dictionary, not ids printed
  on anything;
- marker centres at 10% inset from each card corner;
- six swatch rectangles and the reaction window rectangle;
- the dE00 boundaries `2.0` (positive at or below) and `5.0` (negative at or above).

These are propagated, never hidden: the version string contains `PROVISIONAL`, `provenance` says so
in one sentence, `ProfileValidation::kUnvalidated` travels into `CvColorimetryMeasurement`,
`AnalysisResult.demo` stays `true`, and the record's `reference_data_version` becomes the profile
version rather than a claim of a validated dataset.

Replacing them with validated kit data is a change to `reference_profile.cpp` only.

## What is computed, and against what

| Step | Computed from | Compared against |
| --- | --- | --- |
| Reference colour | mean of the six measured swatches, equal weight per swatch | - |
| Calibration | CCM in linear light, `M = C_ideal . C_obs^T (C_obs . C_obs^T)^-1` | profile's `idealR/G/B` |
| Reaction colour | mean of the reaction window on the rectified card | - |
| Lab | sRGB -> linear -> XYZ -> L*a*b*, D65 | - |
| dE00 | reaction Lab vs reference Lab, kL = kC = kH = 1 | profile's boundary table |

`calibrationMode` is `kNoneProvisional`, so **no CCM is applied** in this build: fitting one to
placeholder targets would launder invented data into a "calibrated" measurement. The solver itself
is implemented and tested, and applies the moment a profile carries authoritative patch colours.

`comparisonTarget` is `kMeasuredReferencePatches`, so the dE00 compares two things that were both
measured in this frame. The number is real; the thresholds interpreting it are not.

## Failure is a result, not an exception

`processJpeg` never throws. Geometry failures are specific, because "there is no card here" and
"this is the wrong card" are different operator problems:

| Status | Meaning |
| --- | --- |
| `kCvStatusNoMarkers` (-4) | no marker of the profile's dictionary anywhere in the frame |
| `kCvStatusUnexpectedMarkerIds` (-5) | markers found, none of them this profile's |
| `kCvStatusIncompleteMarkerSet` (-6) | some of the four, too few to rectify |
| `kCvStatusInvalidHomography` (-7) | four matched markers, unusable geometry |
| `kCvStatusRoiOutsideImage` (-8) | a profile rectangle falls outside its own card |

There is **no centre fallback** on this path. The previous prototype measured a centred crop when
no markers were found, which produced plausible-looking numbers from frames containing no card at
all. The constant `kCvGeometryCentreFallback` still exists in the enum for ABI compatibility and is
never produced.

On any failure, every measurement field stays zero, `geometrySource` stays `kCvGeometryNone`, and
the profile identity still travels with the failure so the record can say which card was being
looked for.

## Verification

- `app/src/main/cpp/vision_test.cpp` — native harness:
  - all 34 supplementary pairs from Sharma, Wu & Dalal (2005),
    <https://www.hajim.rochester.edu/ece/sites/gsharma/ciede2000/dataNprograms/ciede2000testdata.txt>,
    asserted to 1e-4 with symmetry, plus zero-distance and sRGB-companding and Lab-anchor checks;
  - CCM recovery, determinism, and refusal of degenerate or near-collinear patch sets;
  - profile honesty: unvalidated, `PROVISIONAL` in both the version and the provenance, no
    authoritative swatch colours, six swatches, an INCONCLUSIVE band;
  - homography: rectification against the profile, drawn colours recovered through a tilted card,
    internal consistency of Lab/dE00/classification, bit-level determinism;
  - the calibrated branch, run against a profile with authoritative swatch colours so the CCM path
    is actually executed rather than skipped because the shipped profile has no patch data;
  - sRGB companding round-trip, including the re-encode of corrected linear light;
  - the five geometry failure statuses.
- `app/src/androidTest/java/nic/drugrepo/vision/NativeCvTest.kt` — the same fixture across the JNI
  boundary, plus the analyzer's message and demo flag.
- `app/src/test/java/nic/drugrepo/VisionAbiContractTest.kt` — ABI 3, and the 29-argument
  `CvColorimetryMeasurement` constructor the JNI descriptor depends on.

Test fixture greys are arbitrary distinguishable values drawn in the profile's own card space. They
are not colours of any reagent and mean nothing analytically.

## Known limits

1. **No validated profile.** Every number that depends on kit data is a placeholder.
2. **No CCM applied** until a profile has authoritative patch colours.
3. **No quality gating.** Laplacian variance and glare are measured and reported, never thresholded.
4. **No auto-exposure or white-balance handling.** A colour correction matrix fitted per capture
   would need an illuminant estimate this app does not have.
5. **No real card has been photographed.** The synthetic fixture proves the geometry and colour
   code paths, not that any printed card matches this profile.