package com.guaishoudejia.x4doublesysfserv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * BLE 设备浮动按钮 - 位于左侧
 * @param isConnected 是否已连接
 * @param deviceName 设备名称
 * @param onScan 点击扫描回调
 * @param onForget 点击忘记回调
 */
@Composable
fun BleFloatingButton(
    isConnected: Boolean,
    deviceName: String = "",
    onScan: () -> Unit,
    onForget: () -> Unit,
    onStatusClick: () -> Unit = {},
    isPanelExpanded: Boolean = false,
    onTogglePanel: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onExit: () -> Unit = {},
    isOcrProcessing: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // 浮动按钮
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(56.dp),
            containerColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFF2196F3),
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = "Bluetooth",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }

        // 展开菜单
        if (expanded) {
            Popup(
                alignment = Alignment.CenterStart,
                offset = androidx.compose.ui.unit.IntOffset(x = 70, y = 0),
                properties = PopupProperties(focusable = true, dismissOnBackPress = true),
                onDismissRequest = { expanded = false }
            ) {
                Surface(
                    modifier = Modifier
                        .width(200.dp)
                        .background(
                            color = Color.White,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 状态显示
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isConnected) "✓ 已连接" else "✗ 未连接",
                                fontSize = 12.sp,
                                color = if (isConnected) Color(0xFF4CAF50) else Color.Gray
                            )
                            if (isConnected && deviceName.isNotEmpty()) {
                                Text(
                                    text = deviceName,
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Divider()

                        // 扫描按钮
                        MenuItemButton(
                            icon = "🔍",
                            label = "选择设备",
                            onClick = {
                                expanded = false
                                onScan()
                            }
                        )

                        // 忘记设备按钮 (仅在已连接时显示)
                        if (isConnected && deviceName.isNotEmpty()) {
                            MenuItemButton(
                                icon = "🗑️",
                                label = "忘记设备",
                                onClick = {
                                    expanded = false
                                    onForget()
                                },
                                isDanger = true
                            )
                        }

                        // 展开/收起 预览
                        MenuItemButton(
                            icon = "🗂️",
                            label = if (isPanelExpanded) "收起预览" else "展开预览",
                            onClick = {
                                expanded = false
                                onTogglePanel()
                            }
                        )

                        // 重刷当前页
                        MenuItemButton(
                            icon = "🔄",
                            label = "重刷当前页",
                            onClick = {
                                expanded = false
                                onRefresh()
                            }
                        )

                        // 退出
                        MenuItemButton(
                            icon = "🚪",
                            label = "退出",
                            onClick = {
                                expanded = false
                                onExit()
                            }
                        )

                        // 关闭按钮
                        MenuItemButton(
                            icon = "✕",
                            label = "关闭",
                            onClick = { expanded = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItemButton(
    icon: String,
    label: String,
    isDanger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(icon, fontSize = 16.sp)
        Text(
            label,
            fontSize = 13.sp,
            color = if (isDanger) Color.Red else Color.Black,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * BLE 设备扫描底表
 */
@Composable
fun BleDeviceScanSheet(
    isVisible: Boolean,
    isScanning: Boolean,
    deviceList: List<BleDeviceItem>,
    onDeviceSelected: (address: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onDismiss),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = false, onClick = {})
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // 标题栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "选择 BLE 设备",
                            fontSize = 18.sp,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable(onClick = onDismiss)
                            )
                        }
                    }

                    Divider()

                    // 设备列表
                    if (deviceList.isEmpty() && !isScanning) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "未扫描到设备\n请检查 Bluetooth 权限并确保设备已开启",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            deviceList.forEach { device ->
                                BleDeviceRow(
                                    device = device,
                                    onClick = {
                                        onDeviceSelected(device.address, device.name)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 底部按钮
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        enabled = !isScanning
                    ) {
                        Text(if (isScanning) "扫描中..." else "完成")
                    }
                }
            }
        }
    }
}

@Composable
private fun BleDeviceRow(
    device: BleDeviceItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "📡",
            fontSize = 20.sp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name,
                fontSize = 14.sp,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                device.address,
                fontSize = 11.sp,
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (device.rssi > -100) {
            Text(
                "${device.rssi} dBm",
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
    }
    Divider()
}

/**
 * BLE 设备项数据类
 */
data class BleDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int = -100
)
