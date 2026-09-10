package com.zengqi.ai

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.zengqi.ai.common.ContentFilter
import com.zengqi.ai.common.DeviceIdProvider
import com.zengqi.ai.common.SaltStore
import com.zengqi.ai.common.SecureLog
import com.zengqi.ai.common.embedding.VectorLibrary
import com.zengqi.ai.common.safety.ContentSafetyVerifier
import com.zengqi.ai.database.AppDatabase
import com.zengqi.ai.database.BuiltinApiSeeder
import com.zengqi.ai.database.DefaultCompanionSeeder
import com.zengqi.ai.database.SecurityDataSeeder
import com.zengqi.ai.database.repository.ChatRepository
import com.zengqi.ai.database.repository.CompanionRepository
import com.zengqi.ai.database.repository.MemoryRepository
import com.zengqi.ai.database.repository.UserRepository
import com.zengqi.ai.common.AppSettingsStore
import com.zengqi.ai.common.YandereModeManager
import com.zengqi.ai.domain.CompanionProvider
import com.zengqi.ai.domain.AiServiceProvider
import com.zengqi.ai.domain.MemoryProvider
import com.zengqi.ai.domain.ServiceRegistry
import com.zengqi.ai.domain.UserProfileProvider
import com.zengqi.ai.network.AiService
import com.zengqi.ai.network.NtpTimeProvider
import com.zengqi.ai.uicommon.component.ChatBackgroundCache
import com.zengqi.ai.uicommon.component.getChatBackgroundKey
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class ZengqiApplication : Application(), ImageLoaderFactory, androidx.work.Configuration.Provider {

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.CriticalInit)
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this).maxSizeBytes(128 * 1024 * 1024).build()
        }
        .build()

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        // Global DNS resolution timeout (5s) — prevents DNS hang blocking all OkHttp calls
        java.security.Security.setProperty("networkaddress.cache.ttl", "0")
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        System.setProperty("sun.net.spi.nameservice.nameservers", "8.8.8.8")
        System.setProperty("sun.net.spi.nameservice.domain", ".")
        super.onCreate()
        instance = this
        initBusiness(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStoredLanguage(this)
    }

    override fun onTerminate() {
        bgScope.launch {
            ContentFilter.destroy()
            SaltStore.shutdown()
            AppDatabase.shutdown()
            ChatBackgroundCache.clear()
        }
        ServiceRegistry.clear()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: ZengqiApplication
            private set

        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun initBusiness(app: Application) {
            SaltStore.init(app)
            SecureLog.init(com.zengqi.ai.BuildConfig.DEBUG)
            applyStoredLanguage(app)
            AiService.initialize(app)
            NtpTimeProvider.initialize(app)
            registerServiceProviders(app)
            clearUpdateIgnore(app)

            // 注入应用级后台作用域，供跨越 ViewModel 生命周期的任务使用
            com.zengqi.ai.common.ApplicationScopeProvider.init(bgScope)

            // Seed default companion asynchronously — must exist before any chat opens
            bgScope.launch { seedDefaultCompanion(app) }
            bgScope.launch { BuiltinApiSeeder.seedIfNeeded(app) }

            bgScope.launch { ContentFilter.initialize(app) }
            bgScope.launch { preloadBackground(app) }
            bgScope.launch { initSecurityData(app) }
            bgScope.launch { autoBackupDatabase(app) }
            bgScope.launch { initVectorLibrary(app) }
            bgScope.launch { initSafetyVerifier(app) }
            bgScope.launch { initYandereMode(app) }
        }

        private suspend fun initYandereMode(app: Application) {
            try {
                if (AppSettingsStore(app).getYandereModeEnabled()) {
                    ServiceRegistry.getOrThrow(YandereModeManager::class.java).start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("ZengqiApplication", "initYandereMode failed", e)
            }
        }

        private fun preloadBackground(app: Application) {
            ChatBackgroundCache.preload(app, getChatBackgroundKey(app))
        }

        private suspend fun seedDefaultCompanion(app: Application) {
            DefaultCompanionSeeder.seedIfNeeded(app)
        }

        private suspend fun initSecurityData(app: Application) {
            SecurityDataSeeder.seedIfNeeded(app)
        }

        private fun autoBackupDatabase(app: Application) {
            runCatching { AppDatabase.autoBackupIfNeeded(app.applicationContext) }
            runCatching { AppDatabase.clearOldBackups(app.applicationContext) }
        }

        private fun initVectorLibrary(app: Application) {
            runCatching {
                app.assets.open("safety/violation_vectors.bin").use { it.readBytes() }
                    .let { VectorLibrary.Loader().load(it) }
                    ?.let { ContentFilter.setVectorLibrary(it) }
            }
        }

        private suspend fun initSafetyVerifier(app: Application) {
            runCatching {
                ContentSafetyVerifier.init(app)
                val keywords = SecurityDataSeeder.getEnabledKeywords(app)
                if (keywords.isNotEmpty()) {
                    ContentSafetyVerifier.bootstrap(keywords)
                }
            }
        }

        private fun registerServiceProviders(app: Application) {
            // ── Repository 单例注册 ──
            // 统一 Repository 获取方式，供跨模块消费者通过 ServiceRegistry 获取。
            ServiceRegistry.registerSingleton(CompanionRepository::class.java) {
                CompanionRepository(AppDatabase.getDatabase(app).companionDao())
            }
            ServiceRegistry.registerSingleton(ChatRepository::class.java) {
                ChatRepository(AppDatabase.getDatabase(app).chatMessageDao())
            }
            ServiceRegistry.registerSingleton(MemoryRepository::class.java) {
                MemoryRepository(AppDatabase.getDatabase(app).memoryDao(), DeviceIdProvider.getDeviceId(app))
            }
            ServiceRegistry.registerSingleton(UserRepository::class.java) {
                UserRepository(app)
            }

            // ── 跨 feature 服务接口注册 ──
            ServiceRegistry.registerSingleton(UserProfileProvider::class.java) {
                com.zengqi.ai.feature.profile.UserProfileProviderImpl(app)
            }
            ServiceRegistry.registerSingleton(CompanionProvider::class.java) {
                com.zengqi.ai.feature.character.CharacterProviderImpl(app)
            }
            // MemoryProvider：跨会话记忆上下文与提取（feature:memory 实现，core:network 消费）
            // 必须在 AiService 之前注册，因为 AiService.init 会通过 ServiceRegistry 获取 MemoryProvider
            ServiceRegistry.registerSingleton(MemoryProvider::class.java) {
                val db = AppDatabase.getDatabase(app)
                ZengqiMemoryDecorator(
                    delegate = com.zengqi.ai.feature.memory.engine.MemoryManager.getInstance(app),
                    characterMemoryDao = db.characterMemoryDao(),
                    importedMessageDao = db.importedMessageDao()
                )
            }
            ServiceRegistry.registerSingleton(AiServiceProvider::class.java) {
                AiService(app)
            }
            ServiceRegistry.registerSingleton(YandereModeManager::class.java) {
                YandereModeManager(app)
            }

            ServiceRegistry.markInitialized()
        }

        private fun clearUpdateIgnore(app: Application) {
            app.getSharedPreferences("update_config", android.content.Context.MODE_PRIVATE)
                .edit().remove("ignored_version").apply()
        }

        private fun applyStoredLanguage(app: Application) {
            Locale.setDefault(
                when (app.getSharedPreferences("language_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("language", "zh-CN") ?: "zh-CN") {
                    "zh-TW" -> Locale.TRADITIONAL_CHINESE
                    "en" -> Locale.ENGLISH
                    "ja" -> Locale.JAPANESE
                    "ko" -> Locale.KOREAN
                    else -> Locale.SIMPLIFIED_CHINESE
                }
            )
        }
    }
}
