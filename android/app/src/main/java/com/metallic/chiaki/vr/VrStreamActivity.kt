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

/** Bridge to the OpenXR layer in viecity-xr.cpp. */
object XrBridge
{
	init
	{
		System.loadLibrary("viecity-xr")
	}

	external fun nativeStart(activity: VrStreamActivity, environment: java.nio.ByteBuffer?, width: Int, height: Int,
		hint: java.nio.ByteBuffer?, hintWidth: Int, hintHeight: Int)
	external fun nativeShowHint(visible: Boolean)
	external fun nativeStop()
	external fun nativeHaptic(hand: Int, amplitude: Float, durationMs: Int)
	external fun nativeRecenter()
	external fun nativeSetScreen(radius: Float, angle: Float, height: Float)
	external fun nativeSetFollow(follow: Boolean, tau: Float)
	external fun nativeSetAmbient(ambient: Float)
}

/**
 * Stream in VR: screen in the room, Touch controllers as DualSense, head as camera.
 * onXr* is called from the XR thread, not from the main thread.
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
	 * Tuning values from outside, for adjusting over the cable without a rebuild:
	 * adb shell am broadcast -a com.talentsache.viecity.TUNE --es macro "U:120,_:900,O:70"
	 * More fields: speed, dead, min, smooth (head camera), radius, angle, height (screen),
	 * follow 0/1 and tau (screen follows the head, inertia in seconds), ambient 0 to 1 (ambient light).
	 */
	private val tuneReceiver = object: BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent)
		{
			intent.getStringExtra("macro")?.let {
				val steps = ButtonMacro.parse(it)
				Log.i(TAG, "Button sequence from outside: ${steps.size} steps")
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
			intent.getStringExtra("tau")?.toFloatOrNull()?.let {
				followTau = it
				XrBridge.nativeSetFollow(t.headMode == HeadMode.FOLLOW, followTau)
			}
			intent.getStringExtra("mode")?.let { name ->
				HeadMode.values().firstOrNull { it.name.equals(name, true) }?.let {
					t.headMode = it
					mapper.resetPeekReference()
					XrBridge.nativeSetFollow(it == HeadMode.FOLLOW, followTau)
				}
			}
			intent.getStringExtra("peekdead")?.toFloatOrNull()?.let { t.peekDeadDeg = it }
			intent.getStringExtra("peekfull")?.toFloatOrNull()?.let { t.peekFullDeg = it }
			intent.getStringExtra("ambient")?.toFloatOrNull()?.let { XrBridge.nativeSetAmbient(it) }
			intent.getStringExtra("follow")?.let { XrBridge.nativeSetFollow(it == "1", followTau) }
			Log.i(TAG, "Setting: $t screen $screenRadius $screenAngle $screenHeight")
		}
	}
	private var screenRadius = 2.0f
	private var screenAngle = 2.2f
	private var screenHeight = 0f
	private var followTau = 0.18f
	private val settings by lazy { getSharedPreferences("viecity_vr", Context.MODE_PRIVATE) }

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

		mapper.onLeave = { mainHandler.post { finish() } }
		mapper.onRecenter = {
			XrBridge.nativeRecenter()
			mapper.resetPeekReference()
		}
		screenAngle = settings.getFloat("screen_angle", screenAngle)
		mapper.onZoom = { delta ->
			// Screen size as an arc, between a small TV and almost the whole field of view.
			screenAngle = (screenAngle * (1f + delta * 0.6f)).coerceIn(1.0f, 2.9f)
			XrBridge.nativeSetScreen(screenRadius, screenAngle, screenHeight)
			settings.edit().putFloat("screen_angle", screenAngle).apply()
		}
		mapper.onNotice = { text ->
			Log.i(TAG, text)
			XrBridge.nativeHaptic(0, 0.4f, 60)
			// With the head camera the screen follows the head, without it the screen stays fixed in the room.
			XrBridge.nativeSetFollow(mapper.tuning.headMode == HeadMode.FOLLOW, followTau)
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
			Toast.makeText(this, "Could not establish connection: ${e.errorCode}", Toast.LENGTH_LONG).show()
			finish()
			return
		}

		val environment = loadEnvironment()
		XrBridge.nativeStart(this, environment?.first, environment?.second ?: 0, environment?.third ?: 0,
			VrHelpPanel.render(this), VrHelpPanel.WIDTH, VrHelpPanel.HEIGHT)
		xrStarted = true
		// The help shows briefly at start and returns while the menu button is held.
		mapper.onHelp = { visible -> XrBridge.nativeShowHint(visible) }
		mainHandler.postDelayed({ if(xrStarted && !mapper.helpHeld) XrBridge.nativeShowHint(false) }, 9000)
		XrBridge.nativeSetScreen(screenRadius, screenAngle, screenHeight)
		ContextCompat.registerReceiver(this, tuneReceiver, IntentFilter("com.talentsache.viecity.TUNE"), ContextCompat.RECEIVER_EXPORTED)
	}

	override fun onDestroy()
	{
		// Detach the decoder from the screen first, then tear down the screen.
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

	/** Panorama of the surroundings as RGBA bytes. If missing, the room stays black. */
	private fun loadEnvironment(): Triple<java.nio.ByteBuffer, Int, Int>? = try
	{
		val bitmap = assets.open("environment.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }
		val buffer = java.nio.ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
		bitmap.copyPixelsToBuffer(buffer)
		val result = Triple(buffer, bitmap.width, bitmap.height)
		bitmap.recycle()
		result
	}
	catch(e: Exception)
	{
		Log.w(TAG, "Could not load surroundings", e)
		null
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
				Log.i(TAG, "Stream ended: ${event.reason} ${event.reasonString ?: ""}")
				mainHandler.post { finish() }
			}
			is LoginPinRequestEvent -> Log.w(TAG, "The PS5 requires a login PIN. Please connect in the 2D view first.")
			else -> {}
		}
	}

	// --- Calls from the XR thread ---

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
