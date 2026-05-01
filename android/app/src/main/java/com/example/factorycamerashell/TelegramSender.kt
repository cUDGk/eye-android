package com.example.factorycamerashell

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

sealed class SendOutcome {
    data class Success(val description: String) : SendOutcome()
    data class Failure(
        val code: String,
        val titleJa: String,
        val titleEn: String,
        val detail: String,
        val hintJa: String,
        val hintEn: String
    ) : SendOutcome() {
        fun toCopyableText(): String = buildString {
            appendLine(code)
            appendLine("$titleJa / $titleEn")
            appendLine()
            appendLine("詳細 / Detail:")
            appendLine(detail)
            appendLine()
            appendLine("考えられる原因 / Likely cause:")
            appendLine("[JP] $hintJa")
            appendLine("[EN] $hintEn")
        }
    }
}

object TelegramSender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun sendPhoto(
        context: Context,
        imageUri: Uri,
        resizeMax: Int = 0,
        userContext: String = ""
    ): SendOutcome = withContext(Dispatchers.IO) {
        val tempFile = try {
            copyUriToCache(context, imageUri)
        } catch (e: IOException) {
            return@withContext fileReadFailure(e)
        }

        try {
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    tempFile.name,
                    tempFile.asJpegBody()
                )
            if (resizeMax > 0) {
                builder.addFormDataPart("resize_max", resizeMax.toString())
            }
            if (userContext.isNotBlank()) {
                builder.addFormDataPart("context", userContext)
            }
            val body = builder.build()

            val request = Request.Builder()
                .url(Secrets.EYE_SERVER_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext httpFailure(response.code, bodyText)
                }
                val text = parseDescription(bodyText)
                SendOutcome.Success(text)
            }
        } catch (e: SocketTimeoutException) {
            timeoutFailure(e)
        } catch (e: ConnectException) {
            connectFailure(e)
        } catch (e: UnknownHostException) {
            unknownHostFailure(e)
        } catch (e: SSLException) {
            sslFailure(e)
        } catch (e: SocketException) {
            socketAbortFailure(e)
        } catch (e: IOException) {
            ioFailure(e)
        } catch (e: Exception) {
            unknownFailure(e)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun fileReadFailure(e: IOException) = SendOutcome.Failure(
        code = "E_FILE_READ",
        titleJa = "撮影画像が読めません",
        titleEn = "Cannot read captured image",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "ストレージ権限が拒否されたか、キャッシュ領域が破損している可能性。アプリを再起動してください。",
        hintEn = "Storage permission denied or cache corrupted. Restart the app."
    )

    private fun timeoutFailure(e: SocketTimeoutException) = SendOutcome.Failure(
        code = "E_TIMEOUT",
        titleJa = "タイムアウト",
        titleEn = "Timeout",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "PC側のLLM処理が180秒以内に終わらなかった。画像が大きすぎる、PCが重い、もしくは処理中にWiFi切れた可能性。",
        hintEn = "Server LLM did not respond within 180s. Image too large, PC overloaded, or network dropped."
    )

    private fun connectFailure(e: ConnectException) = SendOutcome.Failure(
        code = "E_CONNECT",
        titleJa = "PCに接続できません",
        titleEn = "Cannot connect to PC",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "Tailscale ON か確認、PC側のサーバーが起動してるか確認。Tailscale管理画面でこの端末・PC両方とも online か見る。",
        hintEn = "Check Tailscale is ON, server is running on PC. Verify both ends are online in Tailscale admin."
    )

    private fun unknownHostFailure(e: UnknownHostException) = SendOutcome.Failure(
        code = "E_UNKNOWN_HOST",
        titleJa = "ホスト名が解決できません",
        titleEn = "Cannot resolve host",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "Tailscale が切断されているか、サーバーURLが間違っている。アプリのSecrets内のURLを確認。",
        hintEn = "Tailscale disconnected, or server URL wrong. Check Secrets.EYE_SERVER_URL."
    )

    private fun sslFailure(e: SSLException) = SendOutcome.Failure(
        code = "E_SSL",
        titleJa = "TLS/SSLエラー",
        titleEn = "TLS/SSL error",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "現状の構成はHTTP直接通信のはずなのでSSLエラーは想定外。中間プロキシが入った可能性。",
        hintEn = "Current setup uses plain HTTP, this is unexpected. Possibly a middlebox/proxy is intercepting."
    )

    private fun socketAbortFailure(e: SocketException) = SendOutcome.Failure(
        code = "E_SOCKET_ABORT",
        titleJa = "接続が中断されました",
        titleEn = "Connection aborted mid-request",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "送信中にTailscaleが切れた / WiFi-モバイル切替 / PC側のサーバーが落ちた / リクエスト処理中に再起動された可能性。再送信で復活する事多し。",
        hintEn = "Mid-flight: Tailscale dropped / WiFi-mobile switched / server crashed / restarted during request. Retry usually fixes."
    )

    private fun ioFailure(e: IOException) = SendOutcome.Failure(
        code = "E_IO",
        titleJa = "I/Oエラー",
        titleEn = "I/O error",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "汎用ネットワーク/IOエラー。詳細を見て判断。再送信を試して。",
        hintEn = "Generic network/IO error. Check detail. Retry first."
    )

    private fun unknownFailure(e: Exception) = SendOutcome.Failure(
        code = "E_UNKNOWN",
        titleJa = "想定外のエラー",
        titleEn = "Unexpected error",
        detail = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
        hintJa = "予期しない例外。スタックトレース込みでバグ報告に使える。",
        hintEn = "Unexpected exception. Use this output for bug reports."
    )

    private fun httpFailure(code: Int, body: String): SendOutcome.Failure {
        val (titleJa, titleEn, hintJa, hintEn) = when (code) {
            400 -> Quad(
                "リクエスト不正", "Bad request",
                "サーバー側で画像を受け付けられなかった。content-type が image/jpeg で送られているか確認。",
                "Server rejected the request. Verify content-type is image/jpeg."
            )
            401, 403 -> Quad(
                "認証エラー", "Authentication failed",
                "サーバー側でアクセス制御を入れた場合、トークンを確認。",
                "If server has auth, check token."
            )
            413 -> Quad(
                "画像が大きすぎる", "Payload too large",
                "サーバーの上限を超過。リバプロやFastAPIのアップロード上限を確認。",
                "Image exceeds server upload limit. Check reverse proxy / FastAPI body limit."
            )
            502 -> Quad(
                "サーバー上流エラー", "Upstream error (server reported)",
                "PC側 eye-server から llama-server か Telegram への送信が失敗。サーバーのログを見る。bodyに具体的な失敗内容が入ってる事多い。",
                "eye-server's call to llama-server or Telegram failed. Check server logs. Body usually has specifics."
            )
            in 500..599 -> Quad(
                "サーバー内部エラー", "Server internal error",
                "PC側のサーバーで例外発生。bodyに詳細あり。",
                "Server crashed. Body has detail."
            )
            else -> Quad(
                "HTTP $code エラー", "HTTP $code error",
                "サーバーが想定外のステータスコードを返した。",
                "Server returned an unexpected HTTP status."
            )
        }
        return SendOutcome.Failure(
            code = "HTTP_$code",
            titleJa = titleJa,
            titleEn = titleEn,
            detail = "HTTP $code\n${body.take(400)}",
            hintJa = hintJa,
            hintEn = hintEn
        )
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    private fun parseDescription(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun File.asJpegBody() = asRequestBody("image/jpeg".toMediaType())

    private fun copyUriToCache(context: Context, uri: Uri): File {
        val outDir = File(context.cacheDir, "eye_outbox").apply { mkdirs() }
        val outFile = File(outDir, "send_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open URI: $uri" }
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}
