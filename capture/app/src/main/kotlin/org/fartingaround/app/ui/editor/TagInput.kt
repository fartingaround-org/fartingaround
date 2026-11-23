@file:OptIn(ExperimentalLayoutApi::class)

package org.fartingaround.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Chips + a text field. Enter or a comma commits a tag; the × removes one. */
@Composable
fun TagInput(tags: List<String>, onChange: (List<String>) -> Unit) {
	var text by remember { mutableStateOf("") }

	fun commit() {
		val t = text.trim().trimEnd(',').trim().lowercase()
		if (t.isNotEmpty() && t !in tags) onChange(tags + t)
		text = ""
	}

	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		if (tags.isNotEmpty()) {
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				tags.forEach { tag ->
					InputChip(
						selected = false,
						onClick = { onChange(tags - tag) },
						label = { Text(tag) },
						trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $tag", modifier = Modifier.size(16.dp)) }
					)
				}
			}
		}
		OutlinedTextField(
			value = text,
			onValueChange = { v -> if (v.endsWith(",")) { text = v; commit() } else text = v },
			placeholder = { Text("Add a tag, press enter") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
			keyboardActions = KeyboardActions(onDone = { commit() }),
			modifier = Modifier.fillMaxWidth()
		)
	}
}
