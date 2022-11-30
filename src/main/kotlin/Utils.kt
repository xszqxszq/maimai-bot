package xyz.xszq

import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.soywiz.kds.atomic.kdsFreeze
import com.soywiz.klock.measureTime
import com.soywiz.klock.measureTimeWithResult
import com.soywiz.klock.milliseconds
import com.soywiz.korim.font.DefaultTtfFont
import com.soywiz.korim.font.TtfFont
import com.soywiz.korim.font.TtfNativeSystemFontProvider
import com.soywiz.korio.async.runBlockingNoJs
import com.soywiz.korio.concurrent.atomic.KorAtomicRef
import com.soywiz.korio.file.VfsFile
import com.soywiz.korio.file.baseName
import com.soywiz.korio.file.std.localVfs
import com.soywiz.korio.lang.Environment
import com.soywiz.korio.lang.expand
import kotlinx.coroutines.withTimeout
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.EventPriority
import net.mamoe.mirai.event.MessageDsl
import net.mamoe.mirai.event.MessageSubscribersBuilder
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.syncFromEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.message.data.toPlainText
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource

val pass = {}

suspend fun <T> MessageEvent.notDenied(permission: Permission, block: suspend () -> T): T? = when (this) {
    is GroupMessageEvent -> {
        if (group.permitteeId.hasPermission(permission))
            null
        else
            block.invoke()
    }
    is FriendMessageEvent -> {
        if (sender.permitteeId.hasPermission(permission))
            null
        else
            block.invoke()
    }
    else -> null
}

@PublishedApi // inline, safe to remove in the future
internal inline fun <reified P : MessageEvent>
        P.createMapperForGroup(crossinline filter: suspend P.(P) -> Boolean): suspend (P) -> P? =
    mapper@{ event ->
        if (event !is GroupMessageEvent) return@mapper null
        if (!event.isGroupIdenticalWith(this as GroupMessageEvent)) return@mapper null
        if (!filter(event, event)) return@mapper null
        event
    }
@JvmSynthetic
suspend inline fun <reified P : MessageEvent> P.nextMessageEvent(
    timeoutMillis: Long = -1,
    priority: EventPriority = EventPriority.MONITOR,
    noinline filter: suspend P.(P) -> Boolean = { true }
): MessageEvent {
    val mapper: suspend (P) -> P? = createMapperForGroup(filter)

    return (if (timeoutMillis == -1L) {
        MaimaiBot.channel.syncFromEvent(priority, mapper)
    } else {
        withTimeout(timeoutMillis) {
            MaimaiBot.channel.syncFromEvent(priority, mapper)
        }
    })
}
fun GroupMessageEvent.isGroupIdenticalWith(another: GroupMessageEvent): Boolean {
    return this.group == another.group
}

suspend fun MessageEvent.quoteReply(message: Message): MessageReceipt<Contact> =
    this.subject.sendMessage(this.message.quote() + message)
suspend fun MessageEvent.quoteReply(message: String): MessageReceipt<Contact> = quoteReply(message.toPlainText())


typealias MessageListener<T, R> = @MessageDsl suspend T.(String) -> R
@MessageDsl
internal fun <M : MessageEvent, Ret, R : RR, RR> MessageSubscribersBuilder<M, Ret, R, RR>.content(
    filter: M.(String) -> Boolean,
    onEvent: MessageListener<M, RR>
): Ret =
    subscriber(filter) { onEvent(this, it) }


fun String.substringAfterPrefix(start: String): String = substring(start.length)
fun String.toSimple(): String = ZhConverterUtil.toSimple(this)

internal fun <M : MessageEvent, Ret, R : RR, RR> MessageSubscribersBuilder<M, Ret, R, RR>.startsWithSimpleImpl(
    prefix: String,
    removePrefix: Boolean = true,
    trim: Boolean = true,
    onEvent: @MessageDsl suspend M.(String, String) -> R
): Ret {
    return if (trim) {
        val toCheck = prefix.trim()
        content({ it.toSimple().lowercase().trimStart().startsWith(toCheck) }) {
            if (removePrefix) onEvent(message.contentToString().toSimple().lowercase().substringAfter(toCheck).trim(),
                message.contentToString().substringAfterPrefix(toCheck).trim())
            else onEvent(this, message.contentToString().toSimple().lowercase().trim(),
                message.contentToString().trim())
        }
    } else content({ it.toSimple().lowercase().startsWith(prefix) }) {
        if (removePrefix) onEvent(message.contentToString().toSimple().lowercase().removePrefix(prefix),
            message.contentToString().substringAfterPrefix(prefix).trim())
        else onEvent(this, message.contentToString().toSimple().lowercase(),
            message.contentToString().trim())
    }
}
/**
 * startsWith对于繁体增加支持的版本
 * M.(简体化且小写后的参数, 原始参数) -> R
 */
