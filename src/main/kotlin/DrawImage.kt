@file:Suppress("MemberVisibilityCanBePrivate")

package xyz.xszq

import xyz.xszq.MaimaiImage.resolveCover
import com.soywiz.kds.iterators.fastForEach
import com.soywiz.kmem.toIntFloor
import com.soywiz.korim.bitmap.Bitmap
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.context2d
import com.soywiz.korim.bitmap.effect.BitmapEffect
import com.soywiz.korim.bitmap.effect.applyEffect
import com.soywiz.korim.bitmap.sliceWithSize
import com.soywiz.korim.color.Colors
import com.soywiz.korim.color.RGBA
import com.soywiz.korim.font.TtfFont
import com.soywiz.korim.format.PNG
import com.soywiz.korim.format.encode
import com.soywiz.korim.format.readNativeImage
import com.soywiz.korim.text.HorizontalAlign
import com.soywiz.korim.text.TextAlignment
import com.soywiz.korim.vector.Context2d
import com.soywiz.korio.file.VfsFile
import com.soywiz.korio.file.baseName
import com.soywiz.korio.file.std.toVfs
import com.soywiz.korio.net.MimeType
import com.soywiz.korio.net.mimeType
import kotlinx.serialization.Serializable
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import kotlin.math.min
import kotlin.math.roundToInt


@Serializable
class MaiPicPosition(val fontName: String = "", val size: Int = 0, val x: Int, val y: Int, val scale: Double = 1.0)

object MaimaiImage {
    val fonts = mutableMapOf<String, TtfFont>()
    val imgDir = MaimaiBot.resolveDataFile("img").toVfs()
    val images = mutableMapOf<String, Bitmap>()
    private val sysFonts = MultiPlatformNativeSystemFontProvider()
    lateinit var theme: MaimaiBestTheme
    suspend fun generateBest(info: MaimaiPlayerData, b50: Boolean): ByteArray {
        val config = if (b50) theme.b50 else theme.b40
        val result = images[config.bg]!!.clone()
        if (b50)
            info.charts.values.forEach { type ->
                type.fastForEach {
                    it.dxScore = getNewRa(it.ds, it.achievements)
                }
            }
        val realRating =
            if (b50) info.charts["sd"]!!.sumOf { it.dxScore } + info.charts["dx"]!!.sumOf { it.dxScore }
            else info.rating + info.additional_rating
        return result.context2d {
            images["rating_base_${ratingColor(realRating, b50)}.png"] ?.let { ratingBg ->
                drawImage(ratingBg, config.pos.getValue("ratingBg").x, config.pos.getValue("ratingBg").y)
            }
            drawText(info.nickname.toSBC(), config.pos.getValue("name"))
            drawText(realRating.toString().toList().joinToString(" "), config.pos.getValue("dxrating"),
                Colors.YELLOW, TextAlignment.RIGHT)
            drawText(if (b50) "????????? maimai DX rating ?????????" else "?????????${info.rating} + ????????????${info.additional_rating}",
                config.pos.getValue("ratingDetail"))

            drawCharts(info.charts["sd"]!!.fillEmpty(if (b50) 35 else 25), config.oldCols,
                config.pos.getValue("oldCharts").x, config.pos.getValue("oldCharts").y, config.gap, config)
            drawCharts(info.charts["dx"]!!.fillEmpty(15), config.newCols,
                config.pos.getValue("newCharts").x, config.pos.getValue("newCharts").y, config.gap, config)
            dispose()
        }.encode(PNG)
    }

    suspend fun reloadImages() {
        images.clear()
        listOf(imgDir, MaimaiBot.resolveConfigFile(MaimaiConfig.theme).toVfs()).forEach { path ->
            path.listRecursive().collect {
                if (it.mimeType() in listOf(MimeType.IMAGE_JPEG, MimeType.IMAGE_PNG)) {
                    runCatching {
                        images[it.baseName] = it.readNativeImage()
                    }.onFailure { e ->
                        MaimaiBot.logger.error(e)
                    }
                }
            }
        }
        MaimaiBot.logger.info("???????????????????????????")
    }
    fun reloadFonts() {
        theme.b40.pos.filterNot { it.value.fontName.isBlank() }.forEach {
            fonts[it.value.fontName] = sysFonts.locateFontByName(it.value.fontName) ?: sysFonts.defaultFont()
        }
        theme.b50.pos.filterNot { it.value.fontName.isBlank() }.forEach {
            fonts[it.value.fontName] = sysFonts.locateFontByName(it.value.fontName) ?: sysFonts.defaultFont()
        }
    }

