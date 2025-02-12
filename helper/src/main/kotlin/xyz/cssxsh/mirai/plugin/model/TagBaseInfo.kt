package xyz.cssxsh.mirai.plugin.model

import javax.persistence.*

@Entity
@Table(name = "tags")
data class TagBaseInfo(
    @Id
    @Column(name = "pid", nullable = false, updatable = false)
    val pid: Long = 0,
    @Id
    @Column(name = "name", nullable = false, length = 30, updatable = false)
    val name: String = "",
    @Column(name = "translated_name", nullable = true)
    val translated: String? = null
) : PixivEntity