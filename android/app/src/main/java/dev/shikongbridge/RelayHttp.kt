package dev.shikongbridge

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 给 relay 发 HTTP 命令（档位、模式、全停）和拉状态。relay 是唯一真身；
 * 滑块另有「本地优先」直驱广播那一路，见 [BridgeLink]。
 */
object RelayHttp {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    fun postSet(settings: Settings, suck: Int?, invib: Int?, onFail: (String) -> Unit) {
        val body = JSONObject()
        if (suck != null) body.put("suck", suck)
        if (invib != null) body.put("invib", invib)
        post(settings, "/api/set", body, onFail)
    }

    fun postStop(settings: Settings, onFail: (String) -> Unit) {
        post(settings, "/api/stop", JSONObject(), onFail)
    }

    /** 跑模式。part = "suck" / "invib" / "both"。 */
    fun postPattern(settings: Settings, part: String, name: String, onFail: (String) -> Unit) {
        post(settings, "/api/pattern", JSONObject().put("part", part).put("name", name), onFail)
    }

    /** 停模式，档位保持当前值。 */
    fun postPatternStop(settings: Settings, part: String, onFail: (String) -> Unit) {
        post(settings, "/api/pattern/stop", JSONObject().put("part", part), onFail)
    }

    /** 拉模式表（进控制页时拉一次）。 */
    fun getPatterns(settings: Settings, onOk: (List<Pattern>) -> Unit, onFail: (String) -> Unit) {
        get(settings, "/api/patterns", { body -> onOk(Patterns.parse(body)) }, onFail)
    }

    /** 拉状态（控制页每 2 秒一次，为了拿「在跑哪个模式」——WS 推的 state 里没有这个字段）。 */
    fun getState(settings: Settings, onOk: (RelaySnapshot) -> Unit, onFail: (String) -> Unit) {
        get(settings, "/api/state", { body ->
            val snap = RelaySnapshot.parse(body)
            if (snap == null) onFail("服务器返回的状态看不懂") else onOk(snap)
        }, onFail)
    }

    private fun get(
        settings: Settings,
        path: String,
        onBody: (String) -> Unit,
        onFail: (String) -> Unit,
    ) {
        val builder = build(settings, path, onFail) ?: return
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFail("问服务器失败：" + e.javaClass.simpleName + ": " + (e.message ?: ""))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onFail("服务器返回 " + it.code + (if (it.code == 401) "（口令不对）" else ""))
                        return
                    }
                    val body = try {
                        it.body?.string() ?: ""
                    } catch (e: Exception) {
                        onFail("读服务器返回失败：" + (e.message ?: ""))
                        return
                    }
                    onBody(body)
                }
            }
        })
    }

    private fun post(settings: Settings, path: String, body: JSONObject, onFail: (String) -> Unit) {
        val builder = build(settings, path, onFail) ?: return
        builder.post(body.toString().toRequestBody(json))

        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFail("发给服务器失败：" + e.javaClass.simpleName + ": " + (e.message ?: ""))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onFail("服务器返回 " + it.code + (if (it.code == 401) "（口令不对）" else ""))
                    }
                }
            }
        })
    }

    private fun build(
        settings: Settings,
        path: String,
        onFail: (String) -> Unit,
    ): Request.Builder? {
        val url = try {
            RelayUrls.api(settings.wsUrl, path)
        } catch (e: Exception) {
            onFail("服务器地址不对：" + (e.message ?: ""))
            return null
        }
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + settings.token)
    }
}
