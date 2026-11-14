package net.family.multiki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.family.multiki.MainViewModel
import net.family.multiki.data.formatSize
import net.family.multiki.stream.Downloads
import net.family.multiki.stream.SmbSettings
import net.family.multiki.stream.SmbStore
import java.io.File
import javax.swing.JFileChooser

/** Windows folder picker. Network folders can be typed in its address line: \\192.168.1.50\storage */
fun chooseFolder(title: String, start: String?): File? {
    val chooser = JFileChooser(start?.takeIf { it.isNotBlank() && File(it).exists() }?.let { File(it) })
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false
    chooser.approveButtonText = "Выбрать папку"
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** «Настройки»: where downloads go on this computer, and the home server's folder. */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val root by Downloads.rootFlow.collectAsState()
    var downloadsMsg by remember { mutableStateOf<String?>(null) }

    val saved = SmbStore.settings.value
    var serverFolder by remember { mutableStateOf(saved.folder) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Настройки") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterButton("← Назад", onClick = { vm.back() }, color = Palette.RedDark)
                PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                // ---------------------------------------------------------------- downloads folder
                Text("ПАПКА ДЛЯ СКАЧАННОГО", style = MaterialTheme.typography.titleLarge, color = Palette.Red)
                Text(
                    "Сюда «Скачать» сохраняет фильмы, мультфильмы и сказки на этом компьютере. " +
                        "Уже скачанное остаётся там, где лежит; новая папка — для новых загрузок.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.InkSoft,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                PathBox(root.absolutePath)
                SmallCaption("Свободно на диске: ${formatSize(root.usableSpace)}", Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PosterButton("Выбрать папку…", color = Palette.Teal, onClick = {
                        val dir = chooseFolder("Папка для скачанного", root.absolutePath)
                        if (dir != null) {
                            val err = runCatching {
                                dir.mkdirs()
                                val probe = File(dir, ".proverka-${System.currentTimeMillis()}")
                                probe.writeText("ok"); probe.delete()
                            }.exceptionOrNull()
                            if (err == null) {
                                Downloads.setRoot(dir)
                                downloadsMsg = "Новые загрузки будут сохраняться сюда"
                            } else downloadsMsg = "В эту папку нельзя записывать — выберите другую"
                        }
                    })
                    PosterButton("Открыть в Проводнике", color = Palette.InkSoft, onClick = {
                        runCatching { root.mkdirs(); java.awt.Desktop.getDesktop().open(root) }
                    })
                }
                downloadsMsg?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Palette.Teal, modifier = Modifier.padding(top = 8.dp)) }

                // ---------------------------------------------------------------- home server
                Text(
                    "ДОМАШНИЙ СЕРВЕР",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.Red,
                    modifier = Modifier.padding(top = 28.dp),
                )
                Text(
                    "Общая папка на домашнем сервере — одно «Загруженное» для компьютеров, телевизоров, телефонов и планшетов. " +
                        "Скачанное на одном устройстве смотрится на любом другом, а места остановки общие. " +
                        "Укажите ту же папку, что и в Android-приложении, например \\\\192.168.1.50\\storage\\SrvAV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.InkSoft,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = serverFolder,
                    onValueChange = { serverFolder = it },
                    label = { Text("Папка сервера") },
                    placeholder = { Text("\\\\192.168.1.50\\storage\\SrvAV", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PosterButton("Выбрать папку…", color = Palette.InkSoft, enabled = !busy, onClick = {
                        chooseFolder("Папка домашнего сервера", serverFolder)?.let { serverFolder = it.absolutePath }
                    })
                    PosterButton(if (busy) "Проверяю…" else "Проверить", enabled = !busy, color = Palette.Teal, onClick = {
                        busy = true
                        status = null
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { SmbStore.test(SmbSettings(serverFolder)) }
                            ok = err == null
                            status = err ?: "Папка доступна, запись разрешена"
                            busy = false
                        }
                    })
                    PosterButton("Сохранить", enabled = !busy, onClick = {
                        SmbStore.save(SmbSettings(serverFolder))
                        status = if (serverFolder.isNotBlank()) "Сохранено" else "Сохранено. Сервер отключён"
                        ok = true
                    })
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ok) Palette.Teal else Palette.Red,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Text(
                    "Если сервер просит логин и пароль: один раз откройте эту папку в Проводнике, введите их и отметьте " +
                        "«Запомнить учётные данные» — дальше Windows подключается сама. Чтобы отключить сервер, очистите поле и сохраните.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.InkSoft,
                    modifier = Modifier.padding(top = 16.dp).background(Palette.PaperDark).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun PathBox(path: String) {
    Text(
        path,
        style = MaterialTheme.typography.bodyLarge,
        color = Palette.Ink,
        modifier = Modifier.fillMaxWidth().background(Palette.PaperDark).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Red,
    unfocusedBorderColor = Palette.InkSoft,
    focusedContainerColor = Palette.Paper,
    unfocusedContainerColor = Palette.Paper,
    cursorColor = Palette.Red,
    focusedLabelColor = Palette.Red,
)
