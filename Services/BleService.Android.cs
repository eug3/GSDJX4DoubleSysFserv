#if ANDROID
using Android.Bluetooth;

// 此文件为旧版原生 BLE 实现，目前默认使用 Shiny 实现。
// 抑制平台 API/可空性警告，避免影响构建。
#pragma warning disable CA1416 // 平台特定 API
#pragma warning disable CA1422 // 过时的 Android API
#pragma warning disable CS8602 // 可能的空引用解引用
#pragma warning disable CS8604 // 可能的空引用参数
using Android.Bluetooth.LE;
using Android.Content;
using Android.OS;
using System.Collections.ObjectModel;

namespace GSDJX4DoubleSysFserv.Services;

/// <summary>
/// 蓝牙服务 Android 实现 - 支持后台模式
/// </summary>
public class BleServiceAndroid : IBleService
{
    private const string SavedMacKey = "Ble_SavedMacAddress";
    private readonly IStorageService _storageService;
    private BluetoothAdapter? _adapter;
    private BluetoothLeScanner? _scanner;
    private BluetoothDevice? _connectedDevice;
    private BluetoothGatt? _gatt;
    private readonly Dictionary<string, BluetoothDevice> _discoveredDevices = new();
    private TaskCompletionSource<ObservableCollection<BleDeviceInfo>>? _scanTcs;
    private ObservableCollection<BleDeviceInfo>? _scannedDevices;
    private bool _shouldAutoReconnect = true;
    private GattCallback? _gattCallback;
    
    // 写入同步信号
    private TaskCompletionSource<bool>? _writeTcs;
    private readonly SemaphoreSlim _writeSemaphore = new(1, 1);

    public bool IsConnected { get; private set; }
    public string? ConnectedDeviceName { get; private set; }

    /// <summary>
    /// 按键事件
    /// </summary>
    public event EventHandler<ButtonEventArgs>? ButtonPressed;
    
    /// <summary>
    /// 连接状态变化事件
    /// </summary>
    public event EventHandler<ConnectionStateChangedEventArgs>? ConnectionStateChanged;

    public BleServiceAndroid(IStorageService storageService)
    {
        _storageService = storageService;
        InitializeAdapter();
    }

    private void InitializeAdapter()
    {
        var manager = (BluetoothManager?)Android.App.Application.Context.GetSystemService(Context.BluetoothService);
        _adapter = manager?.Adapter;
        _scanner = _adapter?.BluetoothLeScanner;
        _gattCallback = new GattCallback(this);
        System.Diagnostics.Debug.WriteLine($"BLE: Android 适配器初始化完成，状态: {_adapter?.IsEnabled}");
    }

    public async Task<string?> GetSavedMacAddress()
    {
        return await _storageService.GetAsync<string>(SavedMacKey);
    }

    public async Task SaveMacAddress(string macAddress)
    {
        await _storageService.SetAsync(SavedMacKey, macAddress);
    }

    public async Task DeleteSavedMacAddress()
    {
        await _storageService.RemoveAsync(SavedMacKey);
        Disconnect();
    }

    private TaskCompletionSource<bool>? _connectTcs;

