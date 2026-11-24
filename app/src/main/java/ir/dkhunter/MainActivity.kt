package ir.dkhunter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var startButton: Button
    private lateinit var pageButton: Button
    private lateinit var stopButton: Button
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val found = LinkedHashSet<String>()
    private var pageCount = 0
    private var running = false
    private val maxPages = 5
    private val prefs by lazy { getSharedPreferences("hunter", Context.MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
        }
        root.addView(TextView(this).apply {
            text = "شکارچی کد دیجی‌کالا ۴"
            textSize = 21f
            setPadding(0, 0, 0, 6)
        })
        status = TextView(this).apply { text = "صفحه مسابقه در حال باز شدن است."; textSize = 14f }
        root.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { text = "اسکن چندصفحه‌ای" }
        pageButton = Button(this).apply { text = "اسکن همین صفحه" }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(pageButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        stopButton = Button(this).apply { text = "توقف"; isEnabled = false }
        root.addView(stopButton)
        results = TextView(this).apply {
            text = "کدهای احتمالی اینجا نمایش داده می‌شوند."
            textSize = 15f
            setPadding(0, 6, 0, 6)
        }
        root.addView(ScrollView(this).apply { addView(results) },
            LinearLayout.LayoutParams(-1, 130))

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Hunter")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                status.text = "صفحه آماده است."
                if (running) collectPage()
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl("https://www.digikala.com/landings/prize-catcher/?&tab=hunt")
        restoreHistory()

        startButton.setOnClickListener {
            running = true
            pageCount = 0
            startButton.isEnabled = false
            stopButton.isEnabled = true
            collectPage()
        }
        pageButton.setOnClickListener {
            running = false
            scanCurrentPage()
        }
        stopButton.setOnClickListener { finishScan("اسکن به درخواست شما متوقف شد.") }
    }

    private fun scanCurrentPage() {
        status.text = "در حال بارگذاری تصاویر صفحه..."
        web.evaluateJavascript(scrollJs(), null)
        scope.launch {
            delay(4200)
            web.evaluateJavascript(collectJs(), null)
        }
    }

    private fun collectPage() {
        if (!running || pageCount >= maxPages) {
            finishScan("اسکن صفحات تمام شد.")
            return
        }
        pageCount++
        status.text = "صفحه $pageCount از $maxPages؛ در حال بارگذاری تصاویر..."
        web.evaluateJavascript(scrollJs(), null)
        scope.launch {
            delay(4200)
            web.evaluateJavascript(collectJs(), null)
            delay(2200)
            web.evaluateJavascript(nextJs(), null)
        }
    }

    private fun scrollJs() = """
        (function(){
          let n=0;
          const t=setInterval(()=>{
            window.scrollBy(0, Math.max(500, window.innerHeight*0.8));
            n++;
            if(n>=10){ clearInterval(t); window.scrollTo(0,0); }
          },400);
        })();
    """.trimIndent()

    private fun collectJs() = """
        (function(){
          const out=[];
          const add=u=>{ if(u && /^https?:\/\//i.test(u) && !out.includes(u)) out.push(u); };
          document.querySelectorAll('img').forEach(i=>{
            add(i.currentSrc); add(i.src); add(i.getAttribute('data-src'));
            const s=i.getAttribute('srcset');
            if(s){ const x=s.split(',').pop().trim().split(' ')[0]; add(x); }
          });
          Hunter.onImages(JSON.stringify(out));
        })();
    """.trimIndent()

    private fun nextJs() = """
        (function(){
          const a=[...document.querySelectorAll('a')].find(x=>{
            const t=((x.innerText||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.title||'')).toLowerCase();
            return x.rel==='next' || t.includes('بعدی') || t.includes('next') || t.includes('صفحه بعد');
          });
          Hunter.onNext(a ? a.href : '');
        })();
    """.trimIndent()

    inner class Bridge {
        @JavascriptInterface fun onImages(json: String) {
            val urls = Regex("\"(https?://[^\"\\\\]+)\"").findAll(json)
                .map { it.groupValues[1].replace("\\/", "/").replace("\\u0026", "&") }
                .toList()
            scope.launch { processImages(urls) }
        }
        @JavascriptInterface fun onNext(url: String) {
            scope.launch {
                if (!running) return@launch
                val sameHost = runCatching { URL(url).host == "www.digikala.com" }.getOrDefault(false)
                if (url.isNotBlank() && sameHost && pageCount < maxPages) {
                    status.text = "رفتن به صفحه بعدی..."
                    delay(600)
                    web.loadUrl(url)
                } else finishScan("لینک صفحه بعدی پیدا نشد یا اسکن به سقف صفحات رسید.")
            }
        }
    }

    private suspend fun processImages(urls: List<String>) = withContext(Dispatchers.IO) {
        val batch = urls.filter { it.startsWith("https://") && seen.add(it) }.take(80)
        withContext(Dispatchers.Main) {
            status.text = "تصاویر جدید این صفحه: ${batch.size}؛ شروع OCR..."
        }
        for ((i, imageUrl) in batch.withIndex()) {
            if (running && !isFinishing) { /* continue */ }
            try {
                val conn = URL(imageUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.instanceFollowRedirects = true
                conn.connect()
                val code = conn.responseCode
                if (code == 403 || code == 429) {
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        finishScan("محدودیت دسترسی سایت دریافت شد؛ برای رعایت آن اسکن متوقف شد.")
                    }
                    return@withContext
                }
                if (code in 200..299 && (conn.contentType ?: "").startsWith("image/")) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size <= 4_000_000) {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            val input = InputImage.fromBitmap(bitmap, 0)
                            val text = suspendCancellableCoroutine<String> { c ->
                                recognizer.process(input)
                                    .addOnSuccessListener { if (c.isActive) c.resume(it.text) {} }
                                    .addOnFailureListener { if (c.isActive) c.resume("") {} }
                            }
                            val codes = Regex("(?i)(?<![A-Z0-9])DK[A-Z0-9]{3,8}(?![A-Z0-9])")
                                .findAll(text).map { it.value.uppercase() }.toSet()
                            if (codes.isNotEmpty()) withContext(Dispatchers.Main) { addFound(codes) }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { }
            delay(700)
            if (i % 10 == 0) withContext(Dispatchers.Main) {
                status.text = "بررسی تصاویر: ${i + 1} از ${batch.size}"
            }
        }
    }

    private fun addFound(codes: Set<String>) {
        var changed = false
        codes.forEach { if (found.add(it)) changed = true }
        if (changed) {
            results.text = found.joinToString("\n") { "• $it" }
            prefs.edit().putString("history", found.joinToString(",")).apply()
            status.text = "کد احتمالی پیدا شد؛ در سایت بررسی‌اش کن."
        }
    }

    private fun restoreHistory() {
        (prefs.getString("history", "") ?: "").split(",")
            .filter { it.isNotBlank() }.forEach { found.add(it) }
        if (found.isNotEmpty()) results.text = found.joinToString("\n") { "• $it" }
    }

    private fun finishScan(message: String) {
        running = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        status.text = "$message تصاویر یکتا: ${seen.size} | کدهای احتمالی: ${found.size}"
    }

    override fun onDestroy() {
        scope.cancel()
        recognizer.close()
        web.destroy()
        super.onDestroy()
    }
}
