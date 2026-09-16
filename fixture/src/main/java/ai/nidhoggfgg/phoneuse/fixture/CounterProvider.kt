package ai.nidhoggfgg.phoneuse.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Only synthetic fixture state is exposed, so device tests can verify receiver and exact count. */
internal object Counters {
    private var state = JSONObject()
    private var events = JSONArray()
    @Synchronized fun reset(scenario: String) {
        state = JSONObject().put("scenario", scenario).put("clicks", 0).put("scroll_requests", 0)
            .put("text_requests", 0).put("scroll_y", 0).put("mutations", 0)
        events = JSONArray()
    }
    @Synchronized fun increment(name: String, target: String, action: Int = 0) {
        state.put(name, state.optInt(name) + 1)
        if (events.length() < 128) events.put(JSONObject().put("name", name).put("target", target)
            .put("action", action).put("at_ms", SystemClock.elapsedRealtime()))
    }
    @Synchronized fun value(name: String, value: Any) { state.put(name, value) }
    @Synchronized fun json(): String = JSONObject(state.toString()).put("events", events).toString()
}

class CounterProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(Counters.json())) }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}
