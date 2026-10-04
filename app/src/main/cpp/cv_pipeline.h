#ifndef DRUGREPO_CV_PIPELINE_H
#define DRUGREPO_CV_PIPELINE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "reference_profile.h"

// The real image-processing stage (TODO.md Phase 3, architecture.md section 4).
//
// This header is pure C++ with no JNI types and no OpenCV types, exactly like the
// arucoSelfTest() surface in vision_jni.h, so the same implementation is asserted by the native
// harness (vision_test.cpp) and reached through JNI by Kotlin.
//
// Every physical-card value comes from ReferenceCardProfile (reference_profile.h). This file
// contains no marker id, no rectangle and no threshold of its own.

// Status codes. STATUS_OK is the only value that means "the numbers below are real
// measurements"; every other value leaves the measurement fields at zero/empty, so a caller
// that ignores the status cannot mistake a failure for a measurement.
//
// The geometry failures are specific on purpose. "I could not find your card" and "I found a card
// with markers 11, 12, 13 that are not this profile's markers" are different operator problems
// and the record has to distinguish them (architecture.md section 4.3).
enum CvStatus {
    kCvStatusOk = 0,
    kCvStatusEmptyInput = -1,
    kCvStatusDecodeFailed = -2,
    kCvStatusInvalidGeometry = -3,
    // No marker of this profile's dictionary was detected anywhere in the frame.
    kCvStatusNoMarkers = -4,
    // Markers were detected but none of them is one of this profile's marker ids.
    kCvStatusUnexpectedMarkerIds = -5,
    // Some of the profile's markers were detected, but not the full set: too few to rectify.
    kCvStatusIncompleteMarkerSet = -6,
    // The four matched markers could not be mapped onto the rectified card (degenerate, or a
    // homography that OpenCV refused / that is not invertible).
    kCvStatusInvalidHomography = -7,
    // A patch or the reaction window, expressed in normalised card coordinates, falls outside the
    // profile's own rectified card, or collapses to less than one pixel after scaling.
    kCvStatusRoiOutsideImage = -8,
    kCvStatusCvException = -100,
};

// Where the measured regions came from. Recorded so a record can state what was actually
// detected instead of implying a geometry check that never happened.
enum CvGeometrySource {
    kCvGeometryNone = 0,
    kCvGeometryArucoFull = 1,             // all four corner markers found (architecture.md 4.3)
    kCvGeometryArucoPartial = 2,          // 2..3 markers found; bounding box of what was seen
    kCvGeometryCentreFallback = 3,        // no markers; central fallback region of the frame
    kCvGeometryHomographyRectified = 4,   // four profile markers, warped to the rectified card
};

// Colour metrics (architecture.md section 4, steps 3 to 7). Reported separately from the raw
// pixel means so that "what the camera saw" and "what the pipeline concluded" stay separable in
// the record.
//
// Java-side layout (see CvColorimetryMeasurement.kt): eight ints, one boolean, twenty doubles
// and four strings, in the order declared here.
struct CvColorimetryFrame {
    int profileId = 0;           // index into the profile table
    int profileValidation = 0;   // ProfileValidation
    int profileKind = 0;         // ProfileKind: field data or synthetic demonstration
    int classification = 0;      // CvClassification
    int swatchCount = 0;         // reference patches actually measured
    int rectifiedWidth = 0;
    int rectifiedHeight = 0;
    int ccmApplied = 0;          // 1 only when a colour correction matrix was fitted and applied

    double deltaE2000 = 0.0;

    // sRGB channel means straight off the rectified card, before any correction.
    double referenceRawR = 0.0;
    double referenceRawG = 0.0;
    double referenceRawB = 0.0;
    // After correction. Equals the raw values when no CCM was applied.
    double referenceR = 0.0;
    double referenceG = 0.0;
    double referenceB = 0.0;

    double reactionRawR = 0.0;
    double reactionRawG = 0.0;
    double reactionRawB = 0.0;
    double reactionR = 0.0;
    double reactionG = 0.0;
    double reactionB = 0.0;