    fun ratingColor(rating: Int, b50: Boolean = false): String = if (b50) {
        when (rating) {
            in 0..999 -> "normal"
            in 1000..1999 -> "blue"
            in 2000..3999 -> "green"
            in 4000..6999 -> "orange"
            in 7000..9999 -> "red"
            in 10000..11999 -> "purple"
            in 12000..12999 -> "bronze"
            in 13000..13999 -> "silver"
            in 14000..14499 -> "gold"
            in 14500..14999 -> "gold" // Actually another color
            in 15000..40000 -> "rainbow"
            else -> "normal"
        }
    } else when (rating) {
        in 0..999 -> "normal"
        in 1000..1999 -> "blue"
        in 2000..2999 -> "green"
        in 3000..3999 -> "orange"
        in 4000..4999 -> "red"
        in 5000..5999 -> "purple"
        in 6000..6999 -> "bronze"
        in 7000..7999 -> "silver"
        in 8000..8499 -> "gold"
        in 8500..20000 -> "rainbow"
        else -> "normal"
    }
    fun difficulty2Name(id: Int, english: Boolean = true): String {
        return if (english) {
            when (id) {
                0 -> "Bas"
                1 -> "Adv"
                2 -> "Exp"
                3 -> "Mst"
                4 -> "ReM"
                else -> ""
            }
        } else {
            when (id) {
                0 -> "???"
                1 -> "???"
                2 -> "???"
                3 -> "???"
                4 -> "???"
                else -> ""
            }
        }
    }
    fun name2Difficulty(name: Char): Int? =
        when (name) {
            '???' -> 0
            '???' -> 1
            '???' -> 2
            '???' -> 3
            '???' -> 4
            else -> null
        }
    fun getNewRa(ds: Double, achievement: Double): Int {
        val baseRa = when (achievement) {
            in 0.0..49.9999 ->  7.0
            in 50.0..59.9999 -> 8.0
            in 60.0..69.9999 -> 9.6
            in 70.0..74.9999 -> 11.2
            in 75.0..79.9999 -> 12.0
            in 80.0..89.9999 -> 13.6
            in 90.0..93.9999 -> 15.2
            in 94.0..96.9999 -> 16.8
            in 97.0..97.9999 -> 20.0
            in 98.0..98.9999 -> 20.3
            in 99.0..99.4999 -> 20.8
            in 99.5..99.9999 -> 21.1
            in 100.0..100.4999 -> 21.6
            in 100.5..101.0 -> 22.4
            else -> 0.0
        }
        return (ds * (min(100.5, achievement) / 100) * baseRa).toIntFloor()
    }
    fun resolveCover(id: Int): Bitmap {
        return images["$id.jpg"] ?: images["default_cover.jpg"]!!
    }
    suspend fun resolveCoverFileOrNull(id: String): VfsFile? {
        val target = imgDir["covers/$id.jpg"]
        return if (target.exists()) target else null
    }
}


const val DBC_SPACE = ' '
const val SBC_SPACE = 12288
const val DBC_CHAR_START = 33
const val DBC_CHAR_END = 126
const val CONVERT_STEP = 65248
fun String.toSBC(): String {
    val buf = StringBuilder(length)
    this.toCharArray().forEach {
        buf.append(
            when (it.code) {
                DBC_SPACE.code -> SBC_SPACE
                in DBC_CHAR_START..DBC_CHAR_END -> it + CONVERT_STEP
                else -> it
            }
        )
    }
    return buf.toString()
}
fun Char.isDBC() = this.code in DBC_SPACE.code..DBC_CHAR_END
fun String.ellipsize(max: Int): String {
    var result = ""
    var cnt = 0
    forEach {
        cnt += if (it.isDBC()) 1 else 2
        if (cnt > max) return@forEach
        result += it
    }
    return result + if (result.length != length) "???" else ""
}
fun String.limitDecimal(limit: Int = 4): String {
    if (toDoubleOrNull() == null)
        throw IllegalArgumentException("Only decimal String is allowed")
    var result = substringBefore('.') + '.'
    val afterPart = substringAfter('.')
    result += if (afterPart.length <= limit)
        afterPart + "0".repeat(4 - afterPart.length)
    else
        afterPart.substring(0, limit)
    return result
}

