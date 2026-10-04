package nic.drugrepo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import nic.drugrepo.analysis.AnalysisProfile
import nic.drugrepo.databinding.ActivityDemoBinding

/**
 * Demonstration Mode: the synthetic reference profile made reachable from an installed APK.
 *
 * Until now the synthetic demonstration was only reachable through a debug-build ADB intent extra,
 * which a judge cannot use. This screen is that entry point done properly: it is in the release
 * build, it is reached from Home like any other destination, and it says what it is before it does
 * anything.
 *
 * **A demonstration is the field workflow with different reference data.** So this screen holds no
 * controls at all - no MATCH / NO-MATCH option, no scenario picker, no result of any kind. It
 * forwards to the ordinary [BadgeActivity], and from there the officer badge, the device
 * credential, [CameraActivity] and the CV pipeline are exactly the ones the field path uses. The
 * only thing that differs is [AnalysisProfile.SYNTHETIC_DEMO] riding along in the intent, which
 * selects which reference data [nic.drugrepo.analysis.RealCvTestAnalyzer] compares the measured
 * colour against.
 *
 * The consequence, and the reason there is nothing to select here: the card physically placed in
 * front of the camera decides the outcome. The yellow card measures close to a synthetic anchor and
 * reports DEMONSTRATIVE MATCH; the green card measures far from every anchor and reports
 * DEMONSTRATIVE NO MATCH; no card at all reports INCONCLUSIVE. Nothing in the UI can change that,
 * because the UI has no influence on it - there is no value here that any part of the analysis
 * pipeline reads.
 */
class DemoModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The same destination the field path reaches from NewTestActivity. Forwarding the profile
        // extra here is the only difference between the two, and it survives the badge stage because
        // BadgeActivity passes it on to CameraActivity.
        binding.btnContinue.setOnClickListener {
            startActivity(
                Intent(this, BadgeActivity::class.java)
                    .putExtra(AnalysisProfile.EXTRA, AnalysisProfile.SYNTHETIC_DEMO),
            )
        }

        binding.tvBack.setOnClickListener {
            finish()
        }
    }
}