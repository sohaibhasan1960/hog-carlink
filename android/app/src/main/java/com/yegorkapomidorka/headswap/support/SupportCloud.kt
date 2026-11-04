package com.yegorkapomidorka.headswap.support

import android.content.Context
import com.yegorkapomidorka.headswap.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * HOG CarLink: запросы поддержки через Firebase (проект hog-carlink) без SDK - REST Identity Toolkit + Firestore
 * на HttpURLConnection и org.json. Клиент входит анонимно; владелец - тот, чей uid записан в owners/{uid}
 * (создаётся один раз кодом владельца, проверка в правилах Firestore). Ключ API не секрет: защищают правила.
 * Все сетевые вызовы блокирующие - только не с главной нити.
 */
class SupportCloud(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("support", Context.MODE_PRIVATE)

    data class Request(
        val id: String,
        val uid: String,
        val name: String,
        val phone: String,
        val car: String,
        val app: String,
        val text: String,
        val report: String,
        val status: String,
        val createdAt: String,
        val reply: String,
        val repliedAt: String,
    )

    val uid: String? get() = sp.getString(K_UID, null)

    /** Владелец подтверждён на этом телефоне (owners/{uid} существует). */
    var isOwner: Boolean
        get() = sp.getBoolean(K_OWNER, false)
        private set(v) = sp.edit().putBoolean(K_OWNER, v).apply()

    /** Время последнего просмотренного владельцем запроса (RFC 3339) - для уведомлений о новых. */
    var ownerSeen: String
        get() = sp.getString(K_OWNER_SEEN, "") ?: ""
        set(v) = sp.edit().putString(K_OWNER_SEEN, v).apply()

    /** Ответы, которые клиент уже видел (id запроса -> repliedAt). */
    fun replySeen(id: String): String = sp.getString(K_REPLY_SEEN + id, "") ?: ""
    fun markReplySeen(id: String, at: String) = sp.edit().putString(K_REPLY_SEEN + id, at).apply()

    /** Клиент отправлял запросы - фоновой проверке есть что смотреть. */
    val hasSent: Boolean get() = sp.getBoolean(K_SENT, false)

    // ---------------- вход ----------------

    @Throws(IOException::class)
    @Synchronized
    fun token(): String {
        val now = System.currentTimeMillis()
        val cached = sp.getString(K_ID_TOKEN, null)
        if (cached != null && sp.getLong(K_EXPIRES, 0) - now > REFRESH_MARGIN_MS) return cached
        val refresh = sp.getString(K_REFRESH, null)
        if (refresh != null) {
            try {
                val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
                val r = http("POST", "$SECURE_TOKEN?key=$API_KEY", body, null, form = true)
                return store(r.getString("id_token"), r.getString("refresh_token"), r.getString("user_id"), r.getString("expires_in"))
            } catch (e: HttpError) {
                AppLog.i("поддержка: токен не обновился (${e.code}), вхожу заново", true)
            }
        }
        val r = http("POST", "$SIGN_UP?key=$API_KEY", JSONObject().put("returnSecureToken", true).toString(), null)
        isOwner = false
        return store(r.getString("idToken"), r.getString("refreshToken"), r.getString("localId"), r.getString("expiresIn"))
    }

    /** Вход Google привязан к этому телефону: почта и имя для экрана поддержки; пусто - анонимно. */
    val googleEmail: String get() = sp.getString(K_G_EMAIL, "") ?: ""
    val googleName: String get() = sp.getString(K_G_NAME, "") ?: ""

    /**
     * Вход Google (ID-токен из Credential Manager): привязать к текущему анонимному аккаунту - uid, владелец и запросы
     * сохраняются. Если этот Google уже привязан к другому аккаунту (вход на втором телефоне или после переустановки) -
     * войти в тот аккаунт: видны его запросы, статус владельца проверяется заново.
     */
    @Throws(IOException::class)
    @Synchronized
    fun signInWithGoogle(googleIdToken: String) {
        val post = "id_token=" + URLEncoder.encode(googleIdToken, "UTF-8") + "&providerId=google.com"
        fun body(link: Boolean): String {
            val o = JSONObject().put("postBody", post).put("requestUri", "http://localhost")
                .put("returnSecureToken", true).put("returnIdpCredential", true)
            if (link) o.put("idToken", token())
            return o.toString()
        }
        val r = try {
            http("POST", "$SIGN_IN_IDP?key=$API_KEY", body(link = true), null)
        } catch (e: HttpError) {
            if (!e.body.contains("FEDERATED_USER_ID_ALREADY_LINKED") && !e.body.contains("CREDENTIAL_TOO_OLD")) throw e
            AppLog.i("поддержка: этот Google уже у другого аккаунта, вхожу в него", true)
            val other = http("POST", "$SIGN_IN_IDP?key=$API_KEY", body(link = false), null)
            isOwner = false
            other
        }
        store(r.getString("idToken"), r.getString("refreshToken"), r.getString("localId"), r.optString("expiresIn", "3600"))
        sp.edit().putString(K_G_EMAIL, r.optString("email")).putString(K_G_NAME, r.optString("displayName"))
            .putBoolean(K_SENT, true).apply()
        AppLog.i("поддержка: вход Google выполнен", true)
    }

    private fun store(id: String, refresh: String, uid: String, expiresIn: String): String {
        sp.edit().putString(K_ID_TOKEN, id).putString(K_REFRESH, refresh).putString(K_UID, uid)
            .putLong(K_EXPIRES, System.currentTimeMillis() + (expiresIn.toLongOrNull() ?: 3600L) * 1000L).apply()
        return id
    }

    // ---------------- клиент ----------------

    /** Новый запрос; true = записан. */
    @Throws(IOException::class)
    fun send(name: String, phone: String, car: String, app: String, text: String, report: String) {
        val t = token()
        val id = UUID.randomUUID().toString().replace("-", "").take(20)
        val fields = JSONObject()
            .put("uid", sv(uid!!)).put("name", sv(name.take(80))).put("phone", sv(phone.take(30)))
            .put("car", sv(car.take(120))).put("app", sv(app.take(40))).put("text", sv(text.take(2000)))
            .put("report", sv(report.take(MAX_REPORT))).put("status", sv("new"))
        val write = JSONObject()
            .put("update", JSONObject().put("name", "$DOC_ROOT/requests/$id").put("fields", fields))
            .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "createdAt").put("setToServerValue", "REQUEST_TIME")))
            .put("currentDocument", JSONObject().put("exists", false))
        http("POST", "$BASE:commit", JSONObject().put("writes", JSONArray().put(write)).toString(), t)
        sp.edit().putBoolean(K_SENT, true).apply()
    }

    /** Свои запросы, новые сверху. */
    @Throws(IOException::class)
    fun mine(): List<Request> {
        val t = token()
        val q = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "requests")))
            .put("where", JSONObject().put("fieldFilter", JSONObject()
                .put("field", JSONObject().put("fieldPath", "uid")).put("op", "EQUAL").put("value", sv(uid!!)))))
        return parseQuery(httpArray("$DOC_BASE:runQuery", q.toString(), t)).sortedByDescending { it.createdAt }
    }

    // ---------------- владелец ----------------

    /** Ввести код владельца. true = этот телефон теперь владелец. */
    @Throws(IOException::class)
    fun claimOwner(code: String): Boolean {
        val t = token()
        val fields = JSONObject().put("code", sv(code)).put("at", JSONObject().put("timestampValue", rfc3339(Date())))
        return try {
            http("PATCH", "$DOC_BASE/owners/${uid!!}", JSONObject().put("fields", fields).toString(), t)
            isOwner = true
            true
        } catch (e: HttpError) {
            if (e.code == 403) false else throw e
        }
    }

    /** Проверить, что запись владельца на месте (после переустановки uid другой - нужен код снова). */
    @Throws(IOException::class)
    fun checkOwner(): Boolean {
        val t = token()
        val ok = try {
            http("GET", "$DOC_BASE/owners/${uid!!}", null, t)
            true
        } catch (e: HttpError) {
            if (e.code == 403 || e.code == 404) false else throw e
        }
        isOwner = ok
        return ok
    }

    /** Все запросы, новые сверху (только владелец). */
    @Throws(IOException::class)
    fun all(limit: Int = 100): List<Request> {
        val t = token()
        val q = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "requests")))
            .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "createdAt")).put("direction", "DESCENDING")))
            .put("limit", limit))
        return parseQuery(httpArray("$DOC_BASE:runQuery", q.toString(), t))
    }

    @Throws(IOException::class)
    fun reply(id: String, text: String, status: String = "answered") {
        val t = token()
        val fields = JSONObject().put("reply", sv(text.take(2000))).put("status", sv(status))
            .put("repliedAt", JSONObject().put("timestampValue", rfc3339(Date())))
        val mask = listOf("reply", "repliedAt", "status").joinToString("&") { "updateMask.fieldPaths=$it" }
        http("PATCH", "$DOC_BASE/requests/$id?$mask", JSONObject().put("fields", fields).toString(), t)
    }

    @Throws(IOException::class)
    fun delete(id: String) {
        http("DELETE", "$DOC_BASE/requests/$id", null, token())
    }

    // ---------------- сеть ----------------

    class HttpError(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

    private fun httpArray(url: String, body: String, token: String): JSONArray = JSONArray(raw("POST", url, body, token, false))

    private fun http(method: String, url: String, body: String?, token: String?, form: Boolean = false): JSONObject {
        val s = raw(method, url, body, token, form)
        return if (s.isBlank()) JSONObject() else JSONObject(s)
    }

    private fun raw(method: String, url: String, body: String?, token: String?, form: Boolean): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            // PATCH у HttpURLConnection нет: через POST с переопределением метода (Google API его принимают)
            if (method == "PATCH") {
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                c.requestMethod = method
            }
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json; charset=utf-8")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val PROJECT = "hog-carlink"
        /** Ключ Firebase из google-services.json: не секрет, доступ ограничивают правила Firestore. */
        const val API_KEY = "AIzaSyBqRSUQsj0ans4q_p5Bs3X99uEZ8QVLFpQ"
        private const val SIGN_UP = "https://identitytoolkit.googleapis.com/v1/accounts:signUp"
        private const val SECURE_TOKEN = "https://securetoken.googleapis.com/v1/token"
        private const val SIGN_IN_IDP = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp"
        /** OAuth-клиент «Web» проекта hog-carlink (Firebase: Authentication -> Google -> Web SDK configuration). Не секрет. */
        const val GOOGLE_WEB_CLIENT_ID = "30265274156-62atbm23nothts3vljnree5hop4ora87.apps.googleusercontent.com"
        const val DOC_ROOT = "projects/$PROJECT/databases/(default)/documents"
        private const val BASE = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"
        private const val DOC_BASE = BASE
        const val MAX_REPORT = 100_000
        private const val TIMEOUT_MS = 15_000
        private const val REFRESH_MARGIN_MS = 5 * 60_000L

        private const val K_ID_TOKEN = "idToken"
        private const val K_REFRESH = "refreshToken"
        private const val K_UID = "uid"
        private const val K_EXPIRES = "expiresAt"
        private const val K_OWNER = "owner"
        private const val K_G_EMAIL = "googleEmail"
        private const val K_G_NAME = "googleName"
        private const val K_OWNER_SEEN = "ownerSeen"
        private const val K_REPLY_SEEN = "replySeen:"
        private const val K_SENT = "sent"

        fun sv(s: String): JSONObject = JSONObject().put("stringValue", s)

        fun rfc3339(d: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

        /** Значение поля Firestore как строка (stringValue / timestampValue), иначе "". */
        fun field(fields: JSONObject?, name: String): String {
            val v = fields?.optJSONObject(name) ?: return ""
            return v.optString("stringValue", v.optString("timestampValue", ""))
        }

        /** Ответ runQuery: элементы без document (пустой результат) пропускаются. */
        fun parseQuery(arr: JSONArray): List<Request> {
            val out = ArrayList<Request>()
            for (i in 0 until arr.length()) {
                val doc = arr.optJSONObject(i)?.optJSONObject("document") ?: continue
                val f = doc.optJSONObject("fields")
                out += Request(
                    id = doc.optString("name").substringAfterLast('/'),
                    uid = field(f, "uid"), name = field(f, "name"), phone = field(f, "phone"), car = field(f, "car"),
                    app = field(f, "app"), text = field(f, "text"), report = field(f, "report"),
                    status = field(f, "status"), createdAt = field(f, "createdAt"),
                    reply = field(f, "reply"), repliedAt = field(f, "repliedAt"),
                )
            }
            return out
        }
    }
}
