// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.vr

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*

/** Brücke zur OpenXR-Schicht in viecity-xr.cpp. */
object XrBridge
{
	init
	{
		System.loadLibrary("viecity-xr")
	}

	external fun nativeStart(activity: VrStreamActivity)
	external fun nativeStop()
	external fun nativeHaptic(hand: Int, amplitude: Float, durationMs: Int)
	external fun nativeRecenter()
	external fun nativeSetScreen(radius: Float, angle: Float, height: Float)
}

/**
 * Stream in VR: Leinwand im Raum, Touch-Controller als DualSense, Kopf als Kamera.
 * onXr* wird vom XR-Thread aufgerufen, nicht vom Hauptthread.
 */
class VrStreamActivity : Activity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val TAG = "VieCityVR"
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val mapper = VrInputMapper()

	/**
	 * Stellschrauben von außen, zum Einstellen am Kabel ohne Neubau:
	 * adb shell am broadcast -a com.talentsache.viecity.TUNE --es macro "U:120,_:900,O:70"
	 * Weitere Felder: speed, dead, min, smooth (Kopf-Kamera), radius, angle, height (Leinwand).
	 */
	private val tuneReceiver = object: BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent)
		{
			intent.getStringExtra("macro")?.let {
				val steps = ButtonMacro.parse(it)
				Log.i(TAG, "Tastenfolge von außen: ${steps.size} Schritte")
				mapper.macro.start(steps, System.nanoTime())
			}
			val t = mapper.tuning
			intent.getStringExtra("speed")?.toFloatOrNull()?.let { t.fullStickDegPerSec = it }
			intent.getStringExtra("dead")?.toFloatOrNull()?.let { t.deadDegPerSec = it }
			intent.getStringExtra("min")?.toFloatOrNull()?.let { t.minStick = it }
			intent.getStringExtra("smooth")?.toFloatOrNull()?.let { t.smoothing = it }
			val radius = intent.getStringExtra("radius")?.toFloatOrNull()
			val angle = intent.getStringExtra("angle")?.toFloatOrNull()
			val height = intent.getStringExtra("height")?.toFloatOrNull()
			if(radius != null || angle != null || height != null)
			{
				screenRadius = radius ?: screenRadius
				screenAngle = angle ?: screenAngle
				screenHeight = height ?: screenHeight
				XrBridge.nativeSetScreen(screenRadius, screenAngle, screenHeight)
			}
			Log.i(TAG, "Einstellung: $t Leinwand $screenRadius $screenAngle $screenHeight")
		}
	}
	private var screenRadius = 2.6f
	private var screenAngle = 1.75f
	private var screenHeight = 0f

	@Volatile private var session: Session? = null
	@Volatile private var surface: Surface? = null
	private var xrStarted = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		mapper.onRecenter = { XrBridge.nativeRecenter() }
		mapper.onNotice = { text ->
			Log.i(TAG, text)
			XrBridge.nativeHaptic(0, 0.4f, 60)
		}

		try
		{
			val preferences = Preferences(this)
			val created = Session(connectInfo, LogManager(this).createNewFile().file.absolutePath, preferences.logVerbose)
			created.eventCallback = this::sessionEvent
			created.start()
			session = created
		}
		catch(e: CreateError)
		{
			Toast.makeText(this, "Verbindung konnte nicht aufgebaut werden: ${e.errorCode}", Toast.LENGTH_LONG).show()
			finish()
			return
		}

		XrBridge.nativeStart(this)
		xrStarted = true
		ContextCompat.registerReceiver(this, tuneReceiver, IntentFilter("com.talentsache.viecity.TUNE"), ContextCompat.RECEIVER_EXPORTED)
	}

	override fun onDestroy()
	{
		// Erst den Decoder von der Leinwand lösen, dann die Leinwand abbauen.
		val closing = session
		session = null
		closing?.setSurface(null)
		if(xrStarted)
		{
			xrStarted = false
			unregisterReceiver(tuneReceiver)
			XrBridge.nativeStop()
		}
		closing?.stop()
		closing?.dispose()
		super.onDestroy()
	}

	private fun sessionEvent(event: Event)
	{
		when(event)
		{
			is RumbleEvent ->
			{
				XrBridge.nativeHaptic(0, event.left.toInt() / 255f, 250)
				XrBridge.nativeHaptic(1, event.right.toInt() / 255f, 250)
			}
			is QuitEvent ->
			{
				Log.i(TAG, "Stream beendet: ${event.reason} ${event.reasonString ?: ""}")
				mainHandler.post { finish() }
			}
			is LoginPinRequestEvent -> Log.w(TAG, "Die PS5 verlangt eine Login-PIN. Bitte erst in der 2D-Ansicht verbinden.")
			else -> {}
		}
	}

	// --- Aufrufe vom XR-Thread ---

	@Suppress("unused")
	fun onXrSurface(surface: Surface)
	{
		this.surface = surface
		session?.setSurface(surface)
	}

	@Suppress("unused")
	fun onXrFrame(values: FloatArray)
	{
		val state = mapper.map(XrFrame(values), System.nanoTime())
		session?.setControllerState(state)
	}

	@Suppress("unused")
	fun onXrExit()
	{
		mainHandler.post { if(!isFinishing) finish() }
	}
}
