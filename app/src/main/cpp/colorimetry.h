#ifndef DRUGREPO_COLORIMETRY_H
#define DRUGREPO_COLORIMETRY_H

// Colour science for the analysis pipeline (architecture.md section 4 steps 4 and 5).
//
// Deliberately free of OpenCV types so the same functions are asserted directly by the native
// harness (vision_test.cpp) with no image involved: a colour transform that cannot be tested
// against a known number is a colour transform nobody should trust.
//
// Deterministic: every function is a pure function of its arguments, all arithmetic is double
// precision, and nothing reads a clock, a locale or a random source (AGENTS.md directive 6).

#include <cstddef>
#include <vector>

// CIE 15:2004 / sRGB values. The RGB<->XYZ matrices use the sRGB primaries with a D65 white
// point; the same white normalises XYZ to Lab*.
namespace cie {

constexpr double kWhiteX = 0.95047;
constexpr double kWhiteY = 1.00000;
constexpr double kWhiteZ = 1.08883;

// Delta E parametric weighting factors. The project standard is kL = kC = kH = 1
// (architecture.md section 4, CIEDE2000 heading).
constexpr double kWeightL = 1.0;
constexpr double kWeightC = 1.0;
constexpr double kWeightH = 1.0;

// CIE76 delta E, used only to report how far apart two Lab values are when a CCM has to be
// rejected as ill-conditioned. Never used for classification.
constexpr double kCie76K = 100.0;

}  // namespace cie

/** Linear-light RGB, each channel in [0,1]. */
struct LinearRgb {
    double r = 0.0;
    double g = 0.0;
    double b = 0.0;
};

/** CIE XYZ, Y in [0,1] for colours inside the sRGB gamut, Z and X may exceed 1 slightly. */
struct Xyz {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
};

/** CIE L*a*b* (D65). */
struct Lab {
    double l = 0.0;
    double a = 0.0;
    double b = 0.0;
};

/** sRGB electro-optical transfer function (IEC 61966-2-1), channel in [0,1]. */
double srgbToLinear(double channel);

/**
 * Exact inverse of srgbToLinear. Needed because a colour correction matrix acts on linear light,
 * so its result has to be re-encoded before it can be reported on an 8-bit sRGB scale or shown to
 * an operator. Values are clamped to [0,1]; an out-of-gamut corrected colour is a real signal that
 * the matrix is wrong, and the gain guard in solveColorCorrectionMatrix is meant to catch it
 * before here.
 */
double linearToSrgb(double channel);

/** Linear RGB to CIE XYZ (sRGB primaries, D65). */
Xyz linearRgbToXyz(LinearRgb linear);

/** CIE XYZ to CIE L*a*b* using the D65 reference white above. */
Lab xyzToLab(Xyz xyz);

/** Convenience: sRGB in [0,1] straight to Lab. */
Lab srgbToLab(double r, double g, double b);

/**
 * CIEDE2000 colour difference with kL = kC = kH = 1, implemented from the equation block in
 * architecture.md section 4. Verified against all 34 Sharma, Wu & Dalal (2005) supplementary
 * test pairs by the native harness.
 *
 * Symmetric: swapping the arguments returns the same value.
 */
double ciede2000(Lab reference, Lab sample);

/** CIE76 (Lab Euclidean) difference. Diagnostics only, never a decision boundary. */
double cie76(Lab reference, Lab sample);

/**
 * Solves the colour correction matrix of architecture.md section 4.4:
 *
 *     M = C_ideal * C_obs^T * (C_obs * C_obs^T)^-1
 *
 * observed and ideal are N column vectors in the same order; both must already be linear-light
 * (not sRGB-encoded), because a matrix fitted in gamma space is not a colour correction.
 *
 * Returns false, leaving matrixOut untouched, when:
 *   - there are fewer than kMinimumPatchesForCcm columns, which a 3x3 fit cannot recover from;
 *   - C_obs * C_obs^T is effectively singular: its determinant, normalised by (trace/3)^3 so the
 *     test is scale-free, falls below kMinimumCovarianceIsotropy. That number is 1 for an
 *     isotropic patch set and 0 for a rank-deficient one, so it says directly whether the patches
 *     span the colour space or merely one line of it;
 *   - the correction would change any measured channel by more than kMaximumCorrectionGain in
 *     either direction, which means it is amplifying sensor noise rather than removing it.
 *
 * The gain limit is deliberately about the CORRECTION rather than about the magnitudes of the
 * matrix elements. A colour correction matrix legitimately has small off-diagonal terms, so
 * comparing element magnitudes would reject correct matrices; a gain the fit demands of an actual
 * measurement is both easy to state and impossible to hide.
 *
 * No OpenCV: the 3x3 inverse is the adjugate, so the result is bit-for-bit reproducible.
 */
bool solveColorCorrectionMatrix(const std::vector<LinearRgb>& observedLinear,
                               const std::vector<LinearRgb>& idealLinear,
                               double matrixOut[3][3]);

/**
 * Smallest accepted value of det(A) / (trace(A)/3)^3, where A = C_obs * C_obs^T. Equals 1 when
 * the patch set is isotropic and 0 when the patches are collinear or identical. Below 1e-9 the
 * inverse is numerically meaningless: a rank-1 A has a determinant that is zero in exact
 * arithmetic and around 1e-18 in double precision, which an unnormalised threshold cannot tell
 * apart from a genuinely small but usable fit.
 */
constexpr double kMinimumCovarianceIsotropy = 1e-9;

/**
 * Largest correction the solver will demand of a measured channel, in either direction: a solved
 * matrix that would turn a 0.2 reading into 4.0 is amplifying noise, not calibrating. Stated here
 * rather than hidden inside the solver so a profile change cannot quietly widen it.
 */
constexpr double kMaximumCorrectionGain = 10.0;

/**
 * Floor applied to a channel before its gain is taken, so a near-black channel cannot produce an
 * unbounded ratio from ordinary noise. Chosen well below any swatch this project would use.
 */
constexpr double kCorrectionGainFloor = 1e-3;

/** Minimum patches for a 3x3 fit (architecture.md section 4.4 allows exactly this minimum). */
constexpr int kMinimumPatchesForCcm = 3;

/**
 * Recommended patch count for stability (architecture.md section 4.4: "use at least 6 distinct
 * swatches (include neutral gray/white/black patches)").
 */
constexpr int kRecommendedPatchesForCcm = 6;

#endif  // DRUGREPO_COLORIMETRY_H