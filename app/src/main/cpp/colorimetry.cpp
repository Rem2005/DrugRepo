#include "colorimetry.h"

#include <algorithm>
#include <cmath>

namespace {

constexpr double kPi = 3.14159265358979323846;

// CIE standard: (6/29)^3, the linear segment of the Lab transfer function, and its helpers.
constexpr double kDelta = 6.0 / 29.0;
constexpr double kDeltaCubed = kDelta * kDelta * kDelta;
constexpr double kDeltaSquared = kDelta * kDelta;

// Degrees to radians.
constexpr double kDegToRad = kPi / 180.0;
constexpr double kRadToDeg = 180.0 / kPi;

double deg(double radians) { return radians * kRadToDeg; }

double rad(double degrees) { return degrees * kDegToRad; }

// f(t) of the CIELAB piecewise transfer function (CIE 15:2004 section 8.3.1.3).
double labF(double t) {
    return t > kDeltaCubed ? std::cbrt(t) : t / (3.0 * kDeltaSquared) + 4.0 / 29.0;
}

// Hue angle in [0, 360), with the achromatic case defined as 0 exactly as architecture.md
// section 4 specifies.
double hueDegrees(double b, double aPrime) {
    if (b == 0.0 && aPrime == 0.0) {
        return 0.0;
    }
    const double angle = deg(std::atan2(b, aPrime));
    return angle < 0.0 ? angle + 360.0 : angle;
}

// CIE 15:2004 sRGB (linear) -> XYZ (D65).
constexpr double kM[3][3] = {
    {0.4124564, 0.3575761, 0.1804375},
    {0.2126729, 0.7151522, 0.0721750},
    {0.0193339, 0.1191920, 0.9503041},
};

// Adjugate inverse of a general 3x3. Returns false when the determinant is too small for the
// result to mean anything.
bool invert3(const double in[3][3], double out[3][3]) {
    const double c00 = in[1][1] * in[2][2] - in[1][2] * in[2][1];
    const double c01 = in[1][2] * in[2][0] - in[1][0] * in[2][2];
    const double c02 = in[1][0] * in[2][1] - in[1][1] * in[2][0];
    const double determinant = in[0][0] * c00 + in[0][1] * c01 + in[0][2] * c02;
    if (!std::isfinite(determinant)) {
        return false;
    }
    // Scale-free singularity test. A rank-1 A (every patch the same colour, or all patches on one
    // line) has determinant zero in exact arithmetic and about 1e-18 in doubles, so an absolute
    // threshold cannot be separated from a small-but-usable determinant. For a symmetric positive
    // semi-definite A, det <= (trace/3)^3 with equality only when A is isotropic, so dividing by
    // (trace/3)^3 yields a number in (0, 1] that means "how much of the colour space these patches
    // span". See colorimetry.h.
    const double meanDiagonal = (in[0][0] + in[1][1] + in[2][2]) / 3.0;
    if (meanDiagonal <= 0.0) {
        return false;
    }
    if (determinant / (meanDiagonal * meanDiagonal * meanDiagonal) < kMinimumCovarianceIsotropy) {
        return false;
    }
    const double invDet = 1.0 / determinant;
    out[0][0] = c00 * invDet;
    out[1][0] = c01 * invDet;
    out[2][0] = c02 * invDet;
    out[0][1] = (in[0][2] * in[2][1] - in[0][1] * in[2][2]) * invDet;
    out[1][1] = (in[0][0] * in[2][2] - in[0][2] * in[2][0]) * invDet;
    out[2][1] = (in[0][1] * in[2][0] - in[0][0] * in[2][1]) * invDet;
    out[0][2] = (in[0][1] * in[1][2] - in[0][2] * in[1][1]) * invDet;
    out[1][2] = (in[0][2] * in[1][0] - in[0][0] * in[1][2]) * invDet;
    out[2][2] = (in[0][0] * in[1][1] - in[0][1] * in[1][0]) * invDet;
    return true;
}

}  // namespace

double srgbToLinear(double channel) {
    // IEC 61966-2-1: the inverse companding curve. Clamped, because a decoded JPEG channel can
    // only be 0..255 but a caller may hand over an averaged value with float noise on it.
    const double c = std::clamp(channel, 0.0, 1.0);
    return c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4);
}

