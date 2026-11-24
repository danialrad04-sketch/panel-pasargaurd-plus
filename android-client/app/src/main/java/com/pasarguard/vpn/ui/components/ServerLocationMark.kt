package com.pasarguard.vpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pasarguard.vpn.R
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

private val countryCodes = Locale.getISOCountries().toSet()

internal fun serverCountryCode(code: String?): String? =
    code?.trim()?.uppercase(Locale.ROOT)?.takeIf(countryCodes::contains)

@Composable
internal fun serverCountryLabel(code: String?): String {
    val locale = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Locale.forLanguageTag("fa") else Locale.ENGLISH
    return serverCountryCode(code)?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(locale) }
        ?: stringResource(R.string.server_country_unknown)
}

@Composable
internal fun ServerLocationMark(countryCode: String?, modifier: Modifier = Modifier) {
    val code = serverCountryCode(countryCode)
    val label = serverCountryLabel(code)
    Surface(modifier = modifier.size(40.dp), shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            if (code == null) {
                Icon(Icons.Rounded.Public, contentDescription = label, modifier = Modifier.size(19.dp))
            } else {
                val flag = code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
                Text(flag, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = label })
            }
        }
    }
}
