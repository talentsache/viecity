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

/**
 * FOLLOW: Jede Kopfdrehung dreht die Kamera, die Leinwand läuft mit. Zum Umsehen zu Fuß.
 * PEEK:   Die Leinwand steht fest. Kleine Kopfbewegungen schauen nur über das Bild,
 *         erst ein deutlicher Blick zur Seite hält die Kamera dorthin. Zum Fahren.
 * OFF:    Die Leinwand steht fest, der Kopf steuert nichts.
 */
enum class HeadMode(val label: String) { FOLLOW("Kopf-Kamera: mitlaufend"), PEEK("Kopf-Kamera: Blick zur Seite"), OFF("Kopf-Kamera: aus") }

/** Stellschrauben für Kopf-Kamera und Zielen mit der Hand. */
data class VrTuning(
	/** Wie der Kopf die Kamera steuert. */
	var headMode: HeadMode = HeadMode.FOLLOW,
	/** Blick-Modus: Kopfdrehung in Grad, die noch nichts bewirkt, und Drehung für vollen Ausschlag. */
	var peekDeadDeg: Float = 12f,
	var peekFullDeg: Float = 38f,
	/** Beim Zielen (linker Trigger) steuert die rechte Hand statt des Kopfs. */
	var handAim: Boolean = true,
	/** Drehgeschwindigkeit in Grad je Sekunde, die vollem Stick-Ausschlag entspricht. */
	var fullStickDegPerSec: Float = 110f,
	/** Kleinste Drehung in Grad je Sekunde, die überhaupt wirkt. Hält die Kamera bei ruhigem Kopf still. */
	var deadDegPerSec: Float = 2.5f,
	/** Mindest-Ausschlag, damit die tote Zone des Spiels übersprungen wird. */
	var minStick: Float = 0.30f,
	/** Glättung der Drehgeschwindigkeit, 0 = keine, nah an 1 = träge. */
	var smoothing: Float = 0.25f
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
 *  Menü halten + r. Stick   -> Leinwand kleiner/größer (hoch/runter)
 *  Menü halten + A          -> Ego-Sicht-Tastenfolge
 *  Menü halten + B          -> PS-Taste
 *  Menü halten + r. Trigger -> Leinwand neu ausrichten
 *  Menü halten + r. Griff   -> Kopf-Kamera wechseln: mitlaufend, Blick zur Seite, aus
 *  Menü halten + l. Griff   -> Share (Create)
 *  Menü halten + l. Trigger -> Stream verlassen, zurück zur Liste und zu den Einstellungen
 *
 * Die Menü-Taste liegt unter dem linken Daumen, deshalb liegen alle
 * Kombinationen auf der rechten Hand oder auf den Fingern der linken.
 */
class VrInputMapper(val tuning: VrTuning = VrTuning())
{
	var onRecenter: (() -> Unit)? = null
	var onNotice: ((String) -> Unit)? = null
	/** Hilfe-Tafel zeigen, solange die Menü-Taste gehalten wird. */
	var onHelp: ((Boolean) -> Unit)? = null
	var helpHeld = false
		private set
	private var menuDownNs = 0L
	/** Stream verlassen, zurück zur Liste und zu den Einstellungen. */
	var onLeave: (() -> Unit)? = null
	/** Leinwand größer (positiv) oder kleiner, Anteil je Sekunde. */
	var onZoom: ((Float) -> Unit)? = null

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
	private var leaveAsked = false
	private var comboTriggerWasDown = false
	private var comboGripWasDown = false

	fun map(frame: XrFrame, nowNs: Long): ControllerState
	{
		val state = ControllerState()
		val dt = if(lastTimeNs == 0L) 0f else ((nowNs - lastTimeNs) / 1e9f).coerceIn(0.001f, 0.1f)
		lastTimeNs = nowNs

		val menu = frame.pressed(XrFrame.BTN_MENU)
		val justPressed = frame.buttons and lastButtons.inv()
		lastButtons = frame.buttons

		if(menu && !menuWasDown)
		{
			menuUsedAsModifier = false
			menuDownNs = nowNs
		}
		// Ein kurzer Druck ist Options, erst das Halten holt die Hilfe.
		val help = menu && nowNs - menuDownNs > 350_000_000L
		if(help != helpHeld)
		{
			helpHeld = help
			onHelp?.invoke(help)
		}
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
			if(frame.pressed(XrFrame.BTN_B)) { buttons = buttons or ControllerState.BUTTON_PS; menuUsedAsModifier = true }
			if(abs(frame.rightY) > 0.4f)
			{
				// Stick nach oben schiebt die Leinwand weg (kleiner), nach unten holt sie heran.
				onZoom?.invoke(-frame.rightY * dt)
				menuUsedAsModifier = true
			}
			if(frame.leftTrigger > 0.8f && !leaveAsked)
			{
				leaveAsked = true
				menuUsedAsModifier = true
				onLeave?.invoke()
			}
			val rightTriggerDown = frame.rightTrigger > 0.6f
			val rightGripDown = frame.rightGrip > 0.6f
			if(rightTriggerDown && !comboTriggerWasDown) { onRecenter?.invoke(); menuUsedAsModifier = true }
			if(rightGripDown && !comboGripWasDown)
			{
				tuning.headMode = HeadMode.values()[(tuning.headMode.ordinal + 1) % HeadMode.values().size]
				peekReference = null
				onNotice?.invoke(tuning.headMode.label)
				menuUsedAsModifier = true
			}
			comboTriggerWasDown = rightTriggerDown
			comboGripWasDown = rightGripDown
			if(justPressed and XrFrame.BTN_A != 0)
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
		if(menu)
		{
			// Mit gehaltener Menü-Taste sind Griffe und rechter Trigger Kombinationen, keine Spieltasten.
			if(frame.leftGrip > 0.6f) { buttons = buttons or ControllerState.BUTTON_SHARE; menuUsedAsModifier = true }
		}
		else
		{
			comboTriggerWasDown = false
			comboGripWasDown = false
			if(frame.leftGrip > 0.6f) buttons = buttons or ControllerState.BUTTON_L1
			if(frame.rightGrip > 0.6f) buttons = buttons or ControllerState.BUTTON_R1
		}

		state.buttons = state.buttons or buttons or macro.buttons(nowNs)
		state.l2State = if(menu) 0U else (frame.leftTrigger.coerceIn(0f, 1f) * 255f).toInt().toUByte()
		state.r2State = if(menu) 0U else (frame.rightTrigger.coerceIn(0f, 1f) * 255f).toInt().toUByte()
		state.leftX = stick(lx)
		state.leftY = stick(ly)

		val (lookX, lookY) = look(frame, dt)
		state.rightX = stick((if(menu) 0f else frame.rightX) + lookX)
		state.rightY = stick((if(menu) 0f else -frame.rightY) + lookY)
		return state
	}

	/** Drehung von Kopf oder Hand als Ausschlag des rechten Sticks. */
	private fun look(frame: XrFrame, dt: Float): Pair<Float, Float>
	{
		val head = if(frame.valid and XrFrame.VALID_HEAD != 0) frame.head else null
		val hand = if(frame.valid and XrFrame.VALID_RIGHT != 0) frame.rightHand else null
		val aiming = tuning.handAim && frame.leftTrigger > 0.3f && hand != null

		if(!aiming && tuning.headMode == HeadMode.PEEK && head != null)
		{
			lastHead = head
			lastHand = hand
			rateX = 0f
			rateY = 0f
			return peek(head)
		}

		val current = if(aiming) hand else head
		val previous = if(aiming) lastHand else lastHead
		lastHead = head
		lastHand = hand

		if(current == null || previous == null || dt <= 0f || (!aiming && tuning.headMode != HeadMode.FOLLOW))
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

	/** Blickrichtung, bei der die Kamera geradeaus steht. Wird beim Ausrichten neu gesetzt. */
	private var peekReference: Pair<Float, Float>? = null

	fun resetPeekReference() { peekReference = null }

	/** Blick-Modus: Der Kopf ist ein Steuerknüppel. Abstand von der Mitte hält den Stick. */
	private fun peek(head: Pair<Float, Float>): Pair<Float, Float>
	{
		val reference = peekReference ?: head.also { peekReference = it }
		var yaw = head.first - reference.first
		if(yaw > PI) yaw -= (2 * PI).toFloat()
		if(yaw < -PI) yaw += (2 * PI).toFloat()
		val toDeg = (180.0 / PI).toFloat()
		fun curve(deg: Float, dead: Float, full: Float): Float
		{
			val a = abs(deg)
			if(a < dead)
				return 0f
			val share = ((a - dead) / (full - dead)).coerceIn(0f, 1f)
			// Sanft anlaufen, damit der Übergang aus der Ruhezone nicht ruckt.
			return sign(deg) * (tuning.minStick + (1f - tuning.minStick) * share * share)
		}
		val x = curve(yaw * toDeg, tuning.peekDeadDeg, tuning.peekFullDeg)
		val y = -curve((head.second - reference.second) * toDeg, tuning.peekDeadDeg * 0.8f, tuning.peekFullDeg * 0.7f)
		return Pair(x, y)
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

	@Synchronized
	fun start(steps: List<Step>, nowNs: Long)
	{
		this.steps = steps
		startNs = nowNs
	}

	@Synchronized
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
		 * Liest eine Tastenfolge aus Text, zum Nachstellen des Takts ohne Neubau.
		 * Form: "U:120,_:900,O:70,T:45,_:45". Buchstaben lassen sich kombinieren ("OT:60").
		 * U D L R Steuerkreuz, X Kreuz, O Kreis, S Quadrat, Y Dreieck, T Touchpad,
		 * P Options, 1 L1, 2 R1, _ nichts.
		 */
		fun parse(spec: String): List<Step> = spec.split(",").mapNotNull { part ->
			val pieces = part.trim().split(":")
			val ms = pieces.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
			var buttons = 0U
			for(c in pieces[0])
				buttons = buttons or when(c)
				{
					'U' -> ControllerState.BUTTON_DPAD_UP
					'D' -> ControllerState.BUTTON_DPAD_DOWN
					'L' -> ControllerState.BUTTON_DPAD_LEFT
					'R' -> ControllerState.BUTTON_DPAD_RIGHT
					'X' -> ControllerState.BUTTON_CROSS
					'O' -> ControllerState.BUTTON_MOON
					'S' -> ControllerState.BUTTON_BOX
					'Y' -> ControllerState.BUTTON_PYRAMID
					'T' -> ControllerState.BUTTON_TOUCHPAD
					'P' -> ControllerState.BUTTON_OPTIONS
					'1' -> ControllerState.BUTTON_L1
					'2' -> ControllerState.BUTTON_R1
					else -> 0U
				}
			Step(buttons, ms)
		}

		/**
		 * Ego-Sicht in GTA 6, Stand 24.11.2026: im Auto das Handy holen (Steuerkreuz hoch),
		 * mit Kreis wegstecken und genau beim Wegstecken einmal die Kamerataste drücken.
		 * Voraussetzung im Spiel: Touchpad-Taste von "Karte" auf "Kamera" gelegt.
		 *
		 * Am Spiel ausgemessen: Die Kamerataste muss 150 ms nach Beginn des Kreis-Drucks kommen.
		 * Gleichzeitig mit Kreis ist zu früh, Dauerfeuer auf der Kamerataste verfehlt das Fenster.
		 */
		fun egoView(phoneWaitMs: Int = 1800, circleMs: Int = 100, gapMs: Int = 50, cameraMs: Int = 80): List<Step> = listOf(
			Step(ControllerState.BUTTON_DPAD_UP, 150),
			Step(0U, phoneWaitMs),
			Step(ControllerState.BUTTON_MOON, circleMs),
			Step(0U, gapMs),
			Step(ControllerState.BUTTON_TOUCHPAD, cameraMs)
		)
	}
}
