package xyz.cssxsh.mirai.plugin.command

import kotlinx.coroutines.*
import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.*
import xyz.cssxsh.mirai.plugin.*
import xyz.cssxsh.mirai.plugin.data.*
import xyz.cssxsh.mirai.plugin.tools.*
import xyz.cssxsh.pixiv.*
import xyz.cssxsh.pixiv.apps.*
import java.time.*

object PixivPlayCommand : CompositeCommand(
    owner = PixivHelperPlugin,
    "play", "播放",
    description = "PIXIV播放指令"
) {

    override val prefixOptional: Boolean = true

    private var PixivHelper.play by PixivHelperDelegate { CompletedJob }

    private var PixivHelper.duration by PixivHelperDelegate { 10 * 1000L }

    @SubCommand("interval", "间隔")
    @Description("设置间隔")
    suspend fun CommandSenderOnMessage<*>.interval(seconds: Int) = withHelper {
        duration = seconds * 1000L
        "设置间隔 $duration"
    }

    private suspend fun CommandSenderOnMessage<*>.play(illusts: List<IllustInfo>) = launch {
        for (illust in illusts) {
            if (isActive.not()) break
            delay(helper.duration)

            runCatching {
                sendIllust(illust)
            }.onFailure {
                logger.warning { "播放错误 $it" }
            }
        }
    }

    private suspend fun CommandSenderOnMessage<*>.forward(illusts: List<IllustInfo>, title: String): Message {
        if (illusts.isEmpty()) return "列表为空".toPlainText()

        sendMessage("开始将${illusts.size}个作品合成转发消息，请稍后...")

        val list = mutableListOf<ForwardMessage.Node>()

        for (illust in illusts) {
            if (isActive.not()) break

            runCatching {
                list.add(
                    ForwardMessage.Node(
                        senderId = fromEvent.sender.id,
                        senderName = illust.user.name,
                        time = illust.createAt.toEpochSecond().toInt(),
                        message = helper.buildMessageByIllust(illust)
                    )
                )
            }.onFailure {
                list.add(
                    ForwardMessage.Node(
                        senderId = fromEvent.sender.id,
                        senderName = illust.user.name,
                        time = illust.createAt.toEpochSecond().toInt(),
                        message = "[${illust.pid}]构建失败".toPlainText()
                    )
                )
                logger.warning { "播放错误 $it" }
            }
        }

        logger.info { "play list 合成完毕" }
        return RawForwardMessage(list).render(TitleDisplayStrategy(title))
    }

    @SubCommand("ranking", "排行榜")
    @Description("根据 排行榜 播放图集")
    suspend fun CommandSenderOnMessage<*>.ranking(mode: RankMode, date: LocalDate? = null) = withHelper {
        check(!play.isActive) { "其他列表播放中" }
        val illusts = illustRanking(mode = mode, date = date).illusts
            .apply { replicate() }
            .filter { it.age == AgeLimit.ALL }

        if (model != SendModel.Forward && duration > 0) {
            play = play(illusts = illusts)
            "开始播放[${mode}]排行榜，共${illusts.size}个作品，间隔 ${duration / 1000}s"
        } else {
            forward(illusts = illusts, title = "[${mode}]排行榜")
        }
    }

    @SubCommand("rank", "排行")
    @Description("根据 words 播放NaviRank")
    suspend fun CommandSenderOnMessage<*>.rank(vararg words: String) = withHelper {
        check(!play.isActive) { "其他列表播放中" }
        val rank = NaviRank.getTagRank(words = words)

        if (model != SendModel.Forward && duration > 0) {
            play = launch {
                for (info in rank.records) {
                    if (isActive.not()) break
                    delay(duration)

                    runCatching {
                        sendIllust(getIllustInfo(pid = info.pid, flush = false))
                    }.onFailure {
                        logger.warning { "播放错误 $it" }
                    }
                }
            }
            "开始播放NaviRank[${rank.title}]，共${rank.records.size}个作品，间隔 $duration"
        } else {
            if (rank.records.isEmpty()) return@withHelper "列表为空"
            val list = mutableListOf<ForwardMessage.Node>()

            for (info in rank.records) {
                if (isActive.not()) break

                runCatching {
                    val illust = getIllustInfo(pid = info.pid, flush = false)

                    list.add(
                        ForwardMessage.Node(
                            senderId = fromEvent.sender.id,
                            senderName = fromEvent.sender.nameCardOrNick,
                            time = illust.createAt.toEpochSecond().toInt(),
                            message = buildMessageByIllust(illust)
                        )
                    )
                }.onFailure {
                    logger.warning { "播放错误 $it" }
                }
            }

            RawForwardMessage(list).render(TitleDisplayStrategy("NaviRank[${rank.title}]"))
        }
    }

    @SubCommand("recommended", "推荐")
    @Description("根据 系统推荐 播放图集")
    suspend fun CommandSenderOnMessage<*>.recommended() = withHelper {
        check(!play.isActive) { "其他列表播放中" }
        val user = info().user
        val illusts = illustRecommended().illusts
            .apply { replicate() }
            .filter { it.age == AgeLimit.ALL }

        if (model != SendModel.Forward && duration > 0) {
            play = play(illusts)
            "开始播放用户[${user.name}]系统推荐，共${illusts.size}个作品，间隔 $duration"
        } else {
            forward(illusts = illusts, title = "recommended")
        }
    }

    @SubCommand("mark", "收藏")
    @Description("播放收藏")
    suspend fun CommandSenderOnMessage<*>.mark(tag: String? = null) = withHelper {
        check(!play.isActive) { "其他列表播放中" }
        val user = info().user
        val illusts = bookmarksRandom(detail = userDetail(uid = user.uid), tag = tag).illusts

        if (model != SendModel.Forward && duration > 0) {
            play = play(illusts)
            "开始播放用户[${user.name}](${tag})收藏，共${illusts.size}个作品，间隔 $duration"
        } else {
            forward(illusts = illusts, title = "mark")
        }
    }

    @SubCommand("article", "特辑")
    @Description("根据 AID 播放特辑")
    suspend fun CommandSenderOnMessage<*>.article(aid: Long) = withHelper {
        check(!play.isActive) { "其他列表播放中" }
        val article = Pixivision.getArticle(aid = aid)

        if (model != SendModel.Forward && duration > 0) {
            play = launch {
                for (info in article.illusts) {
                    if (isActive.not()) break
                    delay(duration)
                    sendIllust(getIllustInfo(pid = info.pid, flush = true))
                }
            }
            "开始播放特辑《${article.title}》，共${article.illusts.size}个作品，间隔 $duration"
        } else {
            if (article.illusts.isEmpty()) return@withHelper "列表为空"
            val list = mutableListOf<ForwardMessage.Node>()

            for (info in article.illusts) {
                if (isActive.not()) break

                runCatching {
                    val illust = getIllustInfo(pid = info.pid, flush = false)

                    list.add(
                        ForwardMessage.Node(
                            senderId = fromEvent.sender.id,
                            senderName = fromEvent.sender.nameCardOrNick,
                            time = illust.createAt.toEpochSecond().toInt(),
                            message = buildMessageByIllust(illust)
                        )
                    )
                }.onFailure {
                    logger.warning { "播放错误 $it" }
                }
            }

            RawForwardMessage(list).render(TitleDisplayStrategy("特辑《${article.title}》"))
        }
    }

    @SubCommand("walkthrough", "random", "漫游", "随机")
    @Description("漫游")
    suspend fun CommandSenderOnMessage<*>.walkthrough() = withHelper {
        check(!play.isActive) { "其他列表播放中" }
        val illusts = illustWalkThrough().illusts
            .apply { replicate() }
            .filter { it.age == AgeLimit.ALL && it.isEro() }

        if (model != SendModel.Forward && duration > 0) {
            play = play(illusts = illusts)
            "开始播放漫游，共${illusts.size}个作品，间隔 $duration"
        } else {
            forward(illusts = illusts, title = "walkthrough")
        }
    }

    @SubCommand("stop", "停止")
    @Description("停止播放当前列表")
    suspend fun CommandSenderOnMessage<*>.stop() = withHelper {
        if (play.isActive) {
            play.cancelAndJoin()
            "当前列表已停止播放"
        } else {
            "当前未播放"
        }
    }
}