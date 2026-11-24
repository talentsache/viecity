// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.vr

import com.metallic.chiaki.lib.ControllerState
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.sign

/**
 * Ein Bild Eingabe von der Quest. Der Aufbau des Felds muss zu viecity-xr.cpp passen.
 */
class XrFrame(private val f: FloatArray)
{
	companion object
	{
		const val BTN_A = 1
		const val BTN_B = 2
		const val BTN_X = 4
		const val BTN_Y = 8
		const val BTN_LSTICK = 16
		const val BTN_RSTICK = 32
		const val BTN_MENU = 64

		const val VALID_HEAD = 1
		const val VALID_RIGHT = 2
		const val VALID_LEFT = 4
	}

	val leftX get() = f[21]
	val leftY get() = f[22]
	val rightX get() = f[23]
	val rightY get() = f[24]
	val leftTrigger get() = f[25]
	val rightTrigger get() = f[26]
	val leftGrip get() = f[27]
	val rightGrip get() = f[28]
	val buttons get() = f[29].toInt()
	val valid get() = f[30].toInt()

	fun pressed(button: Int) = buttons and button != 0

	/** Gier und Neigung in Radiant, aus der Blickrichtung q * (0, 0, -1). */
	private fun yawPitch(offset: Int): Pair<Float, Float>
	{
		val x = f[offset]; val y = f[offset + 1]; val z = f[offset + 2]; val w = f[offset + 3]
		val fx = -(2f * (x * z + w * y))
		val fy = -(2f * (y * z - w * x))
		val fz = -(1f - 2f * (x * x + y * y))
		// Gier wächst bei Drehung nach rechts.
		return Pair(atan2(fx, -fz), asin(fy.coerceIn(-1f, 1f)))
	}

	val head get() = yawPitch(0)
	val rightHand get() = yawPitch(7)
}

/** Stellschrauben für Kopf-Kamera und Zielen mit der Hand. */
data class VrTuning(
	/** Kopf steuert die Kamera. */
	var headLook: Boolean = true,
	/** Beim Zielen (linker Trigger) steuert die rechte Hand statt des Kopfs. */
	var handAim: Boolean = true,
	/** Drehgeschwindigkeit in Grad je Sekunde, die vollem Stick-Ausschlag entspricht. */
	var fullStickDegPerSec: Float = 220f,
	/** Kleinste Drehung in Grad je Sekunde, die überhaupt wirkt. Hält die Kamera bei ruhigem Kopf still. */
	var deadDegPerSec: Float = 2.5f,
	/** Mindest-Ausschlag, damit die tote Zone des Spiels übersprungen wird. */
	var minStick: Float = 0.22f,
	/** Glättung der Drehgeschwindigkeit, 0 = keine, nah an 1 = träge. */
	var smoothing: Float = 0.35f
)

/**
 * Übersetzt Touch-Controller und Kopf in einen DualSense.
 *
 * Belegung:
 *  linker/rechter Stick     -> linker/rechter Stick, Stick-Klick -> L3/R3
 *  A B X Y                  -> Kreuz, Kreis, Quadrat, Dreieck
 *  Trigger                  -> L2/R2, Griff-Tasten -> L1/R1
 *  Menü kurz                -> Options
 *  Menü halten + l. Stick   -> Steuerkreuz
 *  Menü halten + r. Klick   -> Touchpad
 *  Menü halten + l. Klick   -> Leinwand neu ausrichten
 *  Menü halten + Y          -> Share (Create)
 *  Menü halten + B          -> PS-Taste
 *  Menü halten + A          -> Kopf-Kamera an/aus
 *  Menü halten + X          -> Ego-Sicht-Tastenfolge
 */
class VrInputMapper(val tuning: VrTuning = VrTuning())
{
	var onRecenter: (() -> Unit)? = null
	var onNotice: ((String) -> Unit)? = null

	val macro = ButtonMacro()

	private var lastHead: Pair<Float, Float>? = null
	private var lastHand: Pair<Float, Float>? = null
	private var lastTimeNs = 0L
	private var rateX = 0f
	private var rateY = 0f

