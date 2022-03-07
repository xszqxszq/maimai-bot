package xyz.xszq.maimai

import com.soywiz.korio.file.VfsFile
import com.soywiz.korio.stream.toInputStream
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.message.data.toPlainText
import net.mamoe.mirai.utils.ExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.io.File


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

suspend fun MessageEvent.quoteReply(message: Message): MessageReceipt<Contact> =
    this.subject.sendMessage(this.message.quote() + message)
suspend fun MessageEvent.quoteReply(message: String): MessageReceipt<Contact> = quoteReply(message.toPlainText())


fun String.toArgsList(): List<String> = this.trim().split(" +".toRegex()).toMutableList().filter { isNotBlank() }

suspend fun VfsFile.toExternalResource(): ExternalResource = readBytes().toExternalResource()