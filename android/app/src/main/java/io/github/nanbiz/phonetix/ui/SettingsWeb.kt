package io.github.nanbiz.phonetix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nanbiz.phonetix.core.Frequency
import io.github.nanbiz.phonetix.core.Packs
import io.github.nanbiz.phonetix.core.Reading
import io.github.nanbiz.phonetix.core.Settings
import io.github.nanbiz.phonetix.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The settings screen, which is the product's settings screen.
 *
 * It is the extension's popup: the same Svelte components, built into the app's assets and
 * drawn here. Which rows a surface shows is the view's own decision, from what it is told it
 * is drawn on - a browser has sites and a pointer, a phone has apps, permissions and a mark -
 * and everything else is one screen written once. A second implementation in Compose is how
 * the two halves of one product drifted into two products with different words for the same
 * setting.
 *
 * What the web view cannot do for itself is here: reading and writing the settings, the
 * dictionaries, and the two permissions that are granted in the system's own screens.
 */
@Composable
fun SettingsWeb(
    permissions: () -> Pair<Boolean, Boolean>,
    /** Whether the bundled dictionary is open yet, so the screen is told when it becomes so
     *  and asks again for what this phone can answer. */
    dictionaryReady: Boolean,
    onOpenReading: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember { mutableStateOf<WebView?>(null) }
    /** Whether the reader is on a screen behind the first one, so the phone's own back
     *  gesture leaves that screen instead of the app. */
    val inside = remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = inside.value) {
        view.value?.evaluateJavascript("window.phonetixBack && window.phonetixBack()", null)
    }
    val work = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) {
        onDispose {
            work.cancel()
            view.value?.destroy()
            view.value = null
        }
    }
    // Told again whenever this screen comes back to the front: a permission is granted in the
    // system's settings, so the only honest moment to re-read it is on the way back.
    val granted = permissions()
    androidx.compose.runtime.LaunchedEffect(granted, dictionaryReady) {
        view.value?.evaluateJavascript("window.phonetixChanged && window.phonetixChanged()", null)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                // A debug build's screen can be driven and read the way the extension's own
                // is, over the same protocol, because it is the same screen. A release build
                // exposes nothing.
                if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                // Nothing is fetched over the network by this page: it is the app's own
                // assets, and everything it asks for goes through the bridge below.
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val web = this
                addJavascriptInterface(
                    Bridge(
                        ctx, work, web, permissions, onOpenReading, onOpenOverlay, onOpenApps,
                    ) { screen -> inside.value = screen != "main" },
                    "Phonetix",
                )
                webViewClient = FromAssets(ctx)
                loadUrl("$FROM/index.html")
                view.value = this
            }
        },
    )
}

/**
 * Where the screen is served from.
 *
 * Not file://, which has no origin: a document loaded from one cannot load a module or a
 * stylesheet beside it, because every such request is cross-origin to a null origin and the
 * page comes up blank. Served instead from an address that goes nowhere and is answered out of
 * the app's own assets, which gives the page a real origin and reaches no network.
 */
private const val FROM = "https://assets.phonetix.invalid"

private class FromAssets(context: Context) : android.webkit.WebViewClient() {
    private val assets = context.applicationContext.assets

    override fun shouldInterceptRequest(
        view: WebView,
        request: android.webkit.WebResourceRequest,
    ): android.webkit.WebResourceResponse? {
        val url = request.url
        if ("$FROM".removePrefix("https://") != url.host) return null
        val path = "ui" + (url.path ?: "/index.html")
        return runCatching {
            android.webkit.WebResourceResponse(
                typeOf(path),
                "utf-8",
                assets.open(path.trimStart('/')),
            )
        }.getOrNull()
    }

    /** What the browser is being handed, which it will not guess from a stream. */
    private fun typeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "text/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }
}

/**
 * What the screen can ask the app, and nothing else.
 *
 * One question at a time, answered by number: a Java interface runs on the web view's own
 * JavaScript thread, so anything that opens a dictionary or a translation model is done off
 * it and the answer handed back when it is done.
 */
