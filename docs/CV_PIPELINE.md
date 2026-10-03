# CV pipeline: what actually runs

Status: full computational path implemented and tested. Two reference profiles ship, in one pipeline:
the source-backed NCB field profile, whose **official data is qualitative only**, and a synthetic
demonstration profile that exists so the colour comparison can be shown running.

Read this before trusting any colour number this app produces. The measurements are real; the field
profile they are compared against is not.

## Flow

```
Camera2 JPEG + profile index
  -> VisionNative.nativeProcessImage (JNI, ABI 4)
  -> processJpeg                    app/src/main/cpp/cv_pipeline.cpp
       decode, quality metrics
       ArUco detection against the profile's dictionary
       homography -> warpPerspective to the profile's rectified card (500x500)
       reference swatches + reaction window, in rectified card coordinates
       calibration (CCM, when the profile has authoritative patch colours)
       sRGB -> linear light -> XYZ -> CIE L*a*b*
       CIEDE2000 against the profile's comparison target
       classification: field profiles never classify without validated numerical
         calibration and validated boundaries; the synthetic demonstration
         profile may classify, and its outcome is reported as a demonstration
  -> CvMeasurement + CvColorimetryMeasurement (carrying ProfileKind)
  -> RealCvTestAnalyzer             app/src/main/java/nic/drugrepo/analysis/
  -> AnalysisResult -> ResultActivity -> AuditableRecord (Room/SQLCipher)
```

Kotlin performs no image maths. There is one implementation of the science and it is in C++. There
is also one implementation of the *pipeline*: the profile index selects reference data, never a
second code path.

## Module layout

| File | Responsibility |
| --- | --- |
| `app/src/main/cpp/reference_profile.h/.cpp` | The profile table: one source-backed field profile and one synthetic demonstration profile. Owns every card value. |
| `app/src/main/cpp/colorimetry.h/.cpp` | sRGB companding, RGB->XYZ, XYZ->Lab, CIEDE2000, CCM solve. No OpenCV. |
| `app/src/main/cpp/cv_pipeline.h/.cpp` | The stage itself. Owns no card values. |
| `app/src/main/cpp/vision_jni.h/.cpp` | JNI shim. ABI version 4. |
| `app/src/main/cpp/vision_test.cpp` | Native harness, including the published CIEDE2000 table. |

## Two profiles, one pipeline

There is one capture path, one decoder, one rectification, one CIEDE2000 implementation and one
analyzer. What differs between a field result and a demonstration result is the **reference data the
same measurement is compared against**, selected by an index into the profile table:

| | Field profile | Demonstration profile |
| --- | --- | --- |
| `id` | `NCB-STANDARD-NARCOTICS-DD-KIT` | `DEMO_SYNTHETIC_PROFILE_V1` |
| `kind` | `ProfileKind::kField` | `ProfileKind::kSyntheticDemonstration` |
| `reagentType` | `NCB_STANDARD_NARCOTICS_DD_KIT` | `SYNTHETIC_DEMONSTRATION_ONLY` |
| Source | NICFS Forensic Guide Ch. 8 Fig. 8.13; NCB Annual Report 2023-24 | `MODEL / SYNTHETIC COLOUR ANCHORS` — no kit, standard, publication or measurement |
| Colour anchors | none. Official sources give colour *names*, not numbers | eleven synthetic hex pairs, stored as text |
| Classification | always `INCONCLUSIVE` | may classify, as a demonstration |
| UI wording | field badge, `UNVALIDATED`, "no real drug identification was performed" | `SYNTHETIC DEMONSTRATION ONLY` badge and a warning on every result |

`DEMO_SYNTHETIC_PROFILE_V1` holds eleven anchors: five Test A, one Test B, one Test C, and four
blister-test entries. They are demonstration data only — not official NCB data, not physical
calibration, not forensically validated, and not a statement about any real reagent. They exist so
the comparison can be shown running end to end. Its `reference_data_version` carries the
`SYNTHETIC_DEMONSTRATION_ONLY` suffix, so a sealed demonstration record stays identifiable from the
record alone months later.

The synthetic anchors live only in a `kSyntheticDemonstration` profile's own
`syntheticAnchors` array; a `kField` profile has no field to hold one, `syntheticAnchorCount` is 0,
and the harness asserts that no synthetic hex value appears anywhere in the field profile's text.

