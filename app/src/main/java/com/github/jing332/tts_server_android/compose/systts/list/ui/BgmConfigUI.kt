package com.github.jing332.tts_server_android.compose.systts.list.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.jing332.common.utils.SafAudio
import com.github.jing332.common.utils.SafUtils
import com.github.jing332.common.utils.toScale
import com.github.jing332.common.utils.toast
import com.github.jing332.compose.ComposeExtensions.clickableRipple
import com.github.jing332.compose.widgets.AppSelectionDialog
import com.github.jing332.compose.widgets.LabelSlider
import com.github.jing332.database.entities.systts.BgmConfiguration
import com.github.jing332.database.entities.systts.SystemTtsV2
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.BasicInfoEditScreen
import com.github.jing332.tts_server_android.compose.systts.list.ui.widgets.SectionCard
import com.github.jing332.tts_server_android.ui.AppActivityResultContracts
import com.github.jing332.tts_server_android.ui.ExoPlayerActivity
import com.github.jing332.tts_server_android.ui.FilePickerActivity
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BgmConfigUI : IConfigUI() {
    override val showSpeechEdit: Boolean = false

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun FullEditScreen(
        modifier: Modifier,
        systemTts: SystemTtsV2,
        onSystemTtsChange: (SystemTtsV2) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        val config = systemTts.config as BgmConfiguration

        val context = LocalContext.current
        val filePicker =
            rememberLauncherForActivityResult(contract = AppActivityResultContracts.filePickerActivity()) {
                runCatching {
                    // SAF 持久读授权已由 FilePickerActivity 落盘（takePersistableUriPermission），
                    // 这里直接把 URI 字符串存进配置：不再解析成绝对路径
                    // —— Android 11+ 没有「所有文件访问权限」时，/storage 路径读不到。
                    val uri = it.second
                    if (uri == null) context.toast(R.string.path_is_empty)
                    else {
                        onSystemTtsChange(
                            systemTts.copy(
                                config = config.copy(
                                    musicList = config.musicList.toMutableList()
                                        .apply { add(uri.toString()) }
                                )
                            )
                        )
                    }
                }.onFailure {
                    context.displayErrorDialog(it)
                }
            }

        // 点条目 → 列出其下音频试听。
        // content:// 单文件/目录树走 SAF 查询（含跨进程 IO），旧绝对路径仍按 File 处理。
        var showMusicList by remember { mutableStateOf("") }
        var audioList by remember { mutableStateOf<List<SafAudio>>(emptyList()) }
        var audioListLoading by remember { mutableStateOf(false) }
        LaunchedEffect(showMusicList) {
            if (showMusicList.isEmpty()) {
                audioList = emptyList()
                return@LaunchedEffect
            }
            audioListLoading = true
            runCatching { withContext(Dispatchers.IO) { SafUtils.listAudio(context, showMusicList) } }
                .onSuccess { audioList = it }
                .onFailure {
                    context.displayErrorDialog(it)
                    audioList = emptyList()
                }
            audioListLoading = false
        }

        if (showMusicList != "") {
            AppSelectionDialog(
                onDismissRequest = { showMusicList = "" },
                title = { Text(SafUtils.displayName(context, showMusicList)) },
                value = Any(),
                values = audioList,
                entries = audioList.map { it.name },
                isLoading = audioListLoading,
                onClick = { value, _ ->
                    context.startActivity(Intent(context, ExoPlayerActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = (value as SafAudio).uriString.toUri()
                    })
                }
            )
        }

        val saveSignal = remember { mutableStateOf<(() -> Unit)?>(null) }
        DefaultFullEditScreen(
            modifier,
            title = stringResource(id = R.string.edit_bgm_tts),
            verticalScrollEnabled = false,
            onCancel = onCancel,
            onSave = {
                saveSignal.value?.invoke()
                onSave()
            }
        ) {
            SectionCard(
                title = "基本信息",
                icon = Icons.Default.Info,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                BasicInfoEditScreen(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    systemTts = systemTts,
                    onSystemTtsChange = onSystemTtsChange
                )
            }

            SectionCard(
                title = "音频参数",
                icon = Icons.Default.Speed,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                ParamsEditScreen(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    systemTts = systemTts,
                    onSystemTtsChange = onSystemTtsChange
                )
            }

            OutlinedCard(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    Modifier.align(Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        filePicker.launch(
                            FilePickerActivity.RequestSelectFile(
                                fileMimes = listOf("audio/*")
                            )
                        )
                    }) {
                        Icon(Icons.Default.AudioFile, stringResource(R.string.add_file))
                        Text(stringResource(id = R.string.add_file))
                    }
                    VerticalDivider(Modifier.height(16.dp))
                    TextButton(onClick = {
                        filePicker.launch(
                            FilePickerActivity.RequestSelectDir()
                        )
                    }) {
                        Icon(Icons.Default.CreateNewFolder, stringResource(R.string.add_folder))
                        Text(stringResource(id = R.string.add_folder))
                    }
                }

                LazyColumn(
                    Modifier
                        .padding(8.dp)
                ) {
                    items(config.musicList) { item ->
                        Row(
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickableRipple {
                                    showMusicList = item
                                }
                        ) {
                            Text(
                                // 条目存的是 content:// URI，展示其短名称（旧绝对路径原样显示）
                                remember(item) { SafUtils.displayName(context, item) },
                                modifier = Modifier.weight(1f),
                                lineHeight = LocalTextStyle.current.lineHeight * 0.8
                            )
                            IconButton(onClick = {
                                onSystemTtsChange(
                                    systemTts.copy(
                                        config = config.copy(
                                            musicList = config.musicList.toMutableList()
                                                .apply { remove(item) }
                                        ),
                                    )
                                )
                            }) {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    stringResource(id = R.string.delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun ParamsEditScreen(
        modifier: Modifier,
        systemTts: SystemTtsV2,
        onSystemTtsChange: (SystemTtsV2) -> Unit,
    ) {
        val config = systemTts.config as BgmConfiguration

        LabelSlider(
            modifier = modifier.padding(vertical = 12.dp),
            text = stringResource(R.string.label_speech_volume, "%.2f".format(config.volume)),
            value = config.volume.toFloat(),
            onValueChange = {
                onSystemTtsChange(systemTts.copy(config = config.copy(volume = it.toScale(2))))
            }, valueRange = 0.1f..1f, step = 0.05f
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                onSystemTtsChange(systemTts.copy(config = config.copy(volume = 1f)))
            }) {
                Text(stringResource(id = R.string.reset))
            }
        }
    }

}