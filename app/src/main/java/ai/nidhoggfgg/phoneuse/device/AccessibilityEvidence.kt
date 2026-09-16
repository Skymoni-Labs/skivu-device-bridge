package ai.nidhoggfgg.phoneuse.device

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Shared by collection and the event journal; never retain framework node objects in the journal. */
internal fun accessibilityIdentityHash(node: AccessibilityNodeInfo): String = nodeContentHash(listOf(
    node.windowId, node.hashCode(), if (Build.VERSION.SDK_INT >= 33) node.uniqueId else null,
    node.viewIdResourceName, node.className?.toString()), NodeSemantics())

/** Opt-in provider contract: must follow the logical data item when views are rebound or reordered. */
internal const val LOGICAL_ITEM_ID_EXTRA = "ai.nidhoggfgg.phoneuse.logical_item_id"
internal fun logicalItemId(node: AccessibilityNodeInfo): String? =
    (node.extras.get(LOGICAL_ITEM_ID_EXTRA) as? String)?.takeIf { it.isNotBlank() && it.length <= 256 }
