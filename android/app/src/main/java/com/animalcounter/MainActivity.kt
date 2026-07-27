package com.animalcounter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.animalcounter.ui.nav.AnimalCounterApp
import com.animalcounter.ui.theme.AnimalCounterTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnimalCounterTheme {
                AnimalCounterApp()
            }
        }
    }
}