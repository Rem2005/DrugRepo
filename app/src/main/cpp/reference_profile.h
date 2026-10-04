#ifndef DRUGREPO_REFERENCE_PROFILE_H
#define DRUGREPO_REFERENCE_PROFILE_H

// The reference card profile: every value that describes a PHYSICAL card lives here, and nowhere
// else in the C++ tree (AGENTS.md directive 3; sprint STEP 2).
//
// Why this file exists
// --------------------
// The pipeline needs to know four marker ids and where they sit, where the reaction window is,
// what colour the printed reference patches are supposed to be, and which dE00 separates a
// presumptive positive from a negative. This project has NONE of those values from a kit
// manufacturer, a standard, or a published reference dataset. What it has is the requirements in
// docs/architecture.md (four corner ArUco markers, warp to 500x500, CCM from at least 6 swatches,
// a configurable dE00 boundary table with an INCONCLUSIVE band).
//
// So the table ships two explicitly-labelled definitions and nothing else:
//
//   NCB-STANDARD-NARCOTICS-DD-KIT  ProfileKind::kField
//       Official qualitative procedures for Tests A, B and E, cited to the NICFS Forensic Guide.
//       No RGB, Lab or dE00 value has ever been published for a physical card, so none is here.
//       Result: INCONCLUSIVE, always, until physical calibration exists.
//
//   DEMO_SYNTHETIC_PROFILE_V1      ProfileKind::kSyntheticDemonstration
//       Synthetic hex colour anchors that exist only to show the shared pipeline running end to
//       end. Not kit data, not official data, not a drug-classification dataset. Every result it
//       produces is a demonstration and is labelled as one.
//
// Both are validated = kUnvalidated, and neither may present its numbers as kit data. The CV stage
// is identical for both: the profile is DATA, so selecting one is selecting reference values, not
// selecting an algorithm (docs/CV_PIPELINE.md).
//
// Replacing either with validated data is a DATA change: fill in the marker ids, the normalised
// rectangles, the ideal patch colours, set validation to kValidated, bump the version. No change
// to cv_pipeline.cpp or colorimetry.cpp is required or permitted for that.

#include <cstddef>
#include <string>

// For Lab, the type nearestSyntheticAnchor takes by value. colorimetry.h is free of OpenCV types
// and declares no dependency of its own, so this costs nothing and avoids a duplicate definition
// of the colour type the profile is compared in.
#include "colorimetry.h"

/** Rectangle in normalised card coordinates: 0..1 of the rectified card, origin top-left. */
struct NormalizedRect {
    double x = 0.0;
    double y = 0.0;
    double width = 0.0;
    double height = 0.0;
};

/**
 * Capacity of ReferenceCardProfile::syntheticAnchors. A fixed array rather than a std::vector
 * because the whole profile table is constexpr; a constexpr std::vector is impossible pre-C++20.
 * Twelve covers the current synthetic dataset's eleven demonstration anchors with room to spare -
 * raise it when the dataset grows, never lower it.
 */
constexpr int kMaxSyntheticAnchors = 12;

/**
 * Whether the numbers in this profile are backed by authoritative kit data. There is exactly one
 * honest answer in this build and it is kUnvalidated; the enum exists so a validated profile can
 * be added later without touching the pipeline or the UI's labelling logic.
 */
enum class ProfileValidation {
    kUnvalidated = 0,
    kValidated = 1,
};

/**
 * What a profile is FOR. The two kinds differ in nothing the pipeline computes - they differ only in
 * what the result is allowed to claim, and that difference is the point.
 *
 * kField is source-backed data intended for real casework. kSyntheticDemonstration is a colour
 * dataset that exists only to prove the CV workflow executes; nothing measured against it is a
 * finding. Kept as an enum rather than a boolean so a third kind (a validated field profile, later)
 * cannot be reached by flipping one flag.
 */
enum class ProfileKind {
    kField = 0,
    kSyntheticDemonstration = 1,
};

/** How the reaction colour is corrected before it reaches Lab. */
enum class CalibrationMode {
    /**
     * No CCM. The measured colour is used as-is. This is the only mode that can be honest while
     * the profile has no authoritative patch colours, because a CCM fitted to invented targets
     * would launder invented data into a "calibrated" measurement.
     */
    kNoneProvisional = 0,
    /** architecture.md section 4.4: M = C_ideal . C_obs^T (C_obs . C_obs^T)^-1. */
    kColorCorrectionMatrix = 1,
};

/** What the reaction Lab is compared against when computing dE00. */
enum class ComparisonTarget {
    /**
     * The Lab of the reference patches measured in THIS image. Every value on both sides of the
     * comparison is then a real measurement, so the dE00 is real even though the thresholds that
     * interpret it are not.
     */
    kMeasuredReferencePatches = 0,
    /** A Lab triple stored in the profile. Requires authoritative reference data. */
    kProfileReferenceLab = 1,
    /**
     * The nearest of the profile's SYNTHETIC demonstration anchors, by CIEDE2000. SYNTHETIC
     * DEMONSTRATION ONLY.
     *
     * Set only by a kSyntheticDemonstration profile, and only reachable because such a profile has
     * syntheticAnchorCount > 0. It answers a different question from kMeasuredReferencePatches:
     * not "how far is the reaction from this card's printed swatches" but "does the measured
     * reaction colour sit on one of the demonstration colours". The anchors are stored as hex text
     * precisely so that converting one is a deliberate act here rather than a value that could be
     * mistaken for calibration anywhere else.
     */
    kNearestSyntheticAnchor = 2,
};