private class Bridge(
    private val context: Context,
    private val work: CoroutineScope,
    private val web: WebView,
    private val permissions: () -> Pair<Boolean, Boolean>,
    private val onOpenReading: () -> Unit,
    private val onOpenOverlay: () -> Unit,
    private val onOpenApps: () -> Unit,
    private val onView: (String) -> Unit,
) {

    @JavascriptInterface
    fun ask(id: Int, kind: String, payload: String) {
        val asked = runCatching { JSONObject(payload) }.getOrDefault(JSONObject())
        work.launch {
            val answer = runCatching {
                when (kind) {
                    "state" -> state()
                    "set" -> {
                        set(asked.optString("name"), asked.opt("value"))
                        JSONObject()
                    }
                    "getPack" -> {
                        val lang = asked.optString("lang")
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            Packs.get(context, SettingsStore.current.packHost, lang)
                        }
                        JSONObject().put("lang", lang)
                    }
                    "forgetPack" -> {
                        Packs.forget(context, asked.optString("lang"))
                        JSONObject()
                    }
                    "say" -> said(asked)
                    "openReading" -> { onOpenReading(); JSONObject() }
                    "openOverlay" -> { onOpenOverlay(); JSONObject() }
                    "openApps" -> { onOpenApps(); JSONObject() }
                    // Which screen the reader is on. The phone's back gesture belongs to the
                    // app, and without this it left the app from a screen the reader had only
                    // opened a moment ago.
                    "view" -> {
                        onView(asked.optString("view").ifEmpty { "main" })
                        JSONObject()
                    }
                    else -> throw IllegalArgumentException("nothing here answers $kind")
                }
            }
            reply(id, answer.isSuccess, answer.getOrNull()?.toString()
                ?: JSONObject.quote(answer.exceptionOrNull()?.message ?: "it did not answer"))
        }
    }

    private fun reply(id: Int, ok: Boolean, json: String) {
        // Through a string literal rather than by interpolation: what comes back is JSON, and
        // JSON inside a script is a script until it is quoted as text.
        val quoted = JSONObject.quote(json)
        web.evaluateJavascript(
            "window.phonetixAnswer && window.phonetixAnswer($id, $ok, $quoted)", null,
        )
    }

    /** Everything the screen draws itself from, in one answer. */
    private suspend fun state(): JSONObject {
        val settings = SettingsStore.current
        val (reading, overlay) = permissions()
        val held = Packs.held(context)
        val offered = kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching { Packs.offered(settings.packHost) }.getOrDefault(emptyList())
        }
        return JSONObject()
            .put("settings", chosen(settings))
            // The bar's own positions, as the core decides them: a hundred and one steps,
            // the same hundred and one the extension draws, so a density set on one surface
            // means the same word in the same paragraph on the other.
            .put("curve", JSONArray((0..100).map { Frequency.densityForPos(it / 100f) }))
            .put(
                "packs",
                JSONObject()
                    .put("held", JSONArray(held))
                    .put("open", JSONArray(held))
                    .put(
                        "offered",
                        JSONArray(
                            offered.map { pack ->
                                JSONObject()
                                    .put("id", pack.lang)
                                    .put("lang", pack.lang)
                                    .put("built", 0)
                                    .put("entries", pack.entries)
                                    .put("keys", pack.entries)
                                    .put("glosses", pack.entries)
                                    .put("bytes", pack.bytes)
                                    .put("sha256", "")
                            },
                        ),
                    ),
            )
            .put(
                "permissions",
                JSONObject().put("reading", reading).put("overlay", overlay),
            )
            .put("version", io.github.nanbiz.phonetix.BuildConfig.VERSION_NAME)
            .put("trouble", JSONArray())
    }

    /** The reader's choices under the names the view knows them by, which are the browser's. */
    private fun chosen(settings: Settings): JSONObject = JSONObject()
        .put("on", settings.enabled)
        .put("layer", settings.layer)
        .put("density", settings.density)
        .put("translate", settings.translate)
        .put("target", settings.target)
        .put("source", "")
        .put("narrow", settings.narrow)
        .put("hideStress", settings.hideStress)
        .put("accents", JSONObject(settings.accents as Map<*, *>))
        .put("delay", 200)
        .put("animations", true)
        .put("host", settings.packHost)
        .put("theme", settings.theme)
        .put("dark", settings.dark)
        .put("off", JSONArray())
        .put("lens", settings.lens)
        .put("touchWords", settings.touchWords)
        .put("apps", JSONArray(settings.apps.toList()))
        .put("allApps", settings.allApps)

    /** One setting changed, by the name the view calls it. */
    private fun set(name: String, value: Any?) {
        when (name) {
            "on" -> SettingsStore.setEnabled(value == true)
            "layer" -> SettingsStore.setLayer(value.toString())
            "density" -> SettingsStore.setDensity((value as? Number)?.toInt() ?: return)
            "target" -> SettingsStore.setTarget(value?.toString().orEmpty())
            "narrow" -> SettingsStore.setNarrow(value == true)
            "hideStress" -> SettingsStore.setHideStress(value == true)
            "theme" -> SettingsStore.setTheme(value?.toString().orEmpty())
            "dark" -> SettingsStore.setDark(value?.toString().orEmpty())
            "translate" -> SettingsStore.setTranslate(value == true)
            "host" -> SettingsStore.setPackHost(value?.toString().orEmpty())
            "lens" -> SettingsStore.setLens(value == true)
            "touchWords" -> SettingsStore.setTouchWords(value == true)
            "accents" -> {
                val said = value as? JSONObject ?: return
                for (lang in said.keys()) SettingsStore.setAccent(lang, said.optString(lang))
            }
            // The rest are the browser's: a rest before a card opens, whether it eases in,
            // which sites are off. A phone has no pointer and no sites, and the view does not
            // draw them here - a value arriving under one of those names is a build of the
            // view newer than this app, and is left alone rather than half-stored.
        }
    }

    /** The word for something the reader wants to say, answered with the card's own JSON. */
    private suspend fun said(asked: JSONObject): JSONObject {
        val text = asked.optString("text")
        val source = asked.optString("source")
        val target = asked.optString("target")
        val answer = kotlinx.coroutines.withContext(Dispatchers.IO) {
            Reading.say(context, text, source, target)
        }
        val missing = answer == null && !io.github.nanbiz.phonetix.core.Translator.ready(
            Packs.models(context), target, source,
        )
        return JSONObject()
            .put("answer", answer?.json?.let { JSONObject(it) })
            .put("missing", missing)
    }
}
