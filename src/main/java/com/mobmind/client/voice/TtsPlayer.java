package com.mobmind.client.voice;

import com.mobmind.MobMindMod;
import com.mobmind.util.MobMindExecutor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;

/**
 * 生物语音播放：将 TTS 返回的 WAV 字节通过 Java Sound 播放。
 * 在独立线程中顺序播放，不打断游戏音频引擎。
 */
public final class TtsPlayer {
	private TtsPlayer() {}

	public static void play(byte[] wavBytes) {
		MobMindExecutor.runAsync(() -> {
			try (AudioInputStream raw = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
				AudioFormat base = raw.getFormat();
				AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
						base.getSampleRate(), 16, base.getChannels(),
						base.getChannels() * 2, base.getSampleRate(), false);
				try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm, raw)) {
					DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcm);
					try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
						line.open(pcm);
						line.start();
						byte[] buf = new byte[8192];
						int n;
						while ((n = in.read(buf)) != -1) {
							line.write(buf, 0, n);
						}
						line.drain();
					}
				}
			} catch (Exception e) {
				MobMindMod.LOGGER.warn("[MobMind] 语音播放失败: {}", e.getMessage());
			}
		});
	}

	/** 播放 WAV 文件，播放完成后删除临时文件 */
	public static void play(java.nio.file.Path wavFile) {
		MobMindExecutor.runAsync(() -> {
			try {
				byte[] bytes = java.nio.file.Files.readAllBytes(wavFile);
				play(bytes);
			} catch (Exception e) {
				MobMindMod.LOGGER.warn("[MobMind] 语音文件读取失败: {}", e.getMessage());
			} finally {
				try {
					java.nio.file.Files.deleteIfExists(wavFile);
				} catch (Exception ignored) {}
			}
		});
	}

	/** 直接播放浮点采样（JNI 合成输出），单声道 */
	public static void play(float[] samples, int sampleRate) {
		byte[] pcm = new byte[samples.length * 2];
		for (int i = 0; i < samples.length; i++) {
			int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767f);
			pcm[i * 2] = (byte) (v & 0xFF);
			pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
		}
		AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
		MobMindExecutor.runAsync(() -> {
			try {
				DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
				try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
					line.open(fmt);
					line.start();
					line.write(pcm, 0, pcm.length);
					line.drain();
				}
			} catch (Exception e) {
				MobMindMod.LOGGER.warn("[MobMind] 语音播放失败: {}", e.getMessage());
			}
		});
	}
}
