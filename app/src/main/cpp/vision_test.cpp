// TODO.md Phase 0 task 4: native test harness for the C++ layer.
//
// Deliberately framework-free: assertions are plain assert(), so the return value alone tells
// the instrumented test (NativeHarnessTest) whether the native layer is healthy. Every
// input here is synthetic and fixed, except the CIEDE2000 table, which is the published
// supplementary data from Sharma, Wu & Dalal (2005) and is quoted with its citation below. Nothing
// in this file is reference data for a reagent, a threshold, or an analytical claim.
//
// Not compiled into the shipped library: CMakeLists.txt declares this target only under
// if(VISION_NATIVE_TEST), which the Gradle debug build type alone passes.
//
// This was originally a main() in a standalone executable that the instrumented test staged in
// app storage and execve'd. That cannot work: apps targeting SDK 29+ may not execute files out
// of their own home directory, so execve fails with EACCES no matter the file mode. The
// harness is therefore a shared library and its asserts run inside the already-running app
// process. A failing assert calls abort(), so a regression shows up as an instrumentation
// crash rather than a tidy assertion message - deliberately loud, not silent.

#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect/aruco_detector.hpp>
#include <opencv2/objdetect/aruco_dictionary.hpp>

#include "colorimetry.h"
#include "cv_pipeline.h"
#include "reference_profile.h"
#include "vision_jni.h"


namespace {

// Fixed synthetic image, no RNG: a diagonal ramp gives the Laplacian something with real
// structure to respond to, so a variance of zero would mean the operator did not run.
cv::Mat syntheticGradient(int size) {
    cv::Mat image(size, size, CV_8UC1);
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            image.at<uchar>(y, x) = static_cast<uchar>((x + y) % 256);
        }
    }
    return image;
}

void testOpenCvIsThePinnedRelease() {
    const std::string linked = openCvVersion();
    assert(linked == kExpectedOpenCvVersion);
}

void testCoreAndImgprocAreUsable() {
    const cv::Mat image = syntheticGradient(64);
    assert(!image.empty());

    cv::Mat laplacian;
    cv::Laplacian(image, laplacian, CV_64F, 1);
    cv::Scalar mean;
    cv::Scalar stddev;
    cv::meanStdDev(laplacian, mean, stddev);
    assert(stddev[0] > 0.0);

    // Fixed bounds on a fixed image: the selected pixel count is exact, which catches an
    // unexpected change in the inRange kernel rather than merely "it ran".
    const cv::Mat flat(4, 4, CV_8UC3, cv::Scalar(10, 20, 30));
    cv::Mat selected;
    cv::inRange(flat, cv::Scalar(10, 20, 30), cv::Scalar(10, 20, 30), selected);
    assert(selected.type() == CV_8UC1);
    assert(cv::countNonZero(selected) == 16);
}

void testArucoModuleIsUsable() {
    const cv::aruco::Dictionary dictionary =
        cv::aruco::getPredefinedDictionary(cv::aruco::DICT_4X4_50);
    assert(!dictionary.bytesList.empty());

    cv::Mat marker;
    cv::aruco::generateImageMarker(dictionary, 7, 64, marker);
    assert(!marker.empty());
    assert(marker.cols == 64 && marker.rows == 64);

    // AGENTS.md directive 6: the same input must always produce the same output bytes.
    cv::Mat markerAgain;
    cv::aruco::generateImageMarker(dictionary, 7, 64, markerAgain);
    assert(marker.type() == markerAgain.type());
    assert(cv::countNonZero(marker != markerAgain) == 0);

    cv::aruco::ArucoDetector detector(dictionary);
    assert(!detector.getDictionary().bytesList.empty());

    // Same code path the JNI layer calls, so both gates cover one implementation.
    assert(arucoSelfTest() == 64);
}

void testAbiContract() {
    assert(kVisionAbiVersion == 3);
}

// --- Colour science (colorimetry.cpp) ------------------------------------------------

// Authoritative, not ours: the 34 supplementary test pairs of
//   G. Sharma, W. Wu and E. N. Dalal, "The CIEDE2000 Color-Difference Formula:
//   Implementation Notes, Supplementary Test Data, and Mathematical Observations",
//   Color Research and Application 30(1), 2005, pp. 21-30.
// Fetched from the first author's own page:
//   https://www.hajim.rochester.edu/ece/sites/gsharma/ciede2000/dataNprograms/ciede2000testdata.txt
// Each row is L1 a1 b1 L2 a2 b2 dE00 with kL = kC = kH = 1.
struct SharmaPair {
    Lab first;
    Lab second;
    double expected;
};

