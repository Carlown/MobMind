package com.mobmind.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局异步执行器：所有 AI HTTP 调用都在守护线程池上执行，绝不阻塞游戏主线程。
 * 信号量限制并发 API 调用数量，避免影响游戏性能。
 */
public final class MobMindExecutor {
	private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "mobmind-ai");
		t.setDaemon(true);
		return t;
	});
	/** 同时进行的 AI 请求上限 */
	private static final Semaphore API_SLOTS = new Semaphore(3);
	private static final AtomicInteger ACTIVE = new AtomicInteger();

	private MobMindExecutor() {}

	public static ExecutorService pool() {
		return POOL;
	}

	/** 尝试获取一个 API 调用槽位（非阻塞），成功返回 true，用完必须调用 releaseApiSlot */
	public static boolean tryAcquireApiSlot() {
		return API_SLOTS.tryAcquire();
	}

	public static void releaseApiSlot() {
		API_SLOTS.release();
	}

	public static int activeCalls() {
		return ACTIVE.get();
	}

	public static void runAsync(Runnable r) {
		POOL.execute(r);
	}
}
