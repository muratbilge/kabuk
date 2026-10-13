package app.kabuk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders launcher-icon candidates as they look on a home screen. Run with KABUK_LOGO_OUT=<dir>. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoPreviewTest {
    @Test fun render() {
        val out = System.getenv("KABUK_LOGO_OUT"); assumeTrue(out != null)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val names = (System.getenv("KABUK_LOGOS") ?: "ic_launcher").split(",")
        val big = 320; val small = 72; val gap = 40
        val sheet = Bitmap.createBitmap(gap + names.size * (big + gap), big + small + 3 * gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(Color.parseColor("#FF2A2F3A"))
        names.forEachIndexed { i, n ->
            val x = gap + i * (big + gap)
            c.drawBitmap(icon(ctx, n, big), x.toFloat(), gap.toFloat(), null)
            c.drawBitmap(icon(ctx, n, small), (x + (big - small) / 2).toFloat(), (2 * gap + big).toFloat(), null)
            icon(ctx, n, 512).compress(Bitmap.CompressFormat.PNG, 100, File(out, "$n.png").outputStream())
        }
        sheet.compress(Bitmap.CompressFormat.PNG, 100, File(out, "sheet.png").outputStream())
        for (n in listOf("ic_launcher_mono", "ic_notification")) {
            val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            val c2 = Canvas(b); c2.drawColor(Color.parseColor("#FF3D4455"))
            ContextCompat.getDrawable(ctx, ctx.resources.getIdentifier(n, "drawable", ctx.packageName))!!.apply { setBounds(0, 0, 160, 160); draw(c2) }
            b.compress(Bitmap.CompressFormat.PNG, 100, File(out, "$n.png").outputStream())
        }
    }

    /** background + foreground, the visible 72 of 108 dp, cut to a squircle-ish mask */
    private fun icon(ctx: android.content.Context, name: String, size: Int): Bitmap {
        val full = (size * 108f / 72f).toInt(); val off = (full - size) / 2
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.3f, size * 0.3f, Path.Direction.CW) }
        c.clipPath(clip)
        for (layer in listOf("_bg", "_fg")) {
            val id = ctx.resources.getIdentifier(name + layer, "drawable", ctx.packageName)
            val d = ContextCompat.getDrawable(ctx, id)!!
            d.setBounds(-off, -off, full - off, full - off); d.draw(c)
        }
        return b
    }
}
