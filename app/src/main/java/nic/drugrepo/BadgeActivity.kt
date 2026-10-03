package nic.drugrepo

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import nic.drugrepo.databinding.ActivityBadgeBinding

/**
 * TODO.md Phase 2 task 1: officer badge entry, and the device-credential unlock that guards a
 * new test.
 *
 * The credential step is deliberately isolated here and fails open only in one specific case:
 * the device has no PIN, pattern or password at all ([KeyguardManager.isDeviceSecure] false).
 * There is then nothing to confirm, so the officer is not blocked by a system dialog that can
 * never succeed. Every other outcome - cancelled, failed, or no available authenticator - is
 * reported and the test does not start.
 */
class BadgeActivity : Activity() {

    private var pendingBadge: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityBadgeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnContinue.setOnClickListener {
            val badge = binding.etBadge.text.toString().trim()
            if (badge.isEmpty()) {
                Toast.makeText(this, "Badge ID required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestDeviceCredential(badge)
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.tvBack.setOnClickListener {
            finish()
        }
    }

    private fun requestDeviceCredential(badge: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            openCamera(badge)
            return
        }
        val confirm = keyguard.createConfirmDeviceCredentialIntent(
            "Unlock DrugRepo",
            "Confirm the device credential to start a new test",
        )
        if (confirm == null) {
            Toast.makeText(this, "Device credential unavailable", Toast.LENGTH_LONG).show()
            return
        }
        pendingBadge = badge
        @Suppress("DEPRECATION")
        startActivityForResult(confirm, REQUEST_CREDENTIAL)
    }

    @Deprecated("Framework Activity result API; the app has no androidx.activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREDENTIAL) return
        val badge = pendingBadge
        pendingBadge = null
        if (resultCode == RESULT_OK && badge != null) {
            openCamera(badge)
        } else {
            Toast.makeText(this, "Unlock cancelled - test not started", Toast.LENGTH_LONG).show()
        }
    }

    private fun openCamera(badge: String) {
        startActivity(
            Intent(this, CameraActivity::class.java).putExtra("badgeId", badge),
        )
        finish()
    }

    private companion object {
        const val REQUEST_CREDENTIAL = 1
    }
}