const SharmaPair kSharmaPairs[] = {
    {{50.0000, 2.6772, -79.7751}, {50.0000, 0.0000, -82.7485}, 2.0425},
    {{50.0000, 3.1571, -77.2803}, {50.0000, 0.0000, -82.7485}, 2.8615},
    {{50.0000, 2.8361, -74.0200}, {50.0000, 0.0000, -82.7485}, 3.4412},
    {{50.0000, -1.3802, -84.2814}, {50.0000, 0.0000, -82.7485}, 1.0000},
    {{50.0000, -1.1848, -84.8006}, {50.0000, 0.0000, -82.7485}, 1.0000},
    {{50.0000, -0.9009, -85.5211}, {50.0000, 0.0000, -82.7485}, 1.0000},
    {{50.0000, 0.0000, 0.0000}, {50.0000, -1.0000, 2.0000}, 2.3669},
    {{50.0000, -1.0000, 2.0000}, {50.0000, 0.0000, 0.0000}, 2.3669},
    {{50.0000, 2.4900, -0.0010}, {50.0000, -2.4900, 0.0009}, 7.1792},
    {{50.0000, 2.4900, -0.0010}, {50.0000, -2.4900, 0.0010}, 7.1792},
    {{50.0000, 2.4900, -0.0010}, {50.0000, -2.4900, 0.0011}, 7.2195},
    {{50.0000, 2.4900, -0.0010}, {50.0000, -2.4900, 0.0012}, 7.2195},
    {{50.0000, -0.0010, 2.4900}, {50.0000, 0.0009, -2.4900}, 4.8045},
    {{50.0000, -0.0010, 2.4900}, {50.0000, 0.0010, -2.4900}, 4.8045},
    {{50.0000, -0.0010, 2.4900}, {50.0000, 0.0011, -2.4900}, 4.7461},
    {{50.0000, 2.5000, 0.0000}, {50.0000, 0.0000, -2.5000}, 4.3065},
    {{50.0000, 2.5000, 0.0000}, {73.0000, 25.0000, -18.0000}, 27.1492},
    {{50.0000, 2.5000, 0.0000}, {61.0000, -5.0000, 29.0000}, 22.8977},
    {{50.0000, 2.5000, 0.0000}, {56.0000, -27.0000, -3.0000}, 31.9030},
    {{50.0000, 2.5000, 0.0000}, {58.0000, 24.0000, 15.0000}, 19.4535},
    {{50.0000, 2.5000, 0.0000}, {50.0000, 3.1736, 0.5854}, 1.0000},
    {{50.0000, 2.5000, 0.0000}, {50.0000, 3.2972, 0.0000}, 1.0000},
    {{50.0000, 2.5000, 0.0000}, {50.0000, 1.8634, 0.5757}, 1.0000},
    {{50.0000, 2.5000, 0.0000}, {50.0000, 3.2592, 0.3350}, 1.0000},
    {{60.2574, -34.0099, 36.2677}, {60.4626, -34.1751, 39.4387}, 1.2644},
    {{63.0109, -31.0961, -5.8663}, {62.8187, -29.7946, -4.0864}, 1.2630},
    {{61.2901, 3.7196, -5.3901}, {61.4292, 2.2480, -4.9620}, 1.8731},
    {{35.0831, -44.1164, 3.7933}, {35.0232, -40.0716, 1.5901}, 1.8645},
    {{22.7233, 20.0904, -46.6940}, {23.0331, 14.9730, -42.5619}, 2.0373},
    {{36.4612, 47.8580, 18.3852}, {36.2715, 50.5065, 21.2231}, 1.4146},
    {{90.8027, -2.0831, 1.4410}, {91.1528, -1.6435, 0.0447}, 1.4441},
    {{90.9257, -0.5406, -0.9208}, {88.6381, -0.8985, -0.7239}, 1.5381},
    {{6.7747, -0.2908, -2.4247}, {5.8714, -0.0985, -2.2286}, 0.6377},
    {{2.0776, 0.0795, -1.1350}, {0.9033, -0.0636, -0.5514}, 0.9082},
};

void testCiede2000MatchesThePublishedPairs() {
    size_t checked = 0;
    for (const SharmaPair& pair : kSharmaPairs) {
        const double measured = ciede2000(pair.first, pair.second);
        // The published values are rounded to 4 decimals, so half a unit in the last place is the
        // tightest honest tolerance; 1e-4 catches a wrong term, a missing factor or a swapped
        // sign, all of which are orders of magnitude out.
        assert(std::fabs(measured - pair.expected) < 1e-4);
        // Symmetry: CIEDE2000 is a distance, not an ordering.
        assert(std::fabs(ciede2000(pair.second, pair.first) - measured) < 1e-12);
        ++checked;
    }
    assert(checked == 34);
}

void testCiede2000IsZeroForIdenticalColours() {
    const std::array<Lab, 4> samples = {
        Lab{50.0, 2.6772, -79.7751}, Lab{0.0, 0.0, 0.0}, Lab{100.0, 0.0, 0.0}, Lab{12.3, -44.1, 39.4}};
    for (const Lab& sample : samples) {
        assert(ciede2000(sample, sample) < 1e-12);
    }
}

void testSrgbCompandingIsMonotonicAndAnchored() {
    // IEC 61966-2-1 anchors and the two branch meeting point.
    assert(srgbToLinear(0.0) == 0.0);
    assert(srgbToLinear(1.0) == 1.0);
    // The two segments of the sRGB curve meet at the breakpoint but are not identical there: the
    // published linear value at 0.04045 is 0.003130, and both branches reach it to 6 decimals. An
    // exact-equality assert here would be asserting that the standard is self-consistent, which it
    // is not.
    const double power = std::pow((0.04045 + 0.055) / 1.055, 2.4);
    assert(std::fabs(srgbToLinear(0.04045) - power) < 1e-6);
    assert(std::fabs(srgbToLinear(0.04045) - 0.003130) < 1e-6);
    // Below the threshold the curve is exactly the linear segment, not the power segment.
    assert(srgbToLinear(0.02) == 0.02 / 12.92);
    // Monotonic across the whole domain: a non-monotonic companding scrambles the image.
    double previous = -1.0;
    for (int i = 0; i <= 255; ++i) {
        const double value = srgbToLinear(i / 255.0);
        assert(value > previous);
        previous = value;
    }
}

void testSrgbToLabAnchors() {
    // Pure white and pure black are the only two points of this transform that can be stated
    // without a source: both are defined by the sRGB specification.
    //
    // The tolerances are not sloppiness. The published 7-decimal RGB->XYZ matrix does not sum to
    // the published white point exactly (the Y row sums to 1.0000001), so white lands a few parts
    // per million away from L* = 100 and a* = b* = 0. Asserting exact equality would be asserting
    // that two independent published tables agree bit for bit, which they do not.
    const Lab white = srgbToLab(1.0, 1.0, 1.0);
    assert(std::fabs(white.l - 100.0) < 1e-4);
    assert(std::fabs(white.a) < 1e-3);
    assert(std::fabs(white.b) < 1e-3);

    // Black involves no matrix at all, so it is exact.
    const Lab black = srgbToLab(0.0, 0.0, 0.0);
    assert(std::fabs(black.l) < 1e-12);
    assert(std::fabs(black.a) < 1e-12);
    assert(std::fabs(black.b) < 1e-12);

    // Mid grey is achromatic: a* and b* must be zero to numerical noise, which is what makes it
    // usable as a neutral-patch target in a CCM.
    const Lab grey = srgbToLab(0.5, 0.5, 0.5);
    assert(std::fabs(grey.a) < 1e-2);
    assert(std::fabs(grey.b) < 1e-2);
    assert(grey.l > 45.0 && grey.l < 55.0);

    // Going through XYZ must give the same Lab as the direct path, with the companding applied
    // exactly once. (Passing raw 0.25/0.5/0.75 as linear light here would compare two different
    // colours, not the same one twice.)
    const Lab roundTrip = xyzToLab(linearRgbToXyz(
        LinearRgb{srgbToLinear(0.25), srgbToLinear(0.5), srgbToLinear(0.75)}));
    const Lab direct = srgbToLab(0.25, 0.5, 0.75);
    assert(std::fabs(roundTrip.l - direct.l) < 1e-12);
    assert(std::fabs(roundTrip.a - direct.a) < 1e-12);
    assert(std::fabs(roundTrip.b - direct.b) < 1e-12);

    // The XYZ matrix must be linear, because the colour correction matrix is applied to linear
    // light and the result is treated as linear light. A matrix with an offset or an interaction
    // term would make the two orders of correction disagree.
    const Xyz sum = linearRgbToXyz(LinearRgb{0.25, 0.5, 0.75});
    const Xyz parts = linearRgbToXyz(LinearRgb{0.25, 0.0, 0.0});
    const Xyz scaled = linearRgbToXyz(LinearRgb{0.0, 0.5, 0.75});
    assert(std::fabs(sum.x - parts.x - scaled.x) < 1e-15);
    assert(std::fabs(sum.y - parts.y - scaled.y) < 1e-15);
    assert(std::fabs(sum.z - parts.z - scaled.z) < 1e-15);
}

