package com.vusense.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.vusense.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inflate the layout using ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The PreviewView provides the SurfaceProvider needed by our headless SDK
        // val surfaceProvider = binding.previewView.surfaceProvider

        binding.btnCapture.setOnClickListener {
            // TODO: Initialize the headless SDK VusenseClient
            // TODO: Start the capture orchestration pipeline
            // e.g. VusenseClient.startCapture(surfaceProvider)
        }
    }
}
