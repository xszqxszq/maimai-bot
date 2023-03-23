@file:Suppress("unused", "SpellCheckingInspection")

package xyz.xszq

import xyz.xszq.MaimaiImage.imgDir
import com.soywiz.korio.async.launch
import com.soywiz.korio.file.writeToFile
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*


@Serializable
data class MaimaiPlayerData(val nickname: String, val rating: Int, val additional_rating: Int, val username: String,
                 val charts: Map<String, List<MaimaiPlayScore>>)

@Serializable
open class MaimaiPlayScore(val achievements: Double, val ds: Double, val fc: String, val fs: String,
                           val level: String, val level_index: Int, val level_label: String, var ra: Int,
                           val rate: String, val song_id: Int, val title: String, val type: String)
object EmptyMaimaiPlayRecord: MaimaiPlayScore(0.0, .0, "", "", "",
    0, "", 0, "", -1, "", "")
fun List<MaimaiPlayScore>.fillEmpty(target: Int): List<MaimaiPlayScore> {
    val result = toMutableList()
    for (i in 1..(target-size))
        result.add(EmptyMaimaiPlayRecord)
    return result
}

@Serializable
data class MaimaiMusicInfo(val id: String, val title: String, val type: String, var ds: MutableList<Double>,
                           var level: MutableList<String>, val cids: List<Int>, val charts: List<MaimaiChartInfo>,
                           val basic_info: MaimaiMusicBasicInfo
)
@Serializable
data class MaimaiChartInfo(val notes: List<Int>, val charter: String)
@Serializable
data class MaimaiMusicBasicInfo(val title: String, val artist: String, val genre: String, val bpm: Int,
                                val release_date: String, val from: String, val is_new: Boolean)
@Serializable
data class MaimaiChartNotes(val tap: Int, val hold: Int, val slide: Int, val break_: Int, val touch: Int ?= null) {
    companion object {
        fun fromList(notes: List<Int>): MaimaiChartNotes? = when (notes.size) {
            4 -> MaimaiChartNotes(notes[0], notes[1], notes[2], notes[3], null)
            5 -> MaimaiChartNotes(notes[0], notes[1], notes[2], notes[4], notes[3]) // Interesting result array
            else -> null
        }
    }
}
@Serializable
data class MaimaiChartStat(
    val count: Int ?= null, val avg: Double ?= null, val sssp_count: Int ?= null, val tag: String ?= null,
    val v: Int ?= null, val t: Int ?= null
)

@Serializable
data class MaiDataItem(val title: String, val image_file: String, val artist: String)

@Serializable
data class ZetarakuResponse(val songs: List<ZetarakuItem>)

@Serializable
data class ZetarakuItem(val title: String, val imageName: String, val artist: String)

@Serializable
data class MaimaiPlateResponse(val verlist: List<MaimaiBriefScore>)

@Serializable
data class MaimaiBriefScore(
    val achievements: Double, val fc: String, val fs: String, val id: Int, val level: String,
    val level_index: Int, val title: String, val type: String)