void testColorCorrectionMatrixRecoversAKnownTransform() {
    // A known, well-conditioned, non-symmetric matrix in linear light: exactly the shape of a
    // real camera's channel response.
    const double truth[3][3] = {
        {1.12, -0.04, 0.01},
        {-0.07, 0.98, 0.03},
        {0.02, -0.11, 1.15},
    };
    std::vector<LinearRgb> ideal;
    std::vector<LinearRgb> observed;
    const double levels[6][3] = {
        {0.05, 0.05, 0.05}, {0.90, 0.90, 0.90}, {0.20, 0.60, 0.95},
        {0.95, 0.35, 0.10}, {0.55, 0.55, 0.60}, {0.10, 0.30, 0.55},
    };
    for (const auto& level : levels) {
        LinearRgb target;
        target.r = level[0];
        target.g = level[1];
        target.b = level[2];
        ideal.push_back(target);
        LinearRgb seen;
        seen.r = truth[0][0] * target.r + truth[0][1] * target.g + truth[0][2] * target.b;
        seen.g = truth[1][0] * target.r + truth[1][1] * target.g + truth[1][2] * target.b;
        seen.b = truth[2][0] * target.r + truth[2][1] * target.g + truth[2][2] * target.b;
        observed.push_back(seen);
    }

    double solved[3][3] = {{0.0}};
    assert(solveColorCorrectionMatrix(observed, ideal, solved));

    // What the architecture's formula guarantees is a least-squares fit: applying the solution to
    // the patches it was fitted to must land on their ideal colours. It does NOT necessarily
    // reproduce the generating matrix, because that only happens when the patch set's covariance
    // is isotropic - so the residual is the assertion, not matrix equality.
    double worstResidual = 0.0;
    double worstUncorrected = 0.0;
    for (size_t p = 0; p < ideal.size(); ++p) {
        const double observedValues[3] = {observed[p].r, observed[p].g, observed[p].b};
        const double idealValues[3] = {ideal[p].r, ideal[p].g, ideal[p].b};
        for (int i = 0; i < 3; ++i) {
            double corrected = 0.0;
            for (int k = 0; k < 3; ++k) {
                corrected += solved[i][k] * observedValues[k];
            }
            worstResidual = std::max(worstResidual, std::fabs(corrected - idealValues[i]));
            worstUncorrected =
                std::max(worstUncorrected, std::fabs(observedValues[i] - idealValues[i]));
        }
    }
    assert(worstResidual < 1e-9);
    // ... and it has to be doing something: the uncorrected reading was off by 0.1 in linear light.
    assert(worstUncorrected > 0.05);

    // Deterministic: the same patches must give bit-identical coefficients (AGENTS.md
    // directive 6), which the adjugate inverse gives for free.
    double again[3][3] = {{0.0}};
    assert(solveColorCorrectionMatrix(observed, ideal, again));
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            assert(solved[i][j] == again[i][j]);
        }
    }

    // Fewer patches than the 3x3 minimum: refused rather than fitted to noise.
    std::vector<LinearRgb> tooFewObserved(observed.begin(), observed.begin() + 2);
    std::vector<LinearRgb> tooFewIdeal(ideal.begin(), ideal.begin() + 2);
    assert(!solveColorCorrectionMatrix(tooFewObserved, tooFewIdeal, solved));

    // Degenerate set: every patch the same colour has rank 1, so the fit is unrecoverable.
    const LinearRgb flat{0.4, 0.4, 0.4};
    assert(!solveColorCorrectionMatrix(std::vector<LinearRgb>(6, flat), std::vector<LinearRgb>(6, flat),
                                       solved));

    // Mismatched column counts: refused, never silently truncated.
    assert(!solveColorCorrectionMatrix(observed, tooFewIdeal, solved));

    // Nearly collinear patches: invertible, but the correction it implies would swing channels by
    // orders of magnitude. This is the case the gain guard exists for, and it is a real risk on a
    // card whose swatches were all printed from one ink batch.
    std::vector<LinearRgb> thinObserved;
    std::vector<LinearRgb> thinIdeal;
    for (int i = 1; i <= 3; ++i) {
        const double level = 0.1 * i;
        thinObserved.push_back(LinearRgb{level, level, level + 1e-7 * level});
        thinIdeal.push_back(LinearRgb{level, level * 0.9, level * 1.1});
    }
    assert(!solveColorCorrectionMatrix(thinObserved, thinIdeal, solved));

    // A refused solve must leave the caller's matrix alone. cv_pipeline reads matrixOut straight
    // into the measurement, so a half-written matrix here would surface as a fabricated
    // calibration rather than as a failure.
    double sentinel[3][3] = {{7.0, 8.0, 9.0}, {10.0, 11.0, 12.0}, {13.0, 14.0, 15.0}};
    assert(!solveColorCorrectionMatrix(thinObserved, thinIdeal, sentinel));
    assert(sentinel[0][0] == 7.0 && sentinel[0][1] == 8.0 && sentinel[0][2] == 9.0);
    assert(sentinel[1][0] == 10.0 && sentinel[1][1] == 11.0 && sentinel[1][2] == 12.0);
    assert(sentinel[2][0] == 13.0 && sentinel[2][1] == 14.0 && sentinel[2][2] == 15.0);

    // A NaN in the ideal targets is just as fatal as one in the observations: it would otherwise
    // propagate through the inverse and produce a finite, confident, wrong matrix.
    std::vector<LinearRgb> poisoned = ideal;
    poisoned[2].r = std::nan("");
    double untouched[3][3] = {{0.0}};
    assert(!solveColorCorrectionMatrix(observed, poisoned, untouched));
}

