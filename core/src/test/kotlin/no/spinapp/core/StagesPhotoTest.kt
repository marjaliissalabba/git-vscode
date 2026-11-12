package no.spinapp.core

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ekte bilde av Stages-konsollet (beskåret til LCD-glasset): 100 W, 71 rpm, 27,1 km/t. */
class StagesPhotoTest {
    private fun load(): GrayImage {
        val img = ImageIO.read(javaClass.getResourceAsStream("/stages_lcd.png"))
        val px = IntArray(img.width * img.height) { i ->
            val rgb = img.getRGB(i % img.width, i / img.width)
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        return GrayImage(img.width, img.height, px)
    }

    private fun crop(img: GrayImage, r: NormRect): GrayImage {
        val x0 = (r.left * img.width).toInt(); val x1 = (r.right * img.width).toInt()
        val y0 = (r.top * img.height).toInt(); val y1 = (r.bottom * img.height).toInt()
        val w = x1 - x0; val h = y1 - y0
        return GrayImage(w, h, IntArray(w * h) { i -> img[x0 + i % w, y0 + i / w] })
    }

    @Test
    fun readsRealConsole() {
        val lcd = load()
        assertEquals(100, SevenSegmentDecoder.decode(crop(lcd, StagesConsoleLayout.WATTS.rect)).asInt())
        assertEquals(71, SevenSegmentDecoder.decode(crop(lcd, StagesConsoleLayout.RPM.rect)).asInt())
        assertEquals(27.1, SevenSegmentDecoder.decode(crop(lcd, StagesConsoleLayout.SPEED.rect)).asTenths())
    }
}