/**
 * Outcome of scanning a profile's synthetic anchors for the one nearest a measured reaction Lab.
 *
 * Every field is derived from the colour the camera actually measured. `label` names the
 * demonstration anchor, which is a statement about a synthetic dataset and never about a substance.
 */
struct SyntheticAnchorMatch {
    /** CIEDE2000 from the measured reaction Lab to the nearest anchor. Meaningful when found. */
    double deltaE = 0.0;
    /** Index into ReferenceCardProfile::syntheticAnchors, or -1 when the profile holds none. */
    int anchorIndex = -1;
    /** Which colour of that anchor, 1 or 2. Zero when no anchor was found. */
    int roleIndex = 0;
    /**
     * Human-readable identification of the anchor, e.g.
     * "FLOW I / TEST A / Amphetamines / Start (#FFFF00)". Empty when no anchor was found.
     */
    std::string label;
    /** True when at least one anchor was found, so deltaE and label may be read. */
    bool found = false;
};

/** Presumptive outcome. Mirrors PresumptiveResult in Kotlin; kept numeric for the JNI boundary. */
enum class CvClassification {
    kInconclusive = 0,
    kPositive = 1,
    kNegative = 2,
};

/** One printed fiducial swatch on the card. */
struct ReferencePatch {
    NormalizedRect rect;
    /** Intended colour, sRGB channels in [0,1]. Meaningful only when hasAuthoritativeColour. */
    double idealR = 0.0;
    double idealG = 0.0;
    double idealB = 0.0;
    /** False whenever idealR/G/B are placeholders, which is the case for the shipped profile. */
    bool hasAuthoritativeColour = false;
};

/**
 * dE00 decision boundary table (architecture.md section 4 step 6). Always includes an
 * INCONCLUSIVE band: dE00 <= positiveAtOrBelow is POSITIVE, dE00 >= negativeAtOrAbove is
 * NEGATIVE, and everything strictly between is INCONCLUSIVE.
 */
struct ClassificationPolicy {
    double positiveAtOrBelow = 2.0;
    double negativeAtOrAbove = 5.0;
    /** True when the policy must not be presented as kit-specific science. */
    bool unvalidated = true;
    /**
     * True only after authoritative numerical reference colours AND decision boundaries have
     * been validated for this physical kit/card. Qualitative colour words never set this flag.
     */
    bool hasValidatedNumericalCalibration = false;
};

/** One source-described reaction colour. It intentionally has no RGB, Lab or hex fields. */
struct QualitativeColourExpectation {
    const char* analyte;
    const char* colour;
};

/**
 * One synthetic demonstration colour anchor. DEMONSTRATION DATA ONLY.
 *
 * These hex values are not measured from a physical kit, are not published by any official source,
 * and are not reference colours for any reagent. They are stored as TEXT rather than as RGB or Lab
 * so that converting one can only ever be a deliberate act, and so no consumer can read a stored
 * triple and mistake it for calibration. Any Lab value derived from one is arithmetic on a
 * synthetic number.
 */
struct SyntheticAnchor {
    const char* flow;
    const char* target;
    const char* reagent;
    const char* expectedPhrase;
    /** Free-text caveat from the synthetic dataset, e.g. a reaction time. Empty when it has none. */
    const char* timingNote;
    const char* role1;
    const char* hex1;
    /** nullptr when the dataset defines a single colour for this anchor and no second one. */
    const char* role2;
    const char* hex2;
};

/** One operator action documented by the cited source. */
struct ReagentStep {
    const char* instruction;
};

/**
 * A qualitative field-test procedure from an authoritative source. The colour names guide
 * protocol display and audit provenance only; they are not a camera-classification target.
 */
struct QualitativeTestProtocol {
    const char* testId;
    const char* scope;
    ReagentStep steps[5];
    int stepCount;
    QualitativeColourExpectation expectedColours[6];
    int expectedColourCount;
    const char* observationInstruction;
};

/** The complete definition of one physical reference card. */
struct ReferenceCardProfile {
    /** Stable identifier, used as the numeric profile id on the JNI boundary. */
    const char* id;
    /** Human-readable version, stored in every record as part of reference_data_version. */
    const char* version;
    ProfileValidation validation;
    /**
     * Whether this profile describes real casework data or a synthetic demonstration. The pipeline
     * runs identically for both; only what the result may claim differs.
     */
    ProfileKind kind;

    /** Reagent this card is designed for. Mirrors AuditableRecord.reagentType. */
    const char* reagentType;

