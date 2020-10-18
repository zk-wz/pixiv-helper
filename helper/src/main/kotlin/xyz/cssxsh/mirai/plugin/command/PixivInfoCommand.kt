package xyz.cssxsh.mirai.plugin.command

import net.mamoe.mirai.console.command.CommandSenderOnMessage
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.message.MessageEvent
import xyz.cssxsh.mirai.plugin.PixivHelper
import xyz.cssxsh.mirai.plugin.PixivHelperLogger
import xyz.cssxsh.mirai.plugin.PixivHelperPlugin
import xyz.cssxsh.mirai.plugin.data.PixivCacheData
import xyz.cssxsh.mirai.plugin.data.PixivStatisticalData
import xyz.cssxsh.mirai.plugin.getHelper

@Suppress("unused")
object PixivInfoCommand : CompositeCommand(
    PixivHelperPlugin,
    "info",
    description = "信息指令",
    prefixOptional = true
), PixivHelperLogger {
    /**
     * 获取助手信息
     */
    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.helper() = getHelper().runCatching {
        buildString {
            appendLine("账户: ${getAuthInfo().user.account}")
            appendLine("Token: ${getAuthInfo().accessToken}")
            appendLine("ExpiresTime: ${expiresTime.format(PixivHelper.DATE_FORMAT_CHINESE)}")
            appendLine("简略信息: $simpleInfo")
            appendLine("缓存数: ${PixivCacheData.caches().size}")
            appendLine("全年龄色图数: ${PixivCacheData.eros().size}")
            appendLine("R18色图数: ${PixivCacheData.r18s().size}")
        }
    }.onSuccess {
        quoteReply(it)
    }.onFailure {
        quoteReply(it.toString())
    }.isSuccess

    /**
     * 获取用户信息
     */
    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.user(target: User) = runCatching {
        PixivStatisticalData.getCount(target).let { (ero, tags) ->
            buildString {
                appendLine("用户: $target")
                appendLine("使用色图指令次数: $ero")
                appendLine("使用tag指令次数: $tags")
            }
        }
    }.onSuccess {
        quoteReply(it)
    }.onFailure {
        quoteReply(it.toString())
    }.isSuccess
}