### How the mode is chosen

`AnalysisActivity` reads the optional intent extra `AnalysisProfile.EXTRA` (`"profile"`).
`"demo"` selects the synthetic profile; an absent, empty or unrecognised value selects the field
profile, so an unrecognised mode fails closed onto real casework rather than onto a demonstration.
`CameraActivity` forwards the extra unchanged. There is no mode picker screen and no second
analyzer: `RealCvTestAnalyzer(profileIndex)` is the same class either way.

`presumptive_result` stays the architecture's `POSITIVE` / `NEGATIVE` / `INCONCLUSIVE`, because it
is a database enum with a CHECK constraint and the enum is normative in `architecture.md`. A
synthetic `POSITIVE` is rendered `DEMONSTRATIVE POSITIVE` and a synthetic `NEGATIVE`
`DEMONSTRATIVE NO MATCH`; the wording lives in `ResultActivity`, and the mode that drives it is
carried on `AnalysisResult.mode`.

## NCB standard-kit profile: official qualitative protocol, unvalidated numerical calibration

`reference_profile.cpp` defines the field profile as `NCB-STANDARD-NARCOTICS-DD-KIT`, version
`NCB-STANDARD-KIT-QUALITATIVE-2020-UNVALIDATED`, `validation = kUnvalidated`. It is
`activeProfile()` and `kNcbProfileIndex = 0`: a caller who names no profile gets the conservative
one.

The profile captures authoritative qualitative procedures from the NICFS Forensic Guide, Chapter 8,
Figure 8.13: Test A’s spot-plate preparation, water/smear step for opium, then A1/A2 quantities;
Test B’s match-head sample/B1 amount, 25-drop B2/B3 steps, shaking/standing times and lower-layer
reading; and Test E’s tablet preparation, E1/E2 amounts and shaking times plus E3/E4 differentiation
amounts for cocaine and methaqualone. It intentionally has **no Test C or Test D data**, because
they are not shown in that source excerpt. NCB’s annual report establishes the Narcotic Drugs
Detection Kit’s procurement/distribution and drug coverage.

### Provenance and validation matrix

