package com.mobmind.client.voice;

import javax.sound.sampled.AudioFormat;

/** PCM 字节封装为 WAV 文件 */
public final class WavUtil {
	private WavUtil() {}

	public static byte[] wrap(byte[] pcm, AudioFormat format) {
		int sampleRate = (int) format.getSampleRate();
		int channels = format.getChannels();
		int bits = format.getSampleSizeInBits();
		int byteRate = sampleRate * channels * bits / 8;
		int blockAlign = channels * bits / 8;
		int dataLen = pcm.length;
		int totalLen = 44 + dataLen;

		byte[] out = new byte[totalLen];
		// RIFF header
		System.arraycopy("RIFF".getBytes(), 0, out, 0, 4);
		putIntLE(out, 4, totalLen - 8);
		System.arraycopy("WAVE".getBytes(), 0, out, 8, 4);
		// fmt chunk
		System.arraycopy("fmt ".getBytes(), 0, out, 12, 4);
		putIntLE(out, 16, 16);
		putShortLE(out, 20, (short) 1); // PCM
		putShortLE(out, 22, (short) channels);
		putIntLE(out, 24, sampleRate);
		putIntLE(out, 28, byteRate);
		putShortLE(out, 32, (short) blockAlign);
		putShortLE(out, 34, (short) bits);
		// data chunk
		System.arraycopy("data".getBytes(), 0, out, 36, 4);
		putIntLE(out, 40, dataLen);
		System.arraycopy(pcm, 0, out, 44, dataLen);
		return out;
	}

	private static void putIntLE(byte[] b, int off, int v) {
		b[off] = (byte) (v & 0xFF);
		b[off + 1] = (byte) ((v >> 8) & 0xFF);
		b[off + 2] = (byte) ((v >> 16) & 0xFF);
		b[off + 3] = (byte) ((v >> 24) & 0xFF);
	}

	private static void putShortLE(byte[] b, int off, short v) {
		b[off] = (byte) (v & 0xFF);
		b[off + 1] = (byte) ((v >> 8) & 0xFF);
	}
}
