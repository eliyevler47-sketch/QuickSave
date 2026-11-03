package com.example.quicksave

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.quicksave.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