	private var menuWasDown = false
	private var menuUsedAsModifier = false
	private var optionsUntilNs = 0L
	private var lastButtons = 0

	fun map(frame: XrFrame, nowNs: Long): ControllerState
	{
		val state = ControllerState()
		val dt = if(lastTimeNs == 0L) 0f else ((nowNs - lastTimeNs) / 1e9f).coerceIn(0.001f, 0.1f)
		lastTimeNs = nowNs

		val menu = frame.pressed(XrFrame.BTN_MENU)
		val justPressed = frame.buttons and lastButtons.inv()
		lastButtons = frame.buttons

		if(menu && !menuWasDown)
			menuUsedAsModifier = false
		if(!menu && menuWasDown && !menuUsedAsModifier)
			optionsUntilNs = nowNs + 120_000_000L
		menuWasDown = menu
		if(nowNs < optionsUntilNs)
			state.buttons = state.buttons or ControllerState.BUTTON_OPTIONS

		var lx = frame.leftX
		var ly = -frame.leftY
		var buttons = 0U

		if(menu)
		{
			// Linker Stick wird zum Steuerkreuz.
			if(abs(frame.leftX) > 0.5f || abs(frame.leftY) > 0.5f)
			{
				menuUsedAsModifier = true
				if(abs(frame.leftX) > abs(frame.leftY))
					buttons = buttons or (if(frame.leftX > 0) ControllerState.BUTTON_DPAD_RIGHT else ControllerState.BUTTON_DPAD_LEFT)
				else
					buttons = buttons or (if(frame.leftY > 0) ControllerState.BUTTON_DPAD_UP else ControllerState.BUTTON_DPAD_DOWN)
			}
			lx = 0f
			ly = 0f
			if(frame.pressed(XrFrame.BTN_RSTICK)) { buttons = buttons or ControllerState.BUTTON_TOUCHPAD; menuUsedAsModifier = true }
			if(frame.pressed(XrFrame.BTN_Y)) { buttons = buttons or ControllerState.BUTTON_SHARE; menuUsedAsModifier = true }
			if(frame.pressed(XrFrame.BTN_B)) { buttons = buttons or ControllerState.BUTTON_PS; menuUsedAsModifier = true }
			if(justPressed and XrFrame.BTN_LSTICK != 0) { onRecenter?.invoke(); menuUsedAsModifier = true }
			if(justPressed and XrFrame.BTN_A != 0)
			{
				tuning.headLook = !tuning.headLook
				onNotice?.invoke(if(tuning.headLook) "Kopf-Kamera an" else "Kopf-Kamera aus")
				menuUsedAsModifier = true
			}
			if(justPressed and XrFrame.BTN_X != 0)
			{
				macro.start(ButtonMacro.egoView(), nowNs)
				onNotice?.invoke("Ego-Sicht wird versucht")
				menuUsedAsModifier = true
			}
		}
		else
		{
			if(frame.pressed(XrFrame.BTN_A)) buttons = buttons or ControllerState.BUTTON_CROSS
			if(frame.pressed(XrFrame.BTN_B)) buttons = buttons or ControllerState.BUTTON_MOON
			if(frame.pressed(XrFrame.BTN_X)) buttons = buttons or ControllerState.BUTTON_BOX
			if(frame.pressed(XrFrame.BTN_Y)) buttons = buttons or ControllerState.BUTTON_PYRAMID
			if(frame.pressed(XrFrame.BTN_LSTICK)) buttons = buttons or ControllerState.BUTTON_L3
			if(frame.pressed(XrFrame.BTN_RSTICK)) buttons = buttons or ControllerState.BUTTON_R3
		}
		if(frame.leftGrip > 0.6f) buttons = buttons or ControllerState.BUTTON_L1
		if(frame.rightGrip > 0.6f) buttons = buttons or ControllerState.BUTTON_R1

		state.buttons = state.buttons or buttons or macro.buttons(nowNs)
		state.l2State = (frame.leftTrigger.coerceIn(0f, 1f) * 255f).toInt().toUByte()
		state.r2State = (frame.rightTrigger.coerceIn(0f, 1f) * 255f).toInt().toUByte()
		state.leftX = stick(lx)
		state.leftY = stick(ly)

		val (lookX, lookY) = look(frame, dt)
		state.rightX = stick(frame.rightX + lookX)
		state.rightY = stick(-frame.rightY + lookY)
		return state
	}

