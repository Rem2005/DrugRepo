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
// So the profile ships one explicitly PROVISIONAL definition, every field of which is an arbitrary
// placeholder chosen to make the pipeline executable end to end. `validation` is
// kUnvalidated, the version string contains PROVISIONAL, and the value propagates into the
// AnalysisResult, the UI and the audit record. Nothing downstream may present these numbers as
// kit data.
//
// Replacing this with a validated card is a DATA change: fill in the marker ids, the normalised
// rectangles, the ideal patch colours, set validation to kValidated, bump the version. No change
// to cv_pipeline.cpp or colorimetry.cpp is required or permitted for that.

#include <cstddef>

/** Rectangle in normalised card coordinates: 0..1 of the rectified card, origin top-left. */
struct NormalizedRect {
    double x = 0.0;
    double y = 0.0;
    double width = 0.0;
    double height = 0.0;
};

/**
 * Whether the numbers in this profile are backed by authoritative kit data. There is exactly one
 * honest answer in this build and it is kUnvalidated; the enum exists so a validated profile can
 * be added later without touching the pipeline or the UI's labelling logic.
 */
enum class ProfileValidation {
    kUnvalidated = 0,
    kValidated = 1,
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
};

/** The complete definition of one physical reference card. */
struct ReferenceCardProfile {
    /** Stable identifier, used as the numeric profile id on the JNI boundary. */
    const char* id;
    /** Human-readable version, stored in every record as part of reference_data_version. */
    const char* version;
    ProfileValidation validation;

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

    /**
     * Where the numbers came from, in one sentence, carried into the UI so nobody has to guess.
     * For the shipped profile this must say the values are placeholders.
     */
    const char* provenance;
};

/** The profile the pipeline uses. */
const ReferenceCardProfile& activeProfile();

/** Number of profiles in the table; 1 today. Lets a validated profile be added as data. */
int profileCount();

/** Profile by table index, or nullptr when the index is out of range. */
const ReferenceCardProfile* profileAt(int index);

/** Index of [activeProfile] in the table; the numeric id reported over JNI. */
int activeProfileIndex();

#endif  // DRUGREPO_REFERENCE_PROFILE_H