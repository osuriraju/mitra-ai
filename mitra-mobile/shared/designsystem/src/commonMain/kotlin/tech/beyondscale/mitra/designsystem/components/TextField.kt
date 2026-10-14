package tech.beyondscale.mitra.designsystem.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.autofill.ContentType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun MitraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    fieldModifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    /** Autofill hint (e.g. [ContentType.Username], [ContentType.Password], [ContentType.NewPassword]). */
    contentType: ContentType? = null,
) {
    val c = Mitra.colors
    var revealed by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The visible label is announced as the field's own label (below), not as a separate text node.
        Text(label, Modifier.clearAndSetSemantics {}, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
                .semantics {
                    contentDescription = label
                    if (error != null) error(error)
                    if (contentType != null) this.contentType = contentType
                }
                .then(fieldModifier),
            singleLine = singleLine && minLines <= 1,
            minLines = minLines,
            placeholder = placeholder?.let { { Text(it, style = Mitra.type.body, color = c.faint) } },
            isError = error != null,
            textStyle = Mitra.type.body.copy(color = c.text),
            visualTransformation = if (isPassword && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(MitraIcons.Eye, contentDescription = if (revealed) "Hide password" else "Show password", tint = if (revealed) c.accent else c.muted)
                    }
                }
            } else null,
            shape = RoundedCornerShape(MitraDimens.radiusSm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent, unfocusedBorderColor = c.border, errorBorderColor = c.danger,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, errorContainerColor = c.surface,
                cursorColor = c.accent,
            ),
        )
        if (error != null) Text(error, Modifier.clearAndSetSemantics {}, style = Mitra.type.xs, color = c.danger)
    }
}
