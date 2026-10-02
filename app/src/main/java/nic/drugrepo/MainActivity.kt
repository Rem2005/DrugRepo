package nic.drugrepo

import android.app.Activity
import android.os.Bundle
import nic.drugrepo.databinding.ActivityMainBinding

/**
 * TODO.md Phase 2 replaces this scaffold with the linear officer flow: badge entry,
 * device-credential unlock, camera viewfinder, kinetic timer, result, chain verification.
 *
 * Uses the framework [Activity] rather than AppCompatActivity so the app carries zero
 * runtime dependencies until a later phase actually needs one.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}