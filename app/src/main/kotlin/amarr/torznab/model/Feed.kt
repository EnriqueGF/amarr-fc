package amarr.torznab.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlNamespaceDeclSpec
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalXmlUtilApi::class)
@Serializable
@XmlNamespaceDeclSpec("${Feed.TORZNAB_PREFIX}=${Feed.TORZNAB_NAMESPACE}")
@SerialName("rss")
data class Feed(val version: String = "2.0", val channel: Channel) {

    @Serializable
    @SerialName("channel")
    data class Channel(
        @XmlElement
        val title: String = "amarr-fc",
        @XmlElement
        val description: String = "aMule eD2k indexer for Sonarr and Radarr",
        val response: Response,
        val item: List<Item>
    ) {

        @Serializable
        @XmlSerialName("response", TORZNAB_NAMESPACE, TORZNAB_PREFIX)
        data class Response(
            val offset: Int,
            val total: Int,
        )

        @Serializable
        @SerialName("item")
        data class Item(
            @XmlElement
            val title: String,
            @XmlElement
            val pubDate: String = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC)
            ),
            val enclosure: Enclosure,
            val attributes: List<TorznabAttribute>
        ) {

            @Serializable
            @SerialName("enclosure")
            data class Enclosure(
                val url: String,
                val length: Long,
                val type: String = "application/x-bittorrent"
            )

            @Serializable
            @XmlSerialName("attr", TORZNAB_NAMESPACE, TORZNAB_PREFIX)
            data class TorznabAttribute(
                val name: String,
                val value: String,
            )
        }
    }

    companion object {
        const val TORZNAB_NAMESPACE = "http://torznab.com/schemas/2015/feed"
        const val TORZNAB_PREFIX = "torznab"
    }

}
