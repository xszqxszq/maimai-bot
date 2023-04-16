@file:Suppress("MemberVisibilityCanBePrivate", "SpellCheckingInspection")

package xyz.xszq

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
import com.soywiz.korio.file.std.tempVfs
import com.soywiz.korio.file.std.toVfs
import com.soywiz.korio.net.MimeType
import com.soywiz.korio.net.mimeType
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import xyz.xszq.MaimaiBot.generateDsList
import xyz.xszq.MaimaiBot.levels
import xyz.xszq.MaimaiImage.resolveCoverCache
import xyz.xszq.MaimaiImage.resolveImageCache
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt


@Serializable
class MaiPicPosition(val fontName: String = "", val size: Int = 0, val x: Int, val y: Int, val scale: Double = 1.0)

object MaimaiImage {
    val fonts = mutableMapOf<String, TtfFont>()
    val imgDir = MaimaiBot.resolveDataFile("img").toVfs()
    val images = mutableMapOf<String, Bitmap>()
    val sysFonts = MultiPlatformNativeSystemFontProvider()
    lateinit var theme: MaimaiPicTheme
    var dsGenerated = false
    suspend fun generateBest(info: MaimaiPlayerData, b50: Boolean): ByteArray {
        val config = if (b50) theme.b50 else theme.b40
        val result = resolveImageCache(config.bg).clone()
        if (b50)
            info.charts.values.forEach { type ->
                type.fastForEach {
                    it.ra = getNewRa(it.ds, it.achievements)
                }
            }
        val realRating =
            if (b50) info.charts["sd"]!!.sumOf { it.ra } + info.charts["dx"]!!.sumOf { it.ra }
            else info.rating + info.additional_rating
        return result.context2d {
            resolveImageCache("rating_base_${ratingColor(realRating, b50)}.png").let { ratingBg ->
                drawImage(ratingBg, config.pos.getValue("ratingBg").x, config.pos.getValue("ratingBg").y)
            }
            drawText(info.nickname.toSBC(), config.pos.getValue("name"))
            drawText(realRating.toString().toList().joinToString(" "), config.pos.getValue("dxrating"),
                Colors.YELLOW, TextAlignment.RIGHT)
            drawText(if (b50) "对海外 maimai DX rating 的拟构" else "底分：${info.rating} + 段位分：${info.additional_rating}",
                config.pos.getValue("ratingDetail"))

            drawCharts(info.charts["sd"]!!.fillEmpty(if (b50) 35 else 25), config.oldCols,
                config.pos.getValue("oldCharts").x, config.pos.getValue("oldCharts").y, config.gap, config)
            drawCharts(info.charts["dx"]!!.fillEmpty(15), config.newCols,
                config.pos.getValue("newCharts").x, config.pos.getValue("newCharts").y, config.gap, config)
            dispose()
        }.encode(PNG)
    }
    suspend fun generateList(level: String, info: MaimaiPlayerData, l: List<MaimaiPlayScore>,
                             nowPage: Int, totalPage: Int): ByteArray {
        val config = theme.scoreList
        val result = resolveImageCache(config.bg).clone()
        val realRating = info.rating + info.additional_rating
        return result.context2d {
            drawText(info.nickname.toSBC(), config.pos.getValue("name"))
            drawImage(resolveImageCache("rating_base_${ratingColor(realRating, false)}.png"), config.pos.getValue("ratingBg").x, config.pos.getValue("ratingBg").y)
            drawText(info.nickname.toSBC(), config.pos.getValue("name"))
            drawText(realRating.toString().toList().joinToString(" "), config.pos.getValue("dxrating"),
                Colors.YELLOW, TextAlignment.RIGHT)
            drawText("${level}分数列表，第 $nowPage 页 (共 $totalPage 页)", config.pos.getValue("ratingDetail"))

            drawCharts(l.fillEmpty(50), config.oldCols,
                config.pos.getValue("oldCharts").x, config.pos.getValue("oldCharts").y, config.gap, config)
            dispose()
        }.encode(PNG)
    }

