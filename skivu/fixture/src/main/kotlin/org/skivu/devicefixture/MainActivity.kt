package org.skivu.devicefixture
import android.app.Activity
import android.os.Bundle
import android.widget.*
class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,64,24,24) }
        val editor = EditText(this).apply { hint = "便签内容"; id = 1001 }
        val result = TextView(this).apply { text = "尚未保存"; textSize = 22f; id = 1002 }
        box.addView(TextView(this).apply { text = "隔离测试便签"; textSize = 26f })
        box.addView(editor); box.addView(Button(this).apply { text = "保存便签"; id = 1003; setOnClickListener { result.text = "已保存：${editor.text}" } }); box.addView(result)
        box.addView(EditText(this).apply { hint="仅限本机输入的测试密码"; inputType=129; id=1004 })
        var sent=0
        box.addView(Button(this).apply { text="发送测试消息"; id=1005; setOnClickListener { result.text="测试发送计数：${++sent}" } })
        setContentView(box)
    }
}
