package nic.drugrepo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import nic.drugrepo.databinding.ActivityNewTestBinding

class NewTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityNewTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenCamera.setOnClickListener {
            startActivity(Intent(this, BadgeActivity::class.java))
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.tvBack.setOnClickListener {
            finish()
        }
    }
}