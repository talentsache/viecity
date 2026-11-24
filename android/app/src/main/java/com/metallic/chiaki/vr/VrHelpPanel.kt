// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.metallic.chiaki.R
import java.nio.ByteBuffer

/**
 * Zeichnet die Hilfe-Tafel, die in VR unter der Leinwand erscheint:
 * beim Start kurz und immer, solange die linke Menü-Taste gehalten wird.
 */
object VrHelpPanel
{
	const val WIDTH = 1600
	const val HEIGHT = 430

	private val entries = listOf(
		"A" to "First person",
		"B" to "PS button",
		"Right stick click" to "Touchpad",
		"Right stick up / down" to "Screen size",
		"Right trigger" to "Recenter screen",
		"Right grip" to "Head camera mode",
		"Left stick" to "D-pad",
		"Left grip" to "Create",
		"Left trigger" to "Leave to settings"
	)

	fun render(context: Context): ByteBuffer
	{
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val pink = 0xFFEAABAC.toInt()

		val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9120628.toInt() }
		canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), 48f, 48f, panel)
		val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pink; style = Paint.Style.STROKE; strokeWidth = 4f; alpha = 150 }
		canvas.drawRoundRect(RectF(2f, 2f, WIDTH - 2f, HEIGHT - 2f), 46f, 46f, edge)

		BitmapFactory.decodeResource(context.resources, R.drawable.viecity_logo)?.let { logo ->
			val h = 250
			val w = logo.width * h / logo.height
			canvas.drawBitmap(logo, null, Rect(50, (HEIGHT - h) / 2, 50 + w, (HEIGHT + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
			logo.recycle()
		}

		val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pink; textSize = 40f; typeface = Typeface.DEFAULT_BOLD }
		val key = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
		val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD9C8E8.toInt(); textSize = 34f }

		val left = 370f
		canvas.drawText("Hold the left menu button, then press", left, 70f, title)
		val columns = 3
		val columnWidth = (WIDTH - left - 40f) / columns
		entries.forEachIndexed { index, (k, v) ->
			val x = left + (index % columns) * columnWidth
			val y = 150f + (index / columns) * 100f
			canvas.drawText(k, x, y, key)
			canvas.drawText(v, x, y + 42f, value)
		}

		val buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
		bitmap.copyPixelsToBuffer(buffer)
		bitmap.recycle()
		return buffer
	}
}
