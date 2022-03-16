import com.soywiz.korim.format.PNG
import com.soywiz.korim.format.encode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage

object GuessGame {
    private val guessStart = mutableMapOf<Long, Long>()
    private const val expectedFinishAfter = 65 * 1000L
    private fun isFinished(group: Long) = guessStart[group]?.let {
        if (System.currentTimeMillis() - it > expectedFinishAfter) {
            reset(group) // Prevent unavailable after exception
            true
        } else {
            false
        }
    } ?: true
    private fun setStart(group: Long) = run { guessStart[group] = System.currentTimeMillis() }
    private fun reset(group: Long) = run { guessStart.remove(group) }
    suspend fun handle(event: GroupMessageEvent) = event.run {
        if (!isFinished(group.id)) {
            quoteReply("本群有正在进行的猜歌哦~")
        } else {
            setStart(group.id)
            getRandomHot() ?.let { selected ->
                val stat = MaimaiBot.stats[selected.id]!!
                quoteReply("请各位发挥自己的聪明才智，根据我的提示来猜一猜这是哪一首歌曲吧！\n" +
                        "作答时，歌曲 id、歌曲标题（请尽量回答完整）、歌曲别名都将被视作有效答案哦~\n" +
                        "(致管理员：您可以使用“猜歌设置”指令开启或者关闭本群的猜歌功能)")
                val descriptions = getDescriptions(selected, stat).shuffled().take(6)
                val ansList = mutableListOf(selected.id, selected.title)
                MaimaiBot.aliases[selected.title] ?.let { ansList.addAll(it) }
                if (selected.type == "SD" && MaimaiBot.musics.containsKey("10" + selected.id)) {
                    ansList.add("10" + selected.id)
                    MaimaiBot.aliases[MaimaiBot.musics["10" + selected.id]!!.title] ?.let { ansList.addAll(it) }
                }
                val options = if (MaimaiImage.resolveCoverFileOrNull(selected.id) == null) 6 else 7
                ansList.replaceAll { it.lowercase() }
                coroutineScope {
                    var finished = false
                    launch {
                        descriptions.forEachIndexed { index, desc ->
                            delay(5000)
                            if (finished)
                                return@launch
                            group.sendMessage("${index + 1}/$options. 这首歌$desc")
                        }
                        val last = MessageChainBuilder()
                        if (options == 7) {
                            MaimaiImage.resolveCover(selected.id.toInt()).randomSlice().encode(PNG)
                                .toExternalResource().use {
                                    last.add("$options/$options. 这首歌的封面部分如图：")
                                    last.add(it.uploadAsImage(group))
                                }
                        }
                        last.add("\n30秒后将揭晓答案哦~")
                        delay(5000)
                        if (finished)
                            return@launch
                        group.sendMessage(last.build())
                        delay(30000)
                    }
                    launch {
                        kotlin.runCatching {
                            val answer = nextMessageEvent(expectedFinishAfter) {
                                ansList.any { ans ->
                                    ans == message.content.lowercase() ||
                                    ans in message.content.lowercase()
                                            || (message.content.length >= 5 && message.content.lowercase() in ans)
                                }
                            }
                            finished = true
                            val reply = MessageChainBuilder()
                            reply.add("恭喜您猜中了哦~\n")
                            answer.quoteReply(MaimaiBot.getMusicBriefForSend(selected, event, reply).build())
                            reset(group.id)
                        }.onFailure {
                            val reply = MessageChainBuilder()
                            reply.add("很遗憾，没有人猜中哦\n")
                            group.sendMessage(MaimaiBot.getMusicBriefForSend(selected, event, reply).build())
                            reset(group.id)
                        }
                    }
                }
            } ?: run {
                reset(group.id)
            }
        }
    }
    private fun getRandomHot() = MaimaiBot.musics.getOrDefault(MaimaiBot.hotList.randomOrNull(), null)
    private fun getDescriptions(song: MaimaiMusicInfo, stat: List<MaimaiChartStat>) = listOf(
        "的版本为 ${song.basic_info.from}${if (song.basic_info.is_new) " (计入b15)" else ""}",
        "的艺术家为 ${song.basic_info.artist}",
        "的分类为 ${song.basic_info.genre}",
        "的 BPM 为 ${song.basic_info.bpm}",
        "的红谱等级为 ${song.level[2]}，查分器统计难度为${tagEnToCh(stat[2].tag!!)}",
        "的紫谱等级为 ${song.level[3]}，查分器统计难度为${tagEnToCh(stat[3].tag!!)}",
        "的紫谱谱师为 ${song.charts[3].charter}",
        "${if (song.level.size == 4) "没有" else "有"}白谱",
        // Don't know whether this will have wrong result for Destroyer SD lol
        "${if (song.type == "DX" || MaimaiBot.musics.containsKey("10" + song.id)) "有" else "没有"} DX 谱面"
    )
    private fun tagEnToCh(tag: String) = when (tag) {
        "Very Easy" -> "十分简单"
        "Easy" -> "简单"
        "Medium" -> "中等"
        "Hard" -> "困难"
        "Very Hard" -> "十分困难"
        else -> tag
    }
}