// --- Reference card profile (reference_profile.cpp) -----------------------------------

void testProfileIsHonestAboutItsProvenance() {
    const ReferenceCardProfile& profile = activeProfile();
    assert(profileCount() >= 1);
    assert(profileAt(0) == &profile);
    assert(profileAt(-1) == nullptr);
    assert(profileAt(profileCount()) == nullptr);

    // Whatever else changes, an unvalidated profile has to keep saying so in every field a UI or
    // a record could read instead of the validation flag.
    assert(profile.validation == ProfileValidation::kUnvalidated);
    assert(profile.classification.unvalidated);
    assert(std::string(profile.version).find("PROVISIONAL") != std::string::npos);
    assert(std::string(profile.provenance).find("PROVISIONAL") != std::string::npos);
    // Placeholder swatches must not masquerade as targets.
    for (int i = 0; i < profile.patchCount; ++i) {
        assert(!profile.patches[i].hasAuthoritativeColour);
    }
    // The architecture's own rules, asserted so a careless edit cannot quietly relax them.
    assert(profile.patchCount >= kRecommendedPatchesForCcm);
    assert(profile.rectifiedWidth == 500 && profile.rectifiedHeight == 500);
    assert(profile.arucoDictionary == cv::aruco::DICT_4X4_50);
    // A boundary table with no INCONCLUSIVE band would let an unvalidated number decide a
    // presumptive result.
    assert(profile.classification.positiveAtOrBelow < profile.classification.negativeAtOrAbove);
}

// --- CV pipeline (cv_pipeline.cpp) -------------------------------------------------

// TEST FIXTURE, not reference data.
//
// The fixture is drawn in the PROFILE's card space (500x500, the profile's own rectified size) and
// then warped into a 640x480 frame. Drawing in card space means the swatches sit exactly where
// reference_profile.cpp says they are, by construction rather than by a second copy of the
// arithmetic, so a test failure cannot be a fixture that drifted away from the profile.
//
// A valid ArUco marker can only be rendered from the dictionary bits, which is why the fixture is
// drawn in C++ and handed to the Kotlin tests over JNI rather than being drawn in Kotlin.
//
// The swatch values and the reaction values below are arbitrary flat greys chosen to be
// distinguishable. They are not colours of any reagent and mean nothing analytically.

constexpr int kFixtureWidth = 640;
constexpr int kFixtureHeight = 480;
constexpr int kFixtureMarkerSide = 60;
constexpr double kFixtureSheetValue = 245.0;
const std::array<double, 6> kFixtureSwatchValues = {30.0, 90.0, 150.0, 210.0, 60.0, 240.0};
// Average of kFixtureSwatchValues, which is what the pipeline must report as the reference mean.
constexpr double kFixtureReferenceMean = 130.0;

// Normalised profile rectangle to card-space pixels. Same rounding as cv_pipeline.cpp's
// toCardRect(), so a mismatch here would be a real disagreement rather than a rounding artefact.
cv::Rect fixtureCardRect(const NormalizedRect& normalized) {
    const int x0 = static_cast<int>(std::lround(normalized.x * activeProfile().rectifiedWidth));
    const int y0 = static_cast<int>(std::lround(normalized.y * activeProfile().rectifiedHeight));
    const int x1 = static_cast<int>(
        std::lround((normalized.x + normalized.width) * activeProfile().rectifiedWidth));
    const int y1 = static_cast<int>(
        std::lround((normalized.y + normalized.height) * activeProfile().rectifiedHeight));
    return cv::Rect(x0, y0, x1 - x0, y1 - y0);
}

cv::Point2f fixtureCardMarkerCentre(int index) {
    const NormalizedRect& centre = activeProfile().markerCentres[index];
    return cv::Point2f(
        static_cast<float>(centre.x * activeProfile().rectifiedWidth),
        static_cast<float>(centre.y * activeProfile().rectifiedHeight));
}

cv::Mat syntheticCardSpace(double reactionValue) {
    const ReferenceCardProfile& profile = activeProfile();
    cv::Mat card(profile.rectifiedHeight, profile.rectifiedWidth, CV_8UC3,
                 cv::Scalar(kFixtureSheetValue, kFixtureSheetValue, kFixtureSheetValue));

    for (int i = 0; i < profile.patchCount; ++i) {
        const cv::Rect rect = fixtureCardRect(profile.patches[i].rect);
        const cv::Scalar value(kFixtureSwatchValues[static_cast<size_t>(i)],
                                kFixtureSwatchValues[static_cast<size_t>(i)],
                                kFixtureSwatchValues[static_cast<size_t>(i)]);
        card(rect).setTo(value);
    }

    const cv::Rect reaction = fixtureCardRect(profile.reactionRoi);
    card(reaction).setTo(cv::Scalar(reactionValue, reactionValue, reactionValue));

    const cv::aruco::Dictionary dictionary =
        cv::aruco::getPredefinedDictionary(profile.arucoDictionary);
    for (int i = 0; i < 4; ++i) {
        cv::Mat marker;
        cv::aruco::generateImageMarker(dictionary, profile.markerIds[i], kFixtureMarkerSide, marker);
        cv::cvtColor(marker, marker, cv::COLOR_GRAY2BGR);
        const cv::Point2f centre = fixtureCardMarkerCentre(i);
        const int half = kFixtureMarkerSide / 2;
        marker.copyTo(card(cv::Rect(static_cast<int>(centre.x) - half,
                                   static_cast<int>(centre.y) - half, kFixtureMarkerSide,
                                   kFixtureMarkerSide)));
    }
    return card;
}

// Frame-space marker centres. The default fills the frame exactly; the tilted variant is
// deliberately non-affine so the recovered geometry has to be a real perspective.
const std::array<cv::Point2f, 4> kFrontalCentres = {
    cv::Point2f(64.0f, 48.0f), cv::Point2f(576.0f, 48.0f),
    cv::Point2f(576.0f, 432.0f), cv::Point2f(64.0f, 432.0f)};
const std::array<cv::Point2f, 4> kTiltedCentres = {
    cv::Point2f(74.0f, 38.0f), cv::Point2f(586.0f, 62.0f),
    cv::Point2f(548.0f, 441.0f), cv::Point2f(52.0f, 418.0f)};

