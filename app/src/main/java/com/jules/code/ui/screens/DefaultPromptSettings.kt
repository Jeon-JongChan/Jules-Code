package com.jules.code.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jules.code.R

/** 명시적으로 저장하고 활성화한 공통 문구를 새 초안에 표시하는 설정을 제공합니다. */
@Composable
fun DefaultPromptSettings(preferences: Map<String, String>, onPreference: (String, String) -> Unit) {
    val savedText = preferences["default_prompt_text"].orEmpty()
    var draft by rememberSaveable(savedText) { mutableStateOf(savedText) }
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.default_prompt_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.default_prompt_description), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.default_prompt_enabled), modifier = Modifier.weight(1f))
                Switch(
                    checked = preferences["default_prompt_enabled"] == "true",
                    onCheckedChange = { onPreference("default_prompt_enabled", it.toString()) }
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.default_prompt_text_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8
            )
            Button(onClick = { onPreference("default_prompt_text", draft) }, enabled = draft != savedText) {
                Text(stringResource(R.string.dialog_save))
            }
        }
    }
}
