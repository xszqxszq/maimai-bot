@file:Suppress("SpellCheckingInspection", "MemberVisibilityCanBePrivate")

package xyz.xszq

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import com.soywiz.kds.iterators.fastForEach
import com.soywiz.kds.iterators.fastForEachWithIndex
import com.soywiz.kds.mapDouble
import com.soywiz.kmem.toIntCeil
import com.soywiz.kmem.toIntFloor
import com.soywiz.korio.file.std.toVfs
import com.soywiz.korio.file.writeToFile
import com.soywiz.korio.lang.substr
import com.soywiz.korio.util.toStringDecimal
import io.ktor.http.*
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.cancel
import net.mamoe.mirai.console.permission.PermissionService.Companion.permit
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.event.*
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import net.mamoe.mirai.utils.info
import net.mamoe.yamlkt.Yaml
import xyz.xszq.MaimaiBotSharedData.aliases
import xyz.xszq.MaimaiBotSharedData.hotList
import xyz.xszq.MaimaiBotSharedData.musics
import xyz.xszq.MaimaiBotSharedData.stats
import xyz.xszq.MaimaiImage.difficulty2Name
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

object MaimaiBotSharedData {
    val musics = mutableMapOf<String, MaimaiMusicInfo>()
    val aliases = mutableMapOf<String, List<String>>()
    val stats = mutableMapOf<String, List<MaimaiChartStat>>()
    var hotList = listOf<String>()
}

