package xyz.cssxsh.mirai.plugin.command

import kotlinx.coroutines.*
import net.mamoe.mirai.console.command.CommandSenderOnMessage
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.ConsoleCommandSender
import net.mamoe.mirai.message.MessageEvent
import xyz.cssxsh.mirai.plugin.*
import xyz.cssxsh.mirai.plugin.data.PixivCacheData
import xyz.cssxsh.mirai.plugin.data.PixivHelperSettings
import xyz.cssxsh.pixiv.RankMode
import xyz.cssxsh.pixiv.api.app.illustFollow
import xyz.cssxsh.pixiv.api.app.illustRanking
import xyz.cssxsh.pixiv.api.app.userFollowing
import xyz.cssxsh.pixiv.data.app.IllustInfo
import java.io.File

@Suppress("unused")
object PixivCacheCommand : CompositeCommand(
    PixivHelperPlugin,
    "cache",
    description = "缓存指令",
    prefixOptional = true
), PixivHelperLogger {
    /**
     * timeMillis
     */
    private var delayTime: Long
        get() = PixivHelperSettings.delayTime
        set(value) { PixivHelperSettings.delayTime = value }

    private var job: Job? = null

    private val isStop: Boolean get() = job?.isActive?.not() ?: true

    private suspend fun PixivHelper.getRank(modes: Array<RankMode> = RankMode.values()) = buildList {
        modes.map { mode ->
            runCatching {
                illustRanking(mode = mode).illusts
            }.onSuccess {
                add(PixivCacheData.filter(it).values)
                logger.verbose("加载排行榜[${mode}]{${it.size}}成功")
            }.onFailure {
                logger.verbose("加载排行榜[${mode}]失败, ${it.message}")
            }
        }
    }

    private suspend fun PixivHelper.getFollow(page: Int = 100) = buildList {
        (0 until page).forEach { index ->
            runCatching {
                illustFollow(offset = index * 30L).illusts
            }.onSuccess {
                if (it.isEmpty()) return@buildList
                add(PixivCacheData.filter(it).values)
                logger.verbose("加载关注用户作品时间线第${index + 1}页{${it.size}}成功")
            }.onFailure {
                logger.verbose("加载关注用户作品时间线第${index + 1}页失败, $it")
            }
        }
    }

    private suspend fun PixivHelper.getUserPreviews(uid: Long, page: Int = 100) = buildList {
        (0 until page).forEach { index ->
            runCatching {
                userFollowing(uid = uid, offset = index * 30L).userPreviews.flatMap { it.illusts }
            }.onSuccess {
                if (it.isEmpty()) return@buildList
                add(PixivCacheData.filter(it).values)
                logger.verbose("加载关注用户作品预览第${index + 1}页{${it.size}}成功")
            }.onFailure {
                logger.verbose("加载关注用户作品预览第${index + 1}页失败, $it")
            }
        }
    }

    private suspend fun CommandSenderOnMessage<MessageEvent>.method(
        block: suspend PixivHelper.() -> List<IllustInfo>
    ) = getHelper().runCatching {
        check(isStop) { "正在缓存中, ${job}..." }
        launch {
            runCatching {
                PixivCacheData.filter(block()).values.also { list ->
                    logger.verbose("共 ${list.size} 个作品信息将会被尝试添加")
                }.count { illust: IllustInfo ->
                    isActive && illust.pid !in PixivCacheData && runCatching {
                        getImages(illust)
                    }.onSuccess {
                        delay(delayTime)
                    }.onFailure {
                        logger.verbose("获取作品(${illust.pid})[${illust.title}]错误", it)
                    }.isSuccess
                }
            }.onSuccess {
                quoteReply("缓存完毕共${it}个新作品")
            }.onFailure {
                quoteReply("缓存失败, ${it.message}")
            }
        }.also {
            job = it
        }
    }.onSuccess {
        quoteReply("添加任务完成${it}")
    }.onFailure {
        quoteReply(it.toString())
    }.isSuccess

    /**
     * 缓存排行榜和关注列表
     */
    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.all() = method {
        (getFollow() + getRank() + getUserPreviews(getAuthInfoOrThrow().user.uid)).flatten().apply {
            forEach { illust ->
                illust.writeTo(File(PixivHelperSettings.imagesFolder(illust.pid), "${illust.pid}.json"))
            }
        }
    }

    /**
     * 缓存指定用户关注的用户的预览作品
     */
    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.preview(uid: Long) = method {
        getUserPreviews(uid).flatten().apply {
            forEach { illust ->
                illust.writeTo(File(PixivHelperSettings.imagesFolder(illust.pid), "${illust.pid}.json"))
            }
        }
    }


    /**
     * 从文件夹中加载信息
     */
    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.load() = method {
        PixivHelperSettings.cacheFolder.also {
            logger.verbose("从 ${it.absolutePath} 加载作品信息")
        }.walk().mapNotNull { file ->
            if (file.isDirectory && file.name.matches("""^[0-9]+$""".toRegex())) {
                file.name.toLong()
            } else {
                null
            }
        }.toList().map { pid ->
            getIllustInfo(pid)
        }
    }

    /**
     * 强制停止缓存
     */
    @SubCommand("cancel", "stop")
    suspend fun CommandSenderOnMessage<MessageEvent>.cancel() = runCatching {
        job?.cancelAndJoin()
    }.onSuccess {
        quoteReply("任务${job}已停止")
    }.onFailure {
        quoteReply(it.toString())
    }.isSuccess

    /**
     * 检查当前数据中不可读，并删除图片文件夹
     */
    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.check() = getHelper().runCatching {
        PixivCacheData.values.also { list ->
            logger.verbose("共有 ${list.size} 个作品需要检查")
        }.mapNotNull { pid ->
            val dir = PixivHelperSettings.imagesFolder(pid)
            val illust = getIllustInfo(pid)
            runCatching {
                (0 until illust.pageCount).forEach { index ->
                    File(dir, "${illust.pid}-origin-${index}.jpg").apply {
                        require(canRead()) {
                            "$name 不可读， 文件将删除，结果：${delete()}"
                        }
                    }
                }
            }.onFailure {
                logger.verbose("作品(${illust.pid})[${illust.title}]缓存出错, ${it.message}")
            }.let {
                if (it.isFailure) illust else null
            }
        }
    }.onSuccess { list ->
        quoteReply("检查缓存完毕，错误数: ${list.size}")
        list.forEach(PixivCacheData::remove)
    }.onFailure {
        quoteReply(it.toString())
    }.isSuccess

    /**
     * 设置缓存目录 cache path /storage/emulated/0/PixivCache
     * @param path 缓存目录
     */
    @SubCommand
    fun ConsoleCommandSender.path(path: String) {
        runCatching {
            if (File(path).exists().not()) File(PixivHelperSettings.cachePath).renameTo(File(path))
        }
        PixivHelperSettings.cachePath = path
    }

    /**
     * 设置缓存延迟时间
     */
    @SubCommand
    fun ConsoleCommandSender.delay(timeMillis: Long) {
        logger.info("$delayTime -> $timeMillis")
        delayTime = timeMillis
    }

    /**
     * 设置缓存延迟时间
     */
    @SubCommand
    fun ConsoleCommandSender.remove(pid: Long) {
        PixivCacheData.eros.remove(pid)?.let {
            logger.info("色图作品(${it.pid})[${it.title}]信息将从{色图}移除, 目前共${PixivCacheData.eros.size}条色图")
        }
    }
}