object DXProberApi {
    private const val site = "https://www.diving-fish.com"
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(JsonFeature) {
            serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        expectSuccess = false
    }
    suspend fun getMusicList(): Array<MaimaiMusicInfo> {
        kotlin.runCatching {
            return client.get("$site/api/maimaidxprober/music_data")
        }.onFailure {
            it.printStackTrace()
        }
        return emptyArray()
    }
    suspend fun getCovers() {
        if (!imgDir.exists())
            imgDir.mkdir()
        val covers = imgDir["covers"]
        if (!covers.exists())
            covers.mkdir()
        MaimaiBot.logger.info("正在缓存歌曲封面中……")
        var cnt = 0
        val semaphore = Semaphore(32)
        coroutineScope {
            when (MaimaiConfig.coverSource) {
                CoverSource.WAHLAP -> {
                    var songs = listOf<MaiDataItem>()
                    MaimaiConfig.maidataJsonUrls.forEach {
                        kotlin.runCatching {
                            client.get<List<MaiDataItem>>(it)
                        }.onSuccess {
                            songs = it
                            return@forEach
                        }
                    }
                    MaimaiBotSharedData.musics.values.forEach { info ->
                        val target = covers["${info.id}.jpg"]
                        if (!target.exists()) {
                            launch {
                                semaphore.withPermit {
                                    kotlin.runCatching {
                                        val response = client.get<HttpResponse>(
                                            "https://maimai.wahlap.com/maimai-mobile/img/Music/" +
                                                    songs.first { it.title.clean() == info.title.clean() &&
                                                            it.artist.clean() == info.basic_info.artist.clean()
                                                    }.image_file
                                        )
                                        if (response.status == HttpStatusCode.OK) {
                                            response.readBytes().writeToFile(target)
                                            cnt++
                                            MaimaiBot.logger.info("${info.id}. ${info.title} 封面下载完成")
                                        }
                                    }.onFailure { e ->
                                        MaimaiBot.logger.verbose(e.stackTraceToString())
                                    }
                                }
                            }
                        }
                    }
                }
                CoverSource.ZETARAKU -> {
                    val songs = client.get<ZetarakuResponse>("${MaimaiConfig.zetarakuSite}/maimai/data.json")
                    MaimaiBotSharedData.musics.values.forEach { info ->
                        val target = covers["${info.id}.jpg"]
                        if (!target.exists()) {
                            launch {
                                semaphore.withPermit {
                                    kotlin.runCatching {
                                        val response = client.get<HttpResponse>(
                                            "${MaimaiConfig.zetarakuSite}/maimai/img/cover/" +
                                                    songs.songs.first {
                                                        it.title.clean() == info.title.clean() &&
                                                            it.artist.clean() == info.basic_info.artist.clean()
                                                    }.imageName
                                        )
                                        if (response.status == HttpStatusCode.OK) {
                                            response.readBytes().writeToFile(target)
                                            cnt++
                                            MaimaiBot.logger.info("${info.id}. ${info.title} 封面下载完成")
                                        }
                                    }.onFailure { e ->
                                        MaimaiBot.logger.verbose(e.stackTraceToString())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        MaimaiBot.logger.info("本次已缓存 $cnt 个歌曲封面。")
    }
    suspend fun getAliases() {
        MaimaiBot.logger.info("正在更新别名……")
        kotlin.runCatching {
            client.get<Map<String, List<String>>>(MaimaiConfig.xrayAliasUrl).forEach { (alias, ids) ->
                ids.forEach ids@{ id ->
                    if (id !in MaimaiBotSharedData.musics)
                        return@ids
                    MaimaiBotSharedData.aliases[id]!!.add(alias)
                }
            }
        }.onFailure {
            it.printStackTrace()
        }
    }
    suspend fun getPlayerData(type: String = "qq", id: String,
                              b50: Boolean = false): Pair<HttpStatusCode, MaimaiPlayerData?> {
        val payload = buildJsonObject {
            put(type, id)
            if (b50)
                put("b50", true)
        }
        kotlin.runCatching {
            val result: HttpResponse = client.post("$site/api/maimaidxprober/query/player") {
                contentType(ContentType.Application.Json)
                body = payload
            }
            return Pair(result.status,
                if (result.status == HttpStatusCode.OK) json.decodeFromString(result.readText()) else null)
        }.onFailure {
            return Pair(HttpStatusCode.BadGateway, null)
        }
        return Pair(HttpStatusCode.BadGateway, null)
    }
    suspend fun getDataByVersion(type: String = "qq", id: String,
                               versions: List<String>): Pair<HttpStatusCode, MaimaiPlateResponse?> {
        val payload = buildJsonObject {
            put(type, id)
            put("version", JsonArray(versions.map { JsonPrimitive(it) }))
        }
        kotlin.runCatching {
            val result: HttpResponse = client.post("$site/api/maimaidxprober/query/plate") {
                contentType(ContentType.Application.Json)
                body = payload
            }
            return Pair(result.status,
                if (result.status == HttpStatusCode.OK) json.decodeFromString(result.readText()) else null)
        }.onFailure {
            return Pair(HttpStatusCode.BadGateway, null)
        }
        return Pair(HttpStatusCode.BadGateway, null)
    }
    suspend fun getChartStat(): Map<String, List<MaimaiChartStat>> {
        kotlin.runCatching {
            return client.get("$site/api/maimaidxprober/chart_stats")
        }.onFailure {
            it.printStackTrace()
        }
        return mapOf()
    }
}