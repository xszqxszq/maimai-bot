@file:Suppress("SpellCheckingInspection", "MemberVisibilityCanBePrivate")

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.soywiz.kds.iterators.fastForEach
import com.soywiz.kds.iterators.fastForEachWithIndex
import com.soywiz.kds.mapDouble
import com.soywiz.korio.file.VfsFile
import com.soywiz.korio.file.baseName
import com.soywiz.korio.file.std.openAsZip
import com.soywiz.korio.file.std.toVfs
import com.soywiz.korio.lang.substr
import io.ktor.http.*
import kotlinx.coroutines.launch
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.subscribeMessages
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import net.mamoe.mirai.utils.info


object MaimaiBot : KotlinPlugin(
    JvmPluginDescription(
        id = "xyz.xszq.maimai-bot",
        name = "MaimaiBot",
        version = "1.0",
    ) {
        author("xszqxszq")
    }
) {
    var musics = arrayOf<MaimaiMusicInfo>()
    var aliases = mutableMapOf<String, List<String>>()
    private val resourcesDataDirs = listOf("img")
    private const val resourcesConfDir = "config"
    private val denied by lazy {
        PermissionService.INSTANCE.register(
            PermissionId("maimai-bot", "denyall"), "禁用 maimai-bot 的所有功能")
    }
    override fun onEnable() {
        launch {
            extractResources()
            reload()
            GlobalEventChannel.subscribeMessages {
                startsWith("b40") { username ->
                    notDenied(denied) {
                        if (username.isBlank())
                            queryBest("qq", sender.id.toString(), false, this)
                        else
                            queryBest("username", username, false, this)
                    }
                }
                startsWith("b50") { username ->
                    notDenied(denied) {
                        if (username.isBlank())
                            queryBest("qq", sender.id.toString(), true, this)
                        else
                            queryBest("username", username, true, this)
                    }
                }

                startsWith("随个") { raw ->
                    notDenied(denied) {
                        if (raw.isNotEmpty()) {
                            var difficulty: Int? = -1
                            val level = if (!raw[0].isDigit()) {
                                difficulty = when (raw[0]) {
                                    '绿' -> 0
                                    '黄' -> 1
                                    '红' -> 2
                                    '紫' -> 3
                                    '白' -> 4
                                    else -> null
                                }
                                raw.substr(1).filter { it.isDigit() || it == '+' }
                            } else {
                                raw.filter { it.isDigit() || it == '+' }
                            }
                            difficulty?.let {
                                getRandom(difficulty, level, this)
                            }
                        }
                    }
                }
                startsWith("id") { id ->
                    notDenied(denied) {
                        searchById(id.filter { it.isDigit() }, this)
                    }
                }
                startsWith("查歌") { name ->
                    notDenied(denied) {
                        searchByName(name, this)
                    }
                }
                endsWith("是什么歌") { alias ->
                    notDenied(denied) {
                        searchByAlias(alias, this)
                    }
                }
                startsWith("定数查歌") { rawArgs ->
                    notDenied(denied) {
                        val args = rawArgs.toArgsList().mapDouble { it.toDouble() }
                        if (args.size == 1)
                            searchByDS(args.first()..args.first(), this)
                        else
                            searchByDS(args.first()..args.last(), this)
                    }
                }

            }
            arrayOf("绿", "黄", "红", "紫", "白").fastForEachWithIndex { difficulty, str ->
                GlobalEventChannel.subscribeMessages {
                    startsWith(str + "id") { id ->
                        notDenied(denied) {
                            searchByIdAndDifficulty(id, difficulty, this)
                        }
                    }
                }
            }
            logger.info { "maimai-bot 插件加载完毕。" }
            DXProberApi.getCovers()
        }
        denied
    }
    private suspend fun reload() {
        musics = DXProberApi.getMusicList()
        MaimaiConfig.reload()
        MaimaiImage.reloadFonts()
        MaimaiImage.reloadImages()
        reloadAliases()
    }
    private suspend fun reloadAliases() {
        aliases.clear()
        csvReader().openAsync(MaimaiBot.resolveConfigFile("aliases.csv")) {
            readNext() // Exclude header
            while (true) {
                readNext() ?.let { row ->
                    aliases[row.first()] = row.subList(1, row.size).filter { it.trim().isNotBlank() }
                } ?: break
            }
        }
    }
    private suspend fun extractResources() {
        // TODO: Switch back to the normal approach. This is only a TEMPORARY solution
        val jar = (resolveConfigFile("../../plugins/")).toVfs().listSimple()
            .find { it.baseName.startsWith("maimai-bot") }!!.openAsZip()
        resourcesDataDirs.fastForEach { dir -> extractResourceDir(jar, dir) }
        extractResourceDir(jar, resourcesConfDir, true)
    }
    private suspend fun extractResourceDir(jar: VfsFile, dir: String, isConfig: Boolean = false) {
        val now = (if (isConfig) MaimaiBot.resolveConfigFile("") else MaimaiBot.resolveDataFile(dir)).toVfs()
        if (now.isFile())
            now.delete()
        now.mkdir()
//        getResourceAsStream(dir)?.reader()?.readLines()?.fastForEach {
//            runCatching {
//                val target = now[it]
//                if (!target.exists())
//                    getResourceAsStream("$dir/$it")!!.readBytes().writeToFile(target)
//            }.onFailure { e ->
//                e.printStackTrace()
//            }
//        }
        // TODO: Fix code above. This is only a TEMPORARY solution
        jar[dir].list().collect {
            runCatching {
                val target = now[it.baseName]
                if (!target.exists())
                    it.copyTo(target)
            }.onFailure { e ->
                e.printStackTrace()
            }
        }
    }

    private suspend fun queryBest(type: String = "qq", id: String, b50: Boolean, event: MessageEvent) = event.run {
        val result = DXProberApi.getPlayerData(type, id, b50)
        when (result.first) {
            HttpStatusCode.OK -> {
                MaimaiImage.generateBest(result.second!!, b50).toExternalResource().use { img ->
                    quoteReply(img.uploadAsImage(subject))
                }
            }
            HttpStatusCode.BadRequest -> quoteReply("用户名不存在，请确认用户名对应的玩家在 Diving-Fish 的舞萌 DX 查分器" +
                    "（https://www.diving-fish.com/maimaidx/prober/）上已注册")
            HttpStatusCode.Forbidden -> quoteReply("该玩家已禁止他人查询成绩")
        }
        return@run
    }
    private suspend fun searchById(id: String, event: MessageEvent) = event.run {
        musics.find { it.id == id } ?.let { selected ->
            quoteReply(getMusicInfoForSend(selected, this).build())
        }
    }
    private suspend fun searchByIdAndDifficulty(id: String, difficulty: Int, event: MessageEvent) = event.run {
        musics.find { it.id == id && it.level.size > difficulty } ?.let { selected ->
            val chart = selected.charts[difficulty]
            val result = MessageChainBuilder()
            result.add("${selected.id}. ${selected.title}\n")
            MaimaiImage.resolveCoverFileOrNull(selected.id) ?.let {
                it.toExternalResource().use { png ->
                    result.add(png.uploadAsImage(subject))
                }
            }
            result.add("\n等级: ${selected.level[difficulty]} (${selected.ds[difficulty]})")
            result.add("\nTAP: ${chart.notes[0]}\nHOLD: ${chart.notes[1]}")
            result.add("\nSLIDE: ${chart.notes[2]}")
            if (chart.notes.size == 5) // Interesting api lol
                result.add("\nTOUCH: ${chart.notes[3]}\nBREAK: ${chart.notes[4]}")
            else
                result.add("\nBREAK: ${chart.notes[3]}")
            if (chart.charter != "-")
                result.add("\n谱师：${chart.charter}")
            quoteReply(result.build())
        }
    }
    private suspend fun searchByName(name: String, event: MessageEvent) = event.run {
        val list = musics.filter { name.lowercase() in it.basic_info.title.lowercase() }.take(50)
        if (list.isEmpty()) {
            quoteReply("未搜索到歌曲，请检查拼写。如需搜索别称请发送“XXX是什么歌”")
        } else {
            var result = ""
            list.forEach { result += "${it.id}. ${it.basic_info.title}\n" }
            quoteReply(result)
        }
    }
    private suspend fun searchByAlias(alias: String, event: MessageEvent) = event.run {
        val names = aliases.filter { (_, value) ->
            var matched = false
            value.fastForEach inner@ {
                if (alias.lowercase().trim() == it.lowercase()) {
                    matched = true
                    return@inner
                }
            }
            matched
        }
        val matched = musics.filter { it.title in names.keys }
        when {
            matched.size > 1 -> {
                var result = "您要找的歌曲可能是："
                matched.fastForEach { result += "\n" + it.id + ". " + it.title }
                quoteReply(result)
            }
            matched.isNotEmpty() -> {
                val selected = matched.first()
                val result = MessageChainBuilder()
                result.add("您要找的歌曲可能是：\n")
                quoteReply(getMusicInfoForSend(selected, this, result).build())
            }
            else -> {}
        }
    }
    private suspend fun searchByDS(range: ClosedFloatingPointRange<Double>, event: MessageEvent) = event.run {
        var result = ""
        musics.filter { it.ds.any { now -> now in range } }.take(50).fastForEach { selected ->
            val difficulties = mutableListOf<Int>()
            selected.ds.fastForEachWithIndex { index, value ->
                if (value in range)
                    difficulties.add(index)
            }
            difficulties.forEach { difficulty ->
                result += "${selected.id}. ${selected.title} ${MaimaiImage.difficulty2Name(difficulty)} " +
                        "${selected.level[difficulty]} (${selected.ds[difficulty]})\n"
            }
        }
        quoteReply(if (result == "") "没有找到歌曲。\n使用方法：\n\t定数查歌 定数\n\t定数查歌 下限 上限" else result)
    }


    private suspend fun getRandom(difficulty: Int = -1, level: String, event: MessageEvent) = event.run {
        val selected = musics.filter {
            (level in it.level && it.level.size > difficulty && difficulty != -1 && it.level[difficulty] == level)
                    || (level == "" && it.level.size > difficulty)
                    || (difficulty == -1 && level in it.level)}.randomOrNull()
        selected ?.let {
            val result = MessageChainBuilder()
            result.add(PlainText("${selected.id}. ${selected.title}\n"))
            MaimaiImage.resolveCoverFileOrNull(selected.id) ?.let {
                it.toExternalResource().use { png ->
                    result.add(png.uploadAsImage(subject))
                }
            }
            quoteReply(result.build())
        } ?: run {
            quoteReply("没有这样的乐曲。")
        }
    }
    private suspend fun getMusicInfoForSend(
        selected: MaimaiMusicInfo, event: MessageEvent, builder: MessageChainBuilder = MessageChainBuilder())
            = event.run {
        builder.add(selected.id + ". " + selected.title + "\n")
        MaimaiImage.resolveCoverFileOrNull(selected.id) ?.let {
            it.toExternalResource().use { png ->
                builder.add(png.uploadAsImage(subject))
            }
        }
        builder.add("\n艺术家：${selected.basic_info.artist}" +
                "\n分类：${selected.basic_info.genre}" +
                "\n版本：${selected.basic_info.from}" + (if (selected.basic_info.is_new) " （计入b15）" else "") +
                "\nBPM：${selected.basic_info.bpm}" +
                "\n定数：" + selected.ds.joinToString("/"))
        builder
    }
}