cv::Mat frameFromCardSpace(double reactionValue, const std::array<cv::Point2f, 4>& centres) {
    const cv::Mat card = syntheticCardSpace(reactionValue);
    std::array<cv::Point2f, 4> cardPoints;
    for (int i = 0; i < 4; ++i) {
        cardPoints[static_cast<size_t>(i)] = fixtureCardMarkerCentre(i);
    }
    const cv::Mat transform = cv::getPerspectiveTransform(cardPoints.data(), centres.data());
    cv::Mat frame(kFixtureHeight, kFixtureWidth, CV_8UC3,
                  cv::Scalar(kFixtureSheetValue, kFixtureSheetValue, kFixtureSheetValue));
    cv::warpPerspective(card, frame, transform, frame.size(), cv::INTER_LINEAR, cv::BORDER_CONSTANT,
                        cv::Scalar(kFixtureSheetValue, kFixtureSheetValue, kFixtureSheetValue));
    return frame;
}

std::vector<uint8_t> encodeJpeg(const cv::Mat& image) {
    std::vector<uint8_t> buffer;
    const std::vector<int> params = {cv::IMWRITE_JPEG_QUALITY, 95};
    assert(cv::imencode(".jpg", image, buffer, params));
    assert(!buffer.empty());
    return buffer;
}

CvFrame process(const std::vector<uint8_t>& jpeg) { return processJpeg(jpeg.data(), jpeg.size()); }

void testPipelineRectifiesAgainstTheProfile() {
    const std::vector<uint8_t> jpeg = encodeJpeg(frameFromCardSpace(128.0, kFrontalCentres));
    const CvFrame frame = process(jpeg);

    assert(frame.status == kCvStatusOk);
    assert(frame.imageWidth == kFixtureWidth);
    assert(frame.imageHeight == kFixtureHeight);
    assert(frame.geometrySource == kCvGeometryHomographyRectified);
    assert(frame.markerIds.size() == 4);
    assert(frame.markerCorners.size() == 4 * 4 * 2);

    // The profile's own four ids, sorted, and nothing else detected.
    std::vector<int> ids = frame.markerIds;
    std::sort(ids.begin(), ids.end());
    std::vector<int> expected = {activeProfile().markerIds[0], activeProfile().markerIds[1],
                                 activeProfile().markerIds[2], activeProfile().markerIds[3]};
    std::sort(expected.begin(), expected.end());
    assert(ids == expected);

    // Regions are now reported in rectified card pixels, against the profile's own card size.
    const cv::Rect reference(frame.regions[0], frame.regions[1], frame.regions[2], frame.regions[3]);
    const cv::Rect reaction(frame.regions[4], frame.regions[5], frame.regions[6], frame.regions[7]);
    assert(reference.width > 0 && reference.height > 0);
    // Both regions must be inside the rectified card, which is the whole point of rectifying it.
    const cv::Rect card(0, 0, activeProfile().rectifiedWidth, activeProfile().rectifiedHeight);
    assert(card.contains(reference.tl()));
    assert(card.contains(reference.br() - cv::Point(1, 1)));
    assert(card.contains(reaction.tl()));
    assert(card.contains(reaction.br() - cv::Point(1, 1)));
    // The swatch band and the reaction window are different parts of the card by design, and they
    // must not overlap: an overlapping reference would mean the reference colour included the
    // sample being judged.
    assert((reference & reaction).empty());
    // The reference region is exactly the union of the six swatches.
    cv::Rect swatchUnion;
    for (int i = 0; i < activeProfile().patchCount; ++i) {
        const cv::Rect rect = fixtureCardRect(activeProfile().patches[i].rect);
        swatchUnion |= rect;
    }
    assert(reference == swatchUnion);
    // Six swatches were measured, not four.
    assert(frame.colorimetry.swatchCount == activeProfile().patchCount);
    assert(frame.colorimetry.rectifiedWidth == activeProfile().rectifiedWidth);
    assert(frame.colorimetry.rectifiedHeight == activeProfile().rectifiedHeight);
}

void testPipelineMeasuresTheDrawnColours() {
    // Reaction at 128: the swatch mean is the arithmetic mean of the six drawn greys.
    const std::vector<uint8_t> jpeg = encodeJpeg(frameFromCardSpace(128.0, kFrontalCentres));
    const CvFrame frame = process(jpeg);
    assert(frame.status == kCvStatusOk);

    for (int channel = 0; channel < 3; ++channel) {
        const double referenceMean = channel == 0 ? frame.referenceMeanB
                                      : channel == 1 ? frame.referenceMeanG
                                                    : frame.referenceMeanR;
        const double reactionMean = channel == 0 ? frame.reactionMeanB
                                     : channel == 1 ? frame.reactionMeanG
                                                   : frame.reactionMeanR;
        // Tolerance is resampling, not sloppiness: the fixture round-trips through two bilinear
        // warps and a JPEG, which bleeds the brighter sheet into the swatch borders. Measured
        // bias on this fixture is about +2 units on the swatch mean and +0.8 on the reaction
        // window. What the assertion rules out is a ROI that is in the wrong place or a pipeline
        // that is not reading the pixels at all.
        assert(std::fabs(referenceMean - kFixtureReferenceMean) < 4.0);
        assert(std::fabs(reactionMean - 128.0) < 2.0);
    }

    // No CCM is claimed while the profile has no authoritative patch colours.
    assert(frame.colorimetry.ccmApplied == 0);
    // ... and the corrected values therefore equal the measured ones, not a different number.
    assert(frame.colorimetry.referenceR == frame.colorimetry.referenceRawR);
    assert(frame.colorimetry.reactionB == frame.colorimetry.reactionRawB);
}

void testPipelineRecoversColoursThroughPerspective() {
    // Same card, tilted: a pipeline that only drew a bounding box cannot recover the swatch means
    // from this frame, so this is the assertion that the homography is actually doing work.
    const std::vector<uint8_t> jpeg = encodeJpeg(frameFromCardSpace(128.0, kTiltedCentres));
    const CvFrame frame = process(jpeg);
    assert(frame.status == kCvStatusOk);
    assert(frame.geometrySource == kCvGeometryHomographyRectified);

    assert(std::fabs(frame.referenceMeanR - kFixtureReferenceMean) < 6.0);
    assert(std::fabs(frame.reactionMeanG - 128.0) < 6.0);
}