    double referenceLabL = 0.0;
    double referenceLabA = 0.0;
    double referenceLabB = 0.0;
    double reactionLabL = 0.0;
    double reactionLabA = 0.0;
double reactionLabB = 0.0;

    // SYNTHETIC DEMONSTRATION ONLY. Distance from the measured reaction colour to the nearest
    // synthetic demonstration anchor, and whether that distance is within this profile's
    // demonstration tolerance. Both stay at zero / false on a field profile, which has no anchors to
    // compare against, so no field result can ever carry a demonstration number.
    double anchorDeltaE2000 = 0.0;
    int anchorMatch = 0;  // 1 only when anchorDeltaE2000 is within the demonstration tolerance

    // Profile provenance, carried so the UI can state where the numbers came from without
    // re-reading the profile table.
    std::string profileVersion;
    std::string profileProvenance;
    std::string reagentType;
    // Which synthetic demonstration anchor the measured colour sits nearest. Empty on a field
    // profile. Names a colour in a synthetic dataset, never a substance in a sample.
    std::string anchorLabel;
};

// Structured pipeline output. Plain data only: no OpenCV types, no JSON.
// Java-side layout (see CvMeasurement.kt):
//   markerIds    : one id per detected marker
//   markerCorners: 4 (x, y) pairs per detected marker, in ArUco corner order, in IMAGE pixels
//   regions      : { referenceX, referenceY, referenceW, referenceH,
//                    reactionX, reactionY, reactionW, reactionH }
//                  in RECTIFIED CARD pixels once geometrySource is
//                  kCvGeometryHomographyRectified, in image pixels otherwise
struct CvFrame {
    int status = kCvStatusDecodeFailed;
    int imageWidth = 0;
    int imageHeight = 0;
    int geometrySource = kCvGeometryNone;
    double laplacianVariance = 0.0;  // measured blur metric; not yet gated on a threshold
    double glareFraction = 0.0;      // measured fraction of near-saturated pixels
    // BGR channel means, 8-bit scale, of the reference and reaction regions as finally measured.
    double referenceMeanB = 0.0;
    double referenceMeanG = 0.0;
    double referenceMeanR = 0.0;
    double reactionMeanB = 0.0;
    double reactionMeanG = 0.0;
    double reactionMeanR = 0.0;
    std::vector<int> markerIds;
    std::vector<int> markerCorners;
    int regions[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    CvColorimetryFrame colorimetry;
};

// Non-card configuration. Kept to the single value that is genuinely a sensor/capture concern;
// anything that describes the physical card belongs in ReferenceCardProfile.
struct CvConfig {
    double glareBrightLevel = 250.0;  // PROTOTYPE: 8-bit level counted as blown out
};

// The single configuration instance the pipeline uses. Exposed so tests can assert against the
// same values the pipeline reads, instead of restating them.
const CvConfig& cvConfig();

// Number of pixels a normalized profile rectangle must cover after scaling to the rectified card
// before it is accepted. Below this, the ROI is a resampling artefact rather than a measurement.
constexpr int kMinimumRoiPixels = 4;

// Decodes JPEG bytes (as written by the Camera2 ImageReader) and measures the frame.
// Never throws and never aborts: any failure is reported through CvFrame::status.
//
// The profile argument selects which reference data the measurement is compared against. It exists
// for three callers and no more: nullptr means activeProfile() in production, a test passes a
// modified copy to reach a branch the shipped profiles cannot reach (the calibrated branch), and
// the demonstration mode passes the synthetic profile. Choosing a profile is choosing DATA; the
// measurement code below is identical for all three.
//
// The shipped field profile never classifies anything: without validated numerical calibration and
// decision boundaries its result is always INCONCLUSIVE. The synthetic profile does classify,
// because demonstrating the comparison is its entire purpose, and its result is reported as
// SYNTHETIC and can never be a presumptive finding.
CvFrame processJpeg(const uint8_t* data, size_t length,
                    const ReferenceCardProfile* profileOverride = nullptr);

#endif  // DRUGREPO_CV_PIPELINE_H