package dev.atlas.nym

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.atlas.nym.ui.NymApp
import dev.atlas.nym.ui.NymViewModel

class MainActivity : ComponentActivity() {
    val model: NymViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NymApp(model) }
    }
}