void testPipelineColorStageIsInternallyConsistent() {
    // Two different reaction colours, so the reported dE00 and the reported classification have to
    // follow the colour rather than being constants.
    const std::vector<uint8_t> nearReference = encodeJpeg(frameFromCardSpace(128.0, kFrontalCentres));
    const std::vector<uint8_t> farFromReference = encodeJpeg(frameFromCardSpace(100.0, kFrontalCentres));
    const CvFrame near = process(nearReference);
    const CvFrame far = process(farFromReference);
    assert(near.status == kCvStatusOk && far.status == kCvStatusOk);

    // dE00 is monotone in the colour difference: the darker reaction is further from the reference.
    assert(near.colorimetry.deltaE2000 < far.colorimetry.deltaE2000);

    // Both classifications are what the profile's own boundary table gives for the dE00 the
    // pipeline measured - computed here from the same table, independently of the pipeline.
    const ReferenceCardProfile& profile = activeProfile();
    const auto expectedClass = [profile](double deltaE) {
        if (deltaE <= profile.classification.positiveAtOrBelow) {
            return static_cast<int>(CvClassification::kPositive);
        }
        if (deltaE >= profile.classification.negativeAtOrAbove) {
            return static_cast<int>(CvClassification::kNegative);
        }
        return static_cast<int>(CvClassification::kInconclusive);
    };
    assert(near.colorimetry.classification == expectedClass(near.colorimetry.deltaE2000));
    assert(far.colorimetry.classification == expectedClass(far.colorimetry.deltaE2000));
    // The two test colours must land on different outcomes, or this assertion would pass on a
    // pipeline that classifies everything the same way.
    assert(near.colorimetry.classification != far.colorimetry.classification);

    // The reported Lab values are the ones dE00 was computed from, and they match a direct
    // conversion of the measured colour rather than a stored constant.
    for (const CvFrame* frame : {&near, &far}) {
        const double reaction = frame->colorimetry.reactionRawR / 255.0;
        const Lab expected = srgbToLab(reaction, reaction, reaction);
        assert(std::fabs(frame->colorimetry.reactionLabL - expected.l) < 1e-9);
        assert(std::fabs(frame->colorimetry.reactionLabA - expected.a) < 1e-9);
        assert(std::fabs(frame->colorimetry.reactionLabB - expected.b) < 1e-9);
        const Lab referenceLab{frame->colorimetry.referenceLabL, frame->colorimetry.referenceLabA,
                               frame->colorimetry.referenceLabB};
        const Lab reactionLab{frame->colorimetry.reactionLabL, frame->colorimetry.reactionLabA,
                              frame->colorimetry.reactionLabB};
        assert(std::fabs(frame->colorimetry.deltaE2000 - ciede2000(referenceLab, reactionLab)) < 1e-9);
    }
}

void testPipelineIsDeterministic() {
    // AGENTS.md directive 6: identical input bytes, identical measurement bytes.
    const std::vector<uint8_t> jpeg = encodeJpeg(frameFromCardSpace(118.0, kTiltedCentres));
    const CvFrame a = process(jpeg);
    const CvFrame b = process(jpeg);

    assert(a.status == b.status);
    assert(a.imageWidth == b.imageWidth && a.imageHeight == b.imageHeight);
    assert(a.geometrySource == b.geometrySource);
    assert(a.laplacianVariance == b.laplacianVariance);
    assert(a.glareFraction == b.glareFraction);
    assert(a.referenceMeanB == b.referenceMeanB);
    assert(a.referenceMeanG == b.referenceMeanG);
    assert(a.referenceMeanR == b.referenceMeanR);
    assert(a.reactionMeanB == b.reactionMeanB);
    assert(a.reactionMeanG == b.reactionMeanG);
    assert(a.reactionMeanR == b.reactionMeanR);
    assert(a.markerIds == b.markerIds);
    assert(a.markerCorners == b.markerCorners);
    for (int i = 0; i < 8; ++i) {
        assert(a.regions[i] == b.regions[i]);
    }
    assert(a.colorimetry.deltaE2000 == b.colorimetry.deltaE2000);
    assert(a.colorimetry.classification == b.colorimetry.classification);
    assert(a.colorimetry.reactionLabL == b.colorimetry.reactionLabL);
    assert(a.colorimetry.profileVersion == b.colorimetry.profileVersion);
}

void testPipelineReportsNoMarkersInsteadOfGuessing() {
    // A blank frame: no card, no measurement, and no centre-crop "result". There is no centre
    // fallback on this path any more, because a plausible-looking crop is how a wrong answer gets
    // sealed.
    cv::Mat blank(kFixtureHeight, kFixtureWidth, CV_8UC3, cv::Scalar(10, 20, 30));
    const CvFrame frame = process(encodeJpeg(blank));

    assert(frame.status == kCvStatusNoMarkers);
    assert(frame.geometrySource == kCvGeometryNone);
    assert(frame.markerIds.empty());
    for (int i = 0; i < 8; ++i) {
        assert(frame.regions[i] == 0);
    }
    // And no colour may be published alongside the failure.
    assert(frame.colorimetry.deltaE2000 == 0.0);
    assert(frame.colorimetry.reactionRawR == 0.0);
    // The profile identity still travels with the failure, or the status is unreadable later.
    assert(frame.colorimetry.profileVersion == activeProfile().version);
    assert(frame.colorimetry.profileValidation ==
           static_cast<int>(activeProfile().validation));
}