	/** Drehung von Kopf oder Hand als Ausschlag des rechten Sticks. */
	private fun look(frame: XrFrame, dt: Float): Pair<Float, Float>
	{
		val head = if(frame.valid and XrFrame.VALID_HEAD != 0) frame.head else null
		val hand = if(frame.valid and XrFrame.VALID_RIGHT != 0) frame.rightHand else null
		val aiming = tuning.handAim && frame.leftTrigger > 0.3f && hand != null

		val current = if(aiming) hand else head
		val previous = if(aiming) lastHand else lastHead
		lastHead = head
		lastHand = hand

		if(current == null || previous == null || dt <= 0f || (!aiming && !tuning.headLook))
		{
			rateX = 0f
			rateY = 0f
			return Pair(0f, 0f)
		}

		var dYaw = current.first - previous.first
		if(dYaw > PI) dYaw -= (2 * PI).toFloat()
		if(dYaw < -PI) dYaw += (2 * PI).toFloat()
		val dPitch = current.second - previous.second

		val toDeg = (180.0 / PI).toFloat()
		val k = tuning.smoothing.coerceIn(0f, 0.95f)
		rateX = rateX * k + (dYaw / dt * toDeg) * (1f - k)
		rateY = rateY * k + (dPitch / dt * toDeg) * (1f - k)

		// Kopf hoch = Kamera hoch = Stick nach oben = negativ.
		return Pair(rateToStick(rateX), -rateToStick(rateY))
	}

	private fun rateToStick(degPerSec: Float): Float
	{
		val a = abs(degPerSec)
		if(a < tuning.deadDegPerSec)
			return 0f
		val share = ((a - tuning.deadDegPerSec) / (tuning.fullStickDegPerSec - tuning.deadDegPerSec)).coerceIn(0f, 1f)
		return sign(degPerSec) * (tuning.minStick + (1f - tuning.minStick) * share)
	}

	private fun stick(v: Float): Short = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
}

/**
 * Spielt eine feste Tastenfolge mit genauem Takt ab.
 * Jeder Schritt hält seine Tasten für die angegebene Dauer.
 */
class ButtonMacro
{
	data class Step(val buttons: UInt, val durationMs: Int)

	private var steps: List<Step> = emptyList()
	private var startNs = 0L

	val running get() = steps.isNotEmpty()

	fun start(steps: List<Step>, nowNs: Long)
	{
		this.steps = steps
		startNs = nowNs
	}

	fun buttons(nowNs: Long): UInt
	{
		if(steps.isEmpty())
			return 0U
		var t = (nowNs - startNs) / 1_000_000L
		for(step in steps)
		{
			if(t < step.durationMs)
				return step.buttons
			t -= step.durationMs
		}
		steps = emptyList()
		return 0U
	}

	companion object
	{
		/**
		 * Ego-Sicht in GTA 6, Stand 23.11.2026: im Auto das Handy holen (Steuerkreuz hoch),
		 * mit Kreis wegstecken und sofort mehrmals die Kamerataste drücken.
		 * Voraussetzung im Spiel: Touchpad-Taste von "Karte" auf "Kamera" gelegt.
		 * Der Takt ist eng, die Zeiten hier sind ein erster Ansatz zum Nachstellen.
		 */
		fun egoView(phoneWaitMs: Int = 900, tapMs: Int = 45, gapMs: Int = 45, taps: Int = 8): List<Step>
		{
			val list = mutableListOf(
				Step(ControllerState.BUTTON_DPAD_UP, 120),
				Step(0U, phoneWaitMs),
				Step(ControllerState.BUTTON_MOON, 70)
			)
			repeat(taps) {
				list += Step(ControllerState.BUTTON_TOUCHPAD, tapMs)
				list += Step(0U, gapMs)
			}
			return list
		}
	}
}