@MessageDsl
fun <M : MessageEvent, Ret, R : RR, RR> MessageSubscribersBuilder<M, Ret, R, RR>.startsWithSimple(
    prefix: String, removePrefix: Boolean = true, trim: Boolean = true,
    onEvent: @MessageDsl suspend M.(String, String) -> R
): Ret = startsWithSimpleImpl(prefix, removePrefix, trim, onEvent)

fun String.toArgsList(): List<String> = this.trim().split(" +".toRegex()).toMutableList().filter { isNotBlank() }

suspend fun VfsFile.toExternalResource(): ExternalResource = readBytes().toExternalResource()


private val linuxFolders get() = listOf("/usr/share/fonts", "/usr/local/share/fonts", "~/.fonts")
private val windowsFolders get() = listOf("%WINDIR%\\Fonts", "%LOCALAPPDATA%\\Microsoft\\Windows\\Fonts")
private val macosFolders get() = listOf("/System/Library/Fonts/", "/Library/Fonts/", "~/Library/Fonts/", "/Network/Library/Fonts/")
private val iosFolders get() = listOf("/System/Library/Fonts/Cache", "/System/Library/Fonts")
private val androidFolders get() = listOf("/system/Fonts", "/system/font", "/data/fonts")

// TODO: Remove this after korim fixed the bug
open class MultiPlatformNativeSystemFontProvider(
    private val folders: List<String> = linuxFolders + windowsFolders + macosFolders + androidFolders + iosFolders
            + listOf(MaimaiBot.resolveDataFile("font").absolutePath),
    private val fontCacheFile: String = "~/.korimFontCache"
) : TtfNativeSystemFontProvider() {
    private fun listFontNamesMap(): Map<String, VfsFile> = runBlockingNoJs {
        val out = LinkedHashMap<String, VfsFile>()
        val time = measureTime {
            val fontCacheVfsFile = localVfs(Environment.expand(fontCacheFile))
            val fileNamesToName = LinkedHashMap<String, String>()
            val oldFontCacheVfsFileText = try {
                fontCacheVfsFile.readString()
            } catch (e: Throwable) {
                ""
            }
            for (line in oldFontCacheVfsFileText.split("\n")) {
                val (file, name) = line.split("=", limit = 2) + listOf("", "")
                fileNamesToName[file] = name
            }
            for (folder in folders) {
                try {
                    val file = localVfs(Environment.expand(folder))
                    for (f in file.listRecursiveSimple()) {
                        try {
                            val name = fileNamesToName.getOrPut(f.baseName) {
                                val (ttf, _) = measureTimeWithResult { TtfFont.readNames(f) }
                                //if (totalTime >= 1.milliseconds) println("Compute name size[${f.size()}] '${ttf.ttfCompleteName}' $totalTime")
                                ttf.ttfCompleteName
                            }
                            //println("name=$name, f=$f")
                            if (name != "") {
                                out[name] = f
                            }
                        } catch (e: Throwable) {
                            fileNamesToName.getOrPut(f.baseName) { "" }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            val newFontCacheVfsFileText = fileNamesToName.map { "${it.key}=${it.value}" }.joinToString("\n")
            if (newFontCacheVfsFileText != oldFontCacheVfsFileText) {
                try {
                    fontCacheVfsFile.writeString(newFontCacheVfsFileText)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
        if (time >= 100.milliseconds) {
            println("Load System font names in $time")
        }
        //println("fileNamesToName: $fileNamesToName")
        out
    }

    private fun listFontNamesMapLC(): Map<String, VfsFile> = listFontNamesMap().mapKeys { it.key.normalizeName() }
    override fun defaultFont(): TtfFont = DefaultTtfFont

    override fun listFontNamesWithFiles(): Map<String, VfsFile> = listFontNamesMap()

    private val _namesMapLC = KorAtomicRef<Map<String, VfsFile>?>(null)
    private val namesMapLC: Map<String, VfsFile> get() {
        if (_namesMapLC.value == null) {
            _namesMapLC.value = kdsFreeze(listFontNamesMapLC())
        }
        return _namesMapLC.value!!
    }

    override fun loadFontByName(name: String, freeze: Boolean): TtfFont? =
        runBlockingNoJs { namesMapLC[name.normalizeName()]?.let { TtfFont(it.readAll(), freeze = freeze) } }
}