object MaimaiBot : KotlinPlugin(
    JvmPluginDescription(
        id = "xyz.xszq.maimai-bot",
        name = "MaimaiBot",
        version = "1.3.0",
    ) {
        author("xszqxszq")
    }
) {
    private val resourcesDataDirs = listOf("font")
    private const val resourcesConfDir = "config"
    private val denied by lazy {
        PermissionService.INSTANCE.register(
            PermissionId("maimaiBot", "denyall"), "?????? maimai-bot ???????????????")
    }
    private val deniedGuess by lazy {
        PermissionService.INSTANCE.register(
            PermissionId("maimaiBot", "guess"), "?????? maimai-bot ???????????????")
    }
    var channel: EventChannel<Event> = GlobalEventChannel
    val yaml = Yaml {}
    override fun onEnable() {
        launch {
            extractResources()
            reload()
            if (MaimaiConfig.multiAccountsMode)
                channel = channel.validate(EventValidator())
            channel.subscribeMessages {
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
                startsWith("??????") { raw ->
                    notDenied(denied) {
                        if (raw.isNotEmpty()) {
                            var difficulty: Int? = -1
                            val level = if (!raw[0].isDigit()) {
                                difficulty = MaimaiImage.name2Difficulty(raw[0])
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
                        searchById(id.filter { it.isDigit() || it.isLetter() }, this)
                    }
                }
                startsWith("??????") { name ->
                    notDenied(denied) {
                        searchByName(name, this)
                    }
                }
                startsWith("????????????") { rawArgs ->
                    notDenied(denied) {
                        val args = rawArgs.toArgsList().mapDouble { it.toDouble() }
                        if (args.size == 1)
                            searchByDS(args.first()..args.first(), this)
                        else
                            searchByDS(args.first()..args.last(), this)
                    }
                }
                startsWith("?????????") { rawArgs ->
                    notDenied(denied) {
                        val args = rawArgs.toArgsList()
                        getScoreRequirements(args, this)
                    }
                }
                endsWith("????????????") { alias ->
                    notDenied(denied) {
                        searchByAlias(alias, this)
                    }
                }
                endsWith("???????????????") { id ->
                    notDenied(denied) {
                        searchAliasById(id.filter { it.isDigit() }, this)
                    }
                }
            }
            arrayOf("???", "???", "???", "???", "???").fastForEachWithIndex { difficulty, str ->
                channel.subscribeMessages {
                    startsWith(str + "id") { id ->
                        notDenied(denied) {
                            searchByIdAndDifficulty(id, difficulty, this)
                        }
                    }
                }
            }
            arrayOf("???", "???", "???", "???", "???", "???", "???", "???", "???", "???", "???", "???", "???", "???", "???", "???", "???",
                "???", "").forEach { ver ->
                arrayOf("???", "???", "???", "??????", "??????").forEach { type ->
                    if (ver != "" || type == "??????")
                        channel.subscribeMessages {
                            startsWithSimple(ver + type + "??????") { _, username ->
                                notDenied(denied) {
                                    if (username.isBlank())
                                        queryPlate(ver, type, "qq", sender.id.toString(), this)
                                    else
                                        queryPlate(ver, type, "username", username, this)
                                }
                            }
                        }
                }
            }
            arrayOf("1", "2", "3", "4", "5", "6", "7", "7+", "8", "8+", "9", "9+", "10", "10+", "11", "11+", "12",
                "12+", "13", "13+", "14", "14+", "15").forEach { level ->
                arrayOf("s", "s+", "ss", "ss+", "sss", "sss+", "ap", "ap+", "fc", "fc+", "fs", "fs+", "fdx", "fdx+",
                    "clear").forEach { type ->
                    channel.subscribeMessages {
                        startsWithSimple(level + type + "??????") { _, username ->
                            notDenied(denied) {
                                if (username.isBlank())
                                    queryStateByLevel(level, type, "qq", sender.id.toString(), this)
                                else
                                    queryStateByLevel(level, type, "username", username, this)
                            }
                        }
                    }
                }
            }
            channel.subscribeGroupMessages {
                startsWith("????????????") { option ->
                    if (sender.isOperator()) {
                        when (option.trim()) {
                            in listOf("??????", "??????", "??????") ->  {
                                group.permitteeId.cancel(deniedGuess, false)
                                quoteReply("????????????")
                            }
                            in listOf("??????", "??????", "??????") -> {
                                group.permitteeId.permit(deniedGuess)
                                quoteReply("????????????")
                            }
                            else -> quoteReply("??????????????????????????????/????????????????????????????????????\n\t???????????? ??????\n\t???????????? ??????")
                        }
                    }
                }
                "??????" {
                    notDenied(denied) {
                        notDenied(deniedGuess) {
                            GuessGame.handle(this)
                        }
                    }
                }
            }
            logger.info { "maimai-bot ?????????????????????" }
            DXProberApi.getCovers()
        }
        denied
    }
    private suspend fun reload() {
        musics.putAll(DXProberApi.getMusicList().associateBy { it.id })
        stats.putAll(DXProberApi.getChartStat())
        hotList = stats.map { (id, stat) -> Pair(id, stat.sumOf { it.count ?: 0 }) }
            .sortedByDescending { it.second }.take(150).map { it.first }
        MaimaiConfig.reload()
        MaimaiImage.theme = yaml.decodeFromString(
            MaimaiBot.resolveConfigFile("${MaimaiConfig.theme}/theme.yml").toVfs().readString())
        MaimaiImage.reloadFonts()
        MaimaiImage.reloadImages()
        reloadAliases()
    }
    private suspend fun reloadAliases() {
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
        resourcesDataDirs.fastForEach { dir -> extractResourceDir(dir) }
        extractResourceDir(resourcesConfDir, true)
    }
    private suspend fun extractResourceDir(dir: String, isConfig: Boolean = false) {
        val now = (if (isConfig) MaimaiBot.resolveConfigFile("") else MaimaiBot.resolveDataFile(dir)).toVfs()
        if (!now.isDirectory())
            now.delete()
        now.mkdir()
        getResourceFileList(dir).fastForEach {
            val target = now[it]
            if (!target.exists()) {
                if (!target.parent.exists())
                    target.parent.mkdir()
                getResourceAsStream("$dir/$it")!!.readBytes().writeToFile(target)
            }
        }
    }
    private fun getResourceFileList(path: String): List<String> {
        val result = mutableListOf<String>()
        javaClass.getResource("/$path") ?.let {
            val uri = it.toURI()
            kotlin.runCatching {
                FileSystems.newFileSystem(uri, buildMap<String, String> {
                    put("create", "true")
                })
            }.onFailure {}
            Files.walk(Paths.get(uri)).forEach { file ->
                if (file.isRegularFile()) {
                    if (file.parent.name != path)
                        result.add(file.parent.name + "/" + file.name)
                    else
                        result.add(file.name)
                }
            }
        }
        return result
    }

    private suspend fun queryBest(type: String = "qq", id: String, b50: Boolean, event: MessageEvent) = event.run {
        val result = DXProberApi.getPlayerData(type, id, b50)
        when (result.first) {
            HttpStatusCode.OK -> {
                MaimaiImage.generateBest(result.second!!, b50).toExternalResource().use { img ->
                    quoteReply(img.uploadAsImage(subject))
                }
            }
            HttpStatusCode.BadRequest -> quoteReply("????????????????????????????????????????????????????????? Diving-Fish ????????? DX ?????????" +
                    "???https://www.diving-fish.com/maimaidx/prober/???????????????")
            HttpStatusCode.Forbidden -> quoteReply("????????????????????????????????????")
        }
        return@run
    }
    private suspend fun searchById(id: String, event: MessageEvent) = event.run {
        musics[id] ?.let { selected ->
            quoteReply(getMusicInfoForSend(selected, this).build())
        }
    }
    private suspend fun searchByIdAndDifficulty(id: String, difficulty: Int, event: MessageEvent) = event.run {
        musics[id] ?.let { selected ->
            if (selected.level.size <= difficulty)
                return@run
            val chart = selected.charts[difficulty]
            val result = MessageChainBuilder()
            result.add("${selected.id}. ${selected.title}\n")
            MaimaiImage.resolveCoverFileOrNull(selected.id) ?.let {
                it.toExternalResource().use { png ->
                    result.add(png.uploadAsImage(subject))
                }
            }
            result.add("\n??????: ${selected.level[difficulty]} (${
                if (selected.level[difficulty].endsWith("+") &&
                    selected.ds[difficulty] == selected.ds[difficulty].toIntFloor() + 0.5)
                    "????????????"
                else
                    selected.ds[difficulty]
            })")
            MaimaiChartNotes.fromList(chart.notes) ?.let { notes ->
                result.add("\nTAP: ${notes.tap}\nHOLD: ${notes.hold}")
                result.add("\nSLIDE: ${notes.slide}")
                notes.touch ?.let { touch -> result.add("\nTOUCH: $touch") }
                result.add("\nBREAK: ${notes.break_}")
                if (chart.charter != "-")
                    result.add("\n?????????${chart.charter}")
                stats[id] ?.let { stat ->
                    result.add("\n????????????${stat[difficulty].tag}")
                    stat[difficulty].avg ?.let { avg ->
                        result.add("\n??????????????????${avg.toStringDecimal(2)}%")
                    }
                }
            }
            quoteReply(result.build())
        }
    }
    private suspend fun searchByName(name: String, event: MessageEvent) = event.run {
        val list = musics.values.filter { name.lowercase() in it.basic_info.title.lowercase() }.take(50)
        if (list.isEmpty()) {
            quoteReply("?????????????????????????????????????????????????????????????????????XXX???????????????")
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
        val matched = musics.values.filter { it.title in names.keys }
        when {
            matched.size > 1 -> {
                var result = "??????????????????????????????"
                matched.fastForEach { result += "\n" + it.id + ". " + it.title }
                quoteReply(result)
            }
            matched.isNotEmpty() -> {
                val selected = matched.first()
                val result = MessageChainBuilder()
                result.add("??????????????????????????????\n")
                quoteReply(getMusicInfoForSend(selected, this, result).build())
            }
            else -> {
                quoteReply("????????????????????????????????? bot ???????????????????????????" +
                        "???????????????????????????https://docs.qq.com/sheet/DWGNNYUdTT01PY2N1\n" +
                        "???????????????????????????????????????????????????\n\t?????? ????????????")
            }
        }
    }
    private suspend fun searchByDS(range: ClosedFloatingPointRange<Double>, event: MessageEvent) = event.run {
        var result = ""
        musics.values.filter { it.ds.any { now -> now in range } }.take(50).fastForEach { selected ->
            val difficulties = mutableListOf<Int>()
            selected.ds.fastForEachWithIndex { index, value ->
                if (value in range)
                    difficulties.add(index)
            }
            difficulties.forEach { difficulty ->
                result += "${selected.id}. ${selected.title} ${difficulty2Name(difficulty)} " +
                        "${selected.level[difficulty]} (${selected.ds[difficulty]})\n"
            }
        }
        quoteReply(if (result == "") "?????????????????????\n???????????????\n\t???????????? ??????\n\t???????????? ?????? ??????" else result)
    }
    private suspend fun searchAliasById(id: String, event: MessageEvent) = event.run {
        musics[id] ?.let { selected ->
            val nowAliases = aliases[selected.title]
            if (nowAliases?.isNotEmpty() == true)
                quoteReply("$id. ${selected.title} ??????????????????\n" + nowAliases.joinToString("\n"))
            else
                quoteReply("?????????????????????????????????\n??????????????? bot ?????????????????????" +
                        "???????????????????????????https://docs.qq.com/sheet/DWGNNYUdTT01PY2N1")
        }
    }


    private suspend fun getRandom(difficulty: Int = -1, level: String, event: MessageEvent) = event.run {
        val selected = musics.values.filter {
            (level in it.level && it.level.size > difficulty && difficulty != -1 && it.level[difficulty] == level)
                    || (level == "" && it.level.size > difficulty)
                    || (difficulty == -1 && level in it.level)}.randomOrNull()
        selected ?.let {
            quoteReply(getMusicInfoForSend(it, this).build())
        } ?: run {
            quoteReply("????????????????????????")
        }
    }
    private suspend fun getScoreRequirements(args: List<String>, event: MessageEvent) = event.run {
        when {
            args.size == 2 && args[1].toDoubleOrNull() != null && (args[1].toDouble() in 0.0..101.0) -> {
                MaimaiImage.name2Difficulty(args[0][0]) ?.let { difficulty ->
                    args[0].filter { it.isDigit() || it.isLetter() }.toIntOrNull() ?.let { id ->
                        musics[id.toString()] ?.let { target ->
                            if (target.level.size <= difficulty) {
                                quoteReply("???????????????????????????????????????????????? ??????????????????????????????")
                            } else {
                                val line = args[1].toDouble()
                                val notes = MaimaiChartNotes.fromList(target.charts[difficulty].notes)!!
                                val totalScore = notes.tap * 500.0 + notes.hold * 1000 + notes.slide * 1500 +
                                        (notes.touch ?: 0) * 500 + notes.break_ * 2500
                                val breakBonus = 0.01 / notes.break_
                                val break50Reduce = totalScore * breakBonus / 4
                                val reduce = 101.0 - line
                                quoteReply("[${args[0][0]}] ${target.title}\n" +
                                        "????????? $line% ??????????????? TAP GREAT ????????? " +
                                        String.format("%.2f", totalScore * reduce / 10000) +
                                        " (?????? -" + String.format("%.4f", 10000.0 / totalScore) + "%),\n" +
                                        "BREAK 50??? (?????? ${notes.break_} ???) ????????? " +
                                        String.format("%.3f", break50Reduce / 100) + " ??? TAP GREAT " +
                                        "(-" + String.format("%.4f", break50Reduce / totalScore * 100) + "%)")
                            }
                        } ?: run {
                            quoteReply("?????????????????????????????????????????? ??????????????????????????????")
                        }
                    }
                } ?: run {
                    quoteReply("???????????????????????????????????? ??????????????????????????????")
                }
            }
            args.size == 1 && args.first() in listOf("??????", "help") ->
                quoteReply(
                    "?????????????????????????????????????????????\n" +
                            "???????????????????????? <??????+??????id> <?????????>\n" +
                            "?????????????????? ???379 100.5\n" +
                            "????????????????????????????????? TAP GREAT ???????????? BREAK 50???????????? TAP GREAT ??????\n" +
                            "????????? TAP GREAT ???????????????\n" +
                            "GREAT/GOOD/MISS\n" +
                            "TAP   1/2.5/5\n" +
                            "HOLD  2/5/10\n" +
                            "SLIDE 3/7/15\n" +
                            "TOUCH 1/2/5\n" +
                            "BREAK 5/12.5/25(??????200???)"
                )
            else -> quoteReply("???????????????????????????????????? ??????????????????????????????")
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
        builder.add("\n????????????${selected.basic_info.artist}" +
                "\n?????????${selected.basic_info.genre}" +
                "\n?????????${selected.basic_info.from}" + (if (selected.basic_info.is_new) " ?????????b15???" else "") +
                "\nBPM???${if (selected.basic_info.bpm < 0) "??????" else selected.basic_info.bpm}" +
                "\n?????????" + selected.ds.mapIndexed { index, d ->
                    if (selected.level[index].endsWith("+") && d == d.toIntFloor() + 0.5)
                        selected.level[index]
                    else
                        d
                }.joinToString("/")
        )
        builder
    }
    suspend fun getMusicBriefForSend(
        selected: MaimaiMusicInfo, event: MessageEvent, builder: MessageChainBuilder = MessageChainBuilder())
            = event.run {
        builder.add(selected.id + ". " + selected.title + "\n")
        MaimaiImage.resolveCoverFileOrNull(selected.id) ?.let {
            it.toExternalResource().use { png ->
                builder.add(png.uploadAsImage(subject))
            }
        }
        builder.add("\n????????????${selected.basic_info.artist}" + "\n?????????" + selected.ds.joinToString("/"))
        builder
    }
    fun getPlateVerList(version: String) = when (version) {
        "???" -> listOf("maimai", "maimai PLUS")
        "???" -> listOf("maimai GreeN")
        "???" -> listOf("maimai GreeN PLUS")
        "???" -> listOf("maimai ORANGE")
        "???" -> listOf("maimai ORANGE PLUS")
        "???" -> listOf("maimai PiNK")
        "???" -> listOf("maimai PiNK PLUS")
        "???" -> listOf("maimai MURASAKi")
        "???" -> listOf("maimai MURASAKi PLUS")
        "???" -> listOf("maimai MiLK")
        "???" -> listOf("MiLK PLUS")
        "???" -> listOf("maimai FiNALE")
        in listOf("???", "???") -> listOf("maimai ???????????????", "maimai ??????????????? PLUS")
        in listOf("???", "???") -> listOf("maimai ??????????????? Splash")
        in listOf("???", "") -> listOf("maimai", "maimai PLUS", "maimai GreeN", "maimai GreeN PLUS", "maimai ORANGE",
            "maimai ORANGE PLUS", "maimai PiNK", "maimai PiNK PLUS", "maimai MURASAKi", "maimai MURASAKi PLUS",
            "maimai MiLK", "MiLK PLUS", "maimai FiNALE")
        "all" -> listOf("maimai", "maimai PLUS", "maimai GreeN", "maimai GreeN PLUS", "maimai ORANGE",
            "maimai ORANGE PLUS", "maimai PiNK", "maimai PiNK PLUS", "maimai MURASAKi", "maimai MURASAKi PLUS",
            "maimai MiLK", "MiLK PLUS", "maimai FiNALE", "maimai ???????????????", "maimai ??????????????? PLUS",
            "maimai ??????????????? Splash", "maimai ??????????????? Splash PLUS")
        else -> emptyList()
    }
    suspend fun queryPlate(vName: String, type: String, queryType: String, id: String, event: MessageEvent) = event.run {
        val vList = getPlateVerList(vName)
        val result = DXProberApi.getDataByVersion(queryType, id, vList)
        when (result.first) {
            HttpStatusCode.BadRequest -> quoteReply("????????????????????????????????????????????????????????? Diving-Fish ????????? DX ?????????" +
                    "???https://www.diving-fish.com/maimaidx/prober/???????????????")
            HttpStatusCode.Forbidden -> quoteReply("?????????????????????????????????????????????????????????????????????QQ????????????????????????????????????????????????")
        }
        if (result.second == null || result.first != HttpStatusCode.OK)
            return@run
        val data = result.second!!.verlist
        val remains = MutableList<MutableList<Pair<Int, Int>>>(5) { mutableListOf() }
        data.filter {
            when (type) {
                "???" -> it.achievements < 100.0
                "???" -> it.fc.isEmpty()
                "??????" -> it.fs !in listOf("fsd", "fsdp")
                "???" -> it.fc !in listOf("ap", "app")
                "??????" -> it.achievements < 60.0
                else -> false
            }
        }.forEach {
            remains[it.level_index].add(Pair(it.id, it.level_index))
        }
        musics.values.filter { it.basic_info.from in vList }.forEach { song ->
             for (i in 0 until song.ds.size) {
                if (data.none { it.id.toString() == song.id && it.level_index == i }) {
                    remains[i].add(Pair(song.id.toInt(), i))
                }
            }
        }
        if (vName != "???" && type != "??????")
            remains[4] = mutableListOf()
        val excluded = listOf(341, 451, 455, 460, 792, 853)
        val remasterExcluded = listOf(85, 111, 115, 133, 134, 144, 155, 239, 240, 248, 260, 261, 364, 367, 378,
            463, 472)
        if (vName == "???")
            remains.forEach { l ->
                l.removeIf { it.first == 70 }
            }
        remains.forEach { l ->
            l.removeIf { it.first in excluded }
        }
        remains[4].removeIf { it.first in remasterExcluded }
        var reply = ""
        when {
            remains.all { it.isEmpty() } -> {
                quoteReply("??????????????????${vName}${type}??????????????????")
                return
            }
            remains[3].isEmpty() && remains[4].isEmpty() -> reply = "???????????????${vName}${type}?????????\n"
        }
        reply += "??????${vName}${type}?????????????????????"
        listOf("??????", "??????", "??????", "??????", "??????").forEachIndexed { i, name ->
            if (remains[i].isNotEmpty()) {
                reply += "\n${name}??????${remains[i].size}???"
            }
        }
        val hard = (remains[3] + remains[4]).filter {
            stats[it.first.toString()]?.get(it.second)?.tag?.equals("Very Hard") == true &&
                    musics[it.first.toString()]!!.ds[it.second] >= 13.7
        }.sortedByDescending { musics[it.first.toString()]!!.ds[it.second] }.take(5)
        if (hard.isNotEmpty()) {
            reply += "\n??????????????????"
            hard.forEach {
                val info = musics[it.first.toString()]!!
                reply += "\n${info.id}. ${info.title} ${difficulty2Name(it.second)} Lv. ${info.level[it.second]}" +
                        "(${data.find { d -> d.id == it.first && d.level_index == it.second }
                            ?.achievements?.toStringDecimal(4)?:"0.0000"}%)"
            }
        }
        val pc = (remains.sumOf { it.size } * 1.0 / 3).toIntCeil()
        reply += "\n??????${remains.sumOf { it.size }}???????????????${pc}pc??????"
        if (pc / 6 != 0) // pc * 10 / 60 floor
            reply += "${(pc * 10.0 / 60).toIntFloor()}??????"
        if (pc * 10 % 60 != 0)
            reply += "${pc * 10 % 60}??????"
        quoteReply(reply)
    }
    suspend fun queryStateByLevel(
        level: String, type: String, queryType: String, id: String, event: MessageEvent
    ) = event.run {
        val result = DXProberApi.getDataByVersion(queryType, id, getPlateVerList("all"))
        when (result.first) {
            HttpStatusCode.BadRequest -> quoteReply("????????????????????????????????????????????????????????? Diving-Fish ????????? DX ?????????" +
                    "???https://www.diving-fish.com/maimaidx/prober/???????????????")
            HttpStatusCode.Forbidden -> quoteReply("?????????????????????????????????????????????????????????????????????QQ????????????????????????????????????????????????")
        }
        if (result.second == null || result.first != HttpStatusCode.OK)
            return@run
        val data = result.second!!.verlist
        val remains = data.filter { it.level == level }.filter {
            when (type) {
                "fc" -> it.fc.isEmpty()
                "fc+" -> it.fc !in listOf("fcp", "ap", "app")
                "ap" -> it.fc !in listOf("ap", "app")
                "ap+" -> it.fc != "app"
                "fs" -> it.fs.isEmpty()
                "fs+" -> it.fs !in listOf("fsp", "fsd", "fsdp")
                "fdx" -> it.fs !in listOf("fsd", "fsdp")
                "fdx+" -> it.fs != "fsdp"
                "clear" -> it.achievements < 80.0
                "s" -> it.achievements < 97.0
                "s+" -> it.achievements < 98.0
                "ss" -> it.achievements < 99.0
                "ss+" -> it.achievements < 99.5
                "sss" -> it.achievements < 100.0
                "sss+" -> it.achievements < 100.5
                else -> return@run
            }
        }.map { Pair(it.id, it.level_index) }.toMutableList()
        var tot = 0
        musics.values.filter { level in it.level }.forEach { song ->
            song.level.forEachIndexed { i, v ->
                if (v == level) {
                    tot += 1
                    if (data.none { it.id.toString() == song.id && it.level_index == i })
                        remains.add(Pair(song.id.toInt(), i))
                }
            }
        }
        if (remains.isEmpty()) {
            quoteReply("??????????????????${level}???${type}???")
            return
        }
        quoteReply("??????${level}${type}???????????????\n???${tot}?????????????????????${tot-remains.size}????????????${remains.size}???")
    }
}