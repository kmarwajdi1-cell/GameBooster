package com.gb.booster

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val keepPkgs = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadKeepList()
        handler.post(ticker)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        status = TextView(this).apply { textSize = 16f }
        info = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        root.addView(info)
        root.addView(status)

        fun btn(t: String, f: () -> Unit) {
            root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        btn("1) السماح بإحصائيات الاستخدام") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btn("2) السماح بعدم الإزعاج") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        btn("3) طلب صلاحية Shizuku") { requestShizuku() }
        btn("تفعيل وضع اللعب الآن") { boost() }
        btn("إيقاف وضع اللعب") { restore() }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun loadKeepList() {
        keepPkgs.add(packageName)
        keepPkgs.add("com.android.systemui")
        keepPkgs.add("com.android.phone")
        keepPkgs.add("com.android.settings")
        keepPkgs.add("com.google.android.gms")
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(i, 0)?.activityInfo?.packageName?.let { keepPkgs.add(it) }
        val s = Settings.Secure.getString(contentResolver, "default_input_method")
        s?.substringBefore("/")?.let { keepPkgs.add(it) }
    }

    private fun hasUsage(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "شغّل تطبيق Shizuku أولاً", Toast.LENGTH_LONG).show()
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1)
            } else {
                Toast.makeText(this, "الصلاحية مفعّلة", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Shizuku غير متاح", Toast.LENGTH_LONG).show()
        }
    }

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    @Suppress("DEPRECATION")
    private fun runCmd(vararg cmd: String) {
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
            p.waitFor()
        } catch (e: Throwable) { }
    }

    private fun userApps(): List<String> {
        val list = ArrayList<String>()
        for (a in packageManager.getInstalledApplications(0)) {
            val system = (a.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!system && a.packageName !in keepPkgs) list.add(a.packageName)
        }
        return list
    }

    private fun boost() {
        if (!shizukuReady()) {
            Toast.makeText(this, "فعّل Shizuku أولاً (الزر 3)", Toast.LENGTH_LONG).show()
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
        Thread {
            var n = 0
            for (p in userApps()) {
                runCmd("am", "force-stop", p)
                n++
            }
            handler.post {
                Toast.makeText(this, "تم إيقاف $n تطبيق", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun restore() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        Toast.makeText(this, "تمت استعادة الإعدادات", Toast.LENGTH_SHORT).show()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val freeMb = mi.availMem / 1048576
            val totalMb = mi.totalMem / 1048576
            val bi = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            info.text = "الرام الحرة: $freeMb / $totalMb MB\nالحرارة: $temp °C"
            status.text = "إحصائيات الاستخدام: " + (if (hasUsage()) "✓" else "✗") +
                "\nShizuku: " + (if (shizukuReady()) "✓" else "✗")
            handler.postDelayed(this, 2000)
        }
    }
}
