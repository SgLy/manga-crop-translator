package me.sgly.translator.about

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R

@Composable
internal fun LicensesScreen(onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            // License names stay in English on purpose: they are the proper names of the licenses
            // themselves, and the body below is the untranslated legal text.
            PrimaryScrollableTabRow(selectedTabIndex = selected) {
                License.entries.forEachIndexed { index, license ->
                    LicenseTab(
                        license = license,
                        selected = index == selected,
                        onClick = { selected = index },
                    )
                }
            }
            LicenseBody(License.entries[selected])
        }
    }
}

@Composable
private fun LicenseTab(
    license: License,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(license.title) },
    )
}

@Composable
private fun ColumnScope.LicenseBody(license: License) {
    val context = LocalContext.current
    val body =
        remember(license) {
            context.resources
                .openRawResource(license.text)
                .bufferedReader()
                .use { it.readText() }
        }
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private enum class License(
    val title: String,
    @RawRes val text: Int,
) {
    Apache("Apache-2.0", R.raw.apache_license_2_0),
    SilOfl("SIL OFL 1.1", R.raw.sil_ofl_1_1),
    PublicDomain("Public Domain", R.raw.public_domain_notice),
    Mpl("MPL-2.0", R.raw.mpl_2_0_notice),
}
