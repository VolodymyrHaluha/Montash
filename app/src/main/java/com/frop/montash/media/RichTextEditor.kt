package com.frop.montash.media

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.method.LinkMovementMethod
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private class RichTextBridge(private val onChanged: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun changed(html: String) {
        main.post { onChanged(html) }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun RichTextEditor(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val initialValue = remember { value }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val latestChange by rememberUpdatedState(onValueChange)
    val bridge = remember { RichTextBridge { latestChange(it) } }

    fun command(name: String, argument: String? = null) {
        val arg = argument?.let(JSONObject::quote) ?: "null"
        webView?.evaluateJavascript(
            "document.getElementById('editor').focus();document.execCommand(${JSONObject.quote(name)},false,$arg);" +
                "window.MontashText.changed(document.getElementById('editor').innerHTML);", null)
    }

    Surface(modifier.semantics {
        editableText = AnnotatedString(value)
        requestFocus {
            webView?.requestFocus()
            true
        }
        setText { replacement ->
            latestChange(replacement.text)
            webView?.evaluateJavascript(
                "document.getElementById('editor').innerHTML=${JSONObject.quote(replacement.text)};", null)
            true
        }
        insertTextAtCursor { inserted ->
            latestChange(inserted.text)
            webView?.evaluateJavascript(
                "document.getElementById('editor').innerHTML=${JSONObject.quote(inserted.text)};", null)
            true
        }
    }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .testTag("rich-text-toolbar")) {
                RichTextTool("H1") { command("formatBlock", "H1") }
                RichTextTool("H2") { command("formatBlock", "H2") }
                RichTextTool("P") { command("formatBlock", "P") }
                RichTextTool("B") { command("bold") }
                RichTextTool("I") { command("italic") }
                RichTextTool("U") { command("underline") }
                RichTextTool("• список") { command("insertUnorderedList") }
                RichTextTool("1. список") { command("insertOrderedList") }
                RichTextTool("←") { command("justifyLeft") }
                RichTextTool("↔") { command("justifyCenter") }
                RichTextTool("→") { command("justifyRight") }
                RichTextTool("Очистити") { command("removeFormat") }
            }
            HorizontalDivider()
            AndroidView(factory = {
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    addJavascriptInterface(bridge, "MontashText")
                    webViewClient = WebViewClient()
                    val content = JSONObject.quote(initialValue)
                    loadDataWithBaseURL(null, """
                        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1" />
                        <style>
                          html,body{margin:0;padding:0;background:transparent;color:#f0f6f3;font:16px sans-serif}
                          #editor{min-height:160px;padding:14px;outline:none;box-sizing:border-box}
                          h1,h2,p{margin:0 0 10px} a{color:#59d6ba}
                        </style></head><body><div id="editor" contenteditable="true"></div>
                        <script>
                          const editor=document.getElementById('editor'); editor.innerHTML=$content;
                          editor.addEventListener('input',()=>window.MontashText.changed(editor.innerHTML));
                        </script></body></html>
                    """.trimIndent(), "text/html", "UTF-8", null)
                    webView = this
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp).testTag("rich-text-input"))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.removeJavascriptInterface("MontashText")
            webView?.destroy()
            webView = null
        }
    }
}

@Composable
private fun RichTextTool(label: String, onClick: () -> Unit) {
    Box(Modifier.height(48.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun RichTextDisplay(html: String, modifier: Modifier = Modifier) {
    AndroidView(factory = { context ->
        TextView(context).apply {
            setTextColor(android.graphics.Color.rgb(240, 246, 243))
            textSize = 16f
            movementMethod = LinkMovementMethod.getInstance()
        }
    }, update = { view ->
        view.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
    }, modifier = modifier.fillMaxWidth())
}
