package me.sgly.translator.about

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            LicenseTabs(selected = selected, onSelect = { selected = it })
            LicenseBody(License.entries[selected])
        }
    }
}

@Composable
private fun LicenseTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // A minimum of one equal share fills the row exactly while the labels fit, and still lets
        // the row scroll once they do not.
        val minTabWidth = maxOf(TabRowDefaults.ScrollableTabRowMinTabWidth, maxWidth / License.entries.size)
        // License names stay in English on purpose: they are the proper names of the licenses
        // themselves, and the body below is the untranslated legal text.
        PrimaryScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = 0.dp,
            minTabWidth = minTabWidth,
        ) {
            License.entries.forEachIndexed { index, license ->
                Tab(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    text = { Text(license.title) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.LicenseBody(license: License) {
    val context = LocalContext.current
    val text =
        remember(license) {
            license.text?.let { id ->
                context.resources
                    .openRawResource(id)
                    .bufferedReader()
                    .use { it.readText() }
            }
        }
    Column(
        modifier =
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        ComponentList(license.components)
        if (text != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            LicenseText(text)
        }
    }
}

// The license texts are hard-wrapped for a fixed-width display, so the body is sized to fit the
// widest line as a whole instead of letting it reflow at arbitrary positions.
private const val LICENSE_TEXT_COLUMNS = 80

// Glyph advances round to whole pixels, so the fitted width needs a little slack to keep the last
// column on the same line.
private const val FIT_SLACK = 0.995f

@Composable
private fun LicenseText(text: String) {
    val measurer = rememberTextMeasurer()
    val baseStyle =
        MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp,
        )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val style =
            remember(width, baseStyle) {
                if (width <= 0) {
                    baseStyle
                } else {
                    val probe = measurer.measure("0".repeat(LICENSE_TEXT_COLUMNS), baseStyle, maxLines = 1)
                    val scale = width.toFloat() / probe.size.width
                    val fontSize = baseStyle.fontSize * scale * FIT_SLACK
                    baseStyle.copy(fontSize = fontSize, lineHeight = fontSize * 1.4f)
                }
            }
        Text(text = text, style = style)
    }
}

private val componentListMaxHeight = 200.dp

@Composable
private fun ComponentList(components: List<Component>) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = componentListMaxHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            components.forEach { ComponentRow(it) }
        }
    }
}

@Composable
private fun ComponentRow(component: Component) {
    Row {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = component.name, style = MaterialTheme.typography.bodyMedium)
            component.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class Component(
    val name: String,
    val note: String? = null,
)

private enum class License(
    val title: String,
    @RawRes val text: Int?,
    val components: List<Component>,
) {
    Apache(
        title = "Apache-2.0",
        text = R.raw.apache_license_2_0,
        components =
            listOf(
                Component("AndroidX Core, Annotation, Collection, Arch Core, Concurrent Futures, Interpolator"),
                Component("AndroidX Activity, Lifecycle, SavedState, NavigationEvent, Window, Autofill, Emoji2"),
                Component("AndroidX ProfileInstaller, Startup, Graphics, SQLite, Tracing, VersionedParcelable"),
                Component("Jetpack Compose (UI, Foundation, Animation, Runtime, Material, Material 3, and related artifacts)"),
                Component("Room"),
                Component("Kotlin standard library, kotlin-reflect, kotlinx.coroutines, kotlinx.serialization"),
                Component("KotlinX Schema (kotlinx-schema-annotations, -json, -generator-core, -generator-json)"),
                Component("OkHttp and Okio"),
                Component("kotlin-logging, jspecify, Guava listenablefuture, JetBrains Annotations"),
            ),
    ),
    SilOfl(
        title = "SIL OFL 1.1",
        text = R.raw.sil_ofl_1_1,
        components =
            listOf(
                Component(
                    name = "Noto Serif JP, variable subset (Japanese)",
                    note =
                        "Copyright © 2017-2024 Adobe (http://www.adobe.com/).\n" +
                            "Not distributed with the app: downloaded at runtime from " +
                            "https://github.com/notofonts/noto-cjk (Serif/Variable/OTF/Subset/NotoSerifJP-VF.otf).",
                ),
            ),
    ),
    PublicDomain(
        title = "Public Domain",
        text = null,
        components =
            listOf(
                Component(
                    name = "org.json (JSON-java)",
                    note =
                        "https://github.com/stleary/JSON-java\n" +
                            "Published into the public domain; its Maven POM states the license as " +
                            "\"Public Domain\". No license text applies.",
                ),
            ),
    ),
    Mpl(
        title = "MPL-2.0",
        text = R.raw.mpl_2_0,
        components =
            listOf(
                Component(
                    name = "Public Suffix List",
                    note =
                        "Embedded by OkHttp (assets/PublicSuffixDatabase.list).\n" +
                            "Maintained at https://publicsuffix.org.",
                ),
            ),
    ),
}
