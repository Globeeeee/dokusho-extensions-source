package eu.kanade.tachiyomi.extension.en.wuxiaworld

import dokusho.utils.getLocalStorage
import dokusho.utils.parseAs
import dokusho.utils.runWebView
import dokusho.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Interceptor
import kotlin.time.Duration.Companion.seconds

/**
 * The signed-in user's API token. The web app keeps its OIDC user in localStorage on the site, and the app's
 * WebView shares that login, so the token is read from there. It lives in memory only and is sent only to the API
 * host. No signed-in user means anonymous calls.
 */
internal class WuxiaWorldAuth(private val apiHost: String) {

    private class Token(val value: String, private val expiresAt: Long) {
        fun expired() = System.currentTimeMillis() / 1000 >= expiresAt - EXPIRY_MARGIN
    }

    @Volatile
    private var token: Token? = null

    @Volatile
    private var lastCheck = 0L
    private val mutex = Mutex()

    val hasToken get() = token != null

    /** Adds the bearer token to API calls, never to other hosts. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val current = token
        if (request.url.host != apiHost || current == null || current.expired()) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("Authorization", "Bearer ${current.value}").build())
        }
    }

    /**
     * Makes sure the cached token is usable. An expired token, or [renew] after an auth error, reloads the site so
     * its silent renew can write a fresh one. Failures leave the calls anonymous.
     */
    suspend fun refresh(renew: Boolean = false) = mutex.withLock {
        val current = token
        val now = System.currentTimeMillis()
        if (!renew && current != null && !current.expired()) return@withLock
        if (!renew && current == null && now - lastCheck < RECHECK_MS) return@withLock
        lastCheck = now
        token = try {
            val stored = if (renew) null else parse(getLocalStorage(SITE, KEY))
            when {
                stored != null && !stored.expired() -> stored
                stored == null && !renew -> null
                else -> reloadSite()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Opens the site and waits a few seconds for a non-expired user in localStorage. */
    private suspend fun reloadSite(): Token? = runWebView(timeout = 30.seconds) {
        var polling = false
        onPageFinished {
            if (polling) return@onPageFinished
            polling = true
            var tries = 0
            poll(1.seconds) {
                evaluateJs(READ_JS) { value ->
                    val found = parse(value.parseAs<String?>())
                    if (found != null && !found.expired()) {
                        resolve(found)
                    } else if (++tries >= RENEW_TRIES) {
                        resolve(null)
                    }
                }
            }
        }
        loadUrl(SITE)
    }

    private fun parse(json: String?): Token? {
        val user = json?.parseAs<JsonObject>() ?: return null
        val value = user["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        return Token(value, user["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L)
    }

    private companion object {
        const val SITE = "https://www.wuxiaworld.com"
        const val KEY = "oidc.user:https://identity.wuxiaworld.com:wuxiaworld_spa"
        val READ_JS = "(function(){try{return localStorage.getItem(${KEY.toJsonString()})}catch(e){return null}})()"
        const val EXPIRY_MARGIN = 30L
        const val RECHECK_MS = 60_000L
        const val RENEW_TRIES = 8
    }
}
