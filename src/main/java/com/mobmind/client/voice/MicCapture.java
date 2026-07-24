package com.mobmind.client.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 麦克风实时采集：16kHz 16bit 单声道 PCM，停止后封装为 WAV。
 * 支持列出可用麦克风并按名称选择。
 */
public class MicCapture {
	public static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

	private TargetDataLine line;
	private ByteArrayOutputStream out;
	private Thread thread;
	private volatile boolean recording;
	private String preferredMixerName = "";

	public void setPreferredMixerName(String name) {
		this.preferredMixerName = name == null ? "" : name;
	}

	/** 枚举所有支持目标格式录音的麦克风名称 */
	public static List<String> listMicrophones() {
		List<String> names = new ArrayList<>();
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
			Mixer mixer = AudioSystem.getMixer(mi);
			if (mixer.isLineSupported(info)) {
				names.add(mi.getName());
			}
		}
		return names;
	}

	public synchronized void start() throws Exception {
		if (recording) return;
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		line = openLine(info);
		if (line == null) {
			throw new IllegalStateException("没有可用的麦克风，请检查音频设备或去设置里换一个");
		}
		line.open(FORMAT);
		line.start();
		out = new ByteArrayOutputStream();
		recording = true;
		thread = new Thread(() -> {
			byte[] buf = new byte[4096];
			while (recording) {
				int n = line.read(buf, 0, buf.length);
				if (n > 0) out.write(buf, 0, n);
			}
		}, "mobmind-mic");
		thread.setDaemon(true);
		thread.start();
	}

	private TargetDataLine openLine(DataLine.Info info) throws Exception {
		// 未指定或找不到时回退到默认
		if (preferredMixerName == null || preferredMixerName.isBlank()) {
			return (TargetDataLine) AudioSystem.getLine(info);
		}
		for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
			if (mi.getName().equals(preferredMixerName)) {
				Mixer mixer = AudioSystem.getMixer(mi);
				if (mixer.isLineSupported(info)) {
					return (TargetDataLine) mixer.getLine(info);
				}
			}
		}
		return (TargetDataLine) AudioSystem.getLine(info);
	}

	/** 停止采集并返回 WAV 字节；未在录音时返回 null */
	public synchronized byte[] stop() {
		if (!recording) return null;
		recording = false;
		try {
			thread.join(500);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		line.stop();
		line.close();
		byte[] pcm = out.toByteArray();
		out = null;
		return pcm.length == 0 ? null : WavUtil.wrap(pcm, FORMAT);
	}

	public boolean isRecording() {
		return recording;
	}
}
