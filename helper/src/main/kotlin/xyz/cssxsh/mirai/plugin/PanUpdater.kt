package xyz.cssxsh.mirai.plugin

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.*
import io.ktor.client.features.compression.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import xyz.cssxsh.mirai.plugin.updater.CreateResultData
import xyz.cssxsh.mirai.plugin.updater.PanConfig
import xyz.cssxsh.mirai.plugin.updater.PreCreateData
import xyz.cssxsh.mirai.plugin.updater.SuperFileData
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.use

object PanUpdater {
    private const val PRE_CREATE = "https://pan.baidu.com/api/precreate"
    private const val SUPER_FILE = "https://c3.pcs.baidu.com/rest/2.0/pcs/superfile2"
    private const val CREATE_FILE = "https://pan.baidu.com/api/create"
    private const val BLOCK_SIZE = 4L * 1024 * 1024
    private val httpClient: HttpClient get() = HttpClient(OkHttp) {
        install(JsonFeature) {
            serializer = KotlinxSerializer()
        }
        BrowserUserAgent()
        ContentEncoding {
            gzip()
            deflate()
            identity()
        }
        install(HttpTimeout) {
            socketTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 600_000
        }
        engine {
            config {
                addInterceptor { chain ->
                    chain.request().let {
                        chain.proceed(it)
                    }
                }
            }
        }
    }
    private val config: PanConfig = PanConfig(
        bdsToken = "a25846e85d9ae2b6356b1c78d31c9ef3",
        logId = "MjA3ODZFQzFBMUNFODVDRjdFRkVBMUZGMkZBOTdBM0Y6Rkc9MQ==",
        targetPath = "/Pixiv",
        cookies = "BDUSS=JnNUVzZTBIRjBxSm10dTVtQ01Mb01nNDNhYkxzck5hTVZsRH5GRGJTdzhyNjFmSUFBQUFBJCQAAAAAAAAAAAEAAADUV~MytLTKwMnx0KHJ-ruvAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADwihl88IoZfTz; BDUSS_BFESS=JnNUVzZTBIRjBxSm10dTVtQ01Mb01nNDNhYkxzck5hTVZsRH5GRGJTdzhyNjFmSUFBQUFBJCQAAAAAAAAAAAEAAADUV~MytLTKwMnx0KHJ-ruvAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADwihl88IoZfTz; STOKEN=0119324ef6417dfc810b4da1c76267b37d3e50d3d6a72f345ff751a453f2a93f"
    )

    private suspend fun HttpClient.preCreate(
        updatePath: String,
        localMtime: Long,
        md5List: List<String> = listOf("5910a591dd8fc18c32a8f3df4fdc1761","a5fc157d78e6ad1c7e114b056c92821e")
    ) = post<PreCreateData>(PRE_CREATE) {
        header(HttpHeaders.Origin, "https://pan.baidu.com")
        header(HttpHeaders.Referrer, "https://pan.baidu.com/disk/home?")
        header(HttpHeaders.Cookie, config.cookies)

        parameter("channel", "chunlei")
        parameter("web", 1)
        parameter("app_id", 250528)
        parameter("bdstoken", config.bdsToken)
        parameter("logid", config.logId)
        parameter("clienttype", 0)

        body = FormDataContent(Parameters.build {
            append("path", updatePath)
            append("autoinit", 1.toString())
            append("target_path", config.targetPath)
            append("block_list", "[${md5List.joinToString(",") { "\"${it}\"" }}]")
            append("local_mtime", localMtime.toString())
        })
    }

    private suspend fun HttpClient.superFile(
        data: ByteArray,
        index: Int,
        uploadId: String,
        path: String
    ) = post<String>(SUPER_FILE) {
        header(HttpHeaders.Origin, "https://pan.baidu.com")
        header(HttpHeaders.Referrer, "https://pan.baidu.com/")
        header(HttpHeaders.Cookie, config.cookies)

        parameter("method", "upload")
        parameter("app_id", 250528)
        parameter("channel", "chunlei")
        parameter("clienttype", 0)
        parameter("web", 1)
        parameter("logid", config.logId)
        parameter("path", path)
        parameter("uploadid", uploadId)
        parameter("uploadsign", 0)
        parameter("partseq", index)

        body = MultiPartFormDataContent(formData {
            append(key = "file", filename = "blob", size = data.size.toLong()) {
                writeFully(data)
            }
        })
    }.let {
        Json.decodeFromString(SuperFileData.serializer(), it)
    }

    private suspend fun HttpClient.createFile(
        length: Long,
        uploadId: String,
        path: String,
        localMtime: Long,
        md5List: List<String>
    ) = post<CreateResultData>(CREATE_FILE) {
        header(HttpHeaders.Origin, "https://pan.baidu.com")
        header(HttpHeaders.Referrer, "https://pan.baidu.com/disk/home?")
        header(HttpHeaders.Cookie, config.cookies)

        parameter("channel", "chunlei")
        parameter("web", 1)
        parameter("rtype", 1)
        parameter("app_id", 250528)
        parameter("bdstoken", config.bdsToken)
        parameter("logid", config.logId)
        parameter("clienttype", 0)

        body = MultiPartFormDataContent(formData {
            append("path", path)
            append("size", length)
            append("uploadid", uploadId)
            append("block_list", "[${md5List.joinToString(",") { "\"${it}\"" }}]")
            append("local_mtime", localMtime)
        })
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun update(pathname: String, updatePath: String) = httpClient.use { client ->
        val file = RandomAccessFile(File(pathname), "r")
        val length = file.length()
        val localMtime = File(pathname).lastModified()
        val blocks = (0 until length step BLOCK_SIZE).map { offset ->
            offset until minOf(offset + BLOCK_SIZE, length)
        }
        val preCreateData = client.preCreate(
            updatePath = updatePath,
            localMtime = localMtime
        )
        val superList = blocks.mapIndexed { index, range ->
            client.superFile(
                data = range.map { file.readByte() }.toByteArray(),
                index = index,
                uploadId = preCreateData.uploadId,
                path = updatePath
            )
        }
        client.createFile(
            length = length,
            localMtime = localMtime,
            path = updatePath,
            md5List = superList.map { it.md5 },
            uploadId = preCreateData.uploadId
        )
    }
}