| Data or behaviour | Status | Source / reason |
| --- | --- | --- |
| Tests A, B and E reagent steps; qualitative expected colours; Test B lower-layer instruction | Authoritative qualitative protocol | [NICFS Forensic Guide, Chapter 8](https://police.py.gov.in/Brief%20on%20Narcotics%20Drugs%20and%20Psychotrophic%20Substances%20-%20Chapter%208.pdf), Figure 8.13 |
| Standard-kit procurement/distribution and covered drugs | Authoritative kit provenance | [NCB Annual Report 2023-24](https://narcoticsindia.nic.in/Publication/ncb-annual-report-2023-24.pdf) |
| Tests C and D | Not represented | Not shown in the cited Figure 8.13 excerpt; no behaviour is inferred |
| RGB, Lab and hex reaction targets | **NOT PROVIDED BY OFFICIAL SOURCE / UNVALIDATED** | Neither cited source supplies machine-readable colour targets |
| ArUco ids, card geometry, swatch positions and reaction ROI | **NOT PROVIDED BY OFFICIAL SOURCE / UNVALIDATED** | Existing pipeline placeholders; no official physical card layout characterized |
| CCM / colour correction | Disabled | Requires authoritative patch colours |
| ΔE00 positive/negative boundaries | **NOT PROVIDED BY OFFICIAL SOURCE / UNVALIDATED** | The pipeline's demonstration values are retained only for the synthetic profile |
| Production POSITIVE / NEGATIVE classification | Disabled | The pipeline returns INCONCLUSIVE until a profile has validated numerical calibration and boundaries |

The structured source fields live beside the existing reference-card data: source title, URLs,
status, per-test reagent steps, expected qualitative colours, and layer-reading instruction. They
are deliberately strings rather than fabricated numeric colour values.

The following pipeline fields remain placeholders, in order of how much they matter:

- marker ids `1, 2, 3, 4` of `DICT_4X4_50` — the first four ids of the dictionary, not ids printed
  on anything;
- marker centres at 10% inset from each card corner;
- six swatch rectangles and the reaction window rectangle;
- the dE00 boundaries `2.0` (positive at or below) and `5.0` (negative at or above).

These are propagated, never hidden: the version and provenance state `UNVALIDATED`,
`ProfileValidation::kUnvalidated` and `ProfileKind::kField` travel into
`CvColorimetryMeasurement`, `AnalysisResult.demo` stays `true`, and the record's
`reference_data_version` becomes the profile version rather than a claim of a validated dataset.

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
is implemented and tested, and applies only when a profile carries authoritative patch colours.

`comparisonTarget` is `kMeasuredReferencePatches`, so the dE00 compares two things that were both
measured in this frame. The number is real; the thresholds interpreting it are not. A `kField`
profile therefore always returns **INCONCLUSIVE**: `classify()` refuses to leave the boundary table
unless the profile is `kField`-exempt *and* carries validated numerical calibration *and* validated
boundaries. Promoting the profile's own validation flags is not enough, because the gate keys off
the numerical-calibration flag. Only a `kSyntheticDemonstration` profile may classify, and its
result is reported as a demonstration at the UI and the record boundary.

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
  - official NCB qualitative protocol entries for Tests A, B and E, provenance URLs and the
    explicit absence of Test C/D; no authoritative swatch colours; and the guard that keeps
    field-profile classification INCONCLUSIVE without validated numerical calibration;
  - the synthetic profile: exact id, version, reagent, source title and status; no government URL
    anywhere in it; `syntheticAnchorCount == 0` on the field profile with no synthetic hex value
    appearing anywhere in its text; all eleven anchors asserted field-by-field; and that no
    reference profile can be mutated through a copy of another;
  - one pipeline, two profiles: the same fixture through `processJpeg` with each profile yields
    bit-identical geometry and colour numbers, differing only in `profileKind`, in whether the
    classification is produced, and in the profile version string;
  - homography: rectification against the profile, drawn colours recovered through a tilted card,
    internal consistency of Lab/dE00/classification, bit-level determinism;
  - the calibrated branch, run against a profile with authoritative swatch colours so the CCM path
    is actually executed rather than skipped because the shipped profile has no patch data;
  - sRGB companding round-trip, including the re-encode of corrected linear light and the clamping
    of out-of-gamut corrected values;
  - the five geometry failure statuses.
- `app/src/androidTest/java/nic/drugrepo/vision/NativeCvTest.kt` — the same fixture across the JNI
  boundary, in both profiles: the field path returns INCONCLUSIVE with no synthetic wording, and
  the same bytes through the synthetic profile classify and carry
  `SYNTHETIC_DEMONSTRATION_ONLY` in the reagent and version fields.
- `app/src/test/java/nic/drugrepo/analysis/AnalysisProfileTest.kt` — the field path is the default,
  an unrecognised mode extra fails closed onto it, and the analyzer defaults to the field profile.
- `app/src/test/java/nic/drugrepo/VisionAbiContractTest.kt` — ABI 4, the `profileIndex` argument on
  `nativeProcessImage`, and the 30-argument `CvColorimetryMeasurement` constructor the JNI
  descriptor depends on.

Test fixture greys are arbitrary distinguishable values drawn in the profile's own card space. They
are not colours of any reagent and mean nothing analytically. The colour-correction-matrix test uses
a second, chromatic set of swatches, because a neutral-grey reference set has identical R, G and B
columns and no 3x3 fit can be recovered from it at all.

## Known limits

1. **No validated numerical profile.** Physical/official colour-card characterization or
   authoritative machine-readable RGB/Lab and classification-validation data is still required
   before camera-based POSITIVE/NEGATIVE classification can be enabled. Until then the field path
   is measurement-only and always INCONCLUSIVE.
2. **The demonstration profile proves the pipeline, not the science.** It shows that the capture,
   rectification, colour maths and recording all run. It says nothing about whether any colour in it
   resembles what a real reagent does.
3. **No CCM applied** until a profile has authoritative patch colours. Neither shipped profile has
   any, deliberately: a matrix fitted to synthetic targets would report a synthetic colour as a
   corrected measurement.
4. **No quality gating.** Laplacian variance and glare are measured and reported, never thresholded.
5. **No auto-exposure or white-balance handling.** A colour correction matrix fitted per capture
   would need an illuminant estimate this app does not have.
6. **No real card has been photographed.** The synthetic fixture proves the geometry and colour
   code paths, not that any printed card matches this profile.
