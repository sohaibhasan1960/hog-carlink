package com.yegorkapomidorka.headswap.support

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetCustomCredentialOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.yegorkapomidorka.headswap.AppLog
import java.util.concurrent.Executors

/**
 * HOG CarLink: вход Google для поддержки через системный Credential Manager (Android 14+), без Play Services в APK:
 * запрос «Войти через Google» собирается тем же типом и ключами, что у библиотеки googleid (её лицензия несовместима
 * с GPL), ответ - ID-токен Google, который SupportCloud.signInWithGoogle меняет на вход Firebase.
 */
object GoogleSignIn {
    private const val TYPE_ID_TOKEN = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
    private const val TYPE_SIWG = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL"
    private const val KEY_SUBTYPE = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE"
    private const val KEY_SERVER_CLIENT_ID = "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_SERVER_CLIENT_ID"
    private const val KEY_AUTO_SELECT = "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_AUTO_SELECT_ENABLED"
    private const val KEY_ID_TOKEN = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN"

    /** Системный Credential Manager есть с Android 14. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 34

    sealed class Result {
        object Ok : Result()
        object Cancelled : Result()
        data class Failed(val why: String) : Result()
    }

    /** Окно выбора аккаунта Google, затем вход Firebase на рабочей нити; [done] - на главной нити. */
    fun start(act: Activity, done: (Result) -> Unit) {
        if (!supported) {
            done(Result.Failed("Android 14+"))
            return
        }
        val data = Bundle().apply {
            putString(KEY_SERVER_CLIENT_ID, SupportCloud.GOOGLE_WEB_CLIENT_ID)
            putBoolean(KEY_AUTO_SELECT, false)
            putString(KEY_SUBTYPE, TYPE_SIWG)
        }
        val option = GetCustomCredentialOption(TYPE_ID_TOKEN, data, data, true)
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val io = Executors.newSingleThreadExecutor()
        CredentialManager.create(act).getCredentialAsync(act, request, null, io,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    val token = if (c is CustomCredential && c.type == TYPE_ID_TOKEN) c.data.getString(KEY_ID_TOKEN) else null
                    val r = if (token.isNullOrBlank()) {
                        Result.Failed("нет ID-токена (${c.type})")
                    } else try {
                        SupportCloud(act).signInWithGoogle(token)
                        Result.Ok
                    } catch (e: Exception) {
                        AppLog.i("поддержка: вход Google в Firebase не прошёл: ${e.javaClass.simpleName}: ${e.message}", true)
                        Result.Failed(e.message ?: e.javaClass.simpleName)
                    }
                    io.shutdown()
                    act.runOnUiThread { done(r) }
                }

                override fun onError(e: GetCredentialException) {
                    io.shutdown()
                    AppLog.i("поддержка: окно входа Google: ${e.javaClass.simpleName}: ${e.message}", true)
                    act.runOnUiThread { done(if (e is GetCredentialCancellationException) Result.Cancelled else Result.Failed(e.message ?: e.type)) }
                }
            })
    }
}
