package dev.surdy.hazri.data

import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.RoomSurvey
import dev.surdy.hazri.domain.VerdictThresholds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.round

/** A whole session, as exported. */
@Serializable
data class SessionDocument(
    val exportedAt: Long,
    val settings: AppSettings,
    val nodes: List<NodeRecord>,
    val rooms: List<String>,
    val surveys: List<StoredSurvey>,
)

/**
 * Turns the stored session into something that can leave the phone.
 *
 * Two formats because they answer different questions: the JSON is the session, complete
 * enough to reconstruct the app's state; the CSV is one row per room per node, which is
 * what a spreadsheet wants when you are comparing two node placements.
 */
object SessionExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * The whole session as JSON, with the broker credentials removed.
     *
     * An export is a file the user hands to someone else — a share sheet, a chat, a
     * spreadsheet. The settings block is worth carrying because it says what thresholds and
     * smoothing produced these numbers; the broker username and password are not part of
     * that and have no business leaving the phone.
     */
    fun toJson(repository: HazriRepository, exportedAt: Long): String = json.encodeToString(
        SessionDocument(
            exportedAt = exportedAt,
            settings = repository.settings.value.withoutCredentials(),
            nodes = repository.nodes.value,
            rooms = repository.rooms.value,
            surveys = repository.surveys.value,
        )
    )

    private fun AppSettings.withoutCredentials(): AppSettings =
        copy(broker = broker.copy(username = "", password = ""))

    /** One row per room per node, plus the room's verdict and margin repeated on each row. */
    fun toCsv(repository: HazriRepository): String {
        val thresholds = repository.settings.value.thresholds()
        val names = repository.displayNames()
        return buildString {
            appendLine(CSV_HEADER)
            repository.latestSurveys().sortedBy { it.room }.forEach { survey ->
                appendSurvey(survey, thresholds, names)
            }
        }
    }

    private fun StringBuilder.appendSurvey(
        survey: RoomSurvey,
        thresholds: VerdictThresholds,
        names: Map<NodeId, String>,
    ) {
        val verdict = survey.verdict(thresholds)
        survey.stats.forEach { stat ->
            appendLine(
                listOf(
                    escape(survey.room),
                    escape(names[stat.nodeId] ?: stat.nodeId.value),
                    escape(stat.nodeId.value),
                    format(stat.mean),
                    format(stat.sigma),
                    stat.count.toString(),
                    survey.source.name,
                    survey.startedAt.toString(),
                    survey.endedAt.toString(),
                    verdict.verdict.name,
                    verdict.margin?.let(::format).orEmpty(),
                ).joinToString(",")
            )
        }
    }

    private fun format(value: Double): String = (round(value * 100) / 100.0).toString()

    /** RFC 4180 quoting, applied only when it is needed. Room names can contain commas. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private const val CSV_HEADER =
        "room,node,node_id,mean_rssi,sigma,samples,source,started_at,ended_at,verdict,margin_db"
}
