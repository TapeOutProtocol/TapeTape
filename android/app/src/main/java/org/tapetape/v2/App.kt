package org.tapetape.v2

import android.app.Application
import android.content.Context
import android.os.StrictMode
import org.tapetape.v2.core.CoreEvent
import org.tapetape.v2.core.CoreFactory
import org.tapetape.v2.core.ProxySettings
import org.tapetape.v2.core.TapeTapeAccount
import org.tapetape.v2.core.TapeTapeCore
import org.tapetape.v2.notify.FetchWorker
import org.tapetape.v2.notify.Notifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
        TapeTape.start(this)
        FetchWorker.schedule(this)
    }
}

enum class ThemeMode { System, Light, Dark }

object TapeTape {
    lateinit var core: TapeTapeCore
        private set
    private lateinit var appContext: Context

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()
    val accountId = MutableStateFlow<Int?>(null)
    val theme = MutableStateFlow(ThemeMode.System)
    val pendingLink = MutableStateFlow<String?>(null)
    val pendingChat = MutableStateFlow<Int?>(null)
    val pendingShare = MutableStateFlow<org.tapetape.v2.ui.Screen.Share?>(null)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val transfers = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val proxy = MutableStateFlow(ProxySettings(false, null, emptyList()))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val ready = MutableStateFlow(false)
    private val started = CompletableDeferred<Unit>()

    suspend fun awaitReady() = started.await()

    fun start(context: Context) {
        appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            Prefs.init(appContext)
            init()
            ready.value = true
            started.complete(Unit)
        }
    }

    val account: TapeTapeAccount? get() = accountId.value?.let { core.account(it) }

    private suspend fun init() {
        theme.value = ThemeMode.entries[prefs().getInt("theme", 0)]
        val start = System.currentTimeMillis()
        core = CoreFactory.create(appContext.filesDir.absolutePath) { acc, event -> onEvent(acc, event) }
        Logx.i("core ready real=${CoreFactory.REAL} accounts=${core.accounts().size} in ${System.currentTimeMillis() - start}ms")
        proxy.value = core.proxySettings()
        Logx.i("proxy enabled=${proxy.value.enabled} count=${proxy.value.proxies.size}")
        val selected = core.selectedAccount()
        core.accounts().filter { it.id != selected }.forEach { startAccount(it.id) }
        selected?.let { activate(it) }
    }

    private fun startAccount(id: Int): TapeTapeAccount {
        val account = core.account(id)
        if (BuildConfig.FILES_BACKEND.isNotEmpty()) {
            account.setFilesBackend(BuildConfig.FILES_BACKEND)
            Logx.i("account $id files backend from build config")
        }
        account.start()
        return account
    }

    suspend fun activate(id: Int) = withContext(Dispatchers.IO) {
        core.selectAccount(id)
        val account = startAccount(id)
        account.savedMessagesChat()
        transfers.value = emptyMap()
        accountId.value = id
        Logx.i("account $id active")
    }

    suspend fun removeAccount(id: Int) = withContext(Dispatchers.IO) {
        core.removeAccount(id)
        Logx.i("account $id removed")
        if (accountId.value == id) {
            val next = core.accounts().firstOrNull()?.id
            if (next != null) activate(next) else accountId.value = null
        }
        touch()
    }

    fun dataDir(): java.io.File = appContext.filesDir

    fun touch() = _revision.update { it + 1 }

    fun setTheme(mode: ThemeMode) {
        theme.value = mode
        prefs().edit().putInt("theme", mode.ordinal).apply()
    }

    private fun prefs() = appContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private fun onEvent(accountId: Int, event: CoreEvent) {
        Logx.d("event acc=$accountId $event")
        if (event is CoreEvent.TransferProgress) {
            if (accountId == this.accountId.value) transfers.update {
                if (event.total <= 0 || event.done >= event.total) it - event.msgId else it + (event.msgId to event.done.toFloat() / event.total)
            }
            return
        }
        if (event is CoreEvent.Notify) Notifier.post(appContext, event.notification)
        if (event is CoreEvent.Error) {
            Logx.w("core error: ${event.message}")
            errors.tryEmit(event.message)
        }
        _revision.update { it + 1 }
    }
}
