package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.util.UUID

@Entity(
    tableName = "auto_task_rules",
    indices = [Index(value = ["enable", "customOrder"])]
)
data class AutoTaskRule(
    @PrimaryKey
    @SerializedName("id")
    var id: String = UUID.randomUUID().toString(),
    @SerializedName("name")
    var name: String = "",
    @SerializedName("enable")
    var enable: Boolean = true,
    @SerializedName("cron")
    var cron: String? = "*/30 * * * *",
    @SerializedName("loginUrl")
    var loginUrl: String? = null,
    @SerializedName("loginUi")
    var loginUi: String? = null,
    @SerializedName("loginCheckJs")
    var loginCheckJs: String? = null,
    @SerializedName("comment")
    var comment: String? = null,
    @SerializedName("script")
    var script: String = "",
    @SerializedName("header")
    var header: String? = null,
    @SerializedName("jsLib")
    var jsLib: String? = null,
    @SerializedName("concurrentRate")
    var concurrentRate: String? = null,
    @SerializedName("enabledCookieJar")
    var enabledCookieJar: Boolean = true,
    @SerializedName("customOrder")
    var customOrder: Int = 0,
    @SerializedName("lastRunAt")
    var lastRunAt: Long = 0L,
    @SerializedName("lastResult")
    var lastResult: String? = null,
    @SerializedName("lastError")
    var lastError: String? = null,
    @SerializedName("lastLog")
    var lastLog: String? = null
)
