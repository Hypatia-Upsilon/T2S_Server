package com.github.jing332.tts_server_android.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.jing332.tts_server_android.R

sealed class PagerDestination(
    val index: Int,
    @StringRes val strId: Int,
    @StringRes val contentDescId: Int,
    val icon: @Composable () -> Unit = {},
    /**
     * 底栏标签用的**简短词汇**（底栏窄，长词会挤）：未提供时同 [strId]。
     * 例：角色管理 → 「角色」。
     */
    @StringRes val shortStrId: Int = strId,
) {
    companion object {
        val routes by lazy {
            listOf(
                SystemTts,
                SystemTtsLog,
                Tool,
                Settings
            )
        }
    }

    object SystemTts : PagerDestination(0, R.string.system_tts, R.string.system_tts, {
        Icon(
            modifier = Modifier.size(24.dp),
            painter = painterResource(id = R.drawable.ic_config),
            contentDescription = null
        )
    })

    // 角色管理：独立于发音人，承载「仅界面模式」的工具型插件
    // 底栏标签用简短词汇「角色」（整页标题仍用 role_management=角色管理）
    object Tool : PagerDestination(
        index = 2,
        strId = R.string.role_management,
        contentDescId = R.string.role_management,
        icon = {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null
            )
        },
        shortStrId = R.string.role_short,
    )

    object SystemTtsLog : PagerDestination(1, R.string.log, R.string.log, {
        Icon(
            Icons.AutoMirrored.Default.TextSnippet,
            contentDescription = null
        )
    })

    object Settings : PagerDestination(3, R.string.settings, R.string.settings, {
        Icon(Icons.Default.Settings, contentDescription = null)
    })
}