package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.R
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import org.xmlpull.v1.XmlPullParser

/** Protect every supported translation, including notifications still showing the previous locale. */
internal fun localControlLabels(context: Context): Set<String> = buildSet {
    context.resources.getXml(R.xml.locales_config).use { parser ->
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "locale") continue
            val tag = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            }
            val localized = context.createConfigurationContext(configuration)
            listOf(R.string.notification_pause, R.string.notification_resume, R.string.notification_stop)
                .forEach { add(localized.getString(it)) }
        }
    }
}