void testPipelineRejectsTheWrongCard() {
    // A card of this profile's dictionary carrying four ids that are not this profile's ids: the
    // operator has the wrong card, and the record must be able to say so.
    const ReferenceCardProfile& profile = activeProfile();
    // Redraw the card with foreign ids at the same four positions, so the only thing that changed
    // is the id.
    cv::Mat card = syntheticCardSpace(128.0);
    const cv::aruco::Dictionary dictionary =
        cv::aruco::getPredefinedDictionary(profile.arucoDictionary);
    for (int i = 0; i < 4; ++i) {
        cv::Mat marker;
        cv::aruco::generateImageMarker(dictionary, profile.markerIds[i] + 10, kFixtureMarkerSide,
                                       marker);
        cv::cvtColor(marker, marker, cv::COLOR_GRAY2BGR);
        const cv::Point2f centre = fixtureCardMarkerCentre(i);
        const int half = kFixtureMarkerSide / 2;
        marker.copyTo(card(cv::Rect(static_cast<int>(centre.x) - half,
                                    static_cast<int>(centre.y) - half, kFixtureMarkerSide,
                                    kFixtureMarkerSide)));
    }
    std::array<cv::Point2f, 4> cardPoints;
    for (int i = 0; i < 4; ++i) {
        cardPoints[static_cast<size_t>(i)] = fixtureCardMarkerCentre(i);
    }
    const cv::Mat transform = cv::getPerspectiveTransform(cardPoints.data(), kFrontalCentres.data());
    cv::Mat frame(kFixtureHeight, kFixtureWidth, CV_8UC3,
                  cv::Scalar(kFixtureSheetValue, kFixtureSheetValue, kFixtureSheetValue));
    cv::warpPerspective(card, frame, transform, frame.size(), cv::INTER_LINEAR);

    const CvFrame result = process(encodeJpeg(frame));
    assert(result.status == kCvStatusUnexpectedMarkerIds);
    assert(result.geometrySource == kCvGeometryNone);
    assert(result.colorimetry.deltaE2000 == 0.0);
}

void testPipelineReportsAnIncompleteMarkerSet() {
    // Three of the four markers: not enough to rectify, and not silently a different geometry.
    cv::Mat card = syntheticCardSpace(128.0);
    // Cover the bottom-left marker with sheet, so it cannot be detected.
    const cv::Point2f hidden = fixtureCardMarkerCentre(3);
    const int half = kFixtureMarkerSide / 2 + 6;
    card(cv::Rect(static_cast<int>(hidden.x) - half, static_cast<int>(hidden.y) - half, half * 2,
                  half * 2))
        .setTo(cv::Scalar(kFixtureSheetValue, kFixtureSheetValue, kFixtureSheetValue));

    std::array<cv::Point2f, 4> cardPoints;
    for (int i = 0; i < 4; ++i) {
        cardPoints[static_cast<size_t>(i)] = fixtureCardMarkerCentre(i);
    }
    const cv::Mat transform = cv::getPerspectiveTransform(cardPoints.data(), kFrontalCentres.data());
    cv::Mat frame(kFixtureHeight, kFixtureWidth, CV_8UC3,
                  cv::Scalar(kFixtureSheetValue, kFixtureSheetValue, kFixtureSheetValue));
    cv::warpPerspective(card, frame, transform, frame.size(), cv::INTER_LINEAR);

    const CvFrame result = process(encodeJpeg(frame));
    assert(result.status == kCvStatusIncompleteMarkerSet);
    assert(result.geometrySource == kCvGeometryNone);
    assert(result.markerIds.size() == 3);
}

void testPipelineRejectsInvalidInputWithoutCrashing() {
    CvFrame empty = processJpeg(nullptr, 0);
    assert(empty.status == kCvStatusEmptyInput);

    const uint8_t garbage[16] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    CvFrame undecodable = processJpeg(garbage, sizeof(garbage));
    assert(undecodable.status == kCvStatusDecodeFailed);
    // A failed decode must not leave plausible-looking measurements behind.
    assert(undecodable.imageWidth == 0);
    assert(undecodable.imageHeight == 0);
    assert(undecodable.referenceMeanR == 0.0);
    assert(undecodable.markerIds.empty());
}

void testSrgbCompandingRoundTrips() {
    // The pipeline re-encodes corrected linear light back to sRGB for display. If linearToSrgb and
    // srgbToLinear are not exact inverses, every reported colour drifts from the one measured.
    for (double channel = 0.0; channel <= 1.0; channel += 1.0 / 512.0) {
        assert(std::fabs(linearToSrgb(srgbToLinear(channel)) - channel) < 1e-12);
        assert(std::fabs(srgbToLinear(linearToSrgb(channel)) - channel) < 1e-12);
    }
    // Out-of-gamut corrected values are clamped, not wrapped or propagated as NaN.
    assert(linearToSrgb(-0.5) == 0.0);
    assert(linearToSrgb(1.5) == 1.0);
}

void testPipelineAppliesTheCalibrationMatrix() {
    // The shipped profile never requests calibration, so without a profile override the calibrated
    // branch below is dead code as far as any test can tell. This builds a profile that does
    // request it, so the branch is actually executed.
    ReferenceCardProfile calibrated = activeProfile();
    calibrated.calibrationMode = CalibrationMode::kColorCorrectionMatrix;

    // Give the swatches authoritative colours: the same greys the fixture draws. With observed
    // equal to ideal the fitted matrix is the identity, so corrected and raw must agree, and the
    // Lab values must match the uncorrected path rather than differing by a transfer function.
    for (int i = 0; i < calibrated.patchCount; ++i) {
        const double level = srgbToLinear(kFixtureSwatchValues[static_cast<size_t>(i)] / 255.0);
        calibrated.patches[i].idealR = level;
        calibrated.patches[i].idealG = level;
        calibrated.patches[i].idealB = level;
        calibrated.patches[i].hasAuthoritativeColour = true;
    }

    const std::vector<uint8_t> jpeg = encodeJpeg(frameFromCardSpace(128.0, kFrontalCentres));
    const CvFrame plain = processJpeg(jpeg.data(), jpeg.size());
    const CvFrame frame = processJpeg(jpeg.data(), jpeg.size(), &calibrated);

    assert(frame.status == kCvStatusOk);
    assert(frame.colorimetry.ccmApplied == 1);
    assert(plain.colorimetry.ccmApplied == 0);

    // The whole point: a CCM acts in linear light, so its result must be re-encoded exactly once.
    // Companding a linear value a second time would still produce plausible numbers, just wrong
    // ones, which is why this compares against the uncorrected path rather than a hand-written
    // expected value.
    assert(std::fabs(frame.colorimetry.reactionLabL - plain.colorimetry.reactionLabL) < 1.0);
    assert(std::fabs(frame.colorimetry.deltaE2000 - plain.colorimetry.deltaE2000) < 1.0);
    // Reported corrected channels stay on an 8-bit sRGB scale, not a linear one: a linear value
    // printed next to a raw 8-bit mean would be read as a much darker colour than it is.
    assert(frame.colorimetry.reactionR >= 0.0 && frame.colorimetry.reactionR <= 255.0);
    assert(std::fabs(frame.colorimetry.reactionR - frame.colorimetry.reactionRawR) < 2.0);

    // A profile that requests calibration but has no authoritative swatches must not fit one.
    ReferenceCardProfile noColours = calibrated;
    for (int i = 0; i < noColours.patchCount; ++i) {
        noColours.patches[i].hasAuthoritativeColour = false;
    }
    const CvFrame refused = processJpeg(jpeg.data(), jpeg.size(), &noColours);
    assert(refused.status == kCvStatusOk);
    assert(refused.colorimetry.ccmApplied == 0);
}

