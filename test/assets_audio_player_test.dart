import 'dart:async';

import 'package:assets_audio_player/assets_audio_player.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// Delivers one native-to-Dart event on this player's receive channel, as the real plugin would.
Future<void> deliverToPlayer(String playerId, MethodCall call) async {
  const codec = StandardMethodCodec();
  await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
    'assets_audio_player/$playerId',
    codec.encodeMethodCall(call),
    (data) {},
  );
  await Future<void>.delayed(Duration.zero);
}

/// Fake of the native plugin contract: an open settles a live transport and playing feedback is only
/// ever produced while that transport is still held natively.
class NativeContractFake {
  final invoked = <String>[];
  int openCalls = 0;
  bool transportLive = false;
  bool holdLoopReply = false;
  final Completer<void> loopReply = Completer<void>();

  Future<dynamic> handle(MethodCall call) async {
    invoked.add(call.method);
    if (call.method == 'open') {
      openCalls += 1;
      transportLive = true;
    } else if (call.method == 'loopSingleAudio' && holdLoopReply) {
      await loopReply.future;
    }
    return null;
  }

  void releaseLoopReply() {
    holdLoopReply = false;
    loopReply.complete();
  }

  void expireTransport() => transportLive = false;

  /// Playing feedback only exists while the native side still holds the transport.
  Future<void> reportPlayingIfLive(AssetsAudioPlayer player, {required bool playing}) async {
    if (transportLive) {
      await deliverToPlayer(player.id, MethodCall('player.isPlaying', playing));
    }
  }
}

Future<void> _pumpUntil(bool Function() condition) async {
  for (var i = 0; i < 200 && !condition(); i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  const channel = MethodChannel('assets_audio_player');

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    // ignore: deprecated_member_use
    channel.setMockMethodCallHandler((MethodCall methodCall) async {
      return '42';
    });
  });

  tearDown(() {
    // ignore: deprecated_member_use
    channel.setMockMethodCallHandler(null);
  });

  test('a superseded open error is not reported as the newer attempt playback failure', () async {
    final player = AssetsAudioPlayer();
    final reports = <AssetsAudioPlayerError>[];
    player.onErrorDo = (handler) => reports.add(handler.error);
    final invokedMethods = <String>[];
    // ignore: deprecated_member_use
    channel.setMockMethodCallHandler((MethodCall methodCall) async {
      invokedMethods.add(methodCall.method);
      if (methodCall.method == 'open') {
        throw PlatformException(code: 'OPEN_SUPERSEDED', message: 'a newer open took the reply slot');
      }
      return null;
    });

    await player.open(Audio('assets/audios/rock.mp3'));

    expect(reports, isEmpty); // the superseded attempt is dropped, not reported
    expect(player.isPlaying.valueOrNull, isNot(true)); // the newer attempt owns the playback state

    // The open admission lock settled: a later public open reaches the channel again.
    final openCallsBefore = invokedMethods.where((m) => m == 'open').length;
    await player.open(Audio('assets/audios/rock.mp3'));
    expect(invokedMethods.where((m) => m == 'open').length, openCallsBefore + 1);
  });

  test('a superseded open error with the default handler never stops the player', () async {
    final player = AssetsAudioPlayer(); // keeps the default onErrorDo, which stops the player
    final invokedMethods = <String>[];
    // ignore: deprecated_member_use
    channel.setMockMethodCallHandler((MethodCall methodCall) async {
      invokedMethods.add(methodCall.method);
      if (methodCall.method == 'open') {
        throw PlatformException(code: 'OPEN_SUPERSEDED', message: 'a newer open took the reply slot');
      }
      return null;
    });

    await player.open(Audio('assets/audios/rock.mp3'));

    expect(invokedMethods, isNot(contains('stop'))); // the newer attempt keeps playing
  });

  test('a real open failure is still reported to onErrorDo', () async {
    final player = AssetsAudioPlayer();
    final reports = <AssetsAudioPlayerError>[];
    player.onErrorDo = (handler) => reports.add(handler.error);
    // ignore: deprecated_member_use
    channel.setMockMethodCallHandler((MethodCall methodCall) async {
      if (methodCall.method == 'open') {
        throw PlatformException(code: 'OPEN_ERROR', message: 'the decoder refused the file');
      }
      return null;
    });

    await player.open(Audio('assets/audios/rock.mp3'));

    expect(reports, isNotEmpty); // a current playback failure keeps its report
  });

  test('an expired accepted transport marks the playlist stopped so the next play reopens and plays', () async {
    final player = AssetsAudioPlayer();
    final fake = NativeContractFake();
    // ignore: deprecated_member_use
    channel.setMockMethodCallHandler(fake.handle);

    await player.prepareForegroundPlayback(metas: Metas(title: 'rain'));
    expect(player.stopped, isFalse); // the prepared card keeps the playlist armable

    await player.open(Audio('assets/audios/rock.mp3'), autoStart: false);
    expect(fake.openCalls, 1);
    expect(player.isPlaying.valueOrNull, isNot(true)); // opened paused: no playing UI

    fake.expireTransport();
    await deliverToPlayer(player.id, const MethodCall('player.expired'));
    expect(player.stopped, isTrue); // the missing transport must not stay playable
    expect(player.playerState.valueOrNull, PlayerState.stop); // a stop, not a pause

    await player.play();
    expect(fake.openCalls, 2, reason: 'the expired playlist reopens on the next authorized play');

    await player.play();
    expect(fake.invoked.where((m) => m == 'play'), isNotEmpty);
    await fake.reportPlayingIfLive(player, playing: true);
    expect(player.isPlaying.valueOrNull, isTrue);
  });

  test('an expiry during the open completion keeps the playlist stopped until a fresh authorized open', () async {
    final player = AssetsAudioPlayer();
    final fake = NativeContractFake();
    fake.holdLoopReply = true;
    // ignore: deprecated_member_use
    channel.setMockMethodCallHandler(fake.handle);

    final opening = player.open(Audio('assets/audios/rock.mp3'), autoStart: false);
    await _pumpUntil(() => fake.invoked.contains('loopSingleAudio'));

    fake.expireTransport();
    await deliverToPlayer(player.id, const MethodCall('player.expired'));
    fake.releaseLoopReply();
    await opening;

    expect(player.stopped, isTrue); // the expiry must outlive the open it interrupted
    expect(player.isPlaying.valueOrNull, isNot(true));
    await fake.reportPlayingIfLive(player, playing: true);
    expect(player.isPlaying.valueOrNull, isNot(true)); // a dead transport never reports playing

    await player.play();
    expect(fake.openCalls, 2, reason: 'the next authorized resume must send a new native open');
  });
}