fun Context2d.drawText(text: String, attr: MaiPicPosition, color: RGBA = Colors.BLACK,
                       align: TextAlignment = TextAlignment.LEFT) {
    this.font = MaimaiImage.fonts[attr.fontName]
    this.fontSize = attr.size.toDouble()
    this.alignment = align
    this.fillStyle = createColor(color)
    // TODO: Submit issue to korim to fix alignment, this is only a TEMPORARY solution
    val offsetx = if (this.alignment.horizontal == HorizontalAlign.RIGHT) -1 * (attr.size - 1) * (text.length / 2) else 0
    fillText(text, attr.x.toDouble() + offsetx, attr.y.toDouble())
}
fun Context2d.drawTextRelative(text: String, x: Int, y: Int, attr: MaiPicPosition,
                               color: RGBA = Colors.BLACK, align: TextAlignment = TextAlignment.LEFT) {
    this.font = MaimaiImage.fonts[attr.fontName]
    this.fontSize = attr.size.toDouble()
    this.alignment = align
    this.fillStyle = createColor(color)
    // TODO: Submit issue to korim to fix alignment, this is only a TEMPORARY solution
    val offsetx = if (this.alignment.horizontal == HorizontalAlign.RIGHT) -1 * (attr.size - 1) * (text.length / 2) else 0
    fillText(text, x + attr.x.toDouble() + offsetx, y + attr.y.toDouble())
}
fun Context2d.drawCharts(charts: List<MaimaiPlayScore>, cols: Int, startX: Int, startY: Int, gap: Int,
                         config: MaimaiBestPicConfig
) {
    charts.sortedWith(compareBy({ -it.ra }, { it.achievements })).forEachIndexed { index, chart ->
        val coverRaw = resolveCover(chart.song_id).toBMP32()
            .scaled(config.coverWidth, config.coverWidth, true)
        val newHeight = (coverRaw.width / config.coverRatio).roundToInt()
        val cover = coverRaw.sliceWithSize(0, (coverRaw.height - newHeight) / 2, coverRaw.width, newHeight)
            .extract().blurFixedSize(4).brightness(-0.05f)
        val x = startX + (index % cols) * (coverRaw.width + gap)
        val y = startY + (index / cols) * (newHeight + gap)

        state.fillStyle = Colors.BLACK // TODO: Make color changeable
        fillRect(x + config.pos.getValue("shadow").x, y + config.pos.getValue("shadow").y, coverRaw.width, newHeight)
        drawImage(cover, x, y)
        if (chart.title != "") {
            val label = config.pos.getValue("label")
            MaimaiImage.images["label_${chart.level_label.replace(":", "")}.png"] ?.let {
                drawImage(it.toBMP32().scaleLinear(label.scale, label.scale), x + label.x, y + label.y)
            }

            // Details
            drawTextRelative(chart.title.ellipsize(12), x, y, config.pos.getValue("chTitle"), Colors.WHITE)
            drawTextRelative(chart.achievements.toString().limitDecimal(4) + "%", x, y,
                config.pos.getValue("chAchievements"), Colors.WHITE)
            drawTextRelative("Base: ${chart.ds} -> ${chart.ra}", x, y, config.pos.getValue("chBase"), Colors.WHITE)
            drawTextRelative("#${index + 1}(${chart.type})", x, y, config.pos.getValue("chRank"), Colors.WHITE)

            val rateIcon = config.pos.getValue("rateIcon")
            MaimaiImage.images["music_icon_${chart.rate}.png"] ?.let {
                drawImage(it.toBMP32().scaleLinear(rateIcon.scale, rateIcon.scale),
                    x + rateIcon.x, y + rateIcon.y)
            }
            if (chart.fc.isNotEmpty()) {
                val fcIcon = config.pos.getValue("fcIcon")
                MaimaiImage.images["music_icon_${chart.fc}.png"] ?.let {
                    drawImage(it.toBMP32().scaleLinear(fcIcon.scale, fcIcon.scale),
                        x + fcIcon.x, y + fcIcon.y)
                }
            }
        }
    }
}


fun Bitmap32.blurFixedSize(radius: Int) = applyEffect(BitmapEffect(radius))
    .removeAlpha().sliceWithSize(radius, radius, width, height).extract()
fun Bitmap32.brightness(ratio: Float = 0.6f): Bitmap32 {
    if (ratio > 1f || ratio < -1f)
        throw IllegalArgumentException("Ratio must be in [-1, 1]")
    val real = ratio / 2f + 0.5f
    updateColors {
        it.times(RGBA.float(real, real, real, 1f))
    }
    return this
}
fun Bitmap32.removeAlpha(): Bitmap32 {
    forEach { n, _, _ ->
        data[n] = RGBA(data[n].r, data[n].g, data[n].b, 255)
    }
    return this
}
fun Bitmap.randomSlice(size: Int = 66) =
    sliceWithSize((0..width - size).random(), (0..height - size).random(), size, size).extract()

enum class CoverSource {
    WAHLAP, ZETARAKU, DIVING_FISH
} // TODO: Implement download from DIVING_FISH source

object MaimaiConfig: AutoSavePluginConfig("settings") {
    val theme: String by value("portrait")
    val multiAccountsMode: Boolean by value(false)
    val coverSource: CoverSource by value(CoverSource.WAHLAP)
    val maidataJsonUrls: List<String> by value(
        listOf(
            "https://raw.githubusercontent.com/CrazyKidCN/maimaiDX-CN-songs-database/main/maidata.json",
            "https://cdn.jsdelivr.net/gh/CrazyKidCN/maimaiDX-CN-songs-database@main/maidata.json",
            "https://raw.fastgit.org/CrazyKidCN/maimaiDX-CN-songs-database/main/maidata.json",
            "https://cdn.githubjs.cf/CrazyKidCN/maimaiDX-CN-songs-database/raw/main/maidata.json",
        )
    )
    val zetarakuSite: String by value("https://dp4p6x0xfi5o9.cloudfront.net")
}
@Serializable
class MaimaiBestPicConfig(
    val bg: String, val coverWidth: Int, val coverRatio: Double, val oldCols: Int, val newCols: Int, val gap: Int,
    val pos: Map<String, MaiPicPosition>)

@Serializable
class MaimaiBestTheme(val b40: MaimaiBestPicConfig, val b50: MaimaiBestPicConfig)