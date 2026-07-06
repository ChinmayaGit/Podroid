package com.excp.podroid.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import android.widget.Toast
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.MetricChartCard
import com.excp.podroid.ui.components.MetricLegend
import com.excp.podroid.ui.components.ChartSeries
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.components.PodroidChipColors
import com.excp.podroid.ui.theme.PodroidTokens
import com.excp.podroid.util.HostMetrics
import com.excp.podroid.util.LoadSimulatorIntensity

private const val TAB_PHONE = 0
private const val TAB_VM = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_VM) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setLiveActive(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setLiveActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.status_page_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val simActive = ui.loadSimulator.active
                    IconButton(onClick = viewModel::toggleLoadSimulator) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = stringResource(
                                if (simActive) R.string.status_load_sim_stop else R.string.status_load_sim_action,
                            ),
                            tint = if (simActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PodroidTokens.Spacing.XL, vertical = PodroidTokens.Spacing.SM),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusLiveBadge(active = ui.liveTick > 0L)
                }
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == TAB_PHONE,
                        onClick = { selectedTab = TAB_PHONE },
                        text = { Text(stringResource(R.string.status_tab_phone)) },
                    )
                    Tab(
                        selected = selectedTab == TAB_VM,
                        onClick = { selectedTab = TAB_VM },
                        text = { Text(stringResource(R.string.status_tab_vm)) },
                    )
                }

                LoadSimulatorSection(
                    ui = ui,
                    onPhoneToggle = viewModel::setLoadSimPhone,
                    onVmToggle = viewModel::setLoadSimVm,
                    onIntensity = viewModel::setLoadSimIntensity,
                    onCopyGuestCommand = {
                        viewModel.copyGuestStressCommand()
                        Toast.makeText(context, R.string.status_load_sim_copied, Toast.LENGTH_SHORT).show()
                    },
                )

                when (selectedTab) {
                    TAB_PHONE -> PhoneStatusTab(ui = ui)
                    TAB_VM -> VmStatusTab(ui = ui)
                }
            }
        }
    }
}