    public async Task<bool> ConnectAsync(string deviceId, string macAddress)
    {
        try
        {
            if (_adapter == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 适配器未初始化");
                return false;
            }

            // 优先使用已发现的设备
            BluetoothDevice? device = null;
            if (_discoveredDevices.TryGetValue(deviceId, out var discovered))
            {
                device = discovered;
                System.Diagnostics.Debug.WriteLine($"BLE: 使用已发现的设备 - {device.Name}");
            }
            else
            {
                device = _adapter.GetRemoteDevice(macAddress);
                System.Diagnostics.Debug.WriteLine($"BLE: 使用 MAC 地址创建设备 - {macAddress}");
            }
            
            if (device == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 设备为空");
                return false;
            }

            _connectedDevice = device;
            _connectTcs = new TaskCompletionSource<bool>();
            
            System.Diagnostics.Debug.WriteLine($"BLE: 开始连接到 {device.Name ?? macAddress}...");
            
            // 使用 autoConnect=false 立即连接，不等待后台
            _gatt = device.ConnectGatt(
                Android.App.Application.Context, 
                false,  // autoConnect: false 立即连接
                _gattCallback,
                BluetoothTransports.Le
            );

            if (_gatt == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: ConnectGatt 返回 null");
                return false;
            }

            // 等待连接结果，最多 15 秒
            var timeoutTask = Task.Delay(15000);
            var completedTask = await Task.WhenAny(_connectTcs.Task, timeoutTask);
            
            if (completedTask == timeoutTask)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 连接超时");
                _gatt?.Disconnect();
                _gatt?.Close();
                _gatt = null;
                return false;
            }

            var result = await _connectTcs.Task;
            if (result)
            {
                await SaveMacAddress(macAddress);
                System.Diagnostics.Debug.WriteLine($"BLE: 连接成功 - {ConnectedDeviceName}");
            }
            else
            {
                System.Diagnostics.Debug.WriteLine("BLE: 连接失败");
            }
            
            return result;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 连接错误 - {ex.Message}");
            _gatt?.Disconnect();
            _gatt?.Close();
            _gatt = null;
            return false;
        }
    }

    public void Disconnect()
    {
        _shouldAutoReconnect = false;
        _gatt?.Disconnect();
        _gatt?.Close();
        _gatt = null;
        _connectedDevice = null;
        IsConnected = false;
        ConnectedDeviceName = null;
        System.Diagnostics.Debug.WriteLine("BLE: 已断开连接");
    }

    public async Task<ObservableCollection<BleDeviceInfo>> ScanDevicesAsync()
    {
        if (_adapter == null)
        {
            System.Diagnostics.Debug.WriteLine("BLE: BluetoothAdapter 未初始化");
            throw new InvalidOperationException("蓝牙适配器未初始化，请检查设备是否支持蓝牙");
        }

        if (!_adapter.IsEnabled)
        {
            System.Diagnostics.Debug.WriteLine("BLE: 蓝牙未启用");
            throw new InvalidOperationException("蓝牙未启用，请先开启蓝牙");
        }

        if (_scanner == null)
        {
            System.Diagnostics.Debug.WriteLine("BLE: BluetoothLeScanner 未初始化");
            throw new InvalidOperationException("蓝牙扫描器不可用");
        }

        _scannedDevices = new ObservableCollection<BleDeviceInfo>();
        _discoveredDevices.Clear();
        _scanTcs = new TaskCompletionSource<ObservableCollection<BleDeviceInfo>>();

        var scanCallback = new BleScanCallback(this);
        var settings = new ScanSettings.Builder()
            .SetScanMode(Android.Bluetooth.LE.ScanMode.LowLatency)
            .Build();

        System.Diagnostics.Debug.WriteLine("BLE: 开始扫描... (低延迟模式，扫描时长 8 秒)");
        
        try
        {
            _scanner.StartScan(null, settings, scanCallback);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 启动扫描失败 - {ex.Message}");
            throw new InvalidOperationException($"启动蓝牙扫描失败: {ex.Message}。可能缺少权限或蓝牙状态异常", ex);
        }

        // 扫描 8 秒后停止
        _ = Task.Run(async () =>
        {
            await Task.Delay(8000);
            try
            {
                _scanner?.StopScan(scanCallback);
                System.Diagnostics.Debug.WriteLine($"BLE: 扫描结束，发现 {_scannedDevices?.Count ?? 0} 个设备");
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 停止扫描时出错 - {ex.Message}");
            }
            _scanTcs?.TrySetResult(_scannedDevices ?? new ObservableCollection<BleDeviceInfo>());
        });

        return await _scanTcs.Task;
    }

    private void OnDeviceFound(BluetoothDevice device, int rssi)
    {
        var deviceId = device.Address ?? "";
        var deviceName = device.Name;

        // 过滤条件：
        // 1. 必须有地址
        // 2. 有名字或者信号强度较强（可能是 ESP32 但名字未广播）
        if (string.IsNullOrEmpty(deviceId))
        {
            return;
        }

        // 过滤掉没有名字的设备（只显示有蓝牙名称的设备）
        if (string.IsNullOrWhiteSpace(deviceName))
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 跳过无名设备 - ({deviceId}) RSSI: {rssi}");
            return;
        }

        System.Diagnostics.Debug.WriteLine($"BLE: 发现设备 - {deviceName} ({deviceId}) RSSI: {rssi}");

        if (!_discoveredDevices.ContainsKey(deviceId))
        {
            _discoveredDevices[deviceId] = device;

            var deviceInfo = new BleDeviceInfo
            {
                Id = deviceId,
                Name = $"{deviceName} (RSSI: {rssi})",
                MacAddress = deviceId
            };

            MainThread.BeginInvokeOnMainThread(() =>
            {
                _scannedDevices?.Add(deviceInfo);
            });
        }
    }

    private void OnConnectionStateChanged(BluetoothGatt? gatt, GattStatus status, ProfileState newState)
    {
        System.Diagnostics.Debug.WriteLine($"BLE: 连接状态变化 - {newState}, Status: {status}");

        if (newState == ProfileState.Connected)
        {
            IsConnected = true;
            ConnectedDeviceName = gatt?.Device?.Name ?? "未知设备";
            _shouldAutoReconnect = true;
            System.Diagnostics.Debug.WriteLine($"BLE: 已连接到 {ConnectedDeviceName}");
            
            // 通知连接成功
            _connectTcs?.TrySetResult(true);
            
            // 触发连接状态变化事件
            MainThread.BeginInvokeOnMainThread(() =>
            {
                ConnectionStateChanged?.Invoke(this, new ConnectionStateChangedEventArgs(true, ConnectedDeviceName, ConnectionChangeReason.UserInitiated));
            });
            
            // 发现服务
            gatt?.DiscoverServices();
        }
        else if (newState == ProfileState.Disconnected)
        {
            var wasConnected = IsConnected;
            var previousDeviceName = ConnectedDeviceName;
            IsConnected = false;
            ConnectedDeviceName = null;
            System.Diagnostics.Debug.WriteLine("BLE: 连接已断开");

            // 如果是连接过程中断开，通知连接失败
            if (_connectTcs != null && !_connectTcs.Task.IsCompleted)
            {
                _connectTcs.TrySetResult(false);
            }
            
            // 触发连接状态变化事件
            if (wasConnected)
            {
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    ConnectionStateChanged?.Invoke(this, new ConnectionStateChangedEventArgs(false, previousDeviceName, ConnectionChangeReason.DeviceDisconnected));
                });
            }

            // 后台自动重连（仅当之前已连接时）
            if (_shouldAutoReconnect && wasConnected && _connectedDevice != null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 尝试自动重连...");
                Task.Delay(1000).ContinueWith(_ =>
                {
                    _gatt = _connectedDevice.ConnectGatt(
                        Android.App.Application.Context,
                        true,  // autoConnect: 后台自动重连
                        _gattCallback,
                        BluetoothTransports.Le
                    );
                });
            }
        }
    }

    private void OnServicesDiscovered(BluetoothGatt? gatt, GattStatus status)
    {
        System.Diagnostics.Debug.WriteLine($"BLE: 服务发现完成 - {status}");
        if (status == GattStatus.Success && gatt != null)
        {
            foreach (var service in gatt.Services ?? Enumerable.Empty<BluetoothGattService>())
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 发现服务 - {service.Uuid}");
                
                // 查找可通知特征值并订阅，用于接收按键事件
                foreach (var characteristic in service.Characteristics ?? Enumerable.Empty<BluetoothGattCharacteristic>())
                {
                    if ((characteristic.Properties & GattProperty.Notify) != 0)
                    {
                        System.Diagnostics.Debug.WriteLine($"BLE: 订阅通知 - {characteristic.Uuid}");
                        gatt.SetCharacteristicNotification(characteristic, true);
                        
                        // 设置 CCCD (Client Characteristic Configuration Descriptor)
                        var cccd = characteristic.GetDescriptor(Java.Util.UUID.FromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (cccd != null)
                        {
                            cccd.SetValue(BluetoothGattDescriptor.EnableNotificationValue.ToArray());
                            gatt.WriteDescriptor(cccd);
                        }
                    }
                }
            }
        }
    }

    /// <summary>
    /// 处理特征值变化（按键事件）
    /// </summary>
    private void OnCharacteristicChanged(byte[] data)
    {
        try
        {
            // 将字节数据转换为字符串
            var text = System.Text.Encoding.UTF8.GetString(data).Trim();
            System.Diagnostics.Debug.WriteLine($"BLE: 收到通知数据 - {text}");

            // 处理按键通知 (KEY:LEFT, KEY:RIGHT, KEY:UP, KEY:DOWN, KEY:OK)
            if (text.StartsWith("KEY:"))
            {
                var key = text.Substring(4).ToUpper(); // 提取 KEY:后的值
                System.Diagnostics.Debug.WriteLine($"BLE: 检测到按键 - {key}");
                
                // 触发按键事件
                MainThread.BeginInvokeOnMainThread(() =>
                {
                    ButtonPressed?.Invoke(this, new ButtonEventArgs { Key = key });
                });
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 处理特征值变化异常 - {ex.Message}");
        }
    }

    /// <summary>
    /// 写入完成回调处理
    /// </summary>
    private void OnCharacteristicWriteComplete(bool success)
    {
        System.Diagnostics.Debug.WriteLine($"BLE: 写入完成回调 - {(success ? "成功" : "失败")}");
        _writeTcs?.TrySetResult(success);
    }

    /// <summary>
    /// 等待写入操作完成
    /// </summary>
    private async Task<bool> WriteWithResponseAsync(BluetoothGattCharacteristic characteristic, byte[] data, int timeoutMs = 5000)
    {
        await _writeSemaphore.WaitAsync();
        try
        {
            // 检查特征值是否支持带响应写入
            bool supportsWriteWithResponse = (characteristic.Properties & GattProperty.Write) != 0;
            bool supportsWriteNoResponse = (characteristic.Properties & GattProperty.WriteNoResponse) != 0;

            characteristic.SetValue(data);

            if (supportsWriteWithResponse)
            {
                // 带响应写入：等待回调
                _writeTcs = new TaskCompletionSource<bool>();
                characteristic.WriteType = GattWriteType.Default;
                
                if (!_gatt!.WriteCharacteristic(characteristic))
                {
                    System.Diagnostics.Debug.WriteLine("BLE: WriteCharacteristic 调用失败");
                    return false;
                }

                // 等待写入完成回调，设置超时
                var completedTask = await Task.WhenAny(_writeTcs.Task, Task.Delay(timeoutMs));
                if (completedTask == _writeTcs.Task)
                {
                    return await _writeTcs.Task;
                }
                else
                {
                    System.Diagnostics.Debug.WriteLine("BLE: 写入超时");
                    return false;
                }
            }
            else if (supportsWriteNoResponse)
            {
                // 无响应写入：直接写入，延迟后返回
                characteristic.WriteType = GattWriteType.NoResponse;
                
                if (!_gatt!.WriteCharacteristic(characteristic))
                {
                    System.Diagnostics.Debug.WriteLine("BLE: WriteCharacteristic (NoResponse) 调用失败");
                    return false;
                }
                
                // 无响应写入需要适当延迟
                await Task.Delay(20);
                return true;
            }
            else
            {
                System.Diagnostics.Debug.WriteLine("BLE: 特征值不支持任何写入方式");
                return false;
            }
        }
        finally
        {
            _writeSemaphore.Release();
        }
    }

    /// <summary>
    /// 启动时尝试自动连接已保存的设备
    /// </summary>
    public async Task TryAutoConnectOnStartupAsync()
    {
        try
        {
            var macAddress = await GetSavedMacAddress();
            if (!string.IsNullOrEmpty(macAddress))
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 启动时尝试自动连接到 {macAddress}");
                await ConnectAsync(macAddress, macAddress);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 启动时自动连接失败 - {ex.Message}");
        }
    }

    /// <summary>
    /// 发送文本到设备（X4IM v2 协议）
    /// </summary>
    /// <param name="text">文本内容</param>
    /// <param name="chapter">章节号</param>
    /// <returns>是否发送成功</returns>
    public async Task<bool> SendTextToDeviceAsync(string text, int chapter = 0)
    {
        try
        {
            if (!IsConnected || _gatt == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 设备未连接");
                return false;
            }

            if (string.IsNullOrEmpty(text))
            {
                System.Diagnostics.Debug.WriteLine("BLE: 文本内容为空");
                return false;
            }

            // 获取写特征值
            var writeChar = FindWriteCharacteristic(_gatt);
            if (writeChar == null)
            {
                System.Diagnostics.Debug.WriteLine("BLE: 未找到可写特征值");
                return false;
            }

            System.Diagnostics.Debug.WriteLine($"BLE: 准备发送文本 - 章节: {chapter}, 长度: {text.Length} 字符");

            // 根据 X4IM v2 协议生成帧头
            var bookId = $"weread_{chapter}";
            var textBytes = System.Text.Encoding.UTF8.GetBytes(text);
            var header = X4IMProtocol.CreateHeader((uint)textBytes.Length, bookId, sd: 0);

            System.Diagnostics.Debug.WriteLine($"BLE: 帧头已生成 - bookId={bookId}, payloadSize={textBytes.Length}");

            // MTU 大小（安全值：512字节）
            int MTU = 512;

            // 第一个分包：帧头(32字节) + 数据
            int firstChunkDataSize = Math.Min(MTU - 32, textBytes.Length);
            var firstChunk = new byte[32 + firstChunkDataSize];
            Array.Copy(header, 0, firstChunk, 0, 32);
            Array.Copy(textBytes, 0, firstChunk, 32, firstChunkDataSize);

            // 使用带响应的写入
            if (!await WriteWithResponseAsync(writeChar, firstChunk))
            {
                System.Diagnostics.Debug.WriteLine("BLE: 写入第一个分包失败");
                return false;
            }

            System.Diagnostics.Debug.WriteLine($"BLE: ✅ 已发送帧头 + 第一块 ({firstChunk.Length} 字节)");

            // 后续分包：纯数据
            int offset = firstChunkDataSize;
            int chunkNum = 1;

            while (offset < textBytes.Length)
            {
                int remainingSize = textBytes.Length - offset;
                int currentChunkSize = Math.Min(MTU, remainingSize);
                var chunk = new byte[currentChunkSize];
                Array.Copy(textBytes, offset, chunk, 0, currentChunkSize);

                // 使用带响应的写入
                if (!await WriteWithResponseAsync(writeChar, chunk))
                {
                    System.Diagnostics.Debug.WriteLine($"BLE: 写入分包 {chunkNum} 失败");
                    return false;
                }

                offset += currentChunkSize;
                chunkNum++;

                if (chunkNum % 10 == 0 || offset >= textBytes.Length)
                {
                    int percent = (int)((offset / (double)textBytes.Length) * 100);
                    System.Diagnostics.Debug.WriteLine($"BLE: 进度 {offset}/{textBytes.Length} 字节 ({percent}%)");
                }
            }

            System.Diagnostics.Debug.WriteLine($"BLE: ✅ 文本数据传输完成 ({chunkNum} 块，{textBytes.Length} 字节)");

            // 等待一段时间确保数据被处理
            await Task.Delay(50);

            // 发送 EOF 标记通知 ESP32 传输完成
            var eofMarker = X4IMProtocol.EOF_MARKER;
            if (!await WriteWithResponseAsync(writeChar, eofMarker))
            {
                System.Diagnostics.Debug.WriteLine("BLE: 发送 EOF 标记失败");
                return false;
            }

            System.Diagnostics.Debug.WriteLine("BLE: ✅ 已发送 EOF 标记，触发 ESP32 显示");

            return true;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: ❌ 发送文本失败 - {ex.Message}");
            System.Diagnostics.Debug.WriteLine($"BLE: 堆栈: {ex.StackTrace}");
            return false;
        }
    }

    /// <summary>
    /// 查找设备的可写特征值
    /// 优先选择带响应的Write，其次WriteWithoutResponse
    /// </summary>
    private BluetoothGattCharacteristic? FindWriteCharacteristic(BluetoothGatt gatt)
    {
        try
        {
            BluetoothGattCharacteristic? writeWithResponse = null;
            BluetoothGattCharacteristic? writeWithoutResponse = null;

            foreach (var service in gatt.Services ?? Enumerable.Empty<BluetoothGattService>())
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 扫描服务 - {service.Uuid}");
                
                foreach (var characteristic in service.Characteristics ?? Enumerable.Empty<BluetoothGattCharacteristic>())
                {
                    var props = characteristic.Properties;
                    System.Diagnostics.Debug.WriteLine($"BLE:   特征值 {characteristic.Uuid} - 属性: {props}");
                    
                    // 优先选择带响应的写入
                    if ((props & GattProperty.Write) != 0)
                    {
                        writeWithResponse = characteristic;
                        System.Diagnostics.Debug.WriteLine($"BLE: ✅ 找到可写特征值(带响应) - {characteristic.Uuid}");
                    }
                    
                    // 备选：无响应写入
                    if ((props & GattProperty.WriteNoResponse) != 0 && writeWithoutResponse == null)
                    {
                        writeWithoutResponse = characteristic;
                        System.Diagnostics.Debug.WriteLine($"BLE: 找到可写特征值(无响应) - {characteristic.Uuid}");
                    }
                }
            }

            // 优先返回带响应的特征值
            if (writeWithResponse != null)
            {
                System.Diagnostics.Debug.WriteLine($"BLE: 使用带响应写入特征值");
                return writeWithResponse;
            }
            
            if (writeWithoutResponse != null)
            {
                System.Diagnostics.Debug.WriteLine($"BLE: ⚠️ 使用无响应写入特征值（可能不稳定）");
                return writeWithoutResponse;
            }

            System.Diagnostics.Debug.WriteLine("BLE: ❌ 未找到任何可写特征值");
            return null;
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"BLE: 查找特征值异常 - {ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// BLE 扫描回调
    /// </summary>
    private class BleScanCallback : ScanCallback
    {
        private readonly BleServiceAndroid _service;

        public BleScanCallback(BleServiceAndroid service)
        {
            _service = service;
        }

        public override void OnScanResult(ScanCallbackType callbackType, ScanResult? result)
        {
            base.OnScanResult(callbackType, result);
            if (result?.Device != null)
            {
                _service.OnDeviceFound(result.Device, result.Rssi);
            }
        }

        public override void OnScanFailed(ScanFailure errorCode)
        {
            base.OnScanFailed(errorCode);
            System.Diagnostics.Debug.WriteLine($"BLE: 扫描失败 - {errorCode}");
        }
    }

    /// <summary>
    /// 蓝牙 GATT 回调实现
    /// </summary>
    private class GattCallback : BluetoothGattCallback
    {
        private readonly BleServiceAndroid _service;

        public GattCallback(BleServiceAndroid service)
        {
            _service = service;
        }

        public override void OnConnectionStateChange(BluetoothGatt? gatt, GattStatus status, ProfileState newState)
        {
            base.OnConnectionStateChange(gatt, status, newState);
            _service.OnConnectionStateChanged(gatt, status, newState);
        }

        public override void OnServicesDiscovered(BluetoothGatt? gatt, GattStatus status)
        {
            base.OnServicesDiscovered(gatt, status);
            _service.OnServicesDiscovered(gatt, status);
        }

        public override void OnCharacteristicChanged(BluetoothGatt? gatt, BluetoothGattCharacteristic? characteristic)
        {
            base.OnCharacteristicChanged(gatt, characteristic);
            if (characteristic?.GetValue() is byte[] data && data.Length > 0)
            {
                _service.OnCharacteristicChanged(data);
            }
        }

        public override void OnCharacteristicWrite(BluetoothGatt? gatt, BluetoothGattCharacteristic? characteristic, GattStatus status)
        {
            base.OnCharacteristicWrite(gatt, characteristic, status);
            _service.OnCharacteristicWriteComplete(status == GattStatus.Success);
        }
    }
}

#pragma warning restore CA1416
#pragma warning restore CA1422
#pragma warning restore CS8602
#pragma warning restore CS8604
#endif
