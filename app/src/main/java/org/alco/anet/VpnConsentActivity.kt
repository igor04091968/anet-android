package org.alco.anet

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Runs before MainActivity and deliberately has no dependency on the native VPN core. */
class VpnConsentActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var request: Button
    private var pending = false
    private var autoRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getBoolean("pending") ?: false
        autoRequested = savedInstanceState?.getBoolean("autoRequested") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "ANet — доступ VPN"
            textSize = 24f
        })
        status = TextView(this).apply {
            text = "Для подключения необходимо ваше согласие в системном окне Android. Разрешение VPN проверяется отдельно от списка разрешений приложения."
            textSize = 17f
        }
        root.addView(status)
        request = Button(this).apply {
            text = "Разрешить VPN"
            isEnabled = !pending
            setOnClickListener { requestConsent() }
        }
        root.addView(request)
        root.addView(Button(this).apply {
            text = "Системные настройки VPN"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (e: Exception) {
                    status.text = "Не удалось открыть настройки VPN: ${e.javaClass.simpleName}"
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Закрыть без подключения"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!autoRequested && !pending) {
            autoRequested = true
            requestConsent()
        }
    }

    private fun requestConsent() {
        if (pending) return
        try {
            val manager = getSystemService(USER_SERVICE) as UserManager
            if (manager.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
                status.text = "Настройка VPN запрещена администратором этого пользователя Android. Снимите ограничение в настройках управления устройством."
                return
            }
            val consent = VpnService.prepare(this)
            if (consent == null) {
                openClient()
                return
            }
            pending = true
            request.isEnabled = false
            status.text = "Ожидается подтверждение в системном окне Android…"
            startActivityForResult(consent, 41)
        } catch (e: Exception) {
            pending = false
            request.isEnabled = true
            status.text = "Android не открыл запрос VPN: ${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41) return
        pending = false
        request.isEnabled = true
        try {
            if (resultCode == RESULT_OK && VpnService.prepare(this) == null) {
                openClient()
            } else {
                status.text = "Android не выдал согласие VPN (результат $resultCode). Подключение не запущено. Повторите запрос. Если окно не появляется, проверьте ограничения устройства и постоянно включённый VPN другого приложения."
            }
        } catch (e: Exception) {
            status.text = "Не удалось проверить согласие VPN: ${e.javaClass.simpleName}"
        }
    }

    private fun openClient() {
        // Preserve configuration-file imports and their temporary URI permission.
        val next = Intent(intent).setClassName(packageName, "org.alco.anet.MainActivity")
        next.removeCategory(Intent.CATEGORY_LAUNCHER)
        startActivity(next)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending", pending)
        outState.putBoolean("autoRequested", autoRequested)
        super.onSaveInstanceState(outState)
    }
}