@Composable
private fun LoadSimulatorSection(
    ui: StatusUiState,
    onPhoneToggle: (Boolean) -> Unit,
    onVmToggle: (Boolean) -> Unit,
    onIntensity: (LoadSimulatorIntensity) -> Unit,
    onCopyGuestCommand: () -> Unit,
) {
    val sim = ui.loadSimulator
    val anyActive = sim.active

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PodroidTokens.Spacing.LG, vertical = PodroidTokens.Spacing.SM),
        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
    ) {
        PodroidSectionLabel(stringResource(R.string.status_load_sim_section))
        Text(
            text = stringResource(R.string.status_load_sim_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (anyActive) {
            Text(
                text = stringResource(R.string.status_load_sim_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        PodroidListRow(
            label = stringResource(R.string.status_load_sim_phone),
            divider = sim.vmViaDownloads,
            rightSlot = {
                PodroidSwitch(checked = sim.phoneEnabled, onCheckedChange = onPhoneToggle)
            },
        )
        if (sim.vmViaDownloads) {
            PodroidListRow(
                label = stringResource(R.string.status_load_sim_vm),
                divider = false,
                rightSlot = {
                    PodroidSwitch(checked = sim.vmEnabled, onCheckedChange = onVmToggle)
                },
            )
        } else {
            Text(
                text = stringResource(R.string.status_load_sim_vm_hint_terminal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PodroidGhostButton(
                text = stringResource(R.string.status_load_sim_copy_command),
                onClick = onCopyGuestCommand,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
        ) {
            LoadSimIntensityChip(
                label = stringResource(R.string.status_load_sim_intensity_low),
                selected = sim.intensity == LoadSimulatorIntensity.LOW,
                onClick = { onIntensity(LoadSimulatorIntensity.LOW) },
            )
            LoadSimIntensityChip(
                label = stringResource(R.string.status_load_sim_intensity_medium),
                selected = sim.intensity == LoadSimulatorIntensity.MEDIUM,
                onClick = { onIntensity(LoadSimulatorIntensity.MEDIUM) },
            )
            LoadSimIntensityChip(
                label = stringResource(R.string.status_load_sim_intensity_high),
                selected = sim.intensity == LoadSimulatorIntensity.HIGH,
                onClick = { onIntensity(LoadSimulatorIntensity.HIGH) },
            )
        }
        Spacer(modifier = Modifier.height(PodroidTokens.Spacing.SM))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadSimIntensityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = PodroidChipColors(),
    )
}

@Composable
private fun StatusLiveBadge(active: Boolean) {
    if (!active) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = stringResource(R.string.status_live_badge),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.status_live_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveRefreshHint(lastRefreshMs: Long) {
    if (lastRefreshMs <= 0L) {
        Text(
            text = stringResource(R.string.status_refresh_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = PodroidTokens.Spacing.SM),
        )
    }
}

@Composable
private fun PhoneStatusTab(ui: StatusUiState) {
    val metrics = ui.metrics
    val charts = ui.charts
    val phoneRamUsedMb = (metrics.phoneTotalRamMb - metrics.phoneAvailRamMb).coerceAtLeast(0)
    val phoneStorageUsedGb = (metrics.phoneStorageTotalGb - metrics.phoneStorageAvailGb)
        .coerceAtLeast(0.0)
    val cpuColor = MaterialTheme.colorScheme.primary
    val ramColor = MaterialTheme.colorScheme.tertiary
    val storageColor = MaterialTheme.colorScheme.secondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PodroidTokens.Spacing.LG, vertical = PodroidTokens.Spacing.MD),
        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
    ) {
        MetricChartCard(
            title = stringResource(R.string.status_metric_phone_cpu),
            series = listOf(ChartSeries(charts.phoneCpu, cpuColor)),
            yMax = 100f,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_cpu_legend),
                    value = ui.phoneCpuPercent?.let { String.format("%.1f%%", it) }
                        ?: stringResource(R.string.status_vm_load_collecting),
                    color = cpuColor,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_phone_ram),
            series = listOf(ChartSeries(charts.phoneRam, ramColor)),
            yMax = 100f,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.ram_label),
                    value = "${HostMetrics.formatMb(phoneRamUsedMb)} / ${HostMetrics.formatMb(metrics.phoneTotalRamMb)}",
                    color = ramColor,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_phone_storage),
            series = listOf(ChartSeries(charts.phoneStorage, storageColor)),
            yMax = 100f,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.storage),
                    value = "${HostMetrics.formatGb(phoneStorageUsedGb)} / ${HostMetrics.formatGb(metrics.phoneStorageTotalGb)}",
                    color = storageColor,
                ),
            ),
        )
        PodroidSectionLabel(stringResource(R.string.status_phone_details))
        PodroidListRow(label = stringResource(R.string.cpu_cores), value = "${metrics.phoneCpuCores}")
        val loadLabel = if (metrics.loadAvg1 != null) {
            String.format("%.2f · %.2f · %.2f", metrics.loadAvg1, metrics.loadAvg5, metrics.loadAvg15)
        } else stringResource(R.string.status_unavailable)
        PodroidListRow(label = stringResource(R.string.status_load_average), value = loadLabel, mono = true)
        PodroidListRow(label = stringResource(R.string.phone_ip), value = ui.phoneIp, mono = true, divider = false)
        LiveRefreshHint(ui.lastRefreshMs)
    }
}

