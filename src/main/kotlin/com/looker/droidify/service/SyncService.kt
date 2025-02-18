package com.looker.droidify.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import com.looker.droidify.*
import com.looker.droidify.content.Preferences
import com.looker.droidify.database.DatabaseX
import com.looker.droidify.database.entity.Repository
import com.looker.droidify.entity.Order
import com.looker.droidify.entity.ProductItem
import com.looker.droidify.entity.Section
import com.looker.droidify.index.RepositoryUpdater
import com.looker.droidify.ui.activities.MainActivityX
import com.looker.droidify.utility.RxUtils
import com.looker.droidify.utility.Utils
import com.looker.droidify.utility.extension.android.Android
import com.looker.droidify.utility.extension.android.notificationManager
import com.looker.droidify.utility.extension.resources.getColorFromAttr
import com.looker.droidify.utility.extension.text.formatSize
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class SyncService : ConnectionService<SyncService.Binder>() {
    companion object {
        private const val ACTION_CANCEL = "${BuildConfig.APPLICATION_ID}.intent.action.CANCEL"

        private val mutableStateSubject = MutableSharedFlow<State>()
        private val mutableFinishState = MutableSharedFlow<Unit>()

        private val stateSubject = mutableStateSubject.asSharedFlow()
        private val finishState = mutableFinishState.asSharedFlow()
    }

    private sealed class State {
        data class Connecting(val name: String) : State()
        data class Syncing(
            val name: String, val stage: RepositoryUpdater.Stage,
            val read: Long, val total: Long?,
        ) : State()

        object Finishing : State()
    }

    private class Task(val repositoryId: Long, val manual: Boolean)
    private data class CurrentTask(
        val task: Task?, val disposable: Disposable,
        val hasUpdates: Boolean, val lastState: State,
    )

    private enum class Started { NO, AUTO, MANUAL }

    private val scope = CoroutineScope(Dispatchers.Default)

    private var started = Started.NO
    private val tasks = mutableListOf<Task>()
    private var currentTask: CurrentTask? = null

    private var updateNotificationBlockerFragment: WeakReference<Fragment>? = null

    private val downloadConnection = Connection(DownloadService::class.java)

    enum class SyncRequest { AUTO, MANUAL, FORCE }

    inner class Binder : android.os.Binder() {
        val finish: SharedFlow<Unit>
            get() = finishState

        private fun sync(ids: List<Long>, request: SyncRequest) {
            val cancelledTask =
                cancelCurrentTask { request == SyncRequest.FORCE && it.task?.repositoryId in ids }
            cancelTasks { !it.manual && it.repositoryId in ids }
            val currentIds =
                synchronized(tasks) { tasks.map { it.repositoryId }.toSet() }
            val manual = request != SyncRequest.AUTO
            synchronized(tasks) {
                tasks += ids.filter {
                    it !in currentIds &&
                            it != currentTask?.task?.repositoryId
                }.map { Task(it, manual) }
            }
            handleNextTask(cancelledTask?.hasUpdates == true)
            if (request != SyncRequest.AUTO && started == Started.AUTO) {
                started = Started.MANUAL
                startSelf()
                handleSetStarted()
                currentTask?.lastState?.let { publishForegroundState(true, it) }
            }
        }

        fun updateApps(products: List<ProductItem>) = batchUpdate(products)
        fun installApps(products: List<ProductItem>) = batchUpdate(products, true)

        fun sync(request: SyncRequest) {
            GlobalScope.launch {
                val ids = db.repositoryDao.all.filter { it.enabled }.map { it.id }.toList()
                sync(ids, request)
            }
        }

        fun sync(repository: Repository) {
            if (repository.enabled) {
                sync(listOf(repository.id), SyncRequest.FORCE)
            }
        }

        fun cancelAuto(): Boolean {
            val removed = cancelTasks { !it.manual }
            val currentTask = cancelCurrentTask { it.task?.manual == false }
            handleNextTask(currentTask?.hasUpdates == true)
            return removed || currentTask != null
        }

        fun setUpdateNotificationBlocker(fragment: Fragment?) {
            updateNotificationBlockerFragment = fragment?.let(::WeakReference)
            if (fragment != null) {
                notificationManager.cancel(NOTIFICATION_ID_UPDATES)
            }
        }

        fun setEnabled(repository: Repository, enabled: Boolean): Boolean {
            db.repositoryDao.put(repository.enable(enabled))
            if (enabled) {
                if (repository.id != currentTask?.task?.repositoryId && !tasks.any { it.repositoryId == repository.id }) {
                    synchronized(tasks) { tasks += Task(repository.id, true) }
                    handleNextTask(false)
                }
            } else {
                cancelTasks { it.repositoryId == repository.id }
                val cancelledTask = cancelCurrentTask { it.task?.repositoryId == repository.id }
                handleNextTask(cancelledTask?.hasUpdates == true)
            }
            return true
        }

        fun isCurrentlySyncing(repositoryId: Long): Boolean {
            return currentTask?.task?.repositoryId == repositoryId
        }

        fun deleteRepository(repositoryId: Long): Boolean {
            val repository = db.repositoryDao.get(repositoryId)
            return repository != null && run {
                setEnabled(repository, false)
                db.repositoryDao.deleteById(repository.id)
                true
            }
        }
    }

    private val binder = Binder()
    override fun onBind(intent: Intent): Binder = binder
    lateinit var db: DatabaseX

    override fun onCreate() {
        super.onCreate()

        db = DatabaseX.getInstance(applicationContext)
        if (Android.sdk(26)) {
            NotificationChannel(
                NOTIFICATION_CHANNEL_SYNCING,
                getString(R.string.syncing), NotificationManager.IMPORTANCE_LOW
            )
                .apply { setShowBadge(false) }
                .let(notificationManager::createNotificationChannel)
            NotificationChannel(
                NOTIFICATION_CHANNEL_UPDATES,
                getString(R.string.updates), NotificationManager.IMPORTANCE_LOW
            )
                .let(notificationManager::createNotificationChannel)
        }

        downloadConnection.bind(this)
        stateSubject.onEach { publishForegroundState(false, it) }.launchIn(scope)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadConnection.unbind(this)
        cancelTasks { true }
        cancelCurrentTask { true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            synchronized(tasks) { tasks.clear() }
            val cancelledTask = cancelCurrentTask { it.task != null }
            handleNextTask(cancelledTask?.hasUpdates == true)
        }
        return START_NOT_STICKY
    }

    private fun cancelTasks(condition: (Task) -> Boolean): Boolean = synchronized(tasks) {
        tasks.removeAll(condition)
    }

    private fun cancelCurrentTask(condition: ((CurrentTask) -> Boolean)): CurrentTask? {
        return currentTask?.let {
            if (condition(it)) {
                currentTask = null
                it.disposable.dispose()
                RepositoryUpdater.await()
                it
            } else {
                null
            }
        }
    }

    private fun showNotificationError(repository: Repository, exception: Exception) {
        notificationManager.notify(
            "repository-${repository.id}", NOTIFICATION_ID_SYNCING, NotificationCompat
                .Builder(this, NOTIFICATION_CHANNEL_SYNCING)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(
                    ContextThemeWrapper(this, R.style.Theme_Main_Light)
                        .getColorFromAttr(android.R.attr.colorPrimary).defaultColor
                )
                .setContentTitle(getString(R.string.could_not_sync_FORMAT, repository.name))
                .setContentText(
                    getString(
                        when (exception) {
                            is RepositoryUpdater.UpdateException -> when (exception.errorType) {
                                RepositoryUpdater.ErrorType.NETWORK -> R.string.network_error_DESC
                                RepositoryUpdater.ErrorType.HTTP -> R.string.http_error_DESC
                                RepositoryUpdater.ErrorType.VALIDATION -> R.string.validation_index_error_DESC
                                RepositoryUpdater.ErrorType.PARSING -> R.string.parsing_index_error_DESC
                            }
                            else -> R.string.unknown_error_DESC
                        }
                    )
                )
                .build()
        )
    }

    private val stateNotificationBuilder by lazy {
        NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_SYNCING)
            .setSmallIcon(R.drawable.ic_sync)
            .setColor(
                ContextThemeWrapper(this, R.style.Theme_Main_Light)
                    .getColorFromAttr(android.R.attr.colorPrimary).defaultColor
            )
            .addAction(
                0, getString(R.string.cancel), PendingIntent.getService(
                    this,
                    0,
                    Intent(this, this::class.java).setAction(ACTION_CANCEL),
                    if (Android.sdk(23))
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    else
                        PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
    }

    private fun publishForegroundState(force: Boolean, state: State) {
        if (force || currentTask?.lastState != state) {
            currentTask = currentTask?.copy(lastState = state)
            if (started == Started.MANUAL) {
                startForeground(NOTIFICATION_ID_SYNCING, stateNotificationBuilder.apply {
                    when (state) {
                        is State.Connecting -> {
                            setContentTitle(getString(R.string.syncing_FORMAT, state.name))
                            setContentText(getString(R.string.connecting))
                            setProgress(0, 0, true)
                        }
                        is State.Syncing -> {
                            setContentTitle(getString(R.string.syncing_FORMAT, state.name))
                            when (state.stage) {
                                RepositoryUpdater.Stage.DOWNLOAD -> {
                                    if (state.total != null) {
                                        setContentText("${state.read.formatSize()} / ${state.total.formatSize()}")
                                        setProgress(
                                            100,
                                            (100f * state.read / state.total).roundToInt(),
                                            false
                                        )
                                    } else {
                                        setContentText(state.read.formatSize())
                                        setProgress(0, 0, true)
                                    }
                                }
                                RepositoryUpdater.Stage.PROCESS -> {
                                    val progress =
                                        state.total?.let { 100f * state.read / it }?.roundToInt()
                                    setContentText(
                                        getString(
                                            R.string.processing_FORMAT,
                                            "${progress ?: 0}%"
                                        )
                                    )
                                    setProgress(100, progress ?: 0, progress == null)
                                }
                                RepositoryUpdater.Stage.MERGE -> {
                                    val progress = (100f * state.read / (state.total
                                        ?: state.read)).roundToInt()
                                    setContentText(
                                        getString(
                                            R.string.merging_FORMAT,
                                            "${state.read} / ${state.total ?: state.read}"
                                        )
                                    )
                                    setProgress(100, progress, false)
                                }
                                RepositoryUpdater.Stage.COMMIT -> {
                                    setContentText(getString(R.string.saving_details))
                                    setProgress(0, 0, true)
                                }
                            }
                        }
                        is State.Finishing -> {
                            setContentTitle(getString(R.string.syncing))
                            setContentText(null)
                            setProgress(0, 0, true)
                        }
                    }::class
                }.build())
            }
        }
    }

    private fun handleSetStarted() {
        stateNotificationBuilder.setWhen(System.currentTimeMillis())
    }

    private fun handleNextTask(hasUpdates: Boolean) {
        if (currentTask == null) {
            GlobalScope.launch {
                if (tasks.isNotEmpty()) {
                    val task = tasks.removeAt(0)
                    val repository = db.repositoryDao.get(task.repositoryId)
                    if (repository != null && repository.enabled) {
                        val lastStarted = started
                        val newStarted =
                            if (task.manual || lastStarted == Started.MANUAL) Started.MANUAL else Started.AUTO
                        started = newStarted
                        if (newStarted == Started.MANUAL && lastStarted != Started.MANUAL) {
                            startSelf()
                            handleSetStarted()
                        }
                        val initialState = State.Connecting(repository.name)
                        publishForegroundState(true, initialState)
                        val unstable = Preferences[Preferences.Key.UpdateUnstable]
                        lateinit var disposable: Disposable
                        disposable = RepositoryUpdater
                            .update(
                                this@SyncService,
                                repository,
                                unstable
                            ) { stage, progress, total ->
                                if (!disposable.isDisposed) {
                                    scope.launch {
                                        mutableStateSubject.emit(
                                            State.Syncing(
                                                repository.name,
                                                stage,
                                                progress,
                                                total
                                            )
                                        )
                                    }
                                }
                            }
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe { result, throwable ->
                                currentTask = null
                                throwable?.printStackTrace()
                                if (throwable != null && task.manual) {
                                    showNotificationError(repository, throwable as Exception)
                                }
                                handleNextTask(result == true || hasUpdates)
                            }
                        currentTask = CurrentTask(task, disposable, hasUpdates, initialState)
                    } else {
                        handleNextTask(hasUpdates)
                    }
                } else if (started != Started.NO) {
                    val disposable = RxUtils
                        .querySingle { it ->
                            db.productDao
                                .queryObject(
                                    installed = true,
                                    updates = true,
                                    searchQuery = "",
                                    section = Section.All,
                                    order = Order.NAME
                                ).map { it.item }
                        }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { result, throwable ->
                            throwable?.printStackTrace()
                            currentTask = null
                            handleNextTask(false)
                            if (result.isNotEmpty()) {
                                if (Preferences[Preferences.Key.InstallAfterSync])
                                    batchUpdate(result)
                                if (hasUpdates && Preferences[Preferences.Key.UpdateNotify] &&
                                    updateNotificationBlockerFragment?.get()?.isAdded == true
                                )
                                    displayUpdatesNotification(result)
                            }
                        }
                    if (hasUpdates) {
                        currentTask = CurrentTask(null, disposable, true, State.Finishing)
                    } else {
                        scope.launch { mutableFinishState.emit(Unit) }
                        val needStop = started == Started.MANUAL
                        started = Started.NO
                        if (needStop) {
                            stopForeground(true)
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    /**
     * Performs automatic update after a repo sync if it is enabled. Otherwise, it continues on to
     * displayUpdatesNotification.
     *
     * @param productItems a list of apps pending updates
     * @see SyncService.displayUpdatesNotification
     */
    private fun batchUpdate(productItems: List<ProductItem>, install: Boolean = false) {
        if (Preferences[Preferences.Key.InstallAfterSync]) GlobalScope.launch {
            // run startUpdate on every item
            productItems.map { productItem ->
                Triple(
                    productItem.packageName,
                    db.installedDao.get(productItem.packageName),
                    db.repositoryDao.get(productItem.repositoryId)
                )
            }
                .filter { pair -> (install || pair.second != null) && pair.third != null }
                .forEach { installedRepository ->
                    run {
                        // Redundant !! as linter doesn't recognise the above filter's effects
                        val packageName = installedRepository.first
                        val installedItem = installedRepository.second
                        val repository = installedRepository.third!!

                        val productRepository = db.productDao.get(packageName)
                            .filter { product -> product?.repository_id == repository.id }
                            .map { product -> Pair(product?.data!!, repository) }

                        scope.launch {
                            Utils.startUpdate(
                                packageName,
                                installedItem,
                                productRepository,
                                downloadConnection
                            )
                        }
                    }
                }
        }
    }

    /**
     * Displays summary of available updates.
     *
     * @param productItems list of apps pending updates
     */
    private fun displayUpdatesNotification(productItems: List<ProductItem>) {
        val maxUpdates = 5
        fun <T> T.applyHack(callback: T.() -> Unit): T = apply(callback)
        notificationManager.notify(
            NOTIFICATION_ID_UPDATES, NotificationCompat
                .Builder(this, NOTIFICATION_CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_new_releases)
                .setContentTitle(getString(R.string.new_updates_available))
                .setContentText(
                    resources.getQuantityString(
                        R.plurals.new_updates_DESC_FORMAT,
                        productItems.size, productItems.size
                    )
                )
                .setColor(
                    ContextThemeWrapper(this, R.style.Theme_Main_Light)
                        .getColorFromAttr(android.R.attr.colorPrimary).defaultColor
                )
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivityX::class.java)
                            .setAction(MainActivityX.ACTION_UPDATES),
                        if (Android.sdk(23))
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        else
                            PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .setStyle(NotificationCompat.InboxStyle().applyHack {
                    for (productItem in productItems.take(maxUpdates)) {
                        val builder = SpannableStringBuilder(productItem.name)
                        builder.setSpan(
                            ForegroundColorSpan(Color.BLACK), 0, builder.length,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        builder.append(' ').append(productItem.version)
                        addLine(builder)
                    }
                    if (productItems.size > maxUpdates) {
                        val summary =
                            getString(R.string.plus_more_FORMAT, productItems.size - maxUpdates)
                        if (Android.sdk(24)) {
                            addLine(summary)
                        } else {
                            setSummaryText(summary)
                        }
                    }
                })
                .build()
        )
    }

    class Job : JobService() {
        private val jobScope = CoroutineScope(Dispatchers.Default)
        private var syncParams: JobParameters? = null
        private val syncConnection =
            Connection(SyncService::class.java, onBind = { connection, binder ->
                jobScope.launch {
                    binder.finish.collect {
                        val params = syncParams
                        if (params != null) {
                            syncParams = null
                            connection.unbind(this@Job)
                            jobFinished(params, false)
                        }
                    }
                }
                binder.sync(SyncRequest.AUTO)
            }, onUnbind = { _, binder ->
                binder.cancelAuto()
                jobScope.cancel()
                val params = syncParams
                if (params != null) {
                    syncParams = null
                    jobFinished(params, true)
                }
            })

        override fun onStartJob(params: JobParameters): Boolean {
            syncParams = params
            syncConnection.bind(this)
            return true
        }

        override fun onStopJob(params: JobParameters): Boolean {
            syncParams = null
            jobScope.cancel()
            val reschedule = syncConnection.binder?.cancelAuto() == true
            syncConnection.unbind(this)
            return reschedule
        }
    }
}