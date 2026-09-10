package com.zengqi.ai

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zengqi.ai.common.AppForegroundTracker
import com.zengqi.ai.common.CompanionRole
import com.zengqi.ai.common.FrameRateManager
import com.zengqi.ai.domain.ServiceRegistry
import com.zengqi.ai.feature.profile.AgreementScreen
import com.zengqi.ai.feature.profile.ProfileViewModel
import com.zengqi.ai.feature.profile.RoleSelectionScreen
import com.zengqi.ai.feature.update.AppUpdateManager
import com.zengqi.ai.uicommon.theme.ZengqiTheme
import com.zengqi.ai.uicommon.theme.ThemeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 应用主入口 Activity。
 *
 * 职责:
 *   生命周期管理 (onCreate / onResume / onPause / onDestroy)
 *   系统栏初始化 (委托 SystemBarController)
 *   帧率策略
 */
class MainActivity : ComponentActivity() {

    val updateManager by lazy { AppUpdateManager(this) }
    private val appScope = CoroutineScope(Dispatchers.Main)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SystemBarController.applyBaseContextLocale(newBase))
    }

    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WorkManager must be initialized before any schedule() call
        try { androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()) } catch (_: Exception) {}
        enableEdgeToEdge()
        window.decorView.post { SystemBarController.applySystemBars(this) }

        // 硬件加速
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // 异形屏适配
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 用户协议检查
        val activity = this
        setContent {
            val agreementPrefs = getSharedPreferences("agreement_prefs", android.content.Context.MODE_PRIVATE)
            val agreementAccepted = agreementPrefs.getBoolean("agreement_accepted", false)
            val userPrefs = getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
            val roleSelected = userPrefs.contains("selected_role")
            val themeViewModel: ThemeViewModel = viewModel()
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            val profileViewModel: ProfileViewModel = viewModel()
            var showRoleSelection by remember { mutableStateOf(agreementAccepted && !roleSelected) }

            val isServiceReady by ServiceRegistry.initialized.collectAsStateWithLifecycle()

            ZengqiTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        !agreementAccepted -> {
                            AgreementScreen(
                                onAgree = {
                                    agreementPrefs.edit()
                                        .putBoolean("agreement_accepted", true)
                                        .putLong("agreement_time", System.currentTimeMillis())
                                        .apply()
                                    activity.recreate()
                                },
                                onDisagree = { activity.finishAffinity() }
                            )
                        }
                        showRoleSelection -> {
                            RoleSelectionScreen(
                                onRoleSelected = { role ->
                                    profileViewModel.switchRole(role) {
                                        showRoleSelection = false
                                    }
                                },
                                onSkip = {
                                    profileViewModel.switchRole(CompanionRole.GIRLFRIEND) {
                                        showRoleSelection = false
                                    }
                                }
                            )
                        }
                        !isServiceReady -> {
                            // 等待跨模块依赖注册中心就绪，避免冷启动后快速进入
                            // 创建人设等页面时 ServiceRegistry.getOrThrow 抛异常导致闪退。
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        else -> {
                            MainScreen(activity)
                        }
                    }
                }
            }
        }

        appScope.launch { updateManager.checkForUpdates() }
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.isInForeground = true
        window.decorView.post {
            SystemBarController.applySystemBars(this)
            val savedRate = FrameRateManager.getSavedFrameRate(this)
            FrameRateManager.applyFrameRate(window, savedRate)
        }
    }

    override fun onPause() {
        super.onPause()
        AppForegroundTracker.isInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
    }
}