void testPipelineRejectsExtraForeignMarkers() {
    // A frame carrying this card's four markers plus a foreign one: all four are present, so a
    // "did I find the right ids" check alone would sail through and report the wrong card.
    cv::Mat withForeign = frameFromCardSpace(128.0, kFrontalCentres);
    const cv::aruco::Dictionary dictionary =
        cv::aruco::getPredefinedDictionary(activeProfile().arucoDictionary);
    cv::Mat foreign;
    cv::aruco::generateImageMarker(dictionary, 9, kFixtureMarkerSide, foreign);
    cv::cvtColor(foreign, foreign, cv::COLOR_GRAY2BGR);
    // Placed on empty sheet between the swatch band and the reaction window, so it is detected
    // without displacing any of the four real markers.
    const cv::Point2f foreignAt(77.0f, 240.0f);
    foreign.copyTo(withForeign(cv::Rect(static_cast<int>(foreignAt.x) - kFixtureMarkerSide / 2,
                                        static_cast<int>(foreignAt.y) - kFixtureMarkerSide / 2,
                                        kFixtureMarkerSide, kFixtureMarkerSide)));
    const std::vector<uint8_t> jpeg = encodeJpeg(withForeign);

    const CvFrame result = process(jpeg);
    assert(result.status == kCvStatusUnexpectedMarkerIds);
    // A rejected frame must not leave measurements behind.
    assert(result.colorimetry.deltaE2000 == 0.0);
    assert(result.geometrySource == kCvGeometryNone);
}

}  // namespace

// Returns 0 when every assert above held. Exported so the instrumented test can reach it
// without a JNI wrapper; JNI symbol names are prefixed, so this never collides with them.
extern "C" int vision_native_test_run() {
    testOpenCvIsThePinnedRelease();
    testCoreAndImgprocAreUsable();
    testArucoModuleIsUsable();
    testAbiContract();
    testCiede2000MatchesThePublishedPairs();
    testCiede2000IsZeroForIdenticalColours();
    testSrgbCompandingIsMonotonicAndAnchored();
    testSrgbToLabAnchors();
    testColorCorrectionMatrixRecoversAKnownTransform();
    testProfileIsHonestAboutItsProvenance();
    testPipelineRectifiesAgainstTheProfile();
    testPipelineMeasuresTheDrawnColours();
    testPipelineRecoversColoursThroughPerspective();
    testPipelineColorStageIsInternallyConsistent();
    testPipelineIsDeterministic();
    testPipelineReportsNoMarkersInsteadOfGuessing();
    testPipelineRejectsTheWrongCard();
    testPipelineReportsAnIncompleteMarkerSet();
    testPipelineRejectsInvalidInputWithoutCrashing();
    testSrgbCompandingRoundTrips();
    testPipelineAppliesTheCalibrationMatrix();
    testPipelineRejectsExtraForeignMarkers();
    return 0;
}

// Cross-language contract gate. The CvMeasurement and CvColorimetryMeasurement constructor
// signatures used by nativeProcessImage must actually exist in the Kotlin classes: a wrong
// int/double count is invisible to the C++ compiler and would otherwise only appear as
// NoSuchMethodError on a device, during a capture, after a green C++ build. Run in-process, so
// the app's own classes are the ones being checked.
extern "C" bool vision_jni_measurement_ctor_exists(JNIEnv* env) {
    jclass measurement = env->FindClass("nic/drugrepo/vision/CvMeasurement");
    if (measurement == nullptr) {
        env->ExceptionClear();
        return false;
    }
    const bool measurementFound =
        env->GetMethodID(measurement, "<init>", kMeasurementCtorSignature) != nullptr;
    if (!measurementFound) {
        env->ExceptionClear();
        return false;
    }

    jclass colorimetry = env->FindClass("nic/drugrepo/vision/CvColorimetryMeasurement");
    if (colorimetry == nullptr) {
        env->ExceptionClear();
        return false;
    }
    const bool colorimetryFound =
        env->GetMethodID(colorimetry, "<init>", kColorimetryCtorSignature) != nullptr;
    if (!colorimetryFound) {
        env->ExceptionClear();
    }
    return colorimetryFound;
}

// The only JNI symbol the harness needs: NativeHarnessTest.runNativeHarness().
// Static (companion @JvmStatic), so the second argument is the class, not an instance.
extern "C" JNIEXPORT jint JNICALL
Java_nic_drugrepo_NativeHarnessTest_runNativeHarness(JNIEnv* env, jclass) {
    assert(vision_jni_measurement_ctor_exists(env));
    return vision_native_test_run();
}

// Hands the same synthetic fixture to the Kotlin side (NativeCvTest), so the JNI contract is
// asserted against real ArUco markers instead of a hand-drawn square that no dictionary would
// accept. A valid marker can only be rendered from the dictionary bits, which is why the fixture
// lives here rather than being drawn in Kotlin.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_nic_drugrepo_vision_NativeCvTest_syntheticCardJpeg(JNIEnv* env, jclass) {
    const std::vector<uint8_t> jpeg = encodeJpeg(frameFromCardSpace(128.0, kFrontalCentres));
    jbyteArray array = env->NewByteArray(static_cast<jsize>(jpeg.size()));
    if (array == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(jpeg.size()),
                            reinterpret_cast<const jbyte*>(jpeg.data()));
    return array;
}

// A frame with no markers at all, so the Kotlin side can assert the failure status rather than
// only the success path.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_nic_drugrepo_vision_NativeCvTest_blankFrameJpeg(JNIEnv* env, jclass) {
    cv::Mat blank(kFixtureHeight, kFixtureWidth, CV_8UC3, cv::Scalar(10, 20, 30));
    const std::vector<uint8_t> jpeg = encodeJpeg(blank);
    jbyteArray array = env->NewByteArray(static_cast<jsize>(jpeg.size()));
    if (array == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(jpeg.size()),
                            reinterpret_cast<const jbyte*>(jpeg.data()));
    return array;
}