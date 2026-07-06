package com.excp.podroid.ui.screens.status

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.ContainerStatsRepository
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.HostMetrics
import com.excp.podroid.util.HostMetricsSnapshot
import com.excp.podroid.util.LoadSimulator
import com.excp.podroid.util.LoadSimulatorIntensity
import com.excp.podroid.util.NetworkUtils
import com.excp.podroid.util.MetricHistory
import com.excp.podroid.util.PhoneCpuSampler
import com.excp.podroid.util.VmLoadSampler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class StatusChartHistories(
    val phoneCpu: List<Float> = emptyList(),
    val phoneRam: List<Float> = emptyList(),
    val phoneStorage: List<Float> = emptyList(),
    val vmCpu: List<Float> = emptyList(),
    val vmRam: List<Float> = emptyList(),
    val vmDisk: List<Float> = emptyList(),
    val vmDiskActivity: List<Float> = emptyList(),
    val vmAvailability: List<Float> = emptyList(),
)

data class LoadSimulatorUiState(
    val phoneEnabled: Boolean = false,
    val vmEnabled: Boolean = false,
    val intensity: LoadSimulatorIntensity = LoadSimulatorIntensity.MEDIUM,
    val vmViaDownloads: Boolean = false,
) {
    val active: Boolean get() = phoneEnabled || vmEnabled
}

