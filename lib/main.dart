import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const BabyRepeaterApp());
}

class BabyRepeaterApp extends StatelessWidget {
  const BabyRepeaterApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '宝宝复读机',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF2D6A4F),
          surface: const Color(0xFFF5F1E8),
        ),
        scaffoldBackgroundColor: const Color(0xFFF5F1E8),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const _methodChannel = MethodChannel('bbreplace/control');
  static const _eventChannel = EventChannel('bbreplace/status');

  StreamSubscription<dynamic>? _statusSubscription;
  StatusViewData _status = const StatusViewData.idle();
  bool _busy = false;
  bool _ignoringBatteryOptimizations = false;
  bool _notificationsEnabled = false;
  InputMode _inputMode = InputMode.auto;

  @override
  void initState() {
    super.initState();
    _statusSubscription = _eventChannel.receiveBroadcastStream().listen(
      _onStatusEvent,
    );
    unawaited(_refreshStatus());
    unawaited(_refreshBatteryOptimizationStatus());
    unawaited(_refreshNotificationPermissionStatus());
    unawaited(_refreshInputMode());
  }

  @override
  void dispose() {
    _statusSubscription?.cancel();
    super.dispose();
  }

  Future<void> _refreshStatus() async {
    try {
      final dynamic raw = await _methodChannel.invokeMethod('getStatus');
      if (!mounted || raw is! Map) {
        return;
      }
      setState(() {
        _status = StatusViewData.fromMap(raw);
      });
    } on MissingPluginException {
      if (!mounted) {
        return;
      }
      setState(() {
        _status = const StatusViewData.idle();
      });
    }
  }

  Future<void> _refreshBatteryOptimizationStatus() async {
    try {
      final result =
          await _methodChannel.invokeMethod<bool>(
            'isIgnoringBatteryOptimizations',
          ) ??
          false;
      if (!mounted) {
        return;
      }
      setState(() {
        _ignoringBatteryOptimizations = result;
      });
    } on MissingPluginException {
      if (!mounted) {
        return;
      }
      setState(() {
        _ignoringBatteryOptimizations = false;
      });
    }
  }

  Future<void> _refreshNotificationPermissionStatus() async {
    try {
      final result =
          await _methodChannel.invokeMethod<bool>(
            'isNotificationPermissionGranted',
          ) ??
          false;
      if (!mounted) {
        return;
      }
      setState(() {
        _notificationsEnabled = result;
      });
    } on MissingPluginException {
      if (!mounted) {
        return;
      }
      setState(() {
        _notificationsEnabled = false;
      });
    }
  }

  Future<void> _refreshInputMode() async {
    try {
      final result =
          await _methodChannel.invokeMethod<String>('getInputMode') ?? 'auto';
      if (!mounted) {
        return;
      }
      setState(() {
        _inputMode = InputMode.fromValue(result);
      });
    } on MissingPluginException {
      if (!mounted) {
        return;
      }
      setState(() {
        _inputMode = InputMode.auto;
      });
    }
  }

  void _onStatusEvent(dynamic event) {
    if (!mounted || event is! Map) {
      return;
    }
    setState(() {
      _status = StatusViewData.fromMap(event);
    });
  }

  Future<void> _toggle() async {
    if (_busy) {
      return;
    }
    setState(() {
      _busy = true;
    });

    try {
      if (_status.isRunning) {
        await _methodChannel.invokeMethod('stopListening');
        return;
      }

      final granted =
          await _methodChannel.invokeMethod<bool>(
            'requestMicrophonePermission',
          ) ??
          false;
      if (!granted) {
        _showSnackBar('需要麦克风权限才能工作');
        return;
      }

      final notificationsGranted =
          await _methodChannel.invokeMethod<bool>(
            'requestNotificationPermission',
          ) ??
          false;
      if (!notificationsGranted) {
        _showSnackBar('需要通知权限才能稳定后台运行');
        await _refreshNotificationPermissionStatus();
        return;
      }

      await _methodChannel.invokeMethod('startListening');
      await _refreshNotificationPermissionStatus();
    } on PlatformException catch (error) {
      _showSnackBar(error.message ?? '操作失败');
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
        });
      }
    }
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _openBatteryOptimizationSettings() async {
    try {
      await _methodChannel.invokeMethod('requestIgnoreBatteryOptimizations');
      await Future<void>.delayed(const Duration(milliseconds: 500));
      await _refreshBatteryOptimizationStatus();
    } on PlatformException catch (error) {
      _showSnackBar(error.message ?? '无法打开电池优化设置');
    }
  }

  Future<void> _setInputMode(InputMode mode) async {
    if (_inputMode == mode) {
      return;
    }
    try {
      await _methodChannel.invokeMethod('setInputMode', {'mode': mode.value});
      if (!mounted) {
        return;
      }
      setState(() {
        _inputMode = mode;
      });
      _showSnackBar('输入模式已切换为${mode.label}');
    } on PlatformException catch (error) {
      _showSnackBar(error.message ?? '切换输入模式失败');
    }
  }

  Future<void> _openNotificationSettings() async {
    try {
      await _methodChannel.invokeMethod('openNotificationSettings');
      await Future<void>.delayed(const Duration(milliseconds: 500));
      await _refreshNotificationPermissionStatus();
    } on PlatformException catch (error) {
      _showSnackBar(error.message ?? '无法打开通知设置');
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [Color(0xFF1B4332), Color(0xFF40916C)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(28),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '宝宝复读机',
                      style: theme.textTheme.headlineMedium?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      '持续监听麦克风，检测到一句完整的话后自动回放一次。',
                      style: theme.textTheme.bodyLarge?.copyWith(
                        color: Colors.white.withValues(alpha: 0.92),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Wrap(
                      spacing: 12,
                      runSpacing: 12,
                      children: [
                        _Badge(
                          label: _status.stateLabel,
                          color: _status.badgeColor,
                        ),
                        _Badge(
                          label: _status.lastUtteranceMs > 0
                              ? '最近一句 ${_status.lastUtteranceMs} ms'
                              : '等待声音',
                          color: Colors.white.withValues(alpha: 0.16),
                          textColor: Colors.white,
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 24),
              Expanded(
                child: Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(28),
                    boxShadow: [
                      BoxShadow(
                        color: scheme.shadow.withValues(alpha: 0.08),
                        blurRadius: 24,
                        offset: const Offset(0, 12),
                      ),
                    ],
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '当前状态',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        _status.message,
                        style: theme.textTheme.headlineSmall?.copyWith(
                          color: const Color(0xFF1B4332),
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 16),
                      _RouteLine(label: '当前输入', value: _status.inputRoute),
                      _RouteLine(label: '当前输出', value: _status.outputRoute),
                      const SizedBox(height: 16),
                      const _FeatureLine('带预录缓存，避免吞掉开头音节'),
                      const _FeatureLine('用动态噪声底和连续帧判定开始讲话'),
                      const _FeatureLine('用静音尾段判定结束，减少误截断'),
                      const _FeatureLine('回放期间暂停触发，避免无限复读'),
                      const SizedBox(height: 12),
                      Text(
                        '输入模式',
                        style: theme.textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 8),
                      SegmentedButton<InputMode>(
                        segments: InputMode.values
                            .map(
                              (mode) => ButtonSegment<InputMode>(
                                value: mode,
                                label: Text(mode.label),
                              ),
                            )
                            .toList(),
                        selected: {_inputMode},
                        onSelectionChanged: (selection) {
                          final mode = selection.first;
                          unawaited(_setInputMode(mode));
                        },
                      ),
                      const SizedBox(height: 12),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(14),
                        decoration: BoxDecoration(
                          color: const Color(0xFFF0F7F2),
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '后台保活',
                              style: theme.textTheme.titleSmall?.copyWith(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            const SizedBox(height: 6),
                            Text(
                              _ignoringBatteryOptimizations
                                  ? '已加入电池优化白名单，后台更稳。'
                                  : '建议关闭电池优化，减少后台被系统杀掉。',
                            ),
                            const SizedBox(height: 10),
                            OutlinedButton(
                              onPressed: _openBatteryOptimizationSettings,
                              child: Text(
                                _ignoringBatteryOptimizations
                                    ? '重新检查电池优化'
                                    : '关闭电池优化',
                              ),
                            ),
                            const SizedBox(height: 10),
                            Text(
                              _notificationsEnabled
                                  ? '通知权限已开启，前台服务通知可见。'
                                  : '通知权限未开启，前台服务通知可能不显示。',
                            ),
                            const SizedBox(height: 10),
                            OutlinedButton(
                              onPressed: _openNotificationSettings,
                              child: Text(
                                _notificationsEnabled
                                    ? '检查通知设置'
                                    : '开启通知权限',
                              ),
                            ),
                          ],
                        ),
                      ),
                      const Spacer(),
                      SizedBox(
                        width: double.infinity,
                        child: FilledButton(
                          onPressed: _busy ? null : _toggle,
                          style: FilledButton.styleFrom(
                            backgroundColor: const Color(0xFF2D6A4F),
                            foregroundColor: Colors.white,
                            padding: const EdgeInsets.symmetric(vertical: 18),
                            textStyle: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.w700,
                            ),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(18),
                            ),
                          ),
                          child: Text(_status.isRunning ? '停止监听' : '开始监听'),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _FeatureLine extends StatelessWidget {
  const _FeatureLine(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        children: [
          Container(
            width: 18,
            height: 18,
            decoration: const BoxDecoration(
              color: Color(0xFF2D6A4F),
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }
}

class _RouteLine extends StatelessWidget {
  const _RouteLine({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: RichText(
        text: TextSpan(
          style: theme.textTheme.bodyMedium?.copyWith(color: const Color(0xFF1B4332)),
          children: [
            TextSpan(
              text: '$label：',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            TextSpan(text: value),
          ],
        ),
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.color, this.textColor});

  final String label;
  final Color color;
  final Color? textColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: textColor ?? const Color(0xFF1B4332),
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class StatusViewData {
  const StatusViewData({
    required this.state,
    required this.message,
    required this.isRunning,
    required this.lastUtteranceMs,
    required this.inputRoute,
    required this.outputRoute,
  });

  const StatusViewData.idle()
    : state = 'idle',
        message = '未启动',
        isRunning = false,
        lastUtteranceMs = 0,
        inputRoute = '未知',
        outputRoute = '未知';

  final String state;
  final String message;
  final bool isRunning;
  final int lastUtteranceMs;
  final String inputRoute;
  final String outputRoute;

  factory StatusViewData.fromMap(Map<dynamic, dynamic> map) {
    return StatusViewData(
      state: map['state'] as String? ?? 'idle',
      message: map['message'] as String? ?? '未启动',
      isRunning: map['isRunning'] as bool? ?? false,
      lastUtteranceMs: (map['lastUtteranceMs'] as num?)?.toInt() ?? 0,
      inputRoute: map['inputRoute'] as String? ?? '未知',
      outputRoute: map['outputRoute'] as String? ?? '未知',
    );
  }

  String get stateLabel => switch (state) {
    'capturing' => '正在录制',
    'playing' => '正在回放',
    'listening' => '正在监听',
    'error' => '发生错误',
    _ => '未启动',
  };

  Color get badgeColor => switch (state) {
    'capturing' => const Color(0xFFFFD166),
    'playing' => const Color(0xFFB7E4C7),
    'listening' => const Color(0xFFD8F3DC),
    'error' => const Color(0xFFF28482),
    _ => const Color(0xFFE9ECEF),
  };
}

enum InputMode {
  auto('auto', '自动选择'),
  bluetooth('bluetooth', '优先蓝牙'),
  phone('phone', '强制手机');

  const InputMode(this.value, this.label);

  final String value;
  final String label;

  factory InputMode.fromValue(String value) {
    return InputMode.values.firstWhere(
      (mode) => mode.value == value,
      orElse: () => InputMode.auto,
    );
  }
}