double linearToSrgb(double channel) {
    const double c = std::clamp(channel, 0.0, 1.0);
    return c <= 0.0031308 ? 12.92 * c : 1.055 * std::pow(c, 1.0 / 2.4) - 0.055;
}

Xyz linearRgbToXyz(LinearRgb linear) {
    Xyz out;
    out.x = kM[0][0] * linear.r + kM[0][1] * linear.g + kM[0][2] * linear.b;
    out.y = kM[1][0] * linear.r + kM[1][1] * linear.g + kM[1][2] * linear.b;
    out.z = kM[2][0] * linear.r + kM[2][1] * linear.g + kM[2][2] * linear.b;
    return out;
}

Lab xyzToLab(Xyz xyz) {
    const double fx = labF(xyz.x / cie::kWhiteX);
    const double fy = labF(xyz.y / cie::kWhiteY);
    const double fz = labF(xyz.z / cie::kWhiteZ);
    Lab lab;
    lab.l = 116.0 * fy - 16.0;
    lab.a = 500.0 * (fx - fy);
    lab.b = 200.0 * (fy - fz);
    return lab;
}

Lab srgbToLab(double r, double g, double b) {
    LinearRgb linear;
    linear.r = srgbToLinear(r);
    linear.g = srgbToLinear(g);
    linear.b = srgbToLinear(b);
    return xyzToLab(linearRgbToXyz(linear));
}

double ciede2000(Lab reference, Lab sample) {
    const double L1 = reference.l;
    const double a1 = reference.a;
    const double b1 = reference.b;
    const double L2 = sample.l;
    const double a2 = sample.a;
    const double b2 = sample.b;

    const double C1 = std::sqrt(a1 * a1 + b1 * b1);
    const double C2 = std::sqrt(a2 * a2 + b2 * b2);
    const double Cbar = (C1 + C2) / 2.0;
    const double Cbar7 = std::pow(Cbar, 7.0);
    const double G = 0.5 * (1.0 - std::sqrt(Cbar7 / (Cbar7 + std::pow(25.0, 7.0))));

    const double a1Prime = (1.0 + G) * a1;
    const double a2Prime = (1.0 + G) * a2;
    const double C1Prime = std::sqrt(a1Prime * a1Prime + b1 * b1);
    const double C2Prime = std::sqrt(a2Prime * a2Prime + b2 * b2);

    const double h1Prime = hueDegrees(b1, a1Prime);
    const double h2Prime = hueDegrees(b2, a2Prime);

    const double dLPrime = L2 - L1;
    const double dCPrime = C2Prime - C1Prime;

    double dHue;
    if (C1Prime * C2Prime == 0.0) {
        dHue = 0.0;
    } else if (std::fabs(h2Prime - h1Prime) <= 180.0) {
        dHue = h2Prime - h1Prime;
    } else if (h2Prime - h1Prime > 180.0) {
        dHue = h2Prime - h1Prime - 360.0;
    } else {
        dHue = h2Prime - h1Prime + 360.0;
    }
    const double dHPrime = 2.0 * std::sqrt(C1Prime * C2Prime) * std::sin(rad(dHue) / 2.0);

    const double LbarPrime = (L1 + L2) / 2.0;
    const double CbarPrime = (C1Prime + C2Prime) / 2.0;

    double hBarPrime;
    if (C1Prime * C2Prime == 0.0) {
        hBarPrime = h1Prime + h2Prime;
    } else if (std::fabs(h1Prime - h2Prime) <= 180.0) {
        hBarPrime = (h1Prime + h2Prime) / 2.0;
    } else if (h1Prime + h2Prime < 360.0) {
        hBarPrime = (h1Prime + h2Prime + 360.0) / 2.0;
    } else {
        hBarPrime = (h1Prime + h2Prime - 360.0) / 2.0;
    }

    const double T = 1.0 - 0.17 * std::cos(rad(hBarPrime - 30.0)) +
                     0.24 * std::cos(rad(2.0 * hBarPrime)) +
                     0.32 * std::cos(rad(3.0 * hBarPrime + 6.0)) -
                     0.20 * std::cos(rad(4.0 * hBarPrime - 63.0));

    const double dTheta = 30.0 * std::exp(-std::pow((hBarPrime - 275.0) / 25.0, 2.0));
    const double CbarPrime7 = std::pow(CbarPrime, 7.0);
    const double R_C = 2.0 * std::sqrt(CbarPrime7 / (CbarPrime7 + std::pow(25.0, 7.0)));
    const double S_L = 1.0 +
                       (0.015 * std::pow(LbarPrime - 50.0, 2.0)) /
                           std::sqrt(20.0 + std::pow(LbarPrime - 50.0, 2.0));
    const double S_C = 1.0 + 0.045 * CbarPrime;
    const double S_H = 1.0 + 0.015 * CbarPrime * T;
    const double R_T = -std::sin(rad(2.0 * dTheta)) * R_C;

    const double termL = dLPrime / (cie::kWeightL * S_L);
    const double termC = dCPrime / (cie::kWeightC * S_C);
    const double termH = dHPrime / (cie::kWeightH * S_H);

    const double delta = termL * termL + termC * termC + termH * termH + R_T * termC * termH;
    return std::sqrt(std::max(delta, 0.0));
}

