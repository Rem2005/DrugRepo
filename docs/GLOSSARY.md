# Shared Domain Vocabulary

| Term | Definition | Context |
|---|---|---|
| Presumptive Assay | A qualitative chemical spot test indicating the probable presence of a target substance. | Establishes probable cause; requires confirmatory lab testing later. Never described as definitive. |
| Reagent Kinetic Window | The strictly defined timeframe where a colorimetric reaction is analytically valid before oxidation. | Enforced by the UI timer lock. |
| CIE Lab* | A 3D, device-independent color space approximating human vision (Lightness, green-red, blue-yellow). | Target space for color conversion after RGB calibration. |
| ΔE00 (CIEDE2000) | The CIE industry-standard color perceptual distance formula. | Used to score the mathematical distance between the field sample and the reference dataset. |
| Homography | A projective transformation matrix mapping coordinates from one planar surface to another. | Used to flatten/rectify the perspective distortion of the reference card in the raw image. |
| ArUco Marker | A square binary fiducial marker detectable by OpenCV. | Four corner markers on the reference card locate and orient the card. |
| Fiducial Swatch | A printed patch of known color on the reference card. | Observed vs ideal swatch colors are used to derive the CCM. |
| CCM (Color Correction Matrix) | A matrix mapping observed swatch colors to their known ideal values. | Normalizes ambient lighting and camera response before colorimetry. |
| Reaction Region / ROI | The specific spatial area on the test card where the chemical reaction takes place. | The isolated pixel array subjected to colorimetry analysis. |
| Hash-Chain Ledger | A linked cryptographic structure where Record N's hash encompasses Record N-1's hash. | Enforces chronological tamper-evidence; supports forensic chain of custody. |
| Canonical JSON | Deterministic JSON serialization per RFC 8785 (JCS). | Input to hashing; makes hashes reproducible on any verifier. |
| Genesis Record | The first record in a chain, using 32 zero bytes as its previous hash. | Anchors the chain start. |
| StrongBox / TEE | Secure, hardware-isolated execution environments on Android devices. | Used to generate ECDSA keys and ensure the private key never enters main OS memory. |
| Key Attestation | A Keystore-issued X.509 certificate chain describing how and where a key was generated. | Parsed via OID 1.3.6.1.4.1.11129.2.1.17 to verify StrongBox/TEE backing. |
| Pre-Magistrate Digital Inventory | The sealed record set presented to a Magistrate under NDPS Sec 52A. | Supports verification that the substance matches its state at seizure. |
| MOCK Data | Placeholder reference values used only for UI/pipeline testing. | Must be labelled in code, UI, and PDF; never presented as validated science. |