    suspend fun reloadImages() {
        MaimaiBot.logger.info("正在载入图片中……")
        // 载入当前主题的所有图片，避免生成图片时频繁读取小图片
            MaimaiBot.resolveConfigFile(MaimaiConfig.theme).toVfs().listRecursive().collect {
                if (it.mimeType() in listOf(MimeType.IMAGE_JPEG, MimeType.IMAGE_PNG)) {
                    runCatching {
                        images[it.baseName] = it.readNativeImage()
                    }.onFailure { e ->
                        e.printStackTrace()
                    }
                }
            }
        val lock = Mutex()
        // 载入封面
        if (MaimaiConfig.enableMemCache) {
            coroutineScope {
                MaimaiBot.resolveDataFile("img/covers").toVfs().listRecursive().collect {
                    if (it.mimeType() == MimeType.IMAGE_JPEG) {
                        launch {
                            runCatching {
                                val nowImage = it.readNativeImage()
                                lock.withLock {
                                    images[it.baseName] = nowImage
                                }
                            }.onFailure { e ->
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
        if (!dsGenerated) {
            MaimaiBot.logger.info("正在生成定数表${if (MaimaiConfig.enableMemCache) "（请耐心等待）" else ""}……")
            dsGenerated = true
            val semaphore = Semaphore(if (MaimaiConfig.enableMemCache) 16 else 1)
            coroutineScope {
                levels.forEachIndexed { ind, level ->
                    launch {
                        semaphore.withPermit {
                            val bg = lock.withLock {
                                resolveImageCache(theme.dsList.bg).clone()
                            }
                            val nowDs = generateDsList(level, bg, lock)
                            tempVfs["ds/${level}.png"].writeBytes(nowDs.encode(PNG))
                            if (MaimaiConfig.enableMemCache) {
                                lock.withLock {
                                    images["ds/${level}.png"] = nowDs
                                }
                            }
                        }
                    }
                }
            }
            MaimaiBot.logger.info("定数表生成完成。")
        }
        // 释放内存
        System.gc()

        MaimaiBot.logger.info("成功载入所有图片。")
    }
    fun reloadFonts() {
        theme.b40.pos.filterNot { it.value.fontName.isBlank() }.forEach {
            fonts[it.value.fontName] = sysFonts.locateFontByName(it.value.fontName) ?: sysFonts.defaultFont()
        }
        theme.b50.pos.filterNot { it.value.fontName.isBlank() }.forEach {
            fonts[it.value.fontName] = sysFonts.locateFontByName(it.value.fontName) ?: sysFonts.defaultFont()
        }
        theme.scoreList.pos.filterNot { it.value.fontName.isBlank() }.forEach {
            fonts[it.value.fontName] = sysFonts.locateFontByName(it.value.fontName) ?: sysFonts.defaultFont()
        }
        theme.dsList.pos.filterNot { it.value.fontName.isBlank() }.forEach {
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
                0 -> "绿"
                1 -> "黄"
                2 -> "红"
                3 -> "紫"
                4 -> "白"
                else -> ""
            }
        }
    }
    fun name2Difficulty(name: Char): Int? =
        when (name) {
            '绿' -> 0
            '黄' -> 1
            '红' -> 2
            '紫' -> 3
            '白' -> 4
            else -> null
        }
    fun levelIndex2Label(index: Int): String =
        when (index) {
            0 -> "Basic"
            1 -> "Advanced"
            2 -> "Expert"
            3 -> "Master"
            4 -> "Re:MASTER"
            else -> ""
        }
    fun acc2rate(acc: Double): String =
        when (acc) {
            in 0.0..49.9999 ->  "d"
            in 50.0..59.9999 -> "c"
            in 60.0..69.9999 -> "b"
            in 70.0..74.9999 -> "bb"
            in 75.0..79.9999 -> "bbb"
            in 80.0..89.9999 -> "a"
            in 90.0..93.9999 -> "aa"
            in 94.0..96.9999 -> "aaa"
            in 97.0..97.9999 -> "s"
            in 98.0..98.9999 -> "sp"
            in 99.0..99.4999 -> "ss"
            in 99.5..99.9999 -> "ssp"
            in 100.0..100.4999 -> "sss"
            in 100.5..101.0 -> "sssp"
            else -> ""
        }
    fun getOldRa(ds: Double, achievement: Double): Int {
        val baseRa = when (achievement) {
            in 0.0..49.9999 ->  0.0
            in 50.0..59.9999 -> 5.0
            in 60.0..69.9999 -> 6.0
            in 70.0..74.9999 -> 7.0
            in 75.0..79.9999 -> 7.5
            in 80.0..89.9999 -> 8.5
            in 90.0..93.9999 -> 9.5
            in 94.0..96.9999 -> 10.5
            in 97.0..97.9999 -> 12.5
            in 98.0..98.9999 -> 12.7
            in 99.0..99.4999 -> 13.0
            in 99.5..99.9999 -> 13.2
            in 100.0..100.4999 -> 13.5
            in 100.5..101.0 -> 14.0
            else -> 0.0
        }
        return (ds * (min(100.5, achievement) / 100) * baseRa).toIntFloor()
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
    suspend fun resolveImageCache(path: String): Bitmap {
        return if (images.containsKey(path)) {
            images[path]!!
        } else {
            MaimaiBot.resolveConfigFile(path).toVfs().readNativeImage()
        }

    }
    suspend fun resolveCoverCache(id: Int): Bitmap {
        return if (MaimaiConfig.enableMemCache) {
            if (images.containsKey("$id.jpg"))
                images["$id.jpg"]!!
            else
                resolveImageCache("default_cover.png")
        } else {
            resolveCover(id).readNativeImage()
        }
    }
    suspend fun resolveCover(id: Int): VfsFile {
        return if (imgDir["covers/$id.jpg"].exists())
            imgDir["covers/$id.jpg"]
        else
            MaimaiBot.resolveConfigFile("${MaimaiConfig.theme}/default_cover.png").toVfs()
    }
    suspend fun resolveCoverOrNull(id: String): VfsFile? {
        val target = imgDir["covers/$id.jpg"]
        return if (target.exists()) target else null
    }
}


const val DBC_SPACE = 32
const val SBC_SPACE = 12288
const val DBC_CHAR_START = 33
const val DBC_CHAR_END = 126
const val SBC_CHAR_START = 65281
const val SBC_CHAR_END = 65374
const val CONVERT_STEP = 65248
fun String.toSBC(): String {
    val buf = StringBuilder(length)
    this.toCharArray().forEach {
        buf.append(
            when (it.code) {
                DBC_SPACE -> SBC_SPACE
                in DBC_CHAR_START..DBC_CHAR_END -> it + CONVERT_STEP
                else -> it
            }
        )
    }
    return buf.toString()
}
fun String.toDBC(): String {
    val buf = StringBuilder(length)
    this.toCharArray().forEach {
        buf.append(
            when (it.code) {
                SBC_SPACE -> DBC_SPACE
                in SBC_CHAR_START..SBC_CHAR_END -> it - CONVERT_STEP
                else -> it
            }
        )
    }
    return buf.toString()
}

fun Char.isDBC() = this.code in DBC_SPACE..DBC_CHAR_END
fun String.ellipsize(max: Int): String {
    var result = ""
    var cnt = 0
    forEach {
        cnt += if (it.isDBC()) 1 else 2
        if (cnt > max) return@forEach
        result += it
    }
    return result + if (result.length != length) "…" else ""
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
fun String.clean(): String {
    if (this == "Link(CoF)")
        return "Link"
    var result = this.toDBC()
    while ("  " in result)
        result = result.replace("  ", " ")
    if (result.isBlank())
        return ""
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
suspend fun Context2d.drawCharts(charts: List<MaimaiPlayScore>, cols: Int, startX: Int, startY: Int, gap: Int,
                                 config: MaimaiPicConfig, sort: Boolean = true
) {
    (if (sort) charts.sortedWith(compareBy({ -it.ra }, { -it.achievements }))
    else charts).forEachIndexed { index, chart ->
        val coverRaw = resolveCoverCache(chart.song_id).toBMP32().scaled(config.coverWidth, config.coverWidth, true)
        val newHeight = (coverRaw.width / config.coverRatio).roundToInt()
        var cover = coverRaw.sliceWithSize(0, (coverRaw.height - newHeight) / 2,
            coverRaw.width, newHeight).extract()
        cover = cover.blurFixedSize(4).brightness(-0.04f)
        val x = startX + (index % cols) * (cover.width + gap)
        val y = startY + (index / cols) * (cover.height + gap)

        state.fillStyle = Colors.BLACK // TODO: Make color changeable
        fillRect(x + config.pos.getValue("shadow").x, y + config.pos.getValue("shadow").y,
            cover.width, cover.height)
        drawImage(cover, x, y)
        if (chart.title != "") {
            val label = config.pos.getValue("label")
            drawImage(resolveImageCache("label_${chart.level_label.replace(":", "")}.png").toBMP32().scaleLinear(label.scale, label.scale), x + label.x, y + label.y)

            // Details
            drawTextRelative(chart.title.ellipsize(12), x, y, config.pos.getValue("chTitle"), Colors.WHITE)
            drawTextRelative(chart.achievements.toString().limitDecimal(4) + "%", x, y,
                config.pos.getValue("chAchievements"), Colors.WHITE)
            drawTextRelative("Base: ${chart.ds} -> ${chart.ra}", x, y, config.pos.getValue("chBase"), Colors.WHITE)
            drawTextRelative("#${index + 1}(${chart.type})", x, y, config.pos.getValue("chRank"), Colors.WHITE)

            val rateIcon = config.pos.getValue("rateIcon")
            drawImage(
                resolveImageCache("music_icon_${chart.rate}.png").toBMP32().scaleLinear(rateIcon.scale, rateIcon.scale),
                x + rateIcon.x, y + rateIcon.y)
            if (chart.fc.isNotEmpty()) {
                val fcIcon = config.pos.getValue("fcIcon")
                drawImage(
                    resolveImageCache("music_icon_${chart.fc}.png").toBMP32().scaleLinear(fcIcon.scale, fcIcon.scale),
                    x + fcIcon.x, y + fcIcon.y)
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
    forEach { _, x, y ->
        this[x, y] = RGBA(this[x, y].r, this[x, y].g, this[x, y].b, 255)
    }
    return this
}
fun Bitmap.randomSlice(size: Int = 66) =
    sliceWithSize((0..width - size).random(), (0..height - size).random(), size, size).extract()

enum class CoverSource {
    WAHLAP, ZETARAKU
}

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
    val xrayAliasUrl: String by value("https://download.fanyu.site/maimai/alias.json")
    val prefix: String by value("")
    val enableMemCache: Boolean by value(true)
}
@Serializable
class MaimaiPicConfig(
    val bg: String, val coverWidth: Int, val coverRatio: Double, val oldCols: Int, val newCols: Int, val gap: Int,
    val pos: Map<String, MaiPicPosition>)

@Serializable
class MaimaiPicTheme(val b40: MaimaiPicConfig, val b50: MaimaiPicConfig, val scoreList: MaimaiPicConfig,
                     val dsList: MaimaiPicConfig, val info: MaimaiPicConfig)

val difficulty2Color = listOf(RGBA(124, 216, 79), RGBA(245, 187, 11), RGBA(255, 128, 140),
    RGBA(178, 91, 245), RGBA(244, 212, 255))