double cie76(Lab reference, Lab sample) {
    const double dl = reference.l - sample.l;
    const double da = reference.a - sample.a;
    const double db = reference.b - sample.b;
    return std::sqrt(dl * dl + da * da + db * db);
}

bool solveColorCorrectionMatrix(const std::vector<LinearRgb>& observedLinear,
                               const std::vector<LinearRgb>& idealLinear,
                               double matrixOut[3][3]) {
    if (observedLinear.size() != idealLinear.size() ||
        observedLinear.size() < static_cast<size_t>(kMinimumPatchesForCcm)) {
        return false;
    }
    const size_t n = observedLinear.size();
    for (size_t p = 0; p < n; ++p) {
        // The ideal targets are checked too: a NaN reference would otherwise propagate through the
        // inverse and surface as a plausible-looking, entirely wrong matrix.
        if (!std::isfinite(observedLinear[p].r) || !std::isfinite(observedLinear[p].g) ||
            !std::isfinite(observedLinear[p].b) || !std::isfinite(idealLinear[p].r) ||
            !std::isfinite(idealLinear[p].g) || !std::isfinite(idealLinear[p].b)) {
            return false;
        }
    }

    // A = C_obs * C_obs^T (3x3 symmetric) and B = C_ideal * C_obs^T (3x3), so M = B * A^-1.
    double a[3][3] = {{0.0}};
    double b[3][3] = {{0.0}};
    const auto column = [](const LinearRgb& c, int index) -> double {
        return index == 0 ? c.r : (index == 1 ? c.g : c.b);
    };
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            double aij = 0.0;
            double bij = 0.0;
            for (size_t k = 0; k < n; ++k) {
                const double ci = column(observedLinear[k], i);
                const double cj = column(observedLinear[k], j);
                aij += ci * cj;
                bij += column(idealLinear[k], i) * cj;
            }
            a[i][j] = aij;
            b[i][j] = bij;
        }
    }

    double inverse[3][3] = {{0.0}};
    if (!invert3(a, inverse)) {
        return false;
    }

    // Solved into a local so that every validation below can bail out without having already
    // half-written the caller's matrix: the contract is that a false return leaves it untouched.
    double matrix[3][3] = {{0.0}};
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            double sum = 0.0;
            for (int k = 0; k < 3; ++k) {
                sum += b[i][k] * inverse[k][j];
            }
            if (!std::isfinite(sum)) {
                return false;
            }
            matrix[i][j] = sum;
        }
    }

    // What the correction actually demands of the measurements it was fitted to. Applying it to
    // the same patches is the only honest check available without a held-out set, and it catches
    // the failure that matters: a fit that swings a channel by an order of magnitude.
    for (size_t p = 0; p < n; ++p) {
        const double observed[3] = {column(observedLinear[p], 0), column(observedLinear[p], 1),
                                    column(observedLinear[p], 2)};
        for (int i = 0; i < 3; ++i) {
            double corrected = 0.0;
            for (int k = 0; k < 3; ++k) {
                corrected += matrix[i][k] * observed[k];
            }
            const double floor = std::max(observed[i], kCorrectionGainFloor);
            const double gain = std::fabs(corrected) / floor;
            if (!std::isfinite(gain) || gain > kMaximumCorrectionGain) {
                return false;
            }
        }
    }

    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            matrixOut[i][j] = matrix[i][j];
        }
    }
    return true;
}