@Composable
private fun VmStatusTab(ui: StatusUiState) {
    val metrics = ui.metrics
    val charts = ui.charts
    val cpuColor = MaterialTheme.colorScheme.primary
    val memColor = Color(0xFFE91E8C)
    val diskColor = MaterialTheme.colorScheme.primary
    val diskActColor = Color(0xFFE91E8C)
    val availColor = MaterialTheme.colorScheme.primary
    val vmCpuUnavailable = when {
        ui.vmState !is VmState.Running -> stringResource(R.string.status_vm_load_stopped)
        ui.vmLoadGraphUnavailable == "avf" -> stringResource(R.string.status_vm_load_avf_only)
        else -> null
    }
    val diskUsedPct = if (ui.storageSizeGb > 0) {
        metrics.vmDiskImageBytes.toDouble() / (ui.storageSizeGb.toLong() * 1024 * 1024 * 1024) * 100.0
    } else 0.0
    val lastDiskAct = charts.vmDiskActivity.lastOrNull() ?: 0f
    val availValue = if (ui.vmState is VmState.Running) "100%" else "0%"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PodroidTokens.Spacing.LG, vertical = PodroidTokens.Spacing.MD),
        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
    ) {
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_cpu),
            series = listOf(ChartSeries(charts.vmCpu, cpuColor)),
            yMax = 100f,
            unavailable = vmCpuUnavailable,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_cpu_legend),
                    value = ui.vmLoadPercent?.let { String.format("%.2f%%", it) }
                        ?: stringResource(R.string.status_vm_load_collecting),
                    color = cpuColor,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_memory),
            series = listOf(ChartSeries(charts.vmRam, memColor)),
            unavailable = if (ui.vmState !is VmState.Running) stringResource(R.string.status_vm_load_stopped) else null,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_memory_legend),
                    value = metrics.emulatorRssMb?.let { HostMetrics.formatMb(it) }
                        ?: stringResource(R.string.status_unavailable),
                    color = memColor,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_disk),
            series = listOf(ChartSeries(charts.vmDisk, diskColor)),
            unavailable = if (ui.vmState !is VmState.Running) stringResource(R.string.status_vm_load_stopped) else null,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_vm_disk),
                    value = "${HostMetrics.formatGb(metrics.vmDiskImageBytes)} (${String.format("%.1f%%", diskUsedPct)})",
                    color = diskColor,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_disk_activity),
            series = listOf(ChartSeries(charts.vmDiskActivity, diskActColor)),
            unavailable = if (ui.vmState !is VmState.Running) stringResource(R.string.status_vm_load_stopped) else null,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_disk_write_legend),
                    value = String.format("%.1f KB/sample", lastDiskAct),
                    color = diskActColor,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_availability),
            series = listOf(ChartSeries(charts.vmAvailability, availColor)),
            yMax = 100f,
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_availability_legend),
                    value = availValue,
                    color = availColor,
                ),
            ),
        )
        PodroidSectionLabel(stringResource(R.string.status_vm_details))
        PodroidListRow(
            label = stringResource(R.string.vm_status),
            value = vmStatusLabel(ui.vmState, ui.uptimeLabel, ui.bootStage),
        )
        PodroidListRow(
            label = stringResource(R.string.backend),
            value = backendLabel(ui.engineSelection, ui.backendId),
            mono = true,
        )
        PodroidListRow(label = stringResource(R.string.cpu_cores), value = "${ui.vmCpus}")
        PodroidListRow(
            label = stringResource(R.string.home_containers_created),
            value = ui.containerCount?.toString() ?: stringResource(R.string.home_containers_unknown),
            mono = true,
        )
        PodroidListRow(
            label = stringResource(R.string.port_forwards),
            value = "${ui.portForwardCount}",
            divider = false,
        )
        LiveRefreshHint(ui.lastRefreshMs)
    }
}

@Composable
private fun vmStatusLabel(vmState: VmState, uptime: String?, bootStage: String): String = when (vmState) {
    is VmState.Running -> uptime?.let { "${stringResource(R.string.status_running)} · $it" }
        ?: stringResource(R.string.status_running)
    is VmState.Starting -> bootStage.ifEmpty { stringResource(R.string.status_starting) }
    is VmState.Error -> stringResource(R.string.status_error)
    else -> stringResource(R.string.status_stopped)
}

@Composable
private fun backendLabel(selection: EngineSelection, activeId: String): String = when (selection) {
    EngineSelection.AUTO -> "${stringResource(R.string.auto)} ($activeId)"
    EngineSelection.AVF -> stringResource(R.string.avf_kvm)
    EngineSelection.QEMU -> stringResource(R.string.qemu_tcg)
}
