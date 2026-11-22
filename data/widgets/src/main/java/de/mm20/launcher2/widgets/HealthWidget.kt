package de.mm20.launcher2.widgets
import android.content.Context
import de.mm20.launcher2.database.entities.PartialWidgetEntity
import java.util.UUID
data class HealthWidget(override val id: UUID): Widget() {
    override fun getLabel(context: Context) = "Health"
    override fun toDatabaseEntity() = PartialWidgetEntity(id = id, type = Type, config = null)
    companion object { const val Type = "health" }
}
