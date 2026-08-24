package com.mymedia.preview;

/**
 * 测试夹具：一个睡指定毫秒数再退出的子进程。
 *
 * <p>用它而不是 {@code sleep} / {@code ping}——后者在 Windows 与 Linux 上
 * 名字和参数都不一样，而当前 JVM 一定存在。
 */
public final class SleepingProcess {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("started");
        System.out.flush();
        Thread.sleep(Long.parseLong(args[0]));
        System.out.println("finished");
    }
}
