package mirai

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.*
import io.ktor.client.features.compression.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.terminal.ConsoleTerminalExperimentalApi
import net.mamoe.mirai.console.terminal.MiraiConsoleImplementationTerminal
import net.mamoe.mirai.console.terminal.MiraiConsoleTerminalLoader
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.event.events.BotInvitedJoinGroupRequestEvent
import net.mamoe.mirai.event.events.NewFriendRequestEvent
import net.mamoe.mirai.event.subscribeAlways
import net.mamoe.mirai.event.subscribeGroupMessages
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.use


@ConsoleExperimentalApi
@ConsoleTerminalExperimentalApi
object RunMirai {


    private val logger by lazy {
        MiraiConsole.createLogger("RunMirai")
    }

    private fun miraiConsoleImpl(rootPath: Path) = MiraiConsoleImplementationTerminal(
        rootPath = rootPath,
        dataStorageForJvmPluginLoader = JsonPluginDataStorage(rootPath.resolve("data"), false),
        dataStorageForBuiltIns = JsonPluginDataStorage(rootPath.resolve("data"), false),
        configStorageForJvmPluginLoader = JsonPluginDataStorage(rootPath.resolve("config"), true),
        configStorageForBuiltIns = JsonPluginDataStorage(rootPath.resolve("config"), true),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // 默认在 /test 目录下运行
        MiraiConsoleTerminalLoader.parse(args, exitProcess = true)
        MiraiConsoleTerminalLoader.startAsDaemon(miraiConsoleImpl(Paths.get(".").toAbsolutePath()))
        MiraiConsole.subscribeEvent()
        try {
            runBlocking {
                MiraiConsole.job.join()
            }
        } catch (e: CancellationException) {
            // ignored
        }
    }

    private suspend fun getAmr(text: String) = HttpClient(OkHttp) {
        install(JsonFeature) {
            serializer = KotlinxSerializer()
        }
        BrowserUserAgent()
        ContentEncoding {
            gzip()
            deflate()
            identity()
        }
    }.use { client ->
        logger.verbose("开始tts $text")
        val file = client.get<ByteArray>("https://fanyi.baidu.com/gettts") {
            parameter("lan", "zh")
            parameter("text", text)
            parameter("spd", 5)
            parameter("source", "web")
        }
        File("tts.mp3").writeBytes(file)
        logger.verbose("开始mp3(${file.size}) -> amr")
        val json = client.submitFormWithBinaryData<String>(
            url = "https://s19.aconvert.com/convert/convert-batch.php",
            formData = formData {
                append(key = "file", filename = "blob", size = file.size.toLong(), contentType = ContentType.Audio.MPEG) {
                    writeFully(file)
                }
                append(key = "targetformat", value = "amr")
            }
        )
        logger.verbose("转换结果: $json")
        val filename = Json.parseToJsonElement(json).jsonObject["filename"]!!.jsonPrimitive.content
        client.get<ByteArray>("https://s19.aconvert.com/convert/p3r68-cdx67/${filename}")
    }

    private fun MiraiConsole.subscribeEvent() = apply {
        subscribeAlways<NewFriendRequestEvent> {
            accept()
        }
        subscribeAlways<BotInvitedJoinGroupRequestEvent> {
            accept()
        }
        subscribeGroupMessages {
            atBot {
                quoteReply("部分指令需要好友私聊，已添加自动好友验证\n有事请联系：QQ: 1438159989")
            }
            """(?:说：|say:)(.+)""".toRegex() matchingReply { result ->
                val text = if (result.groupValues[1].length < 128) result.groupValues[1] else "太长不说"
                File(".").resolve("${text}.amr").apply {
                    if (canRead().not()) {
                        writeBytes(getAmr(text))
                    }
                }.let {
                    group.uploadVoice(it.inputStream())
                }
            }
        }
    }
}