data class StatusUiState(
    val vmState: VmState = VmState.Idle,
    val backendId: String = "qemu",
    val engineSelection: EngineSelection = EngineSelection.AUTO,
    val uptimeLabel: String? = null,
    val phoneIp: String = "—",
    val vmRamMb: Int = 512,
    val vmCpus: Int = 2,
    val storageSizeGb: Int = 8,
    val bandwidthMbps: Int = 0,
    val loadBalanceEnabled: Boolean = false,
    val portForwardCount: Int = 0,
    val vmLoadPercent: Float? = null,
    val vmLoadHistory: List<Float> = emptyList(),
    val vmLoadGraphUnavailable: String? = null,
    val bootStage: String = "",
    val containerCount: Int? = null,
    val lastRefreshMs: Long = 0L,
    val liveTick: Long = 0L,
    val phoneCpuPercent: Float? = null,
    val charts: StatusChartHistories = StatusChartHistories(),
    val loadSimulator: LoadSimulatorUiState = LoadSimulatorUiState(),
    val metrics: HostMetricsSnapshot = HostMetricsSnapshot(
        phoneTotalRamMb = 0,
        phoneAvailRamMb = 0,
        phoneCpuCores = 1,
        loadAvg1 = null,
        loadAvg5 = null,
        loadAvg15 = null,
        phoneStorageTotalGb = 0.0,
        phoneStorageAvailGb = 0.0,
        vmDiskImageBytes = 0L,
        emulatorRssMb = null,
    ),
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VmEngine,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val containerStatsRepository: ContainerStatsRepository,
    private val loadSimulator: LoadSimulator,
) : ViewModel() {

    private val _loadSimulator = MutableStateFlow(LoadSimulatorUiState())

    private val _metrics = MutableStateFlow(defaultMetrics())
    private val _uptimeTick = MutableStateFlow(0L)
    private val _vmLoadPercent = MutableStateFlow<Float?>(null)
    private val _vmLoadUnavailable = MutableStateFlow<String?>(null)
    private val _chartHistories = MutableStateFlow(StatusChartHistories())
    private val _containerCount = MutableStateFlow<Int?>(null)
    private val _lastRefreshMs = MutableStateFlow(0L)
    private val _liveTick = MutableStateFlow(0L)
    private val _phoneIp = MutableStateFlow("—")
    private val _phoneCpuPercent = MutableStateFlow<Float?>(null)
    private val _liveActive = MutableStateFlow(false)
    private var lastDiskBytes = 0L
    private val loadSampler = VmLoadSampler()
    private val phoneCpuSampler = PhoneCpuSampler()

    companion object {
        private const val POLL_MS = 500L
    }

    val uiState: StateFlow<StatusUiState> = combine(
        combine(
            engine.state,
            engine.bootStage,
            settingsRepository.vmRamMb,
            settingsRepository.vmCpus,
            settingsRepository.storageSizeGb,
        ) { vmState, bootStage, ram, cpus, storage ->
            arrayOf(vmState, bootStage, ram, cpus, storage)
        },
        combine(
            settingsRepository.bandwidthMbps,
            settingsRepository.loadBalanceEnabled,
            settingsRepository.engineSelection,
            portForwardRepository.rules,
            _metrics,
        ) { bandwidth, loadBal, engineSel, rules, metrics ->
            arrayOf(bandwidth, loadBal, engineSel, rules, metrics)
        },
        combine(
            _uptimeTick,
            _vmLoadPercent,
            _vmLoadUnavailable,
            _containerCount,
            _chartHistories,
        ) { tick, pct, unavailable, containers, charts ->
            arrayOf(tick, pct, unavailable, containers, charts)
        },
        combine(
            _lastRefreshMs,
            _liveTick,
            _phoneIp,
            _phoneCpuPercent,
            _loadSimulator,
        ) { refreshed, tick, ip, phoneCpu, loadSim ->
            arrayOf(refreshed, tick, ip, phoneCpu, loadSim)
        },
    ) { a, b, c, d ->
        val vmState = a[0] as VmState
        val tick = c[0] as Long
        val charts = c[4] as StatusChartHistories
        StatusUiState(
            vmState = vmState,
            bootStage = a[1] as String,
            backendId = engine.backendId,
            engineSelection = b[2] as EngineSelection,
            uptimeLabel = uptimeLabel(vmState, tick),
            phoneIp = d[2] as String,
            vmRamMb = a[2] as Int,
            vmCpus = a[3] as Int,
            storageSizeGb = a[4] as Int,
            bandwidthMbps = b[0] as Int,
            loadBalanceEnabled = b[1] as Boolean,
            portForwardCount = (b[3] as List<*>).size,
            vmLoadPercent = c[1] as Float?,
            vmLoadHistory = charts.vmCpu,
            vmLoadGraphUnavailable = c[2] as String?,
            containerCount = c[3] as Int?,
            lastRefreshMs = d[0] as Long,
            liveTick = d[1] as Long,
            phoneCpuPercent = d[3] as Float?,
            charts = charts,
            loadSimulator = d[4] as LoadSimulatorUiState,
            metrics = b[4] as HostMetricsSnapshot,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), StatusUiState())

    private var lastContainerPollMs = 0L

    fun setLiveActive(active: Boolean) {
        _liveActive.value = active
        if (active) {
            viewModelScope.launch { pollOnce() }
        } else {
            loadSimulator.stopAll()
            _loadSimulator.value = _loadSimulator.value.copy(phoneEnabled = false, vmEnabled = false)
            phoneCpuSampler.reset()
            loadSampler.reset()
            lastDiskBytes = 0L
            _chartHistories.value = StatusChartHistories()
        }
    }

    fun setLoadSimPhone(enabled: Boolean) {
        _loadSimulator.value = _loadSimulator.value.copy(phoneEnabled = enabled)
        applyLoadSimulator()
    }

    fun setLoadSimVm(enabled: Boolean) {
        _loadSimulator.value = _loadSimulator.value.copy(vmEnabled = enabled)
        applyLoadSimulator()
    }

    fun setLoadSimIntensity(level: LoadSimulatorIntensity) {
        _loadSimulator.value = _loadSimulator.value.copy(intensity = level)
        applyLoadSimulator()
    }

    fun toggleLoadSimulator() {
        val s = _loadSimulator.value
        if (s.active) {
            _loadSimulator.value = s.copy(phoneEnabled = false, vmEnabled = false)
        } else {
            _loadSimulator.value = s.copy(phoneEnabled = true, vmEnabled = s.vmViaDownloads)
        }
        applyLoadSimulator()
    }

    fun copyGuestStressCommand(): String {
        val cmd = if (_loadSimulator.value.vmEnabled) {
            loadSimulator.guestTerminalCommand(_loadSimulator.value.intensity)
        } else {
            loadSimulator.guestStopCommand()
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("podroid-stress", cmd))
        return cmd
    }

    private fun applyLoadSimulator(vmViaDownloads: Boolean = _loadSimulator.value.vmViaDownloads) {
        val s = _loadSimulator.value
        loadSimulator.configure(s.phoneEnabled, s.vmEnabled && vmViaDownloads, s.intensity)
    }

    override fun onCleared() {
        loadSimulator.stopAll()
        super.onCleared()
    }

    init {
        _phoneIp.value = NetworkUtils.localIpv4(context)
        viewModelScope.launch {
            settingsRepository.storageAccessEnabled.collect { enabled ->
                val via = engine.backendId == "qemu" && enabled
                val prev = _loadSimulator.value
                _loadSimulator.value = prev.copy(vmViaDownloads = via)
                if (prev.phoneEnabled || prev.vmEnabled) applyLoadSimulator(via)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                if (_liveActive.value) pollOnce()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun pollOnce() {
        refreshMetrics()
        updatePhoneCharts()
        sampleVmLoad()
        _phoneIp.value = NetworkUtils.localIpv4(context)
        _liveTick.value = System.currentTimeMillis()
        if (engine.state.value is VmState.Running) {
            _uptimeTick.value = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            if (now - lastContainerPollMs >= 5_000) {
                _containerCount.value = containerStatsRepository.readContainerCount()
                lastContainerPollMs = now
            }
        }
    }

    private fun updatePhoneCharts() {
        val m = _metrics.value
        val ramUsedPct = if (m.phoneTotalRamMb > 0) {
            ((m.phoneTotalRamMb - m.phoneAvailRamMb).toFloat() / m.phoneTotalRamMb * 100f)
        } else 0f
        val storageUsedPct = if (m.phoneStorageTotalGb > 0) {
            ((m.phoneStorageTotalGb - m.phoneStorageAvailGb) / m.phoneStorageTotalGb * 100.0).toFloat()
        } else 0f
        var h = _chartHistories.value.copy(
            phoneRam = MetricHistory.append(_chartHistories.value.phoneRam, ramUsedPct),
            phoneStorage = MetricHistory.append(_chartHistories.value.phoneStorage, storageUsedPct),
        )
        phoneCpuSampler.samplePercent()?.let { pct ->
            _phoneCpuPercent.value = pct
            h = h.copy(phoneCpu = MetricHistory.append(h.phoneCpu, pct))
        }
        _chartHistories.value = h
    }

    fun refreshMetrics() {
        val storageImg = File(context.filesDir, "storage.img")
        val running = engine.state.value is VmState.Running
        val rss = if (running) engine.emulatorRssMb() else null
        _metrics.value = HostMetrics.snapshot(context, storageImg, rss)
        _lastRefreshMs.value = System.currentTimeMillis()

        val diskBytes = if (storageImg.isFile) storageImg.length() else 0L
        val diskDeltaKb = if (lastDiskBytes > 0L && diskBytes >= lastDiskBytes) {
            ((diskBytes - lastDiskBytes) / 1024f)
        } else 0f
        lastDiskBytes = diskBytes

        val h = _chartHistories.value
        _chartHistories.value = h.copy(
            vmDisk = if (running) MetricHistory.append(h.vmDisk, diskBytes.toFloat()) else h.vmDisk,
            vmDiskActivity = if (running) MetricHistory.append(h.vmDiskActivity, diskDeltaKb) else h.vmDiskActivity,
            vmRam = if (rss != null) MetricHistory.append(h.vmRam, rss.toFloat()) else h.vmRam,
            vmAvailability = MetricHistory.append(h.vmAvailability, if (running) 100f else 0f),
        )
    }

    private fun sampleVmLoad() {
        if (engine.state.value !is VmState.Running) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            _vmLoadUnavailable.value = null
            return
        }

        val pid = engine.emulatorPid()
        if (pid == null) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            _vmLoadUnavailable.value = "avf"
            return
        }

        _vmLoadUnavailable.value = null
        val pct = loadSampler.sampleCpuPercent(pid, uiState.value.vmCpus) ?: return
        _vmLoadPercent.value = pct
        val h = _chartHistories.value
        _chartHistories.value = h.copy(vmCpu = MetricHistory.append(h.vmCpu, pct))
    }

    private fun defaultMetrics(): HostMetricsSnapshot {
        val storageImg = File(context.filesDir, "storage.img")
        return HostMetrics.snapshot(context, storageImg, null)
    }

    private fun uptimeLabel(vmState: VmState, tick: Long): String? {
        if (vmState !is VmState.Running) return null
        val since = engine.runningSinceMs ?: return null
        val secs = ((tick.takeIf { it > 0 } ?: System.currentTimeMillis()) - since) / 1000
        if (secs < 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
