// Anonymous device registration for the non-official Hongguo client.
// Protocol fields referenced from qq294716498/HongguoTV-Phone-TV-NoAds,
// native/core/provider_hongguo_device.go , commit 8eafa3908e9191c0a5ae186d5beff11e3a971f3c.
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class PlaybackIdentity {
    internal data class Device(val deviceId: String,val installId: String)
    private var cached: Device?=null

    @Synchronized internal fun get(http: OkHttpClient): Device = cached ?: register(http).also { cached=it }
    @Synchronized internal fun invalidate(device: Device) { if(cached==device) cached=null }

    private fun register(http: OkHttpClient): Device {
        val now=System.currentTimeMillis()
        val header=JSONObject()
        mapOf<String,Any>(
            "display_name" to "红果免费短剧", "aid" to 8662, "app_version" to "7.3.5.32", "version_code" to 73532,
            "manifest_version_code" to 73532, "update_version_code" to 73532, "channel" to "update_64", "package" to "com.phoenix.read",
            "app_language" to "zh", "os" to "Android", "os_version" to "16", "os_api" to 36, "device_model" to "25053RT47C",
            "device_brand" to "Redmi", "device_manufacturer" to "Xiaomi", "cpu_abi" to "arm64-v8a", "density_dpi" to 520,
            "display_density" to "xxhdpi", "resolution" to "2772x1280", "language" to "zh", "timezone" to 8, "access" to "wifi",
            "openudid" to UUID.randomUUID().toString().replace("-","").take(16), "clientudid" to UUID.randomUUID().toString(), "cdid" to UUID.randomUUID().toString(),
            "region" to "CN", "tz_name" to "Asia/Shanghai", "tz_offset" to 28800, "sim_region" to "cn", "device_platform" to "android",
            "not_request_sender" to 0, "is_system_app" to 0, "sdk_flavor" to "china", "rom" to "OS2.0", "rom_version" to "BP2A.250605.031.A3"
        ).forEach { (key,value) -> header.put(key,value) }
        val body=JSONObject().put("magic_tag","ss_app_log").put("header",header).put("_gen_time",now).toString()
        val url="https://log.snssdk.com/service/2/device_register/".toHttpUrl().newBuilder().apply {
            val params=linkedMapOf("aid" to "8662", "app_name" to "novelread", "version_code" to "73532", "version_name" to "7.3.5.32",
                "manifest_version_code" to "73532", "update_version_code" to "73532", "channel" to "update_64", "device_platform" to "android",
                "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi", "language" to "zh", "os_api" to "36",
                "os_version" to "16", "resolution" to "1280*2772", "dpi" to "520", "ac" to "wifi", "req_id" to UUID.randomUUID().toString(), "_rticket" to now.toString())
            params.forEach { (key,value) -> addQueryParameter(key,value) }
        }.build()
        val request=Request.Builder().url(url).header("User-Agent","com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)")
            .header("Accept","application/json").post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
            val raw=response.body?.string().orEmpty()
            if(raw.length>65536 || raw.isBlank()) throw IOException("设备注册响应为空或过大")
            val obj=try { JSONObject(raw) } catch(e: Exception) { throw IOException("设备注册响应格式异常",e) }
            val deviceId=obj.str("device_id_str").ifBlank { obj.str("device_id") }
            val installId=obj.str("install_id_str").ifBlank { obj.str("install_id") }
            val valid=Regex("[0-9]{1,30}")
            if(!valid.matches(deviceId) || !valid.matches(installId) || deviceId=="0" || installId=="0") throw IOException("设备注册未返回有效身份")
            Device(deviceId,installId)
        }
    }
}
