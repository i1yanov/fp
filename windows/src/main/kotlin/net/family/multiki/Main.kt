package net.family.multiki

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Архив Вещания") {
        Text("Архив Вещания ${BuildInfo.VERSION}")
    }
}