    /** cv::aruco::PREDEFINED_DICTIONARY_* value. */
    int arucoDictionary;

    /** Marker ids in card order: top-left, top-right, bottom-right, bottom-left. */
    int markerIds[4];
    /** Normalised card position of each marker's centre, same order as markerIds. */
    NormalizedRect markerCentres[4];

    /** Rectified card size in pixels (architecture.md section 4.3: 500x500). */
    int rectifiedWidth;
    int rectifiedHeight;

    ReferencePatch patches[8];
    int patchCount;

    /** Reaction window, normalised card coordinates. */
    NormalizedRect reactionRoi;

    CalibrationMode calibrationMode;
    ComparisonTarget comparisonTarget;
    ClassificationPolicy classification;

    /** Official qualitative procedures supplied with the kit profile (Tests A, B and E only). */
    QualitativeTestProtocol qualitativeProtocols[3];
    int qualitativeProtocolCount;

    /** Source metadata for the qualitative procedures and standard-kit coverage. */
    const char* sourceTitle;
    const char* sourceUrl;
    const char* sourceStatus;

    /**
     * Synthetic demonstration anchors. Populated only by a kSyntheticDemonstration profile and
     * always zero on a kField profile, so a field profile has no place to hold invented colours
     * even by accident.
     */
    SyntheticAnchor syntheticAnchors[kMaxSyntheticAnchors];
    int syntheticAnchorCount;

    /**
     * CIEDE2000 at or below which a measured reaction colour counts as a DEMONSTRATIVE match to
     * the nearest synthetic anchor. SYNTHETIC DEMONSTRATION ONLY.
     *
     * Zero on every field profile, where it is never read: a field profile has no anchors to match
     * against, so there is nothing for the number to mean. It is separate from
     * [classification]'s boundaries on purpose, because it answers a different question. The
     * classification boundaries interpret a comparison against this card's printed swatches;
     * this one judges distance to a demonstration colour, where the error is not the card's but the
     * entire uncalibrated capture chain.
     *
     * The shipped value is 18.0, and it is derived rather than chosen. Photographing a printed
     * colour through a phone camera reproduces it to roughly 17 dE00 on this hardware, measured on
     * an RMX3870 by comparing two regions of one card printed with the same grey and recovering a
     * Lab displacement of magnitude 17.0. A tolerance tight enough to be scientifically meaningful
     * is therefore unreachable through this chain, and a loose one would match almost anything, so
     * the demonstration anchors were selected for margin instead and the tolerance placed in the
     * gap that leaves: at the observed bias the demonstrative match sits at 11.5 dE00 and the
     * demonstrative no-match at 22.9 dE00, and 18.0 separates them. Beyond a bias of about 27 dE00
     * the two are no longer separable by any tolerance, which is a limit of demonstrating against a
     * screen and not something a larger number here would fix.
     *
     * This number says nothing about any reagent, kit or substance. It is a demonstration boundary
     * for a synthetic dataset.
     */
    double syntheticAnchorMatchAtOrBelow = 0.0;

    /**
     * Where the numbers came from, in one sentence, carried into the UI so nobody has to guess.
     * For a field profile this must say the values are placeholders; for a synthetic one it must
     * say the values are synthetic.
     */
    const char* provenance;
};

/** Table index of the source-backed field profile. The real/field mode always means this index. */
constexpr int kNcbProfileIndex = 0;

/** Table index of the synthetic demonstration profile. Demonstration mode only, never casework. */
constexpr int kSyntheticProfileIndex = 1;

/** The source-backed field profile: official qualitative protocol, unvalidated numerics. */
const ReferenceCardProfile& ncbProfile();

/** The synthetic demonstration profile. Never reachable from the field path. */
const ReferenceCardProfile& syntheticProfile();

/**
 * Finds the synthetic anchor whose colour is nearest a measured reaction Lab, by CIEDE2000 over
 * both roles of every anchor. SYNTHETIC DEMONSTRATION ONLY.
 *
 * Declared here, beside the anchors it reads, so that the conversion from stored hex to a Lab triple
 * cannot be reached from anywhere that does not already hold a profile. The caller supplies the
 * measured Lab, never a colour of its own: the whole point is that the answer is driven by what the
 * camera saw.
 *
 * A profile with no anchors yields found == false. A profile whose anchors are all malformed yields
 * found == false as well, because a distance to a colour that could not be parsed is not a
 * measurement.
 */
SyntheticAnchorMatch nearestSyntheticAnchor(const ReferenceCardProfile& profile, Lab measuredLab);

/** The profile the pipeline uses when no profile is named. Always the field profile. */
const ReferenceCardProfile& activeProfile();

/** Number of profiles in the table. Lets a validated profile be added as data. */
int profileCount();

/** Profile by table index, or nullptr when the index is out of range. */
const ReferenceCardProfile* profileAt(int index);

/** Index of [activeProfile] in the table; the numeric id reported over JNI. */
int activeProfileIndex();

#endif  // DRUGREPO_REFERENCE_PROFILE_H
