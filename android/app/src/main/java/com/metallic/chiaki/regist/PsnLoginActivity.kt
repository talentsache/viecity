// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Holt die PSN-Konto-ID über die Anmeldung bei Sony, damit sie niemand von Hand
 * nachschlagen und abtippen muss. Das Passwort wird nur auf der Sony-Seite eingegeben.
 * Derselbe Ablauf wie scripts/psn-account-id.py.
 */
class PsnLoginActivity: AppCompatActivity()
{
	companion object
	{
		const val RESULT_ACCOUNT_ID = "account_id"

		private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		private const val REDIRECT_URL = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
		private const val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
			"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID" +
			"&redirect_uri=$REDIRECT_URL&scope=psn:clientapp&request_locale=en_US&ui=pr" +
			"&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"

		/** Kontonummer als 8 Bytes Little Endian, Base64. So erwartet sie die Kopplung. */
		fun encodeAccountId(userId: Long): String =
			Base64.encodeToString(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(userId).array(), Base64.NO_WRAP)
	}

	private var exchanging = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val webView = WebView(this)
		setContentView(webView)
		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		webView.webViewClient = object: WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean
			{
				val url = request.url
				if(!url.toString().startsWith(REDIRECT_URL))
					return false
				handleRedirect(url)
				return true
			}
		}
		webView.loadUrl(LOGIN_URL)
	}

	private fun handleRedirect(url: Uri)
	{
		if(exchanging)
			return
		val code = url.getQueryParameter("code")
		if(code.isNullOrEmpty())
		{
			fail()
			return
		}
		exchanging = true
		thread {
			val accountId = try { fetchAccountId(code) } catch(e: Exception) { null }
			runOnUiThread {
				if(accountId == null)
					fail()
				else
				{
					setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_ACCOUNT_ID, accountId))
					finish()
				}
			}
		}
	}

	private fun fail()
	{
		Toast.makeText(this, com.metallic.chiaki.R.string.regist_psn_login_failed, Toast.LENGTH_LONG).show()
		setResult(Activity.RESULT_CANCELED)
		finish()
	}

	private fun fetchAccountId(code: String): String
	{
		val auth = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

		val tokenConnection = URL(TOKEN_URL).openConnection() as HttpURLConnection
		tokenConnection.requestMethod = "POST"
		tokenConnection.doOutput = true
		tokenConnection.connectTimeout = 20000
		tokenConnection.readTimeout = 20000
		tokenConnection.setRequestProperty("Authorization", auth)
		tokenConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
		tokenConnection.outputStream.use {
			it.write("grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}&redirect_uri=$REDIRECT_URL&".toByteArray())
		}
		val token = JSONObject(tokenConnection.inputStream.bufferedReader().use { it.readText() }).getString("access_token")

		val infoConnection = URL(TOKEN_URL + "/" + URLEncoder.encode(token, "UTF-8")).openConnection() as HttpURLConnection
		infoConnection.connectTimeout = 20000
		infoConnection.readTimeout = 20000
		infoConnection.setRequestProperty("Authorization", auth)
		val info = JSONObject(infoConnection.inputStream.bufferedReader().use { it.readText() })
		return encodeAccountId(info.getString("user_id").toLong())
	}
}
