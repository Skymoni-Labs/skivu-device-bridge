// SPDX-License-Identifier: MIT
package ai.nidhoggfgg.phoneuse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.widget.*
import ai.nidhoggfgg.phoneuse.bridge.BridgeRegistry
import ai.nidhoggfgg.phoneuse.device.PhoneAccessibilityService

/** The Skivu build is an IPC companion; no HTTP listener or boot auto-start. */
object PhoneUseService { fun restoreIfEnabled(context: Context) {} }

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var rendered=""
    private val refresh = object : Runnable { override fun run() { render(); handler.postDelayed(this, 1200) } }
    override fun onResume() { super.onResume(); refresh.run() }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun render() {
        val state=listOf(BridgeRegistry.pending.keys.sorted().toString(),
            BridgeRegistry.confirmations.values.map { "${it.request}:${it.approved}" }.sorted().toString(),
            getSharedPreferences("skivu-client-grants-v1",0).all.toSortedMap().toString(),
            (PhoneAccessibilityService.current!=null).toString()).joinToString("|")
        if(state==rendered)return
        rendered=state
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun text(value: String, size: Float = 16f) { box.addView(TextView(this).apply { text = value; textSize = size; setPadding(0, 8, 0, 16) }) }
        fun button(label: String, action: () -> Unit) { box.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        text("Skivu 设备伴侣", 25f)
        text("本机授权连接 · 无需 Root 或电脑\n首次信任后可以直接操控，不再每一步弹窗。仅操作任务允许的软件，任务有预算且可随时停止。此授权界面不能被远程操作。")
        text(if(PhoneAccessibilityService.current!=null) "无障碍：已开启" else "无障碍：未开启，请先在系统设置中启用")
        button("打开无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        text("待批准的连接", 20f)
        BridgeRegistry.pending.values.toList().forEach { client ->
            text("${client.packageName}\n签名 SHA-256：${client.fingerprint}")

            button("信任此客户端，后续直接操控并返回") {
                BridgeRegistry.approve(this,client.key,true)
                packageManager.getLaunchIntentForPackage(client.packageName)?.let { startActivity(it) }
                finish()
            }
            button("批准此客户端并返回") {
                BridgeRegistry.approve(this, client.key)
                packageManager.getLaunchIntentForPackage(client.packageName)?.let { startActivity(it) }
                finish()
            }
        }
        val approved=BridgeRegistry.approvedClients(this)
        if(approved.isNotEmpty())text("已信任的客户端",20f)
        approved.forEach { client ->
            text("${client.packageName}\n签名 SHA-256：${client.fingerprint}")
            val direct=BridgeRegistry.directApproved(this,client.key)
            text(if(direct) "直接操控已开启：任务内关键点击不会再次确认。" else "当前为逐次确认模式。")
            button(if(direct) "关闭此客户端的直接操控" else "信任此客户端的直接操控") {
                BridgeRegistry.setDirect(this,client.key,!direct);render()
            }
            button("撤销此客户端") { BridgeRegistry.revoke(this,client.key);render() }
        }
        BridgeRegistry.confirmations.values.filter { !it.approved && SystemClock.elapsedRealtime() < it.expires }.forEach { pending ->
            text("任务动作需要你确认\n${pending.text}")
            button("批准这一次动作并返回") {
                val target=BridgeRegistry.service?.targetFor(pending.task)
                if(target!=null)packageManager.getLaunchIntentForPackage(target)?.let { startActivity(it) }
                BridgeRegistry.approveAction(pending); finish()
            }
        }
        button("立即停止并撤销所有客户端") { BridgeRegistry.revokeAll(this); render() }
        text("基于 PhoneUse v1.3.0（MIT），保留原作者许可。Skivu 独立伴侣构建 0.2.0。", 12f)
        setContentView(ScrollView(this).apply { addView(